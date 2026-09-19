package com.meshdrop.transfer.server

import com.meshdrop.core.model.IncomingRequest
import com.meshdrop.core.resume.PartialFile
import com.meshdrop.data.IncomingFileStore
import com.meshdrop.data.ReceivedFileSink
import com.meshdrop.transfer.crypto.SecurityOffer
import com.meshdrop.transfer.crypto.SessionKeys
import com.meshdrop.transfer.protocol.*
import com.meshdrop.transfer.protocol.Http.readResponse
import com.meshdrop.transfer.protocol.Http.writeRequest
import com.meshdrop.transfer.protocol.Http.writeRequestHead
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.Socket

/** Real loopback sockets, real protocol JSON and disk partials, no Android runtime. */
class TransferServerResumeTest {
    @get:Rule val directory = TemporaryFolder()

    private class Handler(var key: ByteArray? = null) : IncomingTransferHandler {
        val progress = java.util.concurrent.CountDownLatch(1)
        override suspend fun requestApproval(request: IncomingRequest) = true
        override fun negotiateKeys(batchId: String, peerMaterial: SecurityOffer?): SessionKeys? = null
        override fun localMaterialFor(batchId: String): SecurityOffer? = null
        override fun sessionKeyFor(batchId: String): ByteArray? = key
        override fun onFileStarted(batchId: String, fileId: String) {}
        override fun onFileProgress(batchId: String, fileId: String, bytesReceived: Long, sizeBytes: Long) { progress.countDown() }
        override fun onFileCompleted(batchId: String, fileId: String, uri: String?, landedName: String?) {}
        override fun onFileFailed(batchId: String, fileId: String, reason: String) {}
    }

    private fun withServer(handler: IncomingTransferHandler = Handler(), test: (TransferServer, MutableList<ByteArray>) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val published = java.util.Collections.synchronizedList(mutableListOf<ByteArray>())
        val store = object : IncomingFileStore {
            override fun createPartial() = PartialFile(directory.root)
            override fun createSink(requestedName: String, mimeType: String?) = object : ReceivedFileSink {
                override val outputStream = ByteArrayOutputStream()
                override val displayName = requestedName
                override fun finish(): String {
                    published += outputStream.toByteArray()
                    return "content://test/$requestedName"
                }
                override fun abort() { outputStream.reset() }
            }
        }
        val server = TransferServer(store, handler, scope, { "receiver" }, { "Receiver" })
        try {
            assertTrue(server.start() > 0)
            test(server, published)
        } finally { server.stop(); scope.cancel() }
    }

    private fun request(server: TransferServer, method: String, path: String, body: String = ""): Http.Response =
        Socket("127.0.0.1", server.port).use { socket ->
            socket.soTimeout = 5000
            socket.getOutputStream().writeRequest(method, path, body.toByteArray())
            BufferedInputStream(socket.getInputStream()).readResponse()
        }

    private fun offer(server: TransferServer, size: Long = 100_000, name: String = "photo.jpg") = request(
        server, "POST", TransferProtocol.PATH_HANDSHAKE,
        with(ControlMessages) { HandshakeRequest(batchId = "batch", senderId = "sender", senderName = "Sender",
            files = listOf(FileMeta("file", name, size, null))).toJson() },
    )

