package com.meshdrop.transfer.client

import android.net.Uri
import com.meshdrop.core.resume.PartialFile
import com.meshdrop.transfer.protocol.PartialStatus
import com.meshdrop.transfer.protocol.HttpRanges
import com.meshdrop.core.integrity.StreamingDigest
import com.meshdrop.core.integrity.toHex
import com.meshdrop.core.model.PeerDevice
import com.meshdrop.core.model.TransferFile
import com.meshdrop.core.util.MeshLog
import com.meshdrop.data.DocumentRepository
import com.meshdrop.data.FileSpooler
import com.meshdrop.transfer.crypto.SecureFrames
import com.meshdrop.transfer.crypto.TransferCrypto
import com.meshdrop.transfer.crypto.toDto
import com.meshdrop.transfer.crypto.toOffer
import com.meshdrop.transfer.protocol.BatchStatus
import com.meshdrop.transfer.protocol.CompleteFile
import com.meshdrop.transfer.protocol.CompleteReport
import com.meshdrop.transfer.protocol.CompleteRequest
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
import java.security.DigestInputStream
import java.security.MessageDigest
import java.net.Socket
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/** What happened to one file in a batch we pushed to a peer. */
data class FileOutcome(
    val fileId: String,
    val success: Boolean,
    val bytesSent: Long,
    val message: String?,
    /** SHA-256 of the bytes we read off the disk, hex; null when the upload never started. */
    val digest: String? = null,
)

