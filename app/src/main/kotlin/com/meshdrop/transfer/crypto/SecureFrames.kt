package com.meshdrop.transfer.crypto

import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Framing for the encrypted payload stream.
 *
 * A file is split into 64 KB chunks; every chunk becomes one self-describing frame:
 *
 * ```
 * +----------------+---------------------+---------------------------+
 * | length  (4 BE) | nonce (12 bytes)    | ciphertext + GCM tag (16) |
 * +----------------+---------------------+---------------------------+
 * ```
 *
 * Frames (rather than one big cipher stream) mean a receiver that loses sync can resync, and
 * a chunk that fails its authentication tag is detected immediately instead of at the end.
 *
 * The nonce is `session salt (4 bytes) || counter (8 bytes, big endian)`: unique for every
 * chunk of every session, which is the one thing AES-GCM absolutely requires.
 */
object SecureFrames {

    const val LENGTH_BYTES = 4
    const val NONCE_BYTES = 12
    const val TAG_BYTES = 16
    const val CHUNK_SIZE = 64 * 1024
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val TAG_BITS = TAG_BYTES * 8

    /** Exact number of bytes [plaintextLength] becomes once framed and encrypted. */
    fun ciphertextLength(plaintextLength: Long, chunkSize: Int = CHUNK_SIZE): Long {
        if (plaintextLength <= 0L) return 0L
        val chunks = (plaintextLength + chunkSize - 1) / chunkSize
        return chunks * (LENGTH_BYTES + NONCE_BYTES + TAG_BYTES) + plaintextLength
    }

    /** Upper bound on the plaintext inside [ciphertextLength] bytes (used for progress). */
    fun plaintextLength(ciphertextLength: Long, chunkSize: Int = CHUNK_SIZE): Long {
        if (ciphertextLength <= 0L) return 0L
        val perChunkOverhead = (LENGTH_BYTES + NONCE_BYTES + TAG_BYTES).toLong()
        val chunks = (ciphertextLength + chunkSize + perChunkOverhead - 1) / (chunkSize + perChunkOverhead)
        return (ciphertextLength - chunks * perChunkOverhead).coerceAtLeast(0L)
    }

    /** Encrypts one chunk and returns the complete frame. */
    fun encrypt(
        key: ByteArray,
        salt: ByteArray,
        counter: Long,
        plaintext: ByteArray,
        offset: Int = 0,
        length: Int = plaintext.size - offset,
    ): ByteArray {
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce(salt, counter)))
        val ciphertext = cipher.doFinal(plaintext, offset, length)

        return ByteBuffer.allocate(LENGTH_BYTES + NONCE_BYTES + ciphertext.size)
            .putInt(ciphertext.size)
            .put(nonce(salt, counter))
            .put(ciphertext)
            .array()
    }

    /**
     * Decrypts one frame. Throws if the authentication tag does not check out — that is the
     * integrity guarantee the history screen's tick mark refers to.
     */
    fun decrypt(key: ByteArray, frame: ByteArray): ByteArray {
        val buffer = ByteBuffer.wrap(frame)
        val length = buffer.int
        val nonce = ByteArray(NONCE_BYTES)
        buffer.get(nonce)

        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        return cipher.doFinal(frame, buffer.position(), length)
    }

    /** Header size of a frame, so a reader knows where the next one starts. */
    fun frameHeaderSize(): Int = LENGTH_BYTES + NONCE_BYTES

    /** Total frame size for a chunk of [length] plaintext bytes. */
    fun frameSize(length: Int): Int = LENGTH_BYTES + NONCE_BYTES + length + TAG_BYTES

    private fun nonce(salt: ByteArray, counter: Long): ByteArray {
        val nonce = ByteArray(NONCE_BYTES)
        val prefix = minOf(salt.size, 4)
        System.arraycopy(salt, 0, nonce, 0, prefix)
        for (index in 0 until 8) {
            nonce[NONCE_BYTES - 1 - index] = (counter ushr (8 * index)).toByte()
        }
        return nonce
    }
}