    @Test fun `interrupted upload resumes a verified prefix and publishes only once`() = withServer { server, published ->
        val payload = ByteArray(100_000) { (it % 251).toByte() }
        assertEquals(200, offer(server).status)
        Socket("127.0.0.1", server.port).use { socket ->
            socket.soTimeout = 5000
            socket.getOutputStream().writeRequestHead("PUT", TransferProtocol.uploadPath("batch", "file"), payload.size.toLong())
            socket.getOutputStream().write(payload, 0, 65536)
            socket.shutdownOutput()
            assertEquals(500, BufferedInputStream(socket.getInputStream()).readResponse().status)
        }
        assertTrue(published.isEmpty())
        // Completing an interrupted batch must not throw its resumable state away.
        assertEquals(409, request(server, "POST", "${TransferProtocol.PATH_COMPLETE}?batchId=batch").status)
        assertEquals(200, offer(server).status)
        val status = ControlMessages.parseBatchStatus(request(server, "GET", "${TransferProtocol.PATH_STATUS}?batchId=batch").bodyText())!!
        val partial = status.partialFiles.getValue("file")
        assertEquals(65536L, partial.bytes)
        Socket("127.0.0.1", server.port).use { socket ->
            socket.soTimeout = 5000
            socket.getOutputStream().writeRequestHead("PUT", TransferProtocol.uploadPath("batch", "file"), 34464,
                mapOf(TransferProtocol.HEADER_RANGE to HttpRanges.header(partial.bytes),
                    TransferProtocol.HEADER_PREFIX_DIGEST to partial.digest))
            socket.getOutputStream().write(payload, 65536, 34464)
            val ack = ControlMessages.parseUploadAck(BufferedInputStream(socket.getInputStream()).readResponse().bodyText())!!
            assertEquals(payload.size.toLong(), ack.totalOnDisk)
        }
        assertEquals(1, published.size)
        assertArrayEquals(payload, published.single())
        assertEquals(200, request(server, "POST", "${TransferProtocol.PATH_COMPLETE}?batchId=batch").status)
        assertEquals(200, request(server, "POST", "${TransferProtocol.PATH_COMPLETE}?batchId=batch").status)
        assertTrue(directory.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun `retry cannot change an accepted manifest`() = withServer { server, _ ->
        assertEquals(200, offer(server).status)
        assertEquals(409, offer(server, name = "different.jpg").status)
        assertEquals(409, offer(server, size = 1).status)
    }

    @Test fun `forget revokes a batch and stop closes the listener`() = withServer { server, published ->
        assertEquals(200, offer(server).status)
        server.forget("batch")
        assertEquals(404, request(server, "GET", "${TransferProtocol.PATH_STATUS}?batchId=batch").status)
        assertTrue(published.isEmpty())
    }
    @Test fun `encrypted retry keeps only authenticated frames and uses fresh keys`() {
        val handler = Handler(ByteArray(32) { 1 })
        withServer(handler) { server, published ->
            val payload = ByteArray(100_000) { (it % 251).toByte() }
            assertEquals(200, offer(server).status)
            val frame = com.meshdrop.transfer.crypto.SecureFrames.encrypt(handler.key!!, byteArrayOf(1, 2, 3, 4),
                0, payload, 0, 65536)
            Socket("127.0.0.1", server.port).use { socket ->
                socket.soTimeout = 5000
                socket.getOutputStream().writeRequestHead("PUT", TransferProtocol.uploadPath("batch", "file"),
                    com.meshdrop.transfer.crypto.SecureFrames.ciphertextLength(100_000))
                socket.getOutputStream().write(frame)
                socket.getOutputStream().write(byteArrayOf(0, 0)) // incomplete next frame, not plaintext
                socket.shutdownOutput()
                assertEquals(500, BufferedInputStream(socket.getInputStream()).readResponse().status)
            }
            val statusReply = request(server, "GET", "${TransferProtocol.PATH_STATUS}?batchId=batch")
            val partials = ControlMessages.parseBatchStatus(statusReply.bodyText())!!.partialFiles
            assertTrue("Resume prefix missing: ${statusReply.bodyText()}", partials.containsKey("file"))
            val partial = partials.getValue("file")
            assertEquals(65536L, partial.bytes)
            assertTrue(published.isEmpty())
            handler.key = ByteArray(32) { 2 }
            assertEquals(200, offer(server).status)
            val suffix = com.meshdrop.transfer.crypto.SecureFrames.encrypt(handler.key!!, byteArrayOf(4, 3, 2, 1),
                0, payload, 65536, 34464)
            Socket("127.0.0.1", server.port).use { socket ->
                socket.soTimeout = 5000
                socket.getOutputStream().writeRequestHead("PUT", TransferProtocol.uploadPath("batch", "file"), suffix.size.toLong(),
                    mapOf(TransferProtocol.HEADER_RANGE to HttpRanges.header(partial.bytes),
                        TransferProtocol.HEADER_PREFIX_DIGEST to partial.digest))
                socket.getOutputStream().write(suffix)
                assertEquals(200, BufferedInputStream(socket.getInputStream()).readResponse().status)
            }
            assertArrayEquals(payload, published.single())
        }
    }

    @Test fun `cancelling an in-flight receive closes its socket and removes private bytes`() {
        val handler = Handler()
        withServer(handler) { server, published ->
            assertEquals(200, offer(server).status)
            Socket("127.0.0.1", server.port).use { socket ->
                socket.soTimeout = 5000
                socket.getOutputStream().writeRequestHead("PUT", TransferProtocol.uploadPath("batch", "file"), 100_000)
                socket.getOutputStream().write(ByteArray(4096))
                socket.getOutputStream().flush()
                assertTrue(handler.progress.await(5, java.util.concurrent.TimeUnit.SECONDS))
                server.forget("batch")
                val next = runCatching { socket.getInputStream().read() }.getOrDefault(-1)
                assertEquals(-1, next)
                assertTrue(published.isEmpty())
                assertTrue(directory.root.listFiles().orEmpty().isEmpty())
            }
        }
    }

    @Test fun `stopping the receiver cancels pending approval coroutines`() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val cancelled = java.util.concurrent.CountDownLatch(1)
        val handler = object : IncomingTransferHandler by Handler() {
            override suspend fun requestApproval(request: IncomingRequest): Boolean {
                entered.countDown()
                try { awaitCancellation() } finally { cancelled.countDown() }
            }
        }
        withServer(handler) { server, _ ->
            Socket("127.0.0.1", server.port).use { socket ->
                val offer = HandshakeRequest(batchId = "batch", senderId = "sender", senderName = "Sender",
                    files = listOf(FileMeta("file", "photo.jpg", 1, null)))
                socket.getOutputStream().writeRequest("POST", TransferProtocol.PATH_HANDSHAKE,
                    with(ControlMessages) { offer.toJson() }.toByteArray())
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                server.stop()
                assertTrue(cancelled.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
    }

}
