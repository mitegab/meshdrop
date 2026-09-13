package com.meshdrop.core.resume

import com.meshdrop.core.integrity.ChunkHashTree
import com.meshdrop.core.integrity.toHex
import java.util.concurrent.ConcurrentHashMap

/** Half-open byte range `[start, endExclusive)`. */
data class ByteRange(val start: Long, val endExclusive: Long) {

    val length: Long get() = (endExclusive - start).coerceAtLeast(0L)

    fun contains(offset: Long): Boolean = offset in start until endExclusive

    override fun toString(): String = "$start-$endExclusive"

    companion object {
        /** The range covered by chunk [index] of a file of [totalBytes]. */
        fun ofChunk(index: Int, chunkSize: Int, totalBytes: Long): ByteRange {
            val start = index.toLong() * chunkSize
            val end = if (totalBytes <= 0L) 0L else minOf(start + chunkSize, totalBytes)
            return ByteRange(start, end.coerceAtLeast(start))
        }
    }
}

/**
 * What still has to be sent for one file.
 *
 * Produced by [ResumeLedger.plan] and turned straight into HTTP `Range` requests: an
 * empty [ranges] means the file is already whole on the receiving side, and a single
 * range covering the file means "nothing was reusable, start over".
 */
data class ResumePlan(
    val fileId: String,
    val totalBytes: Long,
    val chunkCount: Int,
    val confirmedBytes: Long,
    val ranges: List<ByteRange>,
) {

    val isComplete: Boolean get() = ranges.isEmpty()

    val bytesRemaining: Long get() = ranges.sumOf { it.length }

    /** True when no chunk could be reused, i.e. this is not a resume at all. */
    val startsFromScratch: Boolean
        get() = ranges.size == 1 && ranges.first().length == totalBytes.coerceAtLeast(0L) && confirmedBytes == 0L

    val savedBytes: Long get() = (totalBytes - bytesRemaining).coerceAtLeast(0L)

    /** `resuming 3 of 8 chunks` — one line for the transfer card. */
    val headline: String
        get() = when {
            isComplete -> "already complete"
            startsFromScratch -> "starting over"
            else -> "resuming from ${com.meshdrop.core.util.Format.bytes(confirmedBytes)}"
        }
}

/**
 * Which chunks of a file the other side has already got, and which byte ranges that
 * leaves to send.
 *
 * The MVP restarts a dropped transfer from zero. Everything needed to do better is
 * already computed on the way through — every chunk is hashed for the Merkle tree — so
 * the only missing piece is somewhere to put the answer. That is this class: a set of
 * confirmed chunk indices per file, plus the arithmetic that turns "chunks 0..3 and 7"
 * into the ranges `4*64K..7*64K`.
 *
 * A chunk is only accepted with its hash. If the peer claims a chunk we already recorded
 * with a different hash, the claim is rejected and the range stays open: agreeing on the
 * bytes is the whole point of resuming, and silently keeping a chunk that does not match
 * would produce a file that looks complete and is not.
 *
 * Thread-safe: the sender confirms chunks from the upload loop while the UI reads the
 * plan from the main thread.
 */
class ResumeLedger(private val chunkSize: Int = DEFAULT_CHUNK_SIZE) {

    private data class FileState(
        val hashes: MutableMap<Int, ByteArray> = ConcurrentHashMap(),
    )

    private val states = ConcurrentHashMap<String, FileState>()

    /** Number of chunks a file of [totalBytes] is cut into at this ledger's chunk size. */
    fun chunkCount(totalBytes: Long): Int = ChunkHashTree.chunkCount(totalBytes, chunkSize)

    /**
     * Records that chunk [index] of a [totalBytes] file is present and intact.
     *
     * @return false when the index is outside the file or the claim contradicts a hash
     *         already recorded for that chunk.
     */
    fun confirmChunk(fileId: String, index: Int, totalBytes: Long, hash: ByteArray): Boolean {
        if (index < 0 || index >= chunkCount(totalBytes)) return false
        val state = states.getOrPut(fileId) { FileState() }
        val existing = state.hashes[index]
        if (existing != null) return java.security.MessageDigest.isEqual(existing, hash)
        state.hashes[index] = hash.copyOf()
        return true
    }

    fun confirmedChunks(fileId: String): Set<Int> = states[fileId]?.hashes?.keys?.toSet() ?: emptySet()

    fun isConfirmed(fileId: String, index: Int): Boolean = states[fileId]?.hashes?.containsKey(index) == true

    /** Merkle root over the chunks confirmed so far (null when nothing is confirmed). */
    fun root(fileId: String): ByteArray? = states[fileId]
        ?.hashes
        ?.toSortedMap()
        ?.values
        ?.toList()
        ?.takeIf { it.isNotEmpty() }
        ?.let(ChunkHashTree::root)

    fun rootHex(fileId: String): String? = root(fileId)?.toHex()

    /** How many bytes are already accounted for, honouring a short final chunk. */
    fun confirmedBytes(fileId: String, totalBytes: Long): Long {
        val state = states[fileId] ?: return 0L
        val chunks = chunkCount(totalBytes)
        return state.hashes.keys.asSequence()
            .filter { it in 0 until chunks }
            .sumOf { ByteRange.ofChunk(it, chunkSize, totalBytes).length }
    }

    /** Chunk indexes that are still missing, ascending. */
    fun missingChunks(fileId: String, totalBytes: Long): List<Int> {
        val chunks = chunkCount(totalBytes)
        val state = states[fileId]
        return (0 until chunks).filter { state?.hashes?.containsKey(it) != true }
    }

    /** Missing chunks merged into the fewest contiguous byte ranges. */
    fun missingRanges(fileId: String, totalBytes: Long): List<ByteRange> {
        val missing = missingChunks(fileId, totalBytes)
        if (missing.isEmpty()) return emptyList()
        val ranges = ArrayList<ByteRange>()
        var runStart = missing.first()
        var previous = missing.first()
        for (index in missing.drop(1)) {
            if (index == previous + 1) {
                previous = index
            } else {
                ranges += range(runStart, previous, totalBytes)
                runStart = index
                previous = index
            }
        }
        ranges += range(runStart, previous, totalBytes)
        return ranges
    }

    fun plan(fileId: String, totalBytes: Long): ResumePlan = ResumePlan(
        fileId = fileId,
        totalBytes = totalBytes,
        chunkCount = chunkCount(totalBytes),
        confirmedBytes = confirmedBytes(fileId, totalBytes),
        ranges = missingRanges(fileId, totalBytes),
    )

    /** Drops one chunk (used when a resumed chunk fails its tag and has to be resent). */
    fun invalidate(fileId: String, index: Int) {
        states[fileId]?.hashes?.remove(index)
    }

    /** Forget a file: its transfer finished, failed for good, or was cancelled. */
    fun forget(fileId: String) {
        states.remove(fileId)
    }

    fun forgetAll() = states.clear()

    private fun range(firstChunk: Int, lastChunk: Int, totalBytes: Long): ByteRange = ByteRange(
        start = firstChunk.toLong() * chunkSize,
        endExclusive = ByteRange.ofChunk(lastChunk, chunkSize, totalBytes).endExclusive,
    )

    companion object {
        /** Mirrors TransferProtocol.CHUNK_BYTES; duplicated so the domain stays standalone. */
        const val DEFAULT_CHUNK_SIZE = 64 * 1024
    }
}
