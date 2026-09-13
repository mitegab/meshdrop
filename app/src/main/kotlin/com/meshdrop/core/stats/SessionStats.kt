package com.meshdrop.core.stats

import com.meshdrop.core.model.TransferDirection
import com.meshdrop.core.model.TransferJob
import com.meshdrop.core.model.TransferStatus
import com.meshdrop.core.util.Format

/**
 * What has moved through this device.
 *
 * MeshDrop has no account and no server, so "your stats" can only ever mean this session
 * (or, once the log is on disk, what the log still holds). That is worth saying plainly on
 * the screen rather than implying a lifetime total — but the numbers themselves are useful:
 * they answer "did that file actually go?" and "is this link normally this slow?".
 */
data class SessionStats(
    val transfers: Int = 0,
    val files: Int = 0,
    val bytesSent: Long = 0L,
    val bytesReceived: Long = 0L,
    val succeeded: Int = 0,
    val failed: Int = 0,
    val cancelled: Int = 0,
    val rejected: Int = 0,
    /** Sum of the time transfers were actually running. */
    val activeMillis: Long = 0L,
    val peakBytesPerSecond: Long = 0L,
    val retries: Int = 0,
    /** The device most bytes went to or came from. */
    val topPeer: String? = null,
) {

    val totalBytes: Long get() = bytesSent + bytesReceived

    val isEmpty: Boolean get() = transfers == 0

    val finished: Int get() = succeeded + failed + cancelled + rejected

    val successRate: Float get() = if (finished == 0) 0f else succeeded.toFloat() / finished.toFloat()

    /** Measured over the time transfers were running, not over wall clock. */
    val averageBytesPerSecond: Long
        get() = if (activeMillis <= 0L) 0L else totalBytes * 1000L / activeMillis

    /** `4 transfers · 128 MB · 9.4 MB/s` */
    val headline: String
        get() = when {
            isEmpty -> "Nothing has moved yet"
            else -> listOf(
                Format.count(transfers, "transfer"),
                Format.bytes(totalBytes),
                Format.speed(averageBytesPerSecond),
            ).joinToString(" · ")
        }

    /** The part worth showing when something went wrong. */
    val outcomeLine: String?
        get() = when {
            isEmpty -> null
            failed == 0 && cancelled == 0 && rejected == 0 -> null
            else -> listOf(
                if (failed > 0) Format.count(failed, "failed") else null,
                if (cancelled > 0) Format.count(cancelled, "cancelled") else null,
                if (rejected > 0) Format.count(rejected, "declined") else null,
            ).filterNotNull().joinToString(" · ")
        }

    companion object {

        /**
         * Folds a list of jobs into totals.
         *
         * Only finished jobs count towards the outcome numbers; a transfer still running
         * contributes its bytes and its time so far, so the readouts move while you watch.
         */
        fun from(jobs: List<TransferJob>, nowMillis: Long = System.currentTimeMillis()): SessionStats {
            if (jobs.isEmpty()) return SessionStats()

            var sent = 0L
            var received = 0L
            var files = 0
            var succeeded = 0
            var failed = 0
            var cancelled = 0
            var rejected = 0
            var active = 0L
            var peak = 0L
            var retries = 0
            val perPeer = HashMap<String, Long>()

            for (job in jobs) {
                val bytes = job.bytesTransferred.coerceAtLeast(0L)
                when (job.direction) {
                    TransferDirection.SEND -> sent += bytes
                    TransferDirection.RECEIVE -> received += bytes
                }
                files += job.completedFiles
                when (job.status) {
                    TransferStatus.COMPLETED -> succeeded++
                    TransferStatus.FAILED -> failed++
                    TransferStatus.CANCELLED -> cancelled++
                    TransferStatus.REJECTED -> rejected++
                    else -> Unit
                }
                active += ((job.finishedAtMillis ?: nowMillis) - job.startedAtMillis).coerceAtLeast(0L)
                peak = maxOf(peak, job.bytesPerSecond, job.averageBytesPerSecond)
                retries += job.retryCount
                perPeer[job.peerName] = (perPeer[job.peerName] ?: 0L) + bytes
            }

            return SessionStats(
                transfers = jobs.size,
                files = files,
                bytesSent = sent,
                bytesReceived = received,
                succeeded = succeeded,
                failed = failed,
                cancelled = cancelled,
                rejected = rejected,
                activeMillis = active,
                peakBytesPerSecond = peak,
                retries = retries,
                topPeer = perPeer.maxByOrNull { it.value }?.takeIf { it.value > 0L }?.key,
            )
        }
    }
}
