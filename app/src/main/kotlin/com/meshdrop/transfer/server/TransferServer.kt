package com.meshdrop.transfer.server

import com.meshdrop.core.model.IncomingRequest
import com.meshdrop.core.model.TransferFile
import com.meshdrop.core.util.MeshLog
import com.meshdrop.data.ReceivedFileSink
import com.meshdrop.data.ReceivedFileStore
import com.meshdrop.transfer.protocol.ControlMessages
import com.meshdrop.transfer.protocol.Http
import com.meshdrop.transfer.protocol.Http.copyToWithProgress
import com.meshdrop.transfer.protocol.Http.readExactly
import com.meshdrop.transfer.protocol.Http.readRequestHead
import com.meshdrop.transfer.protocol.Http.writeError
import com.meshdrop.transfer.protocol.Http.writeJson
import com.meshdrop.transfer.protocol.Http.writeResponse
import com.meshdrop.transfer.protocol.TransferProtocol
import com.meshdrop.transfer.protocol.UploadAck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * The receiving half of MeshDrop: a tiny HTTP server bound to an ephemeral port on all
 * interfaces, advertised to peers by the discovery strategies.
 *
 * Endpoints (see [TransferProtocol]):
 *  * `GET  /meshdrop/ping`                       → identity + protocol version
 *  * `POST /meshdrop/handshake`                  → offer a batch, long-polls the user's answer
 *  * `PUT  /meshdrop/upload/<batchId>/<fileId>`  → raw file bytes
 *  * `POST /meshdrop/complete?batchId=…`         → end of batch
 *
 * One connection per request keeps concurrency trivial; each connection is handled in its
 * own coroutine on [Dispatchers.IO].
 */
