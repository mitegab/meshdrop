package com.meshdrop.core.integrity

import java.security.MessageDigest

/**
 * Whole-file integrity.
 *
 * Per-chunk AES-GCM tags already prove that no chunk was modified *in flight*, but they
 * say nothing about the file as a whole: a sender that read the wrong document, a receiver
 * that ran out of space halfway, or a resume that stitched two attempts together would all
 * produce chunks that individually authenticate. A single digest over the payload closes
 * that gap and is cheap — SHA-256 runs faster than the Wi-Fi link it is protecting.
 *
 * Pure Kotlin over `java.security`, so the hashing rules are unit tested on the JVM.
 */
class StreamingDigest(algorithm: String = ALGORITHM) {

    private val digest = MessageDigest.getInstance(algorithm)

    /** Bytes fed in so far; the receiver compares this against the declared size. */
    var bytesWritten: Long = 0L
        private set

    fun update(chunk: ByteArray, offset: Int = 0, length: Int = chunk.size - offset) {
        digest.update(chunk, offset, length)
        bytesWritten += length
    }

    /** Finishes the digest. The instance must not be reused afterwards. */
    fun digest(): ByteArray = digest.digest()

    fun hex(): String = digest().toHex()

    /** Resets for the next file, keeping the same MessageDigest instance. */
    fun reset() {
        digest.reset()
        bytesWritten = 0L
    }

    companion object {
        const val ALGORITHM = "SHA-256"

        /** Hex of an empty payload — the digest of a zero byte file. */
        val EMPTY_HEX: String by lazy { StreamingDigest().hex() }
    }
}

/**
 * A Merkle tree over the 64 KB chunks of one file.
 *
 * The tree is not needed to prove integrity (the whole-file digest does that), it is what
 * makes a *partial* transfer resumable and re-checkable: both sides can compare the hashes
 * of the chunks they already have and agree on exactly which ranges are missing, instead
 * of re-sending the file and hoping.
 *
 * Layout: leaves are `SHA-256(chunk)`, and a node is `SHA-256(left ‖ right)`. An odd count
 * at any level duplicates the last node, which is the usual convention and keeps the tree
 * shape a function of the chunk count alone.
 */
object ChunkHashTree {

    const val ALGORITHM = StreamingDigest.ALGORITHM

    fun leaf(chunk: ByteArray, offset: Int = 0, length: Int = chunk.size - offset): ByteArray =
        MessageDigest.getInstance(ALGORITHM).digest(chunk.copyOfRange(offset, offset + length))

    /**
     * Root of the tree over [leaves] (each already a chunk hash).
     *
     * An empty leaf list hashes to the digest of nothing, so "no chunks yet" has a stable
     * representation both sides can compare.
     */
    fun root(leaves: List<ByteArray>): ByteArray {
        if (leaves.isEmpty()) return MessageDigest.getInstance(ALGORITHM).digest(ByteArray(0))
        var level = leaves
        val hasher = MessageDigest.getInstance(ALGORITHM)
        while (level.size > 1) {
            val next = ArrayList<ByteArray>((level.size + 1) / 2)
            var index = 0
            while (index < level.size) {
                val left = level[index]
                val right = if (index + 1 < level.size) level[index + 1] else level[index]
                hasher.reset()
                hasher.update(left)
                hasher.update(right)
                next += hasher.digest()
                index += 2
            }
            level = next
        }
        return level.single()
    }

    fun rootHex(leaves: List<ByteArray>): String = root(leaves).toHex()

    /** True when the supplied chunk hashes reproduce [expectedRoot]. */
    fun verify(leaves: List<ByteArray>, expectedRoot: ByteArray): Boolean =
        MessageDigest.isEqual(root(leaves), expectedRoot)

    /**
     * Number of leaves a payload of [sizeBytes] produces at [chunkSize].
     *
     * A zero byte file still has one (empty) chunk, so an empty transfer is resumable in
     * the same way as any other.
     */
    fun chunkCount(sizeBytes: Long, chunkSize: Int): Int =
        if (sizeBytes <= 0L) 1 else ((sizeBytes + chunkSize - 1) / chunkSize).toInt()
}

/** Lowercase hex, two digits per byte. */
fun ByteArray.toHex(): String = joinToString("") { byte ->
    (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
}

/** Parses hex back to bytes; null when the input is not hex. */
fun String.hexToBytesOrNull(): ByteArray? {
    if (length % 2 != 0) return null
    val result = ByteArray(length / 2)
    for (index in result.indices) {
        val high = Character.digit(this[index * 2], 16)
        val low = Character.digit(this[index * 2 + 1], 16)
        if (high < 0 || low < 0) return null
        result[index] = ((high shl 4) or low).toByte()
    }
    return result
}
