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

    private class Handler : IncomingTransferHandler {
        override suspend fun requestApproval(request: IncomingRequest) = true
        override fun negotiateKeys(batchId: String, peerMaterial: SecurityOffer?): SessionKeys? = null
        override fun localMaterialFor(batchId: String): SecurityOffer? = null
        override fun sessionKeyFor(batchId: String): ByteArray? = null
        override fun onFileStarted(batchId: String, fileId: String) {}
        override fun onFileProgress(batchId: String, fileId: String, bytesReceived: Long, sizeBytes: Long) {}
        override fun onFileCompleted(batchId: String, fileId: String, uri: String?, landedName: String?) {}
        override fun onFileFailed(batchId: String, fileId: String, reason: String) {}
    }

    private fun withServer(test: (TransferServer, MutableList<ByteArray>) -> Unit) {
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
        val server = TransferServer(store, Handler(), scope, { "receiver" }, { "Receiver" })
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
}