/** What happened to a whole batch. */
data class SendOutcome(
    val accepted: Boolean,
    val message: String?,
    val outcomes: List<FileOutcome>,
    /** The receiver's verdict from `POST /meshdrop/complete`; null for a v2 peer. */
    val report: CompleteReport? = null,
    val requiresVerification: Boolean = false,
    val completionConfirmed: Boolean = true,
) {
    val isSuccess: Boolean get() = accepted && outcomes.isNotEmpty() && outcomes.all { it.success } &&
        report?.integrityFailed != true && completionConfirmed

    /** What the receiver said, when it said something worth repeating. */
    val integrityMessage: String? get() = report?.message
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
        crypto: TransferCrypto? = null,
        /**
         * True on a retry: ask the receiver what it already holds and skip those files.
         * Off on a first attempt, where the answer is always "nothing" and the round trip
         * would only delay the first byte.
         */
        resume: Boolean = false,
        onHandshakeAccepted: suspend () -> Unit = {},
        isCancelled: () -> Boolean = { false },
        onFileStarted: (fileId: String) -> Unit = {},
        onFileProgress: (fileId: String, bytesSent: Long, sizeBytes: Long, bytesPerSecond: Long) -> Unit = { _, _, _, _ -> },
        onFileFinished: (fileId: String, success: Boolean, message: String?) -> Unit = { _, _, _ -> },
    ): SendOutcome {
        if (isCancelled()) return SendOutcome(false, "Cancelled", emptyList())
        try {
        val host = peer.host
        if (host == null || peer.port <= 0) {
            return SendOutcome(false, "That device has no reachable address yet", emptyList())
        }

        val handshake = runCatching {
            requestHandshake(peer, batchId, senderId, senderName, files, crypto)
        }
            .getOrElse { error ->
                MeshLog.w(TAG, "Handshake with ${peer.name} failed", error)
                return SendOutcome(false, readable(error), emptyList())
            }

        if (!handshake.accepted) {
            return SendOutcome(false, handshake.message, emptyList())
        }

        onHandshakeAccepted()
        if (handshake.requiresVerification && !isCancelled()) {
            connect(peer, batchId).use { socket ->
                socket.soTimeout = TransferProtocol.HANDSHAKE_TIMEOUT_MS
                socket.getOutputStream().writeRequest("GET", "${TransferProtocol.PATH_READY}?batchId=$batchId")
                check(BufferedInputStream(socket.getInputStream()).readResponse().isSuccessful) {
                    "The receiver did not verify the safety code"
                }
            }
        }
        if (isCancelled()) return SendOutcome(false, "Cancelled", emptyList())
        val alreadyThere = if (resume) {
            runCatching { fetchStatus(peer, batchId) }.getOrNull() ?: BatchStatus(batchId)
        } else {
            BatchStatus(batchId)
        }

        val outcomes = ArrayList<FileOutcome>(files.size)
        for (file in files) {
            if (isCancelled()) break
            if (alreadyThere.isComplete(file.id)) {
                MeshLog.i(TAG, "Skipping ${file.name}: the receiver already has it")
                outcomes += FileOutcome(file.id, true, file.sizeBytes, "Already on the other device")
                onFileStarted(file.id)
                onFileFinished(file.id, true, null)
                continue
            }
            currentCoroutineContext().ensureActive()
            onFileStarted(file.id)

            val outcome = runCatching {
                uploadFile(peer, batchId, file, crypto, isCancelled, onFileProgress, alreadyThere.partialFiles[file.id])
            }.getOrElse { error ->
                MeshLog.w(TAG, "Upload of ${file.name} failed", error)
                FileOutcome(file.id, false, 0L, readable(error))
            }
            outcomes += outcome
            onFileFinished(file.id, outcome.success, outcome.message)
        }

        // The digests travel at the end rather than in the handshake: the sender only knows
        // them once it has read the file, and asking it to read every file twice to announce
        // a checksum would double the time before the first byte moves.
        var completionConfirmed = false
        var completionError: String? = null
        val report = if (!isCancelled() && outcomes.size == files.size && outcomes.all { it.success }) {
            runCatching {
                notifyComplete(
                    peer = peer,
                    batchId = batchId,
                    files = outcomes.map { CompleteFile(it.fileId, it.bytesSent, it.digest) },
                ).also { completionConfirmed = true }
            }.getOrElse { error ->
                completionError = "Files sent, but the receiver did not confirm completion. Retry to check."
                MeshLog.v(TAG, "Completion ping failed: ${error.message}")
                null
            }
        } else {
            null
        }

        return SendOutcome(
            accepted = true,
            message = if (isCancelled()) "Cancelled" else completionError ?: report?.message,
            outcomes = outcomes,
            report = report,
            completionConfirmed = completionConfirmed,
        )
        } finally {
            abort(batchId)
        }
    }

    // region protocol steps ---------------------------------------------------------------

    private fun requestHandshake(
        peer: PeerDevice,
        batchId: String,
        senderId: String,
        senderName: String,
        files: List<TransferFile>,
        crypto: TransferCrypto?,
    ): SendOutcome {
        val request = HandshakeRequest(
            batchId = batchId,
            senderId = senderId,
            senderName = senderName,
            files = files.map { FileMeta(it.id, it.name, it.sizeBytes, it.mimeType) },
            security = crypto?.localMaterial()?.toDto(),
        )
        val body = with(ControlMessages) { request.toJson() }.toByteArray(StandardCharsets.UTF_8)

        val socket = connect(peer, batchId)
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
            if (parsed?.accepted == true) {
                // Establishes the shared secret; null means this transfer stays plaintext.
                crypto?.establish(parsed.security?.toOffer())
            }
            return when {
                response.isSuccessful && parsed?.accepted == true ->
                    SendOutcome(true, null, emptyList(), requiresVerification = parsed.requiresVerification)
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
        crypto: TransferCrypto?,
        isCancelled: () -> Boolean,
        onFileProgress: (fileId: String, bytesSent: Long, sizeBytes: Long, bytesPerSecond: Long) -> Unit,
        partial: PartialStatus? = null,
    ): FileOutcome {
        val opened = openPayload(file)
            ?: return FileOutcome(file.id, false, 0L, "Cannot open ${file.name}")
        val (stream, sizeBytes) = opened
        val digest = MessageDigest.getInstance(StreamingDigest.ALGORITHM)
        val buffered = BufferedInputStream(DigestInputStream(stream, digest), TransferProtocol.CHUNK_BYTES)
        val offset = partial?.bytes?.takeIf { it in 1..sizeBytes } ?: 0L
        if (offset > 0) {
            val prefix = try {
                PartialFile.digestPrefix(buffered, offset) {
                    if (isCancelled()) throw java.io.InterruptedIOException("Cancelled")
                }
            } catch (error: Throwable) {
                buffered.close()
                throw error
            }
            if (!prefix.equals(partial?.digest, true)) {
                buffered.close()
                // Source changed since the interrupted attempt. Never append mismatched bytes.
                return uploadFile(peer, batchId, file, crypto, isCancelled, onFileProgress)
            }
        }
        if (isCancelled()) {
            buffered.close()
            throw java.io.InterruptedIOException("Cancelled")
        }
        val socket = try { connect(peer, batchId) } catch (error: Throwable) {
            stream.close()
            throw error
        }

        val sessionKeys = crypto?.sessionKeys()
        val wireLength = if (sessionKeys != null) SecureFrames.ciphertextLength(sizeBytes - offset) else sizeBytes - offset

        try {
        return socket.use outer@{ connection ->
            connection.soTimeout = TransferProtocol.SOCKET_TIMEOUT_MS
            val output = connection.getOutputStream()
            output.writeRequestHead(
                method = "PUT",
                path = TransferProtocol.uploadPath(batchId, file.id),
                contentLength = wireLength,
                headers = buildMap {
                    put(TransferProtocol.HEADER_FILE_NAME, URLEncoder.encode(file.name, "UTF-8"))
                    put(TransferProtocol.HEADER_BATCH_ID, batchId)
                    put(TransferProtocol.HEADER_PLAIN_SIZE, sizeBytes.toString())
                    if (offset > 0) {
                        put(TransferProtocol.HEADER_RANGE, HttpRanges.header(offset))
                        put(TransferProtocol.HEADER_PREFIX_DIGEST, requireNotNull(partial).digest)
                    }
                    put("Content-Type", file.mimeType ?: Http.CONTENT_TYPE_OCTET)
                    if (sessionKeys != null) {
                        put(TransferProtocol.HEADER_ENCRYPTION, sessionKeys.cipherSuite)
                    }
                },
            )

            var sent = offset

            // One place measures the rate, whichever path below is moving the bytes, so the
            // UI sees the same numbers for encrypted and plaintext transfers.
            var lastReportAt = System.currentTimeMillis()
            var lastReportBytes = offset
            var bytesPerSecond = 0L

            fun report(sentNow: Long) {
                val now = System.currentTimeMillis()
                val elapsed = now - lastReportAt
                if (elapsed < PROGRESS_REPORT_MS) return
                val delta = sentNow - lastReportBytes
                val instant = (delta * 1000L) / elapsed
                bytesPerSecond = if (bytesPerSecond == 0L) instant else (bytesPerSecond / 2) + (instant / 2)
                lastReportAt = now
                lastReportBytes = sentNow
                onFileProgress(file.id, sentNow, sizeBytes, bytesPerSecond)
            }

            buffered.use {
                if (sessionKeys != null) {
                    SecureFrames.encryptStream(
                        key = sessionKeys.sendKey,
                        salt = sessionKeys.nonceSalt,
                        source = buffered,
                        plaintextLength = sizeBytes - offset,
                        sink = output,
                    ) { plaintextBytes ->
                        sent = offset + plaintextBytes
                        report(sent)
                        if (isCancelled()) throw java.io.InterruptedIOException("Cancelled")
                    }
                } else {
                    val buffer = ByteArray(TransferProtocol.CHUNK_BYTES)
                    while (sent < sizeBytes) {
                        if (isCancelled()) throw java.io.InterruptedIOException("Cancelled")
                        val read = buffered.read(buffer, 0, minOf(buffer.size.toLong(), sizeBytes - sent).toInt())
                        if (read == -1) throw java.io.EOFException("Source file was truncated")
                        output.write(buffer, 0, read)
                        sent += read
                        report(sent)
                        if (isCancelled()) throw java.io.InterruptedIOException("Cancelled")
                    }
                }
                output.flush()
            }

            onFileProgress(file.id, sent, sizeBytes, bytesPerSecond)

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
            // digest() resets the MessageDigest, so it is called exactly once.
            val sentDigest = digest.digest().toHex()
            val ack = ControlMessages.parseUploadAck(response.bodyText())
            if (ack?.digest != null && !ack.digest.equals(sentDigest, ignoreCase = true)) {
                return@outer FileOutcome(file.id, false, sent, "Integrity check failed: receiver digest differs", sentDigest)
            }
            FileOutcome(file.id, true, ack?.totalOnDisk ?: sent, null, sentDigest)
        }
        } finally {
            buffered.close()
        }
    }

    private fun notifyComplete(
        peer: PeerDevice,
        batchId: String,
        files: List<CompleteFile>,
    ): CompleteReport? {
        val body = with(ControlMessages) { CompleteRequest(batchId, files).toJson() }
            .toByteArray(StandardCharsets.UTF_8)
        val socket = connect(peer, batchId)
        return socket.use {
            it.soTimeout = TransferProtocol.SOCKET_TIMEOUT_MS
            it.getOutputStream().writeRequest(
                method = "POST",
                path = "${TransferProtocol.PATH_COMPLETE}?${TransferProtocol.QUERY_BATCH_ID}=$batchId",
                body = body,
            )
            val response = BufferedInputStream(it.getInputStream()).readResponse()
            check(response.isSuccessful) { "Receiver did not confirm completion (HTTP ${response.status})" }
            ControlMessages.parseCompleteReport(response.bodyText())
        }
    }

    /** What the receiver already holds for [batchId]; null when it does not know the batch. */
    fun fetchStatus(peer: PeerDevice, batchId: String): BatchStatus? = runCatching {
        val socket = connect(peer, batchId)
        socket.use {
            it.soTimeout = TransferProtocol.SOCKET_TIMEOUT_MS
            it.getOutputStream().writeRequest(
                method = "GET",
                path = "${TransferProtocol.PATH_STATUS}?${TransferProtocol.QUERY_BATCH_ID}=$batchId",
            )
            val response = BufferedInputStream(it.getInputStream()).readResponse()
            if (!response.isSuccessful) null else ControlMessages.parseBatchStatus(response.bodyText())
        }
    }.getOrNull()

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

    /**
     * Sockets currently in use by a batch, so a stalled transfer can be abandoned.
     *
     * The copy loop only notices cancellation between files, and a transfer that has stalled
     * is stalled *inside* a file: the socket is open, no bytes are moving, and nothing will
     * change until the read times out on its own. Closing the socket is what turns "wait two
     * minutes" into "fail over now".
     */
    private val openSockets = ConcurrentHashMap<String, Socket>()

    /** Closes the connection a batch is using. The in-flight upload fails; the caller decides what that means. */
    fun abort(batchId: String) {
        openSockets.remove(batchId)?.let { socket ->
            runCatching { socket.close() }
            MeshLog.i(TAG, "$batchId: connection closed to abandon the lane")
        }
    }

    private fun connect(peer: PeerDevice, batchId: String? = null): Socket {
        val socket = Socket()
        // Register before connect: even a stalled TCP connection attempt can be cancelled.
        if (batchId != null) openSockets.put(batchId, socket)?.let { runCatching { it.close() } }
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(requireNotNull(peer.host), peer.port), TransferProtocol.CONNECT_TIMEOUT_MS)
            socket.soTimeout = TransferProtocol.SOCKET_TIMEOUT_MS
            return socket
        } catch (error: Throwable) {
            runCatching { socket.close() }
            if (batchId != null) openSockets.remove(batchId, socket)
            throw error
        }
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
        private const val PROGRESS_REPORT_MS = 120L
    }
}
