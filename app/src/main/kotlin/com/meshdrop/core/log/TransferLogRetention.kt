package com.meshdrop.core.log

/**
 * How much history to keep.
 *
 * A transfer log that grows forever is a liability on a phone: it is the only place
 * MeshDrop records who sent what, and "records everything forever" is not a privacy
 * feature. The default keeps three months and at most [DEFAULT_MAX_ENTRIES] transfers,
 * and the Settings screen exposes the age in days so a user can shorten it to a week.
 *
 * @param maxAgeDays entries finished more than this many days ago are dropped
 * @param maxEntries hard cap, newest kept — the size guard for a device that transfers
 *                   hundreds of files a day
 * @param keepFailures failures are usually the entries somebody goes looking for
 *                   ("did that file ever arrive?"), so they can outlive the window
 */
data class RetentionPolicy(
    val maxAgeDays: Int = DEFAULT_MAX_AGE_DAYS,
    val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    val keepFailures: Boolean = true,
) {

    val maxAgeMillis: Long get() = maxAgeDays.toLong() * MILLIS_PER_DAY

    companion object {
        const val DEFAULT_MAX_AGE_DAYS = 90
        const val DEFAULT_MAX_ENTRIES = TransferLogStore.DEFAULT_CAPACITY
        const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

        /** Everything is kept: used when the user has not chosen a policy yet. */
        val NEVER = RetentionPolicy(maxAgeDays = Int.MAX_VALUE, maxEntries = Int.MAX_VALUE)
    }
}

/** What a prune pass did, so the caller can report it and rewrite the sink once. */
data class PruneResult(
    val kept: List<TransferLogEntry>,
    val dropped: List<TransferLogEntry>,
) {
    val changed: Boolean get() = dropped.isNotEmpty()

    val freedBytes: Long get() = dropped.sumOf { it.totalBytes }
}

/**
 * Decides which log entries have earned their place.
 *
 * Pure on purpose: retention is the kind of rule that is easy to get subtly wrong
 * (off-by-one on the day boundary, dropping the newest instead of the oldest) and
 * impossible to notice in the app until the entry you wanted is gone.
 */
object TransferLogRetention {

    /**
     * Applies [policy] to [entries] (any order) and returns what stays, newest first.
     *
     * Entries whose job id is in [pinnedJobIds] are never dropped by age: a transfer
     * whose files are still in the vault is provenance for a file that still exists.
     */
    fun prune(
        entries: List<TransferLogEntry>,
        nowMillis: Long,
        policy: RetentionPolicy,
        pinnedJobIds: Set<String> = emptySet(),
    ): PruneResult {
        val newestFirst = entries.sortedByDescending { it.finishedAtMillis }
        val survivors = ArrayList<TransferLogEntry>(newestFirst.size)
        val dropped = ArrayList<TransferLogEntry>()

        for (entry in newestFirst) {
            val pinned = entry.jobId in pinnedJobIds
            val tooOld = nowMillis - entry.finishedAtMillis > policy.maxAgeMillis
            val isFailure = !entry.succeeded
            val keep = pinned || !tooOld || (isFailure && policy.keepFailures && !pastDoubleAge(entry, nowMillis, policy))
            if (keep) survivors += entry else dropped += entry
        }

        // Size cap last, so a burst of tiny transfers cannot push out the one big
        // transfer from this morning.
        val capped = if (survivors.size > policy.maxEntries) {
            dropped += survivors.drop(policy.maxEntries)
            survivors.take(policy.maxEntries)
        } else {
            survivors
        }
        return PruneResult(kept = capped, dropped = dropped)
    }

    /** Failures get one extra window before they go, then they go. */
    private fun pastDoubleAge(entry: TransferLogEntry, nowMillis: Long, policy: RetentionPolicy): Boolean =
        nowMillis - entry.finishedAtMillis > policy.maxAgeMillis * 2
}
