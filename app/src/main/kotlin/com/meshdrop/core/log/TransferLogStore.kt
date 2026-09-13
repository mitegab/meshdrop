package com.meshdrop.core.log

import com.meshdrop.core.model.SecurityState
import com.meshdrop.core.model.TransferJob

/**
 * Where log lines are written.
 *
 * Kept as an interface because the domain must stay Android free: the app wires this to a
 * file in `filesDir`, tests wire it to a list, and a future desktop client can wire it to
 * anything. Implementations are expected to be cheap — a line is appended once per
 * finished transfer, not once per chunk.
 */
interface TransferLogSink {

    /** Appends one encoded entry. Must tolerate being called from a background thread. */
    fun append(line: String)

    /** Every line written so far, oldest first. Malformed lines are the caller's problem. */
    fun readAll(): List<String>

    /** Rewrites the log with exactly these lines (used when retention prunes old entries). */
    fun rewrite(lines: List<String>)
}

/**
 * Append-only log of finished transfers, newest last.
 *
 * This is the piece that makes History survive a process restart: the engine keeps jobs in
 * memory for the session, and every job that reaches a terminal state is also recorded
 * here. The store is bounded ([capacity]) so a device that never clears its history cannot
 * grow the file without limit.
 *
 * Everything is `@Synchronized`: transfers finish on IO threads and the UI reads from the
 * main thread, and the critical sections are microseconds long.
 */
class TransferLogStore(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val sink: TransferLogSink? = null,
) {

    /** Oldest first. */
    private val entries = ArrayDeque<TransferLogEntry>()

    init {
        sink?.let { source ->
            source.readAll().mapNotNull(TransferLogEntry::fromLine).forEach { entries.addLast(it) }
            trimToCapacity()
        }
    }

    /** Number of entries held in memory. */
    val size: Int
        @Synchronized get() = entries.size

    /**
     * Records a job that has reached a terminal state. Returns null (and records nothing)
     * while the transfer is still running: a log of unfinished business is not a log.
     */
    @Synchronized
    fun record(
        job: TransferJob,
        security: SecurityState = SecurityState.UNKNOWN,
        finishedAtMillis: Long = job.finishedAtMillis ?: System.currentTimeMillis(),
    ): TransferLogEntry? {
        if (!job.status.isFinished) return null
        return add(TransferLogEntry.from(job, security, finishedAtMillis))
    }

    /**
     * Adds (or replaces) one entry.
     *
     * A retried transfer finishes more than once with the same job id; the newest outcome
     * is the one worth keeping, so an existing entry for that id is overwritten in place.
     */
    @Synchronized
    fun add(entry: TransferLogEntry): TransferLogEntry {
        val existing = entries.indexOfFirst { it.jobId == entry.jobId }
        if (existing >= 0) entries[existing] = entry else entries.addLast(entry)
        trimToCapacity()
        sink?.append(entry.toLine())
        return entry
    }

    /** Newest first — the order every screen wants. */
    @Synchronized
    fun all(): List<TransferLogEntry> = entries.toList().asReversed()

    @Synchronized
    fun forJob(jobId: String): TransferLogEntry? = entries.firstOrNull { it.jobId == jobId }

    @Synchronized
    fun contains(jobId: String): Boolean = entries.any { it.jobId == jobId }

    /**
     * Replaces the whole log with [entries] and rewrites the sink.
     *
     * Used by retention: pruning changes the middle of the file, so the only honest
     * way to persist it is to write the file again.
     */
    @Synchronized
    fun replaceAll(entries: List<TransferLogEntry>) {
        val newestFirst = entries.sortedByDescending { it.finishedAtMillis }
        this.entries.clear()
        this.entries.addAll(newestFirst.asReversed())
        trimToCapacity()
        sink?.rewrite(this.entries.map { it.toLine() })
    }

    /**
     * Drops entries the [policy] no longer wants and persists the result.
     *
     * @param pinnedJobIds jobs that must survive any age rule — a transfer whose files
     *                     are still sitting in the vault is provenance for a file that
     *                     still exists.
     */
    @Synchronized
    fun prune(
        nowMillis: Long = System.currentTimeMillis(),
        policy: RetentionPolicy = RetentionPolicy(),
        pinnedJobIds: Set<String> = emptySet(),
    ): PruneResult {
        val result = TransferLogRetention.prune(all(), nowMillis, policy, pinnedJobIds)
        if (result.changed) replaceAll(result.kept)
        return result
    }

    /** Drops everything, in memory and on disk. */
    @Synchronized
    fun clear() {
        entries.clear()
        sink?.rewrite(emptyList())
    }

    private fun trimToCapacity() {
        var dropped = false
        while (entries.size > capacity) {
            entries.removeFirst()
            dropped = true
        }
        // The sink is rewritten only when something was actually dropped, so a normal
        // append stays a single write.
        if (dropped) sink?.rewrite(entries.map { it.toLine() })
    }

    companion object {
        /** A year of heavy use is a few hundred transfers; this is comfortably above that. */
        const val DEFAULT_CAPACITY = 500
    }
}
