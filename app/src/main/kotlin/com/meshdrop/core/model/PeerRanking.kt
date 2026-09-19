package com.meshdrop.core.model

/**
 * How good a nearby device looks as a transfer target right now.
 *
 * The nearby list used to be "whatever discovery returned, in arrival order", which reads
 * as a ranking but is not one. A peer that is five seconds stale on Wi-Fi Direct is a much
 * better target than one that went quiet fifteen seconds ago on a guest network, and the
 * list should say so — with the reason, not just a bar.
 *
 * @param score 0..100, comparable between peers
 * @param reachable whether we actually have an address and a port to connect to
 * @param freshnessMillis how long ago we last heard from it
 * @param reasons short human strings, in the order they contributed
 */
data class PeerScore(
    val score: Int,
    val reachable: Boolean,
    val freshnessMillis: Long,
    val reasons: List<String> = emptyList(),
) {

    val grade: Grade
        get() = when {
            !reachable -> Grade.UNREACHABLE
            score >= 85 -> Grade.EXCELLENT
            score >= 65 -> Grade.GOOD
            score >= 40 -> Grade.FAIR
            else -> Grade.WEAK
        }

    /** One line for the device row: `Verified · seen 2s ago`. */
    val headline: String
        get() = reasons.joinToString(" · ").ifBlank { grade.label }

    enum class Grade(val label: String) {
        EXCELLENT("Excellent"),
        GOOD("Good"),
        FAIR("Fair"),
        WEAK("Weak"),
        UNREACHABLE("Not reachable"),
    }
}

/**
 * Turns "what discovery knows" into an ordering.
 *
 * Pure on purpose: the weights are the interesting part, and they are the kind of thing
 * that should be argued about in a test rather than discovered by tapping devices.
 */
object PeerRanking {

    /** Matches the coordinator's stale-peer window: after this, a peer is gone anyway. */
    const val STALE_WINDOW_MILLIS = 20_000L

    const val MAX_SCORE = 100

    private const val WEIGHT_REACHABLE = 50
    private const val WEIGHT_FRESHNESS = 30
    private const val WEIGHT_VERIFIED = 6
    private const val WEIGHT_LATENCY = 8

    fun score(
        peer: PeerDevice,
        nowMillis: Long = System.currentTimeMillis(),
        verified: Boolean = false,
        latencyMillis: Long? = null,
    ): PeerScore {
        val reasons = ArrayList<String>()
        var total = 0

        if (peer.isReachable()) {
            total += WEIGHT_REACHABLE
            reasons += "reachable"
        } else {
            reasons += "no address yet"
        }

        val age = (nowMillis - peer.lastSeenMillis).coerceAtLeast(0L)
        val freshness = (WEIGHT_FRESHNESS * (STALE_WINDOW_MILLIS - age) / STALE_WINDOW_MILLIS)
            .coerceIn(0L, WEIGHT_FRESHNESS.toLong()).toInt()
        total += freshness
        reasons += when {
            age < 2_000L -> "just seen"
            age < STALE_WINDOW_MILLIS -> "seen ${age / 1000}s ago"
            else -> "gone quiet"
        }

        val lane = peer.transferLane
        val laneBonus = when (lane.rank) {
            0 -> 12
            1 -> 8
            2 -> 4
            else -> 2
        }
        total += laneBonus
        reasons += lane.label

        if (verified) {
            total += WEIGHT_VERIFIED
            reasons += "verified"
        }

        if (latencyMillis != null && latencyMillis >= 0L) {
            val latencyBonus = when {
                latencyMillis <= 20L -> WEIGHT_LATENCY
                latencyMillis <= 60L -> 5
                latencyMillis <= 150L -> 2
                else -> 0
            }
            total += latencyBonus
            reasons += "${latencyMillis}ms away"
        }

        return PeerScore(
            score = total.coerceIn(0, MAX_SCORE),
            reachable = peer.isReachable(),
            freshnessMillis = age,
            reasons = reasons,
        )
    }

    /**
     * Best target first; peers we cannot connect to always go last, whatever they scored.
     *
     * Ties break on the device name so the list does not reshuffle between recompositions.
     */
    fun rank(
        peers: List<PeerDevice>,
        nowMillis: Long = System.currentTimeMillis(),
        verifiedIds: Set<String> = emptySet(),
        latenciesMillis: Map<String, Long> = emptyMap(),
    ): List<PeerDevice> = peers.sortedWith(
        compareByDescending<PeerDevice> { it.isReachable() }
            .thenByDescending { score(it, nowMillis, it.id in verifiedIds, latenciesMillis[it.id]).score }
            .thenBy { it.name },
    )
}

/** Scored view of a peer, for UIs that want the number and the reason together. */
data class RankedPeer(
    val peer: PeerDevice,
    val score: PeerScore,
)

fun List<PeerDevice>.scored(
    nowMillis: Long = System.currentTimeMillis(),
    verifiedIds: Set<String> = emptySet(),
    latenciesMillis: Map<String, Long> = emptyMap(),
): List<RankedPeer> = PeerRanking.rank(this, nowMillis, verifiedIds, latenciesMillis)
    .map { RankedPeer(it, PeerRanking.score(it, nowMillis, it.id in verifiedIds, latenciesMillis[it.id])) }
