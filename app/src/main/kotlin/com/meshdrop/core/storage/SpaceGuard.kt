package com.meshdrop.core.storage

import com.meshdrop.core.util.Format

/**
 * One volume as the platform reports it.
 *
 * Wrapped so callers hand [SpaceGuard] something that says what it is, rather than two
 * unlabelled Longs that are easy to pass the wrong way round - swapping them silently turns
 * "4 GB free of 64 GB" into "64 GB free of 4 GB", which reads as plenty of room.
 */
data class VolumeSpace(
    val freeBytes: Long,
    val totalBytes: Long = 0L,
) {
    /** A platform call that returned nothing usable. Treated as "unknown", never as "full". */
    val isUnknown: Boolean get() = freeBytes <= 0L

    companion object {
        val UNKNOWN = VolumeSpace(0L, 0L)
    }
}

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

    /**
     * Convenience for the common case: a batch of [batchBytes] onto a volume with [freeBytes].
     *
     * A [VolumeSpace.UNKNOWN] volume - free and total both zero, i.e. the platform did not
     * answer - responds [SpaceVerdict.ENOUGH]: a platform that will not report its free space
     * is not a reason to refuse a transfer. A negative report is not unknown, it is wrong, and
     * is still treated as zero bytes free.
     */
    fun forBatch(
        batchBytes: Long,
        freeBytes: Long,
        totalBytes: Long = 0L,
        spooled: Boolean = false,
    ): SpaceCheck = if (freeBytes == 0L && totalBytes == 0L) {
        // Exactly the "the platform did not answer" reading (see VolumeSpace.UNKNOWN). A
        // *negative* report is a different thing: that is a reading we did not understand,
        // and it still goes through the normal path and comes out NOT_ENOUGH.
        SpaceCheck(SpaceVerdict.ENOUGH, requiredFor(batchBytes, spooled), 0L, 0L)
    } else {
        check(requiredFor(batchBytes, spooled), freeBytes, totalBytes)
    }

    /** Same check against a platform-reported [volume]. */
    fun forVolume(
        batchBytes: Long,
        volume: VolumeSpace,
        spooled: Boolean = false,
    ): SpaceCheck = forBatch(batchBytes, volume.freeBytes, volume.totalBytes, spooled)
}
