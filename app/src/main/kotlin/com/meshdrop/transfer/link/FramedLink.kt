package com.meshdrop.transfer.link

import java.io.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * MDLINK/1: 9-byte header (type u8, stream ID i32 BE, payload length i32 BE).
 * OPEN=1, DATA=2, CLOSE=3, CREDIT=4. Initiator IDs are odd, responder IDs even.
 * Four DATA frames of credit per channel bound both memory and a slow reader's influence.
 * USB/RFCOMM carry this framing directly; they do not tunnel through TCP or require an IP.
 */
class FramedLink(
    private val input: InputStream,
    private val output: OutputStream,
    private val physical: Closeable,
    private val initiator: Boolean,
    private val onIncoming: (DuplexConnection) -> Unit,
    private val onClosed: () -> Unit = {},
) : Closeable {
    private val channels = ConcurrentHashMap<Int, Channel>()
    private val nextId = AtomicInteger(if (initiator) 1 else 2)
    private val closed = AtomicBoolean()
    private val writerLock = Any()
    private val ready = CountDownLatch(1)

    init {
        thread(name = "meshdrop-link", isDaemon = true) {
            try {
                synchronized(writerLock) { output.write(PREFACE); output.flush() }
                val source = DataInputStream(input)
                val greeting = ByteArray(PREFACE.size).also { source.readFully(it) }
                require(greeting.contentEquals(PREFACE)) { "Unsupported link protocol" }
                ready.countDown()
                while (!closed.get()) {
                    val type = source.readUnsignedByte()
                    val id = source.readInt()
                    val size = source.readInt()
                    require(id > 0 && size in 0..FRAME_BYTES) { "Invalid link frame" }
                    val body = ByteArray(size).also { source.readFully(it) }
                    when (type) {
                        OPEN -> {
                            require(size == 0 && (id % 2 == 1) != initiator && !channels.containsKey(id))
                            if (channels.size >= MAX_CHANNELS) { send(CLOSE, id); continue }
                            val channel = Channel(id)
                            channels[id] = channel
                            try { onIncoming(channel) } catch (error: Exception) { channel.close() }
                        }
                        DATA -> {
                            require(size > 0)
                            val channel = channels[id] ?: continue
                            if (!channel.queue.offer(body)) throw IOException("Peer exceeded flow-control credit")
                        }
                        CLOSE -> { require(size == 0); channels.remove(id)?.remoteClose() }
                        CREDIT -> {
                            require(size == 0)
                            channels[id]?.let {
                                if (it.credits.availablePermits() >= WINDOW) throw IOException("Excess credit")
                                it.credits.release()
                            }
                        }
                        else -> throw IOException("Unknown link frame")
                    }
                }
            } catch (_: Exception) { /* close wakes every blocked request, not just the reader */ }
            finally { close() }
        }
    }

    fun open(): DuplexConnection {
        check(ready.await(10, TimeUnit.SECONDS) && !closed.get()) { "Link handshake did not complete" }
        synchronized(channels) {
            check(channels.size < MAX_CHANNELS) { "Link has too many active requests" }
            val id = nextId.getAndAdd(2)
            check(id > 0) { "Link stream IDs exhausted; reconnect" }
            val channel = Channel(id)
            channels[id] = channel
            try { send(OPEN, id) } catch (error: Exception) { channels.remove(id); throw error }
            return channel
        }
    }

    private fun send(type: Int, id: Int, body: ByteArray = EMPTY) = synchronized(writerLock) {
        if (closed.get()) throw IOException("Link disconnected")
        val writer = DataOutputStream(output)
        writer.writeByte(type); writer.writeInt(id); writer.writeInt(body.size); writer.write(body); writer.flush()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        ready.countDown()
        runCatching { physical.close() }
        channels.values.forEach { it.remoteClose() }
        channels.clear()
        onClosed()
    }

    private inner class Channel(val id: Int) : DuplexConnection {
        val queue = ArrayBlockingQueue<ByteArray>(WINDOW + 1)
        val credits = Semaphore(WINDOW)
        private val ended = AtomicBoolean()
        private var current = EMPTY
        private var cursor = 0
        override val remoteAddress = "link:$id"
        @Volatile override var soTimeout = 120_000
        private val reader = object : InputStream() {
            override fun read(): Int {
                val b = ByteArray(1)
                return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 255
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                require(off >= 0 && len >= 0 && off <= b.size - len)
                if (len == 0) return 0
                while (cursor == current.size) {
                    if (current.isNotEmpty() && !ended.get()) runCatching { send(CREDIT, id) }
                    if (ended.get() && queue.isEmpty()) return -1
                    current = queue.poll(soTimeout.toLong(), TimeUnit.MILLISECONDS)
                        ?: throw java.net.SocketTimeoutException("Link read timed out")
                    cursor = 0
                    if (current.isEmpty()) return -1
                }
                val count = minOf(len, current.size - cursor)
                current.copyInto(b, off, cursor, cursor + count)
                cursor += count
                return count
            }
            override fun close() = this@Channel.close()
        }
        private val writer = object : OutputStream() {
            override fun write(value: Int) = write(byteArrayOf(value.toByte()))
            override fun write(b: ByteArray, off: Int, len: Int) {
                require(off >= 0 && len >= 0 && off <= b.size - len)
                var cursor = off
                while (cursor < off + len) {
                    if (ended.get()) throw IOException("Channel closed")
                    if (!credits.tryAcquire(soTimeout.toLong(), TimeUnit.MILLISECONDS)) throw java.net.SocketTimeoutException("Link write timed out")
                    if (ended.get()) throw IOException("Channel closed")
                    val count = minOf(FRAME_BYTES, off + len - cursor)
                    send(DATA, id, b.copyOfRange(cursor, cursor + count))
                    cursor += count
                }
            }
            override fun close() = this@Channel.close()
        }
        override fun getInputStream(): InputStream = reader
        override fun getOutputStream(): OutputStream = writer
        fun remoteClose() {
            ended.set(true)
            queue.offer(EMPTY)
            credits.release(WINDOW)
        }
        override fun close() {
            if (!ended.getAndSet(true)) {
                channels.remove(id)
                runCatching { send(CLOSE, id) }
                queue.offer(EMPTY)
                credits.release(WINDOW)
            }
        }
    }

    companion object {
        const val FRAME_BYTES = 32 * 1024
        const val MAX_CHANNELS = 8
        private const val WINDOW = 4
        private const val OPEN = 1
        private const val DATA = 2
        private const val CLOSE = 3
        private const val CREDIT = 4
        private val EMPTY = ByteArray(0)
        private val PREFACE = "MESHDROP-LINK/1\n".toByteArray(Charsets.US_ASCII)
    }
}
