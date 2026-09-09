package com.meshdrop.transfer.client

import android.net.Uri
import com.meshdrop.core.model.PeerDevice
import com.meshdrop.core.model.TransferFile
import com.meshdrop.core.util.MeshLog
import com.meshdrop.data.DocumentRepository
import com.meshdrop.data.FileSpooler
import com.meshdrop.transfer.protocol.ControlMessages
import com.meshdrop.transfer.protocol.FileMeta
import com.meshdrop.transfer.protocol.HandshakeRequest
import com.meshdrop.transfer.protocol.Http
import com.meshdrop.transfer.protocol.Http.readResponse
import com.meshdrop.transfer.protocol.Http.writeRequest
import com.meshdrop.transfer.protocol.Http.writeRequestHead
import com.meshdrop.transfer.protocol.TransferProtocol
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** What happened to one file in a batch we pushed to a peer. */
data class FileOutcome(
    val fileId: String,
    val success: Boolean,
    val bytesSent: Long,
    val message: String?,
)

/** What happened to a whole batch. */
data class SendOutcome(
    val accepted: Boolean,
    val message: String?,
    val outcomes: List<FileOutcome>,
) {
    val isSuccess: Boolean get() = accepted && outcomes.all { it.success }
}

/**
 * The sending half of MeshDrop.
 *
 * Talks the [TransferProtocol] dialect over plain sockets: handshake → one PUT per file →
 * completion. Progress is reported through callbacks so the caller (the [TransferEngine])
 * can decide how often the UI is updated.
 *
 * Every socket operation honours cancellation: the caller passes [isCancelled] and the
 * coroutine context is checked between chunks, so a "Cancel" tap stops a large upload
 * within one buffer.
 */
