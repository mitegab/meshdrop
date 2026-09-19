package com.meshdrop.transfer.link

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.*

class FramedLinkTest {
    private class Pair : Closeable {
        private val listener = ServerSocket(0)
        private val left = Socket("127.0.0.1", listener.localPort)
        private val right = listener.accept().also { listener.close() }
        val incomingLeft = LinkedBlockingQueue<DuplexConnection>()
        val incomingRight = LinkedBlockingQueue<DuplexConnection>()
        val a = FramedLink(left.getInputStream(), left.getOutputStream(), left, true, { incomingLeft.add(it) })
        val b = FramedLink(right.getInputStream(), right.getOutputStream(), right, false, { incomingRight.add(it) })
        override fun close() { a.close(); b.close() }
    }

    @Test(timeout = 10000) fun `simultaneous bidirectional streams are independent`() {
        Pair().use { pair ->
            val a = pair.a.open(); val b = pair.b.open()
            val atB = pair.incomingRight.poll(2, TimeUnit.SECONDS)!!
            val atA = pair.incomingLeft.poll(2, TimeUnit.SECONDS)!!
            a.getOutputStream().write("left".toByteArray())
            b.getOutputStream().write("right".toByteArray())
            assertEquals("left", String(ByteArray(4).also { DataInputStream(atB.getInputStream()).readFully(it) }))
            assertEquals("right", String(ByteArray(5).also { DataInputStream(atA.getInputStream()).readFully(it) }))
            atB.getOutputStream().write(123)
            assertEquals(123, a.getInputStream().read())
            a.close(); b.close(); atA.close(); atB.close()
        }
    }

    @Test(timeout = 10000) fun `slow stream uses credit without blocking a different request`() {
        Pair().use { pair ->
            val slow = pair.a.open(); pair.incomingRight.poll(2, TimeUnit.SECONDS)!!
            val worker = Executors.newSingleThreadExecutor()
            try {
                val writing = worker.submit { slow.getOutputStream().write(ByteArray(1024 * 1024)) }
                try { writing.get(100, TimeUnit.MILLISECONDS); fail("Writer ignored flow-control credit") } catch (_: TimeoutException) {}
                val fast = pair.a.open(); val remote = pair.incomingRight.poll(2, TimeUnit.SECONDS)!!
                fast.getOutputStream().write(7)
                assertEquals(7, remote.getInputStream().read())
                slow.close()
                try { writing.get(2, TimeUnit.SECONDS); fail("Closed stream kept writing") } catch (_: ExecutionException) {}
                fast.close(); remote.close()
            } finally { worker.shutdownNow() }
        }
    }

    @Test(timeout = 10000) fun `large transfers replenish credits and retain bytes before close`() {
        Pair().use { pair ->
            val channel = pair.a.open(); val remote = pair.incomingRight.poll(2, TimeUnit.SECONDS)!!
            val expected = ByteArray(1_000_013) { (it % 251).toByte() }
            val worker = Executors.newSingleThreadExecutor()
            try {
                val sending = worker.submit { channel.getOutputStream().write(expected); channel.close() }
                val actual = remote.getInputStream().readBytes()
                assertArrayEquals(expected, actual)
                sending.get(2, TimeUnit.SECONDS)
            } finally { worker.shutdownNow() }
        }
    }

    @Test(timeout = 10000) fun `stream cap and physical disconnect wake blocked readers`() {
        Pair().use { pair ->
            val channels = (0 until FramedLink.MAX_CHANNELS).map { pair.a.open() }
            try { pair.a.open(); fail("Exceeded channel cap") } catch (_: IllegalStateException) {}
            val worker = Executors.newSingleThreadExecutor()
            try {
                val reading = worker.submit<Int> { channels.first().getInputStream().read() }
                pair.b.close()
                assertEquals(-1, reading.get(2, TimeUnit.SECONDS).toInt())
            } finally { worker.shutdownNow() }
        }
    }

    @Test(timeout = 10000) fun `malicious frames exceeding credit close the physical link`() {
        val listener = ServerSocket(0)
        val raw = Socket("127.0.0.1", listener.localPort)
        val accepted = listener.accept(); listener.close()
        val closed = CountDownLatch(1)
        val link = FramedLink(accepted.getInputStream(), accepted.getOutputStream(), accepted, false, {}, { closed.countDown() })
        try {
            val output = DataOutputStream(raw.getOutputStream())
            output.write("MESHDROP-LINK/1\n".toByteArray())
            output.writeByte(1); output.writeInt(1); output.writeInt(0)
            repeat(5) { output.writeByte(2); output.writeInt(1); output.writeInt(1); output.writeByte(0) }
            output.flush()
            assertTrue(closed.await(2, TimeUnit.SECONDS))
        } finally { link.close(); raw.close() }
    }
}