class TransferServer(
    private val fileStore: ReceivedFileStore,
    private val handler: IncomingTransferHandler,
    private val scope: CoroutineScope,
    private val deviceId: () -> String,
    private val deviceName: () -> String,
) {

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    /** batchId → state of that batch (accepted? which files landed?). */
    private val batches = ConcurrentHashMap<String, BatchState>()

    @Volatile
    var port: Int = 0
        private set

    val isRunning: Boolean get() = serverSocket?.isClosed == false

    /** Binds an ephemeral port and starts serving. Returns the port, or 0 on failure. */
    fun start(): Int {
        if (isRunning) return port
        val socket = runCatching { ServerSocket(0, BACKLOG) }
            .onFailure { MeshLog.e(TAG, "Cannot bind a local port", it) }
            .getOrNull() ?: return 0
        serverSocket = socket
        port = socket.localPort
        acceptJob = scope.launch(Dispatchers.IO) { acceptLoop(socket) }
        MeshLog.i(TAG, "Transfer server listening on $port")
        return port
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
        port = 0
        MeshLog.i(TAG, "Transfer server stopped")
    }

    private suspend fun acceptLoop(socket: ServerSocket) {
        while (scope.isActive && !socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrElse { error ->
                if (!socket.isClosed) MeshLog.w(TAG, "accept() failed", error)
                return
            }
            client.soTimeout = TransferProtocol.SOCKET_TIMEOUT_MS
            client.tcpNoDelay = true
            scope.launch(Dispatchers.IO) { handleConnection(client) }
        }
    }

    private suspend fun handleConnection(client: Socket) {
        val output = runCatching { BufferedOutputStream(client.getOutputStream()) }.getOrNull()
        val input = runCatching { BufferedInputStream(client.getInputStream(), TransferProtocol.CHUNK_BYTES) }.getOrNull()
        if (output == null || input == null) {
            MeshLog.w(TAG, "Connection without usable streams")
            client.closeQuietly()
            return
        }

        runCatching {
            val head = input.readRequestHead()
            if (head == null) {
                client.closeQuietly()
                return
            }
            MeshLog.v(TAG, "${head.method} ${head.path} (${head.contentLength} bytes)")
            when {
                head.method == "GET" && head.path == TransferProtocol.PATH_PING -> {
                    output.writeJson(200, "OK", ControlMessages.ping(deviceId(), deviceName()))
                }

                head.method == "POST" && head.path == TransferProtocol.PATH_HANDSHAKE -> {
                    handleHandshake(input, output, head.contentLength)
                }

                head.method == "PUT" && head.path.startsWith(TransferProtocol.PATH_UPLOAD) -> {
                    handleUpload(head, input, output)
                }

                head.method == "POST" && head.path == TransferProtocol.PATH_COMPLETE -> {
                    handleComplete(head, output)
                }

                else -> output.writeError(404, "Not Found", "Unknown endpoint ${head.path}")
            }
        }.onFailure { error ->
            MeshLog.w(TAG, "Error while handling ${client.inetAddress}", error)
            runCatching { output.writeError(500, "Internal Server Error", error.message ?: "unknown") }
        }
        client.closeQuietly()
    }

    // region endpoints --------------------------------------------------------------------

    private suspend fun handleHandshake(input: BufferedInputStream, output: BufferedOutputStream, contentLength: Long) {
        if (contentLength <= 0L || contentLength > MAX_CONTROL_BYTES) {
            output.writeError(411, "Length Required", "A handshake needs a Content-Length body")
            return
        }
        val body = input.readExactly(contentLength).toString(Charsets.UTF_8)
        val request = ControlMessages.parseHandshake(body)
        if (request == null) {
            output.writeError(400, "Bad Request", "Malformed handshake")
            return
        }
        if (request.version > TransferProtocol.VERSION) {
            output.writeError(426, "Upgrade Required", "Peer speaks protocol v${request.version}")
            return
        }

        val files = request.files.map { meta ->
            TransferFile(
                id = meta.id,
                name = meta.name,
                sizeBytes = meta.size,
                mimeType = meta.mime,
            )
        }
        val incoming = IncomingRequest(
            batchId = request.batchId,
            senderId = request.senderId,
            senderName = request.senderName,
            senderAddress = "",
            files = files,
            totalBytes = files.sumOf { it.sizeBytes },
        )

        // Long poll: the peer waits here until a human taps Accept or Reject.
        val accepted = withTimeoutOrNull(TransferProtocol.HANDSHAKE_TIMEOUT_MS) {
            handler.requestApproval(incoming)
        }

        when (accepted) {
            true -> {
                batches[request.batchId] = BatchState(incoming)
                output.writeJson(200, "OK", acceptedJson())
            }

            false -> {
                output.writeJson(403, "Forbidden", rejectedJson())
            }

            null -> {
                output.writeJson(408, "Request Timeout", timeoutJson())
            }
        }
    }

    private fun handleUpload(
        head: Http.RequestHead,
        input: BufferedInputStream,
        output: BufferedOutputStream,
    ) {
        val segments = head.segments()
        if (segments.size < 4) {
            output.writeError(400, "Bad Request", "Expected /meshdrop/upload/<batchId>/<fileId>")
            return
        }
        val batchId = segments[2]
        val fileId = segments[3]
        val batch = batches[batchId]
        if (batch == null) {
            output.writeError(409, "Conflict", "Unknown or expired batch")
            return
        }
        val file = batch.request.files.firstOrNull { it.id == fileId }
        if (file == null) {
            output.writeError(404, "Not Found", "No such file in this batch")
            return
        }

        val sink = fileStore.createSink(file.name, file.mimeType)
        val startedAt = System.currentTimeMillis()
        handler.onFileStarted(batchId, fileId)

        val bytes = runCatching {
            input.copyToWithProgress(sink.outputStream, head.contentLength) { copied ->
                handler.onFileProgress(batchId, fileId, copied, file.sizeBytes)
            }
        }.getOrElse { error ->
            sink.abort()
            handler.onFileFailed(batchId, fileId, error.message ?: "write failed")
            output.writeError(500, "Internal Server Error", error.message ?: "write failed")
            return
        }

        val expectedSize = if (file.sizeBytes > 0L) file.sizeBytes else bytes
        if (file.sizeBytes > 0L && bytes != file.sizeBytes) {
            sink.abort()
            handler.onFileFailed(batchId, fileId, "Truncated: got $bytes of ${file.sizeBytes} bytes")
            output.writeError(400, "Bad Request", "Size mismatch")
            return
        }

        val uri = sink.finish()
        batch.completed += fileId
        handler.onFileCompleted(batchId, fileId, uri?.toString())
        output.writeJson(
            200,
            "OK",
            uploadAckJson(
                UploadAck(
                    fileId = fileId,
                    bytes = expectedSize,
                    durationMillis = System.currentTimeMillis() - startedAt,
                ),
            ),
        )
    }

    private fun handleComplete(head: Http.RequestHead, output: BufferedOutputStream) {
        val batchId = head.query.substringAfter("${TransferProtocol.QUERY_BATCH_ID}=", "")
            .substringBefore('&')
        val batch = batches.remove(batchId)
        val received = batch?.completed?.size ?: 0
        output.writeJson(200, "OK", completeJson(received))
    }

    /** Drops a batch (used when the user cancels a download). */
    fun forget(batchId: String) {
        batches.remove(batchId)
    }

    // endregion

    private fun Socket.closeQuietly() = runCatching { close() }

    private class BatchState(
        val request: IncomingRequest,
        val completed: MutableSet<String> = ConcurrentHashMap.newKeySet(),
    )

    companion object {
        const val TAG = "TransferServer"
        private const val BACKLOG = 16
        private const val MAX_CONTROL_BYTES = 256 * 1024L

        private fun acceptedJson(): String =
            controlJson(ControlMessages.HandshakeResponse.Decision.ACCEPTED, null)

        private fun rejectedJson(): String =
            controlJson(
                ControlMessages.HandshakeResponse.Decision.REJECTED,
                "The user declined the transfer",
            )

        private fun timeoutJson(): String =
            controlJson(
                ControlMessages.HandshakeResponse.Decision.TIMEOUT,
                "No answer within ${TransferProtocol.HANDSHAKE_TIMEOUT_MS / 1000}s",
            )

        private fun controlJson(
            decision: ControlMessages.HandshakeResponse.Decision,
            message: String?,
        ): String = with(ControlMessages) {
            ControlMessages.HandshakeResponse(decision, message).toJson()
        }

        private fun uploadAckJson(ack: UploadAck): String = with(ControlMessages) { ack.toJson() }

        private fun completeJson(received: Int): String = org.json.JSONObject()
            .put("status", "completed")
            .put("received", received)
            .toString()
    }
}
