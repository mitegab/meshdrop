package com.meshdrop.core.transport

/** How a peer's measured round trip compares to the others. */
enum class LatencyGrade(val label: String) {
    /** Same room, same access point: a LAN hop and nothing else. */
    EXCELLENT("Excellent"),
    GOOD("Good"),
    POOR("Poor"),

    /** No answer at all, or the probe never ran. */
    UNKNOWN("Unknown"),
}

/**
 * A peer's round trip, as a smoothed number.
 *
 * One sample is not a measurement: on Wi-Fi a single probe can hit a retry and come back at
 * 400 ms while the link is genuinely fine. An exponential average over the last few probes
 * is what "how good is this connection" actually means, and it stops one bad sample from
 * reordering the peer list under the user's thumb.
 */
data class LatencyEstimate(
    val millis: Long? = null,
    val samples: Int = 0,
    val failures: Int = 0,
    val lastProbeMillis: Long = 0L,
    /** Highest round trip seen, so the UI can say "peaks at 400 ms" rather than hide it. */
    val worstMillis: Long = 0L,
) {

    /**
     * Averages [sampleMillis] in.
     *
     * Weighted so recent probes matter more than old ones, but never so heavily that one
     * spike moves the number by more than a third of the distance to it.
     */
    fun observe(sampleMillis: Long, nowMillis: Long): LatencyEstimate {
        val smoothed = when {
            millis == null -> sampleMillis
            else -> (millis + ((sampleMillis - millis) * WEIGHT)).toLong()
        }.coerceAtLeast(0L)
        return LatencyEstimate(
            millis = smoothed,
            samples = samples + 1,
            failures = failures,
            lastProbeMillis = nowMillis,
            worstMillis = maxOf(worstMillis, sampleMillis),
        )
    }

    /** The peer did not answer. Counted, because repeated failures are information. */
    fun observeFailure(nowMillis: Long): LatencyEstimate =
        copy(failures = failures + 1, lastProbeMillis = nowMillis)

    /** True when the number is old enough that it should not be trusted over a fresh one. */
    fun isStale(nowMillis: Long, staleAfterMillis: Long = ProbePolicy.STALE_AFTER_MILLIS): Boolean =
        millis == null || nowMillis - lastProbeMillis > staleAfterMillis

    fun grade(): LatencyGrade = when {
        millis == null -> LatencyGrade.UNKNOWN
        millis!! <= ProbePolicy.EXCELLENT_AT_MILLIS -> LatencyGrade.EXCELLENT
        millis!! <= ProbePolicy.GOOD_AT_MILLIS -> LatencyGrade.GOOD
        else -> LatencyGrade.POOR
    }

    /** `12 ms` — or a dash before anything has been measured. */
    fun label(): String = millis?.let { "$it ms" } ?: "—"

    companion object {
        /** How much of a new sample lands in the average. A third: responsive, not twitchy. */
        const val WEIGHT = 0.34

        val NONE = LatencyEstimate()
    }
}

/**
 * When to measure, and when to stop bothering.
 *
 * Probing costs a connection attempt per peer, and the peer's transfer server has to answer
 * it, so it is not free. It is worth doing while the user is looking at a peer list — the
 * number is right there next to the name — and pointless once the screen is gone.
 */
object ProbePolicy {

    /** How often a peer is probed while the list is on screen. */
    const val PROBE_INTERVAL_MILLIS = 5_000L

    /** Below this the connection is as good as Wi-Fi gets. */
    const val EXCELLENT_AT_MILLIS = 15L

    /** Above this the user will notice on a large file. */
    const val GOOD_AT_MILLIS = 80L

    /** After this long without a probe, the number is a rumour. */
    const val STALE_AFTER_MILLIS = 30_000L

    /** A peer that keeps refusing is probed less and less often, up to a minute. */
    const val MAX_BACKOFF_MILLIS = 60_000L

    /**
     * True when [peerId] is due another probe.
     *
     * Failures back off: a device that has walked out of range should not be connected to
     * every five seconds for as long as its advertisement lingers.
     */
    fun isDue(
        estimate: LatencyEstimate,
        nowMillis: Long,
        intervalMillis: Long = PROBE_INTERVAL_MILLIS,
    ): Boolean {
        if (estimate.lastProbeMillis <= 0L) return true
        val backoff = if (estimate.failures > 0) backoffMillis(estimate.failures) else 0L
        return nowMillis - estimate.lastProbeMillis >= intervalMillis + backoff
    }

    /** 5s, 10s, 20s, 40s, 60s, 60s… */
    fun backoffMillis(failures: Int): Long {
        if (failures <= 0) return 0L
        var backoff = PROBE_INTERVAL_MILLIS
        repeat((failures - 1).coerceAtMost(16)) { backoff *= 2 }
        return backoff.coerceAtMost(MAX_BACKOFF_MILLIS)
    }

    /** True when nothing here is worth measuring yet: no address, no port. */
    fun canProbe(hasAddress: Boolean, port: Int): Boolean = hasAddress && port > 0

    /**
     * Which peers to probe this pass, worst-served first.
     *
     * Never-measured peers go before stale ones, and a peer with a fresh number is skipped
     * entirely: on a list of twenty devices that is the difference between one probe per
     * pass and twenty.
     */
    fun duePeers(
        peerIds: List<String>,
        estimates: Map<String, LatencyEstimate>,
        nowMillis: Long,
        limit: Int = 4,
    ): List<String> = peerIds
        .filter { isDue(estimates[it] ?: LatencyEstimate(), nowMillis) }
        .sortedWith(
            compareBy<String> { estimates[it]?.millis != null }
                .thenBy { estimates[it]?.lastProbeMillis ?: 0L },
        )
        .take(limit.coerceAtLeast(1))
}
