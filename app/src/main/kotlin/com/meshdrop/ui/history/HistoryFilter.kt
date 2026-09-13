package com.meshdrop.ui.history

import com.meshdrop.core.model.SecurityState
import com.meshdrop.core.model.TransferDirection
import com.meshdrop.core.model.TransferJob
import com.meshdrop.core.model.TransferStatus
import com.meshdrop.core.model.TrustLevel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Which transfers a filter chip lets through. */
enum class HistoryScope(val label: String) {
    ALL("All"),
    RECEIVED("Received"),
    SENT("Sent"),
    FAILED("Problems"),
    VERIFIED("Verified"),
    ;

    companion object {
        /** The chips in the order they should appear; ALL is the default and never shown. */
        val CHIPS = listOf(RECEIVED, SENT, FAILED, VERIFIED)
    }
}

/** How a filtered list is ordered. */
enum class HistoryOrder(val label: String) {
    NEWEST("Newest"),
    OLDEST("Oldest"),
    LARGEST("Largest"),
    FASTEST("Fastest"),
    NAME("Device"),
}

/** What the search box and the chips are asking for. */
data class HistoryQuery(
    val text: String = "",
    val scope: HistoryScope = HistoryScope.ALL,
    val order: HistoryOrder = HistoryOrder.NEWEST,
) {
    val isActive: Boolean get() = text.isNotBlank() || scope != HistoryScope.ALL || order != HistoryOrder.NEWEST

    fun cleared(): HistoryQuery = HistoryQuery()
}

/** A day header and the transfers under it. */
data class HistorySection(
    val label: String,
    val jobs: List<TransferJob>,
) {
    val count: Int get() = jobs.size
}

/** Why a list came back empty, so the screen can say something useful. */
enum class HistoryEmptyReason {
    /** Nothing has ever finished. */
    NO_TRANSFERS,

    /** There are transfers, just none matching the chips or the search text. */
    NO_MATCHES,
}

/**
 * Searching, filtering and grouping the transfer feed.
 *
 * Pure and separate from the view model on purpose: the interesting behaviour here is "does
 * a search for 'pixel' match a transfer whose file was called PIXEL.jpg", and that is the
 * kind of question that should have a test rather than a device.
 */
object HistoryFiltering {

    /** Matches a peer name, a file name or a batch id, ignoring case. */
    fun matches(job: TransferJob, text: String): Boolean {
        val needle = text.trim().lowercase()
        if (needle.isEmpty()) return true
        if (job.peerName.lowercase().contains(needle)) return true
        if (job.id.lowercase().contains(needle)) return true
        return job.files.any { it.name.lowercase().contains(needle) }
    }

    fun inScope(
        job: TransferJob,
        scope: HistoryScope,
        security: SecurityState = SecurityState.UNKNOWN,
    ): Boolean = when (scope) {
        HistoryScope.ALL -> true
        HistoryScope.RECEIVED -> job.direction == TransferDirection.RECEIVE
        HistoryScope.SENT -> job.direction == TransferDirection.SEND
        HistoryScope.FAILED -> job.status == TransferStatus.FAILED || job.status == TransferStatus.CANCELLED
        HistoryScope.VERIFIED -> security.trustLevel == TrustLevel.VERIFIED
    }

    fun apply(
        jobs: List<TransferJob>,
        query: HistoryQuery = HistoryQuery(),
        security: Map<String, SecurityState> = emptyMap(),
    ): List<TransferJob> {
        val filtered = jobs.filter { job ->
            matches(job, query.text) &&
                inScope(job, query.scope, security[job.id] ?: SecurityState.UNKNOWN)
        }
        return when (query.order) {
            HistoryOrder.NEWEST -> filtered.sortedByDescending { it.finishedAtMillis ?: it.startedAtMillis }
            HistoryOrder.OLDEST -> filtered.sortedBy { it.finishedAtMillis ?: it.startedAtMillis }
            HistoryOrder.LARGEST -> filtered.sortedByDescending { it.totalBytes }
            HistoryOrder.FASTEST -> filtered.sortedByDescending { it.averageBytesPerSecond }
            HistoryOrder.NAME -> filtered.sortedWith(
                compareBy<TransferJob, String>(String.CASE_INSENSITIVE_ORDER) { it.peerName }
                    .thenByDescending { it.startedAtMillis },
            )
        }
    }

    /**
     * Groups into days, newest first.
     *
     * The day boundary is the device's own zone: "Today" has to mean the user's today, not
     * UTC's, or a transfer at 11 pm lands under tomorrow.
     */
    fun sections(
        jobs: List<TransferJob>,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<HistorySection> = jobs
        .groupBy { dayOf(it.finishedAtMillis ?: it.startedAtMillis, zone) }
        .toList()
        .sortedByDescending { it.first }
        .map { (day, list) -> HistorySection(label = labelFor(day, today), jobs = list) }

    fun dayOf(millis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    fun labelFor(day: LocalDate, today: LocalDate = LocalDate.now()): String = when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> day.format(DATE_FORMAT)
    }

    fun emptyReason(allJobs: List<TransferJob>, shown: List<TransferJob>): HistoryEmptyReason? = when {
        shown.isNotEmpty() -> null
        allJobs.isEmpty() -> HistoryEmptyReason.NO_TRANSFERS
        else -> HistoryEmptyReason.NO_MATCHES
    }

    /** What the empty state should say, in the words the screen uses. */
    fun emptyMessage(reason: HistoryEmptyReason?, query: HistoryQuery): String? = when (reason) {
        HistoryEmptyReason.NO_TRANSFERS -> "Nothing has been sent or received yet"
        HistoryEmptyReason.NO_MATCHES ->
            if (query.text.isNotBlank()) "Nothing matches “${query.text.trim()}”" else "No transfers in this view"
        null -> null
    }

    private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy")
}
