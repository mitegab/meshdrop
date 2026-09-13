package com.meshdrop.core.storage

import com.meshdrop.core.util.Format

/** What the free-space check concluded. */
enum class SpaceVerdict {
    /** Comfortable: the batch and the headroom both fit. */
    ENOUGH,

    /** It fits, but it would eat into the headroom the system expects to keep. */
    TIGHT,

    /** It does not fit. Accepting would fail halfway through a large file. */
    NOT_ENOUGH,
}

/**
 * The answer to "can this batch land here?".
 *
 * @param requiredBytes what the transfer actually needs on disk
 * @param freeBytes what the volume reports as available
 * @param headroomBytes the space deliberately left alone
 */
data class SpaceCheck(
    val verdict: SpaceVerdict,
    val requiredBytes: Long,
    val freeBytes: Long,
    val headroomBytes: Long,
) {

    val shortfallBytes: Long get() = (requiredBytes + headroomBytes - freeBytes).coerceAtLeast(0L)

    val fits: Boolean get() = verdict != SpaceVerdict.NOT_ENOUGH

    /** Plain language, no byte counts unless there is a problem to explain. */
    val message: String?
        get() = when (verdict) {
            SpaceVerdict.ENOUGH -> null
            SpaceVerdict.TIGHT -> "This will use most of the free space on this device"
            SpaceVerdict.NOT_ENOUGH ->
                "Not enough space — ${Format.bytes(shortfallBytes)} short of the ${Format.bytes(requiredBytes)} needed"
        }
}

/**
 * Deciding whether to accept a batch before a single byte arrives.
 *
 * Running out of space halfway through a 4 GB video is the worst failure mode in the app:
 * the sender has already spent the time, the receiver has a truncated file, and the only
 * honest message is "it failed". A cheap check up front turns that into "no" before
 * either side has committed.
 *
 * @see requiredFor — receiving can need twice the batch size while a file is spooled
 */
object SpaceGuard {

    /**
     * Space left alone even when it is technically free.
     *
     * Android needs room to breathe (MediaStore writes, app caches, the OS itself), and a
     * device at 100% full misbehaves in ways that have nothing to do with MeshDrop.
     */
    const val HEADROOM_BYTES = 256L * 1024 * 1024

    /** On a small device the absolute headroom is too much; a fraction is used instead. */
    const val HEADROOM_FRACTION = 0.05

    /**
     * What a batch of [batchBytes] needs on the destination volume.
     *
     * @param spooled true when files are written to a scratch directory first and copied
     *                into place afterwards — the honest number is then twice the batch,
     *                because both copies exist at the same moment.
     */
    fun requiredFor(batchBytes: Long, spooled: Boolean = false): Long {
        val size = batchBytes.coerceAtLeast(0L)
        return if (spooled) size * 2 else size
    }

    fun check(
        requiredBytes: Long,
        freeBytes: Long,
        totalBytes: Long = 0L,
        headroomBytes: Long = HEADROOM_BYTES,
        headroomFraction: Double = HEADROOM_FRACTION,
    ): SpaceCheck {
        val fractionHeadroom = (totalBytes * headroomFraction).toLong()
        val headroom = maxOf(headroomBytes, fractionHeadroom).coerceAtMost(freeBytes.coerceAtLeast(0L))
        val required = requiredBytes.coerceAtLeast(0L)
        val verdict = when {
            freeBytes < required -> SpaceVerdict.NOT_ENOUGH
            freeBytes < required + headroom -> SpaceVerdict.TIGHT
            else -> SpaceVerdict.ENOUGH
        }
        return SpaceCheck(
            verdict = verdict,
            requiredBytes = required,
            freeBytes = freeBytes.coerceAtLeast(0L),
            headroomBytes = headroom,
        )
    }

    /** Convenience for the common case: a batch of [batchBytes] onto a volume with [freeBytes]. */
    fun forBatch(
        batchBytes: Long,
        freeBytes: Long,
        totalBytes: Long = 0L,
        spooled: Boolean = false,
    ): SpaceCheck = check(requiredFor(batchBytes, spooled), freeBytes, totalBytes)
}
