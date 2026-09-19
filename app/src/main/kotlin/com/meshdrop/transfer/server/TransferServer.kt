package com.meshdrop.transfer.server

import com.meshdrop.core.resume.PartialFile
import com.meshdrop.transfer.protocol.PartialStatus
import com.meshdrop.core.model.IncomingRequest
import com.meshdrop.core.model.TransferFile
import com.meshdrop.core.util.MeshLog
import com.meshdrop.data.IncomingFileStore
import com.meshdrop.transfer.crypto.SecureFrames
import com.meshdrop.transfer.crypto.toDto
import com.meshdrop.transfer.crypto.toOffer
import com.meshdrop.transfer.protocol.BatchStatus
import com.meshdrop.transfer.protocol.CompleteReport
import com.meshdrop.transfer.protocol.ControlMessages
import com.meshdrop.transfer.protocol.HandshakeResponse
import com.meshdrop.transfer.protocol.Http
import com.meshdrop.transfer.protocol.Http.copyToWithProgress
import com.meshdrop.transfer.protocol.Http.readExactly
import com.meshdrop.transfer.protocol.Http.readRequestHead
import com.meshdrop.transfer.protocol.Http.writeError
import com.meshdrop.transfer.protocol.Http.writeJson
import com.meshdrop.transfer.protocol.TransferProtocol
import com.meshdrop.transfer.protocol.UploadAck
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.ServerSocket
import com.meshdrop.transfer.link.DuplexConnection
import com.meshdrop.transfer.link.TcpConnection
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
    private val fileStore: IncomingFileStore,
    private val handler: IncomingTransferHandler,
    private val scope: CoroutineScope,
    private val deviceId: () -> String,
    private val deviceName: () -> String,
) {

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    /** batchId → state of that batch (accepted? which files landed?). */
    var meshService: com.meshdrop.transfer.mesh.MeshService? = null
    private val batches = ConcurrentHashMap<String, BatchState>()
    private val journal = fileStore.partialDirectory?.let { com.meshdrop.core.persistence.ReceiveJournal(it) }
    private val connections = ConcurrentHashMap.newKeySet<DuplexConnection>()
    private val uploads = ConcurrentHashMap<DuplexConnection, String>()
    private val handshakes = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    var port: Int = 0
        private set

    val isRunning: Boolean get() = serverSocket?.isClosed == false

    /** Binds an ephemeral port and starts serving. Returns the port, or 0 on failure. */
    fun start(requestedPort: Int = 0): Int {
        if (isRunning) return port
        val socket = runCatching { ServerSocket(requestedPort, BACKLOG) }
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
        connections.forEach { it.closeQuietly() }
        // Stopping is not cancellation: keep verified prefixes for a fresh approval after restart.
        batches.values.forEach { checkpoint(it) }
        batches.clear()
        meshService?.stop()
        MeshLog.i(TAG, "Transfer server stopped")
    }

    private suspend fun acceptLoop(socket: ServerSocket) = coroutineScope {
        while (isActive && !socket.isClosed) {
            val client = runCatching { TcpConnection(socket.accept()) }.getOrElse { error ->
                if (!socket.isClosed) MeshLog.w(TAG, "accept() failed", error)
                return@coroutineScope
            }
            if (connections.size >= 32 || socket.isClosed) {
                client.closeQuietly()
                continue
            }
            connections += client
            client.soTimeout = TransferProtocol.SOCKET_TIMEOUT_MS
            client.socket.tcpNoDelay = true
            launch(Dispatchers.IO) { handleConnection(client) }
        }
    }

    suspend fun serve(connection: DuplexConnection) {
        if (!isRunning || connections.size >= 32) { connection.close(); return }
        connections += connection
        handleConnection(connection)
    }

    private suspend fun handleConnection(client: DuplexConnection) {
        val output = runCatching { BufferedOutputStream(client.getOutputStream()) }.getOrNull()
        val input = runCatching { BufferedInputStream(client.getInputStream(), TransferProtocol.CHUNK_BYTES) }.getOrNull()
        if (output == null || input == null) {
            MeshLog.w(TAG, "Connection without usable streams")
            connections.remove(client)
            client.closeQuietly()
            return
        }

        runCatching {
            val head = input.readRequestHead()
            if (head == null) {
                connections.remove(client)
                client.closeQuietly()
                return
            }
            MeshLog.v(TAG, "${head.method} ${head.path} (${head.contentLength} bytes)")
            when {
                meshService?.handle(head, input, output) == true -> Unit
                head.method == "GET" && head.path == TransferProtocol.PATH_PING -> {
                    output.writeJson(200, "OK", ControlMessages.ping(deviceId(), deviceName()))
                }

                head.method == "POST" && head.path == TransferProtocol.PATH_HANDSHAKE -> {
                    handleHandshake(input, output, head.contentLength)
                }

                head.method == "PUT" && head.path.startsWith(TransferProtocol.PATH_UPLOAD) -> {
                    uploads[client] = head.segments().getOrNull(2).orEmpty()
                    handleUpload(head, input, output)
                }

                head.method == "GET" && head.path == TransferProtocol.PATH_READY -> {
                    val id = head.query.substringAfter("batchId=", "").substringBefore('&')
                    uploads[client] = id
                    if (batches[id] != null && handler.awaitVerification(id)) {
                        output.writeJson(200, "OK", "{}")
                    } else output.writeError(403, "Forbidden", "Safety-code verification was not completed")
                }

                head.method == "GET" && head.path == TransferProtocol.PATH_STATUS -> {
                    handleStatus(head, output)
                }

                head.method == "POST" && head.path == TransferProtocol.PATH_COMPLETE -> {
                    handleComplete(head, input, output)
                }

                else -> output.writeError(404, "Not Found", "Unknown endpoint ${head.path}")
            }
        }.onFailure { error ->
            MeshLog.w(TAG, "Error while handling ${client.remoteAddress}", error)
            runCatching { output.writeError(500, "Internal Server Error", error.message ?: "unknown") }
        }
        uploads.remove(client)
        connections.remove(client)
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
        if (request.version < 1) {
            output.writeError(400, "Bad Request", "Invalid protocol version")
            return
        }
        if (request.version > TransferProtocol.VERSION) {
            output.writeError(426, "Upgrade Required", "Peer speaks protocol v${request.version}")
            return
        }

        // Interrupted bytes expire instead of filling the private cache indefinitely.
        val cutoff = System.currentTimeMillis() - com.meshdrop.core.persistence.ReceiveJournal.RETENTION_MS
        batches.entries.filter { it.value.lastActivity < cutoff && it.value.writing.isEmpty() && it.key !in handshakes }
            .forEach { forget(it.key) }
        if (!batches.containsKey(request.batchId) && batches.size >= 64) {
            val completed = batches.entries.filter { it.value.completed.size == it.value.request.files.size }
                .minByOrNull { it.value.lastActivity }
            if (completed != null) forget(completed.key) else {
                output.writeError(503, "Service Unavailable", "Too many incomplete batches; cancel an old transfer")
                return
            }
        }
        if (!handshakes.add(request.batchId)) {
            output.writeError(409, "Conflict", "A handshake for this batch is already pending")
            return
        }
        try {
            val owner = request.security?.let { security ->
                val derived = com.meshdrop.transfer.crypto.IdentityVerifier.fingerprintOf(security.identityPublicKey)
                if (derived.equals(security.fingerprint, true) && com.meshdrop.transfer.crypto.IdentityVerifier.verify(
                        security.identityPublicKey, security.ephemeralPublicKey, security.signature))
                    com.meshdrop.core.persistence.AtomicJson.digest(security.identityPublicKey) else null
            }
            val previous = batches[request.batchId] ?: restore(request.batchId)
            if (previous != null && (previous.request.senderId != request.senderId ||
                    previous.fingerprint != owner ||
                    previous.request.files.map { listOf(it.id, it.name, it.sizeBytes, it.mimeType) } !=
                    request.files.map { listOf(it.id, it.name, it.size, it.mime) } ||
                    previous.writing.isNotEmpty())) {
                output.writeError(409, "Conflict", "Batch identity or manifest changed, or upload is active")
                return
            }
            previous?.lastActivity = System.currentTimeMillis()
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

            // Keys are derived before the user decides, so the safety code can be shown
            // alongside the "accept?" question rather than after it.
            handler.negotiateKeys(request.batchId, request.security?.toOffer())

            // Long poll: the peer waits here until a human taps Accept or Reject.
            val accepted = withTimeoutOrNull(TransferProtocol.HANDSHAKE_TIMEOUT_MS.toLong()) {
                handler.requestApproval(incoming)
            }

            when (accepted) {
                true -> {
                    if (!isRunning) return
                    val acceptedBatch = previous ?: BatchState(incoming, owner)
                    batches[request.batchId] = acceptedBatch
                    checkpoint(acceptedBatch)
                    acceptedBatch.receipts.forEach { (id, receipt) -> handler.onFileCompleted(request.batchId, id, receipt.first, receipt.second) }
                    output.writeJson(200, "OK", with(ControlMessages) {
                        HandshakeResponse(HandshakeResponse.Decision.ACCEPTED,
                            security = handler.localMaterialFor(request.batchId)?.toDto(),
                            requiresVerification = handler.verificationRequired).toJson()
                    })
                }

                false -> {
                    output.writeJson(403, "Forbidden", rejectedJson())
                }

                null -> {
                    output.writeJson(408, "Request Timeout", timeoutJson())
                }
            }
        } finally {
            handshakes.remove(request.batchId)
        }
    }

    private suspend fun handleUpload(
        head: Http.RequestHead,
        input: BufferedInputStream,
        output: BufferedOutputStream,
    ) {
        // Commit/close the file and release its writer slot BEFORE the peer sees a reply.
        // Otherwise an immediate status/retry races the finally block and misses the prefix.
        val reply = java.io.ByteArrayOutputStream()
        receiveUpload(head, input, BufferedOutputStream(reply))
        output.write(reply.toByteArray())
        output.flush()
    }

    private suspend fun receiveUpload(
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
        if (!handler.awaitVerification(batchId)) {
            output.writeError(403, "Forbidden", "Safety-code verification is required")
            return
        }
        val file = batch.request.files.firstOrNull { it.id == fileId }
        if (file == null) {
            output.writeError(404, "Not Found", "No such file in this batch")
            return
        }

        if (!batch.writing.add(fileId)) {
            output.writeError(409, "Conflict", "This file is already being uploaded")
            return
        }
        try {
            if (batch.cancelled) throw java.io.InterruptedIOException("Cancelled")
            if (fileId in batch.completed) {
                output.writeError(409, "Conflict", "File already completed; query batch status")
                return
            }
            val offset = head.rangeStart ?: 0L
            if (head.header(TransferProtocol.HEADER_RANGE) != null && head.rangeStart == null) {
                output.writeError(416, "Range Not Satisfiable", "Invalid resume range")
                return
            }
            val size = head.header(TransferProtocol.HEADER_PLAIN_SIZE)?.toLongOrNull() ?: file.sizeBytes
            val key = handler.sessionKeyFor(batchId)
            val knownSize = size > 0 || head.header(TransferProtocol.HEADER_PLAIN_SIZE) != null
            if (size < 0 || offset > size || (file.sizeBytes > 0 && size != file.sizeBytes) ||
                !head.hasContentLength || head.contentLength < 0 ||
                (knownSize && head.contentLength != (if (key != null) SecureFrames.ciphertextLength(size - offset) else size - offset))) {
                output.writeError(400, "Bad Request", "Upload size does not match manifest")
                return
            }
            val part = batch.partials.getOrPut(fileId) { fileStore.createPartial() }
            checkpoint(batch) // publish the part's identity before writing any payload bytes
            val stream = runCatching { part.append(offset, head.header(TransferProtocol.HEADER_PREFIX_DIGEST)) }
                .getOrElse {
                    output.writeError(409, "Conflict", it.message ?: "Resume prefix changed")
                    return
                }
            val startedAt = System.currentTimeMillis()
            handler.onFileStarted(batchId, fileId)
            try {
                val bytes = stream.use { target ->
                    fun progress(copied: Long) {
                        batch.lastActivity = System.currentTimeMillis()
                        if (batch.cancelled) throw java.io.InterruptedIOException("Cancelled")
                        handler.onFileProgress(batchId, fileId, offset + copied, size)
                    }
                    if (key != null) {
                        SecureFrames.decryptStream(key, input, head.contentLength, target, ::progress)
                    } else {
                        input.copyToWithProgress(target, head.contentLength, onProgress = ::progress)
                    }
                }
                if (batch.cancelled) throw java.io.InterruptedIOException("Cancelled")
                if (key == null && bytes != head.contentLength) throw java.io.EOFException("Truncated upload")
                if (knownSize && part.size != size) throw java.io.EOFException("Truncated file")
                val digest = part.digest()
                // Publishing happens only after the entire payload is present and authenticated.
                val sink = fileStore.createSink(file.name, file.mimeType)
                val uri = try {
                    part.file.inputStream().use { it.copyTo(sink.outputStream) }
                    if (batch.cancelled) throw java.io.InterruptedIOException("Cancelled")
                    sink.finish() ?: throw java.io.IOException("Could not publish received file")
                } catch (error: Throwable) {
                    sink.abort()
                    throw error
                }
                batch.digests[fileId] = digest
                synchronized(batch) { batch.bytesWritten += part.size }
                batch.completed += fileId
                batch.receipts[fileId] = uri to sink.displayName
                checkpoint(batch)
                batch.partials.remove(fileId)?.discard()
                handler.onFileCompleted(batchId, fileId, uri.toString(), sink.displayName)
                output.writeJson(200, "OK", uploadAckJson(UploadAck(
                    fileId, bytes, System.currentTimeMillis() - startedAt, digest, offset,
                )))
            } catch (error: Exception) {
                // Losing only the acknowledgement must not invalidate an already published file.
                if (fileId in batch.completed) return
                // Retain a disconnected transfer's prefix. Never retain corrupt or cancelled bytes.
                if (batch.cancelled || error is javax.crypto.AEADBadTagException) {
                    batch.partials.remove(fileId)?.discard()
                }
                handler.onFileFailed(batchId, fileId, error.message ?: "Receive failed")
                output.writeError(500, "Internal Server Error", error.message ?: "Receive failed")
            }
        } finally {
            batch.writing.remove(fileId)
            if (!batch.cancelled) checkpoint(batch)
        }
    }

    /**
     * End of batch: compares the sender's digests with what landed and answers with a
     * [CompleteReport].
     *
     * A v2 peer posts an empty body here, which parses to "no digests offered" and produces
     * a report with nothing mismatched - the old behaviour, unchanged.
     */
    private fun handleComplete(
        head: Http.RequestHead,
        input: BufferedInputStream,
        output: BufferedOutputStream,
    ) {
        val batchId = head.query.substringAfter("${TransferProtocol.QUERY_BATCH_ID}=", "")
            .substringBefore('&')
        if (head.contentLength !in 0..MAX_CONTROL_BYTES) {
            output.writeError(400, "Bad Request", "Invalid completion body length")
            return
        }
        val body = input.readExactly(head.contentLength).toString(Charsets.UTF_8)
        val report = ControlMessages.parseCompleteRequest(body, batchId)
        if (report == null || report.batchId != batchId) {
            output.writeError(400, "Bad Request", "Malformed completion report")
            return
        }
        val batch = batches[batchId]
        if (batch == null) {
            output.writeError(404, "Not Found", "Unknown batch")
            return
        }
        if (batch.writing.isNotEmpty() || batch.completed.size != batch.request.files.size) {
            output.writeError(409, "Conflict", "Batch is incomplete; retry missing files")
            return
        }
        val received = batch.completed.size

        val mismatched = report.files
            .filter { file -> file.digest != null }
            .filterNot { file ->
                val landed = batch.digests[file.fileId]
                landed != null && landed.equals(file.digest, ignoreCase = true)
            }
            .map { it.fileId }

        val result = CompleteReport(
            filesLanded = received,
            bytesWritten = batch.bytesWritten,
            mismatchedFiles = mismatched,
        )
        if (result.integrityFailed) {
            MeshLog.w(TAG, "Batch $batchId: ${result.message}")
            handler.onBatchIntegrityFailed(batchId, mismatched)
        }
        output.writeJson(200, "OK", with(ControlMessages) { result.toJson() })
    }

    /**
     * What we already hold for a batch, so a retry can skip it.
     *
     * Batch state survives a failed transfer on purpose: the files that did land are still
     * on disk and still hashed, and throwing that away because the connection dropped is
     * what makes a 10-file batch start over from file one.
     */
    private fun handleStatus(head: Http.RequestHead, output: BufferedOutputStream) {
        val batchId = head.query.substringAfter("${TransferProtocol.QUERY_BATCH_ID}=", "").substringBefore('&')
        val batch = batches[batchId]
        if (batch == null) {
            output.writeError(404, "Not Found", "Unknown or expired batch")
            return
        }
        val status = BatchStatus(
            batchId = batchId,
            completedFileIds = batch.completed.toList(),
            bytesReceived = batch.bytesWritten,
            partialFiles = batch.partials.entries.filter { it.key !in batch.writing }.associate { (id, part) ->
                id to PartialStatus(part.size, part.digest())
            },
        )
        output.writeJson(200, "OK", with(ControlMessages) { status.toJson() })
    }

    /** Drops a batch (used when the user cancels a download). */
    fun forget(batchId: String) {
        meshService?.cancel(batchId)
        journal?.remove(batchId)
        val batch = batches.remove(batchId)
        if (batch != null) {
            batch.cancelled = true
            uploads.entries.filter { it.value == batchId }.forEach { it.key.closeQuietly() }
            batch.partials.values.forEach { it.discard() }
        }
    }

    // endregion

    private fun DuplexConnection.closeQuietly() = runCatching { close() }

    private fun checkpoint(batch: BatchState) {
        if (batch.cancelled || journal == null || batch.fingerprint == null) return
        synchronized(batch) {
            val json = org.json.JSONObject().put("senderId", batch.request.senderId).put("senderName", batch.request.senderName)
                .put("fingerprint", batch.fingerprint ?: "").put("files", com.meshdrop.core.persistence.JournalCodec.files(batch.request.files))
                .put("touched", batch.lastActivity).put("bytes", batch.bytesWritten)
                .put("parts", org.json.JSONObject().apply { batch.partials.forEach { (id, part) -> if (id !in batch.completed) put(id, part.file.name) } })
                .put("receipts", org.json.JSONObject().apply { batch.receipts.forEach { (id, receipt) ->
                    put(id, org.json.JSONObject().put("uri", receipt.first).put("name", receipt.second).put("digest", batch.digests[id]))
                } })
            journal.write(batch.request.batchId, json)
        }
    }

    private fun restore(id: String): BatchState? = runCatching {
        val json = journal?.read(id) ?: return null
        val directory = fileStore.partialDirectory ?: return null
        val files = com.meshdrop.core.persistence.JournalCodec.files(json.getJSONArray("files"))
        val request = IncomingRequest(id, json.getString("senderId"), json.getString("senderName"), "", files, files.sumOf { it.sizeBytes })
        val batch = BatchState(request, json.optString("fingerprint").ifBlank { null })
        val parts = json.getJSONObject("parts")
        parts.keys().forEach { fileId ->
            val meta = files.firstOrNull { it.id == fileId } ?: error("Journal file not in manifest")
            val part = PartialFile.restore(directory, parts.getString(fileId)) ?: return@forEach
            if (meta.sizeBytes == 0L || part.size <= meta.sizeBytes) batch.partials[fileId] = part else part.discard()
        }
        val receipts = json.getJSONObject("receipts")
        receipts.keys().forEach { fileId ->
            require(files.any { it.id == fileId })
            val receipt = receipts.getJSONObject(fileId)
            batch.completed += fileId
            batch.digests[fileId] = receipt.getString("digest")
            batch.receipts[fileId] = receipt.getString("uri") to receipt.getString("name")
        }
        batch.bytesWritten = json.optLong("bytes")
        batch
    }.onFailure { MeshLog.w(TAG, "Discarding corrupt receive journal", it) }.getOrNull()

    private class BatchState(
        val request: IncomingRequest,
        val fingerprint: String?,
        val completed: MutableSet<String> = ConcurrentHashMap.newKeySet(),
        /** fileId → SHA-256 of what landed, for the end-of-batch comparison. */
        val digests: MutableMap<String, String> = ConcurrentHashMap(),
    ) {
        val receipts = ConcurrentHashMap<String, Pair<String, String>>()
        val partials = ConcurrentHashMap<String, PartialFile>()
        val writing = ConcurrentHashMap.newKeySet<String>()
        @Volatile var lastActivity = System.currentTimeMillis()
        @Volatile var cancelled = false
        @Volatile var bytesWritten: Long = 0L
    }

    companion object {
        const val TAG = "TransferServer"
        private const val BACKLOG = 16
        private const val MAX_CONTROL_BYTES = 256 * 1024L

        private fun acceptedJson(security: com.meshdrop.transfer.protocol.SecurityOfferDto? = null): String =
            controlJson(HandshakeResponse.Decision.ACCEPTED, null, security)

        private fun rejectedJson(): String =
            controlJson(
                HandshakeResponse.Decision.REJECTED,
                "The user declined the transfer",
                null,
            )

        private fun timeoutJson(): String =
            controlJson(
                HandshakeResponse.Decision.TIMEOUT,
                "No answer within ${TransferProtocol.HANDSHAKE_TIMEOUT_MS / 1000}s",
                null,
            )

        private fun controlJson(
            decision: HandshakeResponse.Decision,
            message: String?,
            security: com.meshdrop.transfer.protocol.SecurityOfferDto?,
        ): String = with(ControlMessages) {
            HandshakeResponse(decision, message, security).toJson()
        }

        private fun uploadAckJson(ack: UploadAck): String = with(ControlMessages) { ack.toJson() }

    }
}
