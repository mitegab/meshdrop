package com.meshdrop.core.integrity

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hashing is pure Kotlin over java.security, so it runs on the JVM without a device. */
class FileDigestTest {

    private val abc = "abc".toByteArray()
    private val abcSha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private val emptySha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    @Test
    fun `a whole file digest matches the published sha-256 vector`() {
        assertEquals(abcSha256, StreamingDigest().apply { update(abc) }.hex())
    }

    @Test
    fun `the empty payload has the well known empty digest`() {
        assertEquals(emptySha256, StreamingDigest().hex())
        assertEquals(emptySha256, StreamingDigest.EMPTY_HEX)
    }

    @Test
    fun `feeding a file in chunks matches feeding it at once`() {
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        val chunked = StreamingDigest()
        var offset = 0
        while (offset < payload.size) {
            val length = minOf(64 * 1024, payload.size - offset)
            chunked.update(payload, offset, length)
            offset += length
        }

        val whole = StreamingDigest().apply { update(payload) }

        assertEquals(whole.hex(), chunked.hex())
        assertEquals(payload.size.toLong(), chunked.bytesWritten)
    }

    @Test
    fun `reset makes a digest reusable`() {
        val digest = StreamingDigest()
        digest.update(abc)
        digest.reset()

        assertEquals(0L, digest.bytesWritten)
        assertEquals(emptySha256, digest.hex())
    }

    @Test
    fun `a single chunk is its own root`() {
        val leaf = ChunkHashTree.leaf(abc)
        assertArrayEquals(leaf, ChunkHashTree.root(listOf(leaf)))
    }

    @Test
    fun `the root of an empty tree is the empty digest`() {
        assertEquals(emptySha256, ChunkHashTree.rootHex(emptyList()))
    }

    @Test
    fun `the root depends on every chunk and on their order`() {
        val a = ChunkHashTree.leaf("chunk-a".toByteArray())
        val b = ChunkHashTree.leaf("chunk-b".toByteArray())
        val c = ChunkHashTree.leaf("chunk-c".toByteArray())

        val root = ChunkHashTree.root(listOf(a, b, c))
        assertEquals(ChunkHashTree.rootHex(listOf(a, b, c)), root.toHex())
        assertNotEquals(root.toHex(), ChunkHashTree.rootHex(listOf(a, b)))
        assertNotEquals(root.toHex(), ChunkHashTree.rootHex(listOf(c, b, a)))
        // An odd level duplicates the last node, so 3 leaves behave like 4.
        assertEquals(ChunkHashTree.rootHex(listOf(a, b, c, c)), root.toHex())
    }

    @Test
    fun `a modified chunk is detected`() {
        val original = listOf("one".toByteArray(), "two".toByteArray(), "three".toByteArray())
        val leaves = original.map { ChunkHashTree.leaf(it) }
        val expected = ChunkHashTree.root(leaves)

        assertTrue(ChunkHashTree.verify(leaves, expected))

        val tampered = leaves.toMutableList().also { it[1] = ChunkHashTree.leaf("TWO".toByteArray()) }
        assertFalse(ChunkHashTree.verify(tampered, expected))
    }

    @Test
    fun `a zero byte file still has one chunk`() {
        assertEquals(1, ChunkHashTree.chunkCount(0L, 64 * 1024))
        assertEquals(1, ChunkHashTree.chunkCount(1L, 64 * 1024))
        assertEquals(1, ChunkHashTree.chunkCount(65_536L, 64 * 1024))
        assertEquals(2, ChunkHashTree.chunkCount(65_537L, 64 * 1024))
        assertEquals(4, ChunkHashTree.chunkCount(4L * 64 * 1024, 64 * 1024))
    }

    @Test
    fun `hex round trips and rejects nonsense`() {
        val bytes = byteArrayOf(0, 1, 15, 16, -1, 127, -128)
        val hex = bytes.toHex()
        assertEquals("00010f10ff7f80", hex)
        assertArrayEquals(bytes, hex.hexToBytesOrNull())

        assertNull("abc".hexToBytesOrNull())
        assertNull("zz".hexToBytesOrNull())
        assertEquals(0, "".hexToBytesOrNull()?.size)
    }
}
