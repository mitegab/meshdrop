package com.meshdrop.transfer.crypto

import java.io.BufferedInputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
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

    // Shared across all file streams. A counter starting at zero per file reused the
    // same nonce/key pair throughout a batch. Session keys are ephemeral across processes.
    private val nextCounter = AtomicLong()

    /** Exact number of bytes [plaintextLength] becomes once framed and encrypted. */
    fun ciphertextLength(plaintextLength: Long, chunkSize: Int = CHUNK_SIZE): Long {
        require(plaintextLength >= 0L && chunkSize in 1..CHUNK_SIZE)
        if (plaintextLength == 0L) return 0L
        val chunks = 1 + (plaintextLength - 1) / chunkSize
        return Math.addExact(Math.multiplyExact(chunks, (LENGTH_BYTES + NONCE_BYTES + TAG_BYTES).toLong()), plaintextLength)
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
        require(frame.size >= frameHeaderSize() + TAG_BYTES) { "Truncated frame" }
        val buffer = ByteBuffer.wrap(frame)
        val length = buffer.int
        require(length in TAG_BYTES..(CHUNK_SIZE + TAG_BYTES) && length == frame.size - frameHeaderSize()) {
            "Invalid frame length"
        }
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

    // region streaming ---------------------------------------------------------------------

    /**
     * Encrypts [plaintextLength] bytes read from [source] and writes the framed ciphertext to
     * [sink], reporting progress in *plaintext* bytes so the UI shows the user's file size
     * rather than the slightly larger encrypted size.
     */
    fun encryptStream(
        key: ByteArray,
        salt: ByteArray,
        source: BufferedInputStream,
        plaintextLength: Long,
        sink: OutputStream,
        chunkSize: Int = CHUNK_SIZE,
        onProgress: (plaintextBytes: Long) -> Unit,
    ): Long {
        require(plaintextLength >= 0 && chunkSize in 1..CHUNK_SIZE)
        val buffer = ByteArray(chunkSize)
        var remaining = plaintextLength
        var written = 0L

        while (remaining > 0L) {
            val toRead = minOf(remaining, buffer.size.toLong()).toInt()
            source.readFully(buffer, toRead)
            val counter = nextCounter.getAndIncrement()
            check(counter >= 0) { "Nonce counter exhausted" }
            val frame = encrypt(key, salt, counter, buffer, 0, toRead)
            sink.write(frame)
            remaining -= toRead
            written += toRead
            onProgress(written)
        }
        sink.flush()
        return written
    }

    /**
     * Reads [ciphertextLength] bytes of frames from [source], verifies every chunk's
     * authentication tag, and writes the plaintext to [sink].
     *
     * Throws as soon as a tag fails — the transfer is aborted rather than silently delivering
     * tampered bytes, and the failure is reported as a failed integrity check.
     */
    fun decryptStream(
        key: ByteArray,
        source: BufferedInputStream,
        ciphertextLength: Long,
        sink: OutputStream,
        onProgress: (plaintextBytes: Long) -> Unit,
    ): Long {
        require(ciphertextLength >= 0)
        val header = ByteArray(LENGTH_BYTES + NONCE_BYTES)
        var remaining = ciphertextLength
        var written = 0L

        while (remaining > 0L) {
            if (remaining < header.size + TAG_BYTES) throw java.io.EOFException("Truncated frame header")
            source.readFully(header, header.size)
            remaining -= header.size

            val buffer = ByteBuffer.wrap(header)
            val length = buffer.int
            val nonce = ByteArray(NONCE_BYTES)
            buffer.get(nonce)
            if (length <= TAG_BYTES || length > CHUNK_SIZE + TAG_BYTES || length > remaining) {
                throw javax.crypto.AEADBadTagException("Implausible frame length: $length")
            }

            val ciphertext = ByteArray(length)
            source.readFully(ciphertext, length)
            remaining -= length

            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            val plaintext = cipher.doFinal(ciphertext)

            sink.write(plaintext)
            written += plaintext.size
            onProgress(written)
        }
        sink.flush()
        return written
    }

    // endregion
}

/** Reads exactly [length] bytes, or throws if the stream ends early. */
private fun BufferedInputStream.readFully(destination: ByteArray, length: Int) {
    var offset = 0
    while (offset < length) {
        val read = read(destination, offset, length - offset)
        if (read == -1) throw java.io.EOFException("Stream ended after $offset of $length bytes")
        offset += read
    }
}
