package com.meshdrop.core.queue

import com.meshdrop.core.util.Format

/** Something waiting for its turn. */
data class QueuedTransfer(
    val jobId: String,
    val peerId: String,
    val totalBytes: Long,
    val enqueuedAtMillis: Long,
    /** Higher goes first. Reserved for "the user is staring at this screen". */
    val priority: Int = 0,
)

/** Something already using the radio. */
data class RunningTransfer(
    val jobId: String,
    val peerId: String,
    val bytesTransferred: Long = 0L,
    val totalBytes: Long = 0L,
)

/** What the scheduler decided this moment. */
data class QueuePlan(
    val start: List<QueuedTransfer>,
    val waiting: List<QueuedTransfer>,
    val limitReached: Boolean,
) {
    val startedIds: List<String> get() = start.map { it.jobId }

    fun positionOf(jobId: String): Int? = waiting.indexOfFirst { it.jobId == jobId }
        .takeIf { it >= 0 }
        ?.let { it + 1 }

    /** `Next in line`, `3rd in line` — null when this job is not waiting. */
    fun labelFor(jobId: String): String? = positionOf(jobId)?.let { position ->
        if (position == 1) "Next in line" else "${ordinal(position)} in line"
    }
}

/** 1st, 2nd, 3rd, 4th … 11th, 12th, 13th, 21st. */
internal fun ordinal(value: Int): String = when {
    value % 100 in 11..13 -> "${value}th"
    value % 10 == 1 -> "${value}st"
    value % 10 == 2 -> "${value}nd"
    value % 10 == 3 -> "${value}rd"
    else -> "${value}th"
}

/**
 * Who sends next.
 *
 * Wi-Fi is one radio. Letting five batches run at once does not make any of them finish
 * sooner — it makes all of them finish later, and it turns the progress bar on each screen
 * into a lie, because every one of them is really getting a fifth of the link. A small
 * concurrency limit plus one batch per peer is both faster in practice and honest in the
 * UI.
 *
 * Pure on purpose: the engine holds the state, this decides the order, and the rules can
 * be argued about in a test.
 */
object TransferScheduler {

    /** Two concurrent batches: enough to keep the pipe full across a hiccup, no more. */
    const val DEFAULT_MAX_CONCURRENT = 2

    /** One batch per peer at a time — the receiver accepts one handshake at a time anyway. */
    const val DEFAULT_MAX_PER_PEER = 1

    /**
     * Picks the jobs to start now.
     *
     * Order: highest priority first, then whoever has been waiting longest. A job is
     * skipped (not dropped) when the peer it targets already has a batch in flight, so it
     * starts as soon as that one finishes.
     */
    fun schedule(
        running: List<RunningTransfer>,
        waiting: List<QueuedTransfer>,
        maxConcurrent: Int = DEFAULT_MAX_CONCURRENT,
        maxPerPeer: Int = DEFAULT_MAX_PER_PEER,
    ): QueuePlan {
        val ordered = waiting.sortedWith(
            compareByDescending<QueuedTransfer> { it.priority }
                .thenBy { it.enqueuedAtMillis }
                .thenBy { it.jobId },
        )

        val slots = (maxConcurrent - running.size).coerceAtLeast(0)
        val perPeer = running.groupingBy { it.peerId }.eachCount().toMutableMap()

        val start = ArrayList<QueuedTransfer>()
        val stillWaiting = ArrayList<QueuedTransfer>()

        for (job in ordered) {
            val busyWithPeer = (perPeer[job.peerId] ?: 0) >= maxPerPeer
            if (start.size < slots && !busyWithPeer) {
                start += job
                perPeer[job.peerId] = (perPeer[job.peerId] ?: 0) + 1
            } else {
                stillWaiting += job
            }
        }

        return QueuePlan(
            start = start,
            waiting = stillWaiting,
            limitReached = stillWaiting.isNotEmpty(),
        )
    }

    /**
     * Rough wait for a job in position [position], given what is already moving.
     *
     * Deliberately coarse — it is a hint under a progress bar, not a promise. Returns null
     * when there is no useful estimate (nothing is running, or nothing has been measured).
     */
    fun estimatedWaitMillis(
        position: Int,
        running: List<RunningTransfer>,
        bytesPerSecond: Long,
    ): Long? {
        if (position <= 0 || bytesPerSecond <= 0L || running.isEmpty()) return null
        val aheadBytes = running.sumOf { (it.totalBytes - it.bytesTransferred).coerceAtLeast(0L) }
        // Jobs behind the running ones are unknown, so each is assumed to be the same size
        // as the average of what is in flight. Coarse on purpose: it is a hint, not a promise.
        val averageJob = aheadBytes / running.size
        val totalBytes = aheadBytes + averageJob * (position - 1)
        return (totalBytes * 1000L) / bytesPerSecond
    }

    /** `2 jobs waiting · next in about 12s`. */
    fun statusLine(plan: QueuePlan, bytesPerSecond: Long, running: List<RunningTransfer>): String? {
        if (plan.waiting.isEmpty()) return null
        val count = plan.waiting.size
        val jobs = if (count == 1) "1 job waiting" else "$count jobs waiting"
        val wait = estimatedWaitMillis(1, running, bytesPerSecond)
            ?.let { Format.duration(it) }
            ?.let { " · next in about $it" }
            ?: ""
        return jobs + wait
    }
}