class TransferClient(
    private val documentRepository: DocumentRepository,
    private val fileSpooler: FileSpooler,
) {

    /**
     * @param isCancelled polled between files and between chunks; lets the engine abort a
     *                    transfer that is already in flight.
     */
    suspend fun send(
        peer: PeerDevice,
        batchId: String,
        senderId: String,
        senderName: String,
        files: List<TransferFile>,
        isCancelled: () -> Boolean = { false },
        onFileStarted: (fileId: String) -> Unit = {},
        onFileProgress: (fileId: String, bytesSent: Long, sizeBytes: Long, bytesPerSecond: Long) -> Unit = { _, _, _, _ -> },
        onFileFinished: (fileId: String, success: Boolean, message: String?) -> Unit = { _, _, _ -> },
    ): SendOutcome {
        val host = peer.host
        if (host == null || peer.port <= 0) {
            return SendOutcome(false, "That device has no reachable address yet", emptyList())
        }

        val handshake = runCatching { requestHandshake(peer, batchId, senderId, senderName, files) }
            .getOrElse { error ->
                MeshLog.w(TAG, "Handshake with ${peer.name} failed", error)
                return SendOutcome(false, readable(error), emptyList())
            }

        if (!handshake.accepted) {
            return SendOutcome(false, handshake.message, emptyList())
        }

        val outcomes = ArrayList<FileOutcome>(files.size)
        for (file in files) {
            if (isCancelled()) break
            currentCoroutineContext().ensureActive()
            onFileStarted(file.id)

            val outcome = runCatching {
                uploadFile(peer, batchId, file, isCancelled, onFileProgress)
            }.getOrElse { error ->
                MeshLog.w(TAG, "Upload of ${file.name} failed", error)
                FileOutcome(file.id, false, 0L, readable(error))
            }
            outcomes += outcome
            onFileFinished(file.id, outcome.success, outcome.message)
        }

        if (outcomes.isNotEmpty()) {
            runCatching { notifyComplete(peer, batchId) }
                .onFailure { MeshLog.v(TAG, "Completion ping failed (peer may already be gone)") }
        }

        return SendOutcome(
            accepted = true,
            message = if (isCancelled()) "Cancelled" else null,
            outcomes = outcomes,
        )
    }

    // region protocol steps ---------------------------------------------------------------

    private fun requestHandshake(
        peer: PeerDevice,
        batchId: String,
        senderId: String,
        senderName: String,
        files: List<TransferFile>,
    ): SendOutcome {
        val request = HandshakeRequest(
            batchId = batchId,
            senderId = senderId,
            senderName = senderName,
            files = files.map { FileMeta(it.id, it.name, it.sizeBytes, it.mimeType) },
        )
        val body = with(ControlMessages) { request.toJson() }.toByteArray(StandardCharsets.UTF_8)

        val socket = connect(peer)
        socket.use {
            it.soTimeout = TransferProtocol.HANDSHAKE_TIMEOUT_MS
            it.getOutputStream().writeRequest(
                method = "POST",
                path = TransferProtocol.PATH_HANDSHAKE,
                body = body,
                headers = mapOf(TransferProtocol.HEADER_BATCH_ID to batchId),
            )
            val response = BufferedInputStream(it.getInputStream()).readResponse()
            val parsed = ControlMessages.parseHandshakeResponse(response.bodyText())
            return when {
                response.isSuccessful && parsed?.accepted == true -> SendOutcome(true, null, emptyList())
                response.status == 403 -> SendOutcome(false, parsed?.message ?: "The receiver declined", emptyList())
                response.status == 408 -> SendOutcome(false, "The receiver did not answer in time", emptyList())
                else -> SendOutcome(
                    false,
                    ControlMessages.errorMessage(response.bodyText())
                        ?: parsed?.message
                        ?: "Transfer refused (HTTP ${response.status})",
                    emptyList(),
                )
            }
        }
    }

    private fun uploadFile(
        peer: PeerDevice,
        batchId: String,
        file: TransferFile,
        isCancelled: () -> Boolean,
        onFileProgress: (fileId: String, bytesSent: Long, sizeBytes: Long, bytesPerSecond: Long) -> Unit,
    ): FileOutcome {
        val opened = openPayload(file)
            ?: return FileOutcome(file.id, false, 0L, "Cannot open ${file.name}")
        val (stream, sizeBytes) = opened
        val socket = connect(peer)

        return socket.use outer@{ connection ->
            connection.soTimeout = TransferProtocol.SOCKET_TIMEOUT_MS
            val output = connection.getOutputStream()
            output.writeRequestHead(
                method = "PUT",
                path = TransferProtocol.uploadPath(batchId, file.id),
                contentLength = sizeBytes,
                headers = mapOf(
                    TransferProtocol.HEADER_FILE_NAME to URLEncoder.encode(file.name, "UTF-8"),
                    TransferProtocol.HEADER_BATCH_ID to batchId,
                    "Content-Type" to (file.mimeType ?: Http.CONTENT_TYPE_OCTET),
                ),
            )

            var sent = 0L
            var cancelled = false
            var windowBytes = 0L
            var windowStart = System.currentTimeMillis()
            var lastReport = 0L
            var bytesPerSecond = 0L

            stream.use inner@{ input ->
                val buffered =
                    input as? BufferedInputStream ?: BufferedInputStream(input, TransferProtocol.CHUNK_BYTES)
                val buffer = ByteArray(TransferProtocol.CHUNK_BYTES)
                while (!cancelled) {
                    val read = buffered.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    sent += read
                    windowBytes += read

                    val now = System.currentTimeMillis()
                    val elapsed = now - windowStart
                    if (elapsed >= SPEED_WINDOW_MS) {
                        bytesPerSecond = (windowBytes * 1000L) / elapsed
                        windowBytes = 0L
                        windowStart = now
                    }
                    if (now - lastReport >= PROGRESS_REPORT_MS) {
                        lastReport = now
                        onFileProgress(file.id, sent, sizeBytes, bytesPerSecond)
                    }
                    if (isCancelled()) cancelled = true
                }
                output.flush()
            }

            onFileProgress(file.id, sent, sizeBytes, bytesPerSecond)
            if (cancelled) {
                return@outer FileOutcome(file.id, false, sent, "Cancelled")
            }

            val response = BufferedInputStream(connection.getInputStream()).readResponse()
            if (!response.isSuccessful) {
                return@outer FileOutcome(
                    file.id,
                    false,
                    sent,
                    ControlMessages.errorMessage(response.bodyText())
                        ?: "Receiver error (HTTP ${response.status})",
                )
            }
            val ack = ControlMessages.parseUploadAck(response.bodyText())
            FileOutcome(file.id, true, ack?.bytes ?: sent, null)
        }
    }

    private fun notifyComplete(peer: PeerDevice, batchId: String) {
        val socket = connect(peer)
        socket.use {
            it.soTimeout = TransferProtocol.SOCKET_TIMEOUT_MS
            it.getOutputStream().writeRequest(
                method = "POST",
                path = "${TransferProtocol.PATH_COMPLETE}?${TransferProtocol.QUERY_BATCH_ID}=$batchId",
            )
            runCatching { BufferedInputStream(it.getInputStream()).readResponse() }
        }
    }

    /** Cheap liveness probe used before offering a transfer. */
    fun ping(peer: PeerDevice): Boolean = runCatching {
        val socket = connect(peer)
        socket.use {
            it.soTimeout = TransferProtocol.CONNECT_TIMEOUT_MS
            it.getOutputStream().writeRequest("GET", TransferProtocol.PATH_PING)
            BufferedInputStream(it.getInputStream()).readResponse().isSuccessful
        }
    }.getOrDefault(false)

    // endregion

    // region helpers ----------------------------------------------------------------------

    private fun connect(peer: PeerDevice): Socket {
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(requireNotNull(peer.host), peer.port), TransferProtocol.CONNECT_TIMEOUT_MS)
        socket.soTimeout = TransferProtocol.SOCKET_TIMEOUT_MS
        return socket
    }

    /**
     * Opens the bytes to send. When the document provider did not report a size we spool the
     * document to the cache first, because the protocol needs `Content-Length` up front.
     */
    private fun openPayload(file: TransferFile): Pair<InputStream, Long>? {
        val uri = file.localUri?.let { Uri.parse(it) } ?: return null
        if (file.sizeBytes > 0L) {
            val stream = documentRepository.openInputStream(uri) ?: return null
            return stream to file.sizeBytes
        }
        val original = documentRepository.openInputStream(uri) ?: return null
        val spooled: File = fileSpooler.spool(uri, original, file.name) ?: return null
        val stream = FileInputStream(spooled)
        return RememberingStream(stream, spooled) to spooled.length()
    }

    /** Deletes the spooled copy once the upload is done. */
    private inner class RememberingStream(
        private val delegate: InputStream,
        private val spooled: File,
    ) : InputStream() {
        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
        override fun available(): Int = delegate.available()
        override fun close() {
            runCatching { delegate.close() }
            fileSpooler.delete(spooled)
        }
    }

    private fun readable(error: Throwable): String = when {
        error.message?.contains("ECONNREFUSED", ignoreCase = true) == true -> "The device is no longer reachable"
        error.message?.contains("ETIMEDOUT", ignoreCase = true) == true -> "The device did not answer"
        error.message?.contains("ENETUNREACH", ignoreCase = true) == true -> "No route to that device"
        error is java.net.SocketTimeoutException -> "The device did not answer"
        else -> error.message ?: "Transfer failed"
    }

    companion object {
        const val TAG = "TransferClient"
        private const val SPEED_WINDOW_MS = 700L
        private const val PROGRESS_REPORT_MS = 120L
    }
}
