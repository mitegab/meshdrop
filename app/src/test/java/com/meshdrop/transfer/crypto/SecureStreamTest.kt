package com.meshdrop.transfer.crypto

import org.junit.Assert.*
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class SecureStreamTest {
    private val key = ByteArray(32) { it.toByte() }
    private val salt = byteArrayOf(1, 2, 3, 4)
    private fun input(bytes: ByteArray) = BufferedInputStream(ByteArrayInputStream(bytes))
    private fun encode(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also {
        SecureFrames.encryptStream(key, salt, input(bytes), bytes.size.toLong(), it) {}
    }.toByteArray()

    @Test fun `separate files never reuse a session nonce`() {
        val payload = ByteArray(70_000) { 42 }
        val first = encode(payload)
        val second = encode(payload)
        assertFalse(first.copyOfRange(4, 16).contentEquals(second.copyOfRange(4, 16)))
        val output = ByteArrayOutputStream()
        SecureFrames.decryptStream(key, input(second), second.size.toLong(), output) {}
        assertArrayEquals(payload, output.toByteArray())
    }

    @Test fun `stream round trips at chunk boundaries and zero length`() {
        for (size in listOf(0, 1, 65535, 65536, 65537, 131072)) {
            val payload = ByteArray(size) { (it % 251).toByte() }
            val encoded = encode(payload)
            assertEquals(SecureFrames.ciphertextLength(size.toLong()), encoded.size.toLong())
            val output = ByteArrayOutputStream()
            SecureFrames.decryptStream(key, input(encoded), encoded.size.toLong(), output) {}
            assertArrayEquals(payload, output.toByteArray())
        }
    }

    @Test fun `oversized frame is rejected before allocating its payload`() {
        val header = ByteBuffer.allocate(16).putInt(Int.MAX_VALUE).put(ByteArray(12)).array()
        assertThrows(javax.crypto.AEADBadTagException::class.java) {
            SecureFrames.decryptStream(key, input(header), Int.MAX_VALUE.toLong() + 16, ByteArrayOutputStream()) {}
        }
    }

    @Test fun `cancellation callback stops after one frame`() {
        val output = ByteArrayOutputStream()
        assertThrows(java.io.InterruptedIOException::class.java) {
            SecureFrames.encryptStream(key, salt, input(ByteArray(200_000)), 200_000, output) {
                throw java.io.InterruptedIOException("cancelled")
            }
        }
        assertEquals(SecureFrames.frameSize(65536), output.size())
    }

    @Test fun `truncated frames are never published as plaintext`() {
        val encoded = encode(ByteArray(512))
        val output = ByteArrayOutputStream()
        assertThrows(java.io.EOFException::class.java) {
            SecureFrames.decryptStream(key, input(encoded.dropLast(1).toByteArray()), encoded.size.toLong(), output) {}
        }
        assertEquals(0, output.size())
    }
}
