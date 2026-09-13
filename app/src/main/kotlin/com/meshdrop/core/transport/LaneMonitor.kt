package com.meshdrop.core.transport

import com.meshdrop.core.model.LaneActivity
import com.meshdrop.core.model.TransportLane

/** What the failover logic concluded, and why. */
data class LaneDecision(
    val stay: Boolean,
    val lane: TransportLane? = null,
    val reason: String? = null,
) {
    val failedOver: Boolean get() = !stay && lane != null

    companion object {
        fun keep(reason: String? = null) = LaneDecision(stay = true, reason = reason)

        fun moveTo(lane: TransportLane, reason: String) = LaneDecision(stay = false, lane = lane, reason = reason)
    }
}

/**
 * Deciding when to give up on a pipe.
 *
 * A Wi-Fi transfer does not usually fail loudly. What actually happens is that a phone
 * walks towards the edge of the network, the rate falls off a cliff, and the socket sits
 * there for a minute producing nothing while the progress bar holds still. The honest
 * thing to do at that point is move to another lane — and the UI already has a
 * `justFailedOver` flag and a route line that animates when it happens.
 *
 * Two signals, either of which is enough:
 *  * **stall** — no bytes at all for [STALL_AFTER_MILLIS], which is longer than any normal
 *    chunk gap and shorter than a person's patience;
 *  * **crawl** — bytes are moving but below [MIN_RATE_BYTES_PER_SECOND] for long enough
 *    that the remaining time is no longer sensible.
 *
 * Pure, so the thresholds can be argued about in a test rather than on a device.
 */
object LaneFailover {

    /** No progress at all for this long means the lane is gone. */
    const val STALL_AFTER_MILLIS = 4_000L

    /** Below this, the transfer is technically alive and uselessly slow. */
    const val MIN_RATE_BYTES_PER_SECOND = 32L * 1024

    /** A lane has to be this bad for this long before we act on the rate alone. */
    const val SLOW_FOR_MILLIS = 8_000L

    fun isStalled(lastProgressMillis: Long, nowMillis: Long, stallAfterMillis: Long = STALL_AFTER_MILLIS): Boolean =
        nowMillis - lastProgressMillis >= stallAfterMillis

    fun isCrawling(bytesPerSecond: Long, minimum: Long = MIN_RATE_BYTES_PER_SECOND): Boolean =
        bytesPerSecond in 1 until minimum

    /**
     * The best lane to move to, or null when there is nowhere better to go.
     *
     * "Better" is the ordering the rest of the app already uses ([TransportLane.rank]), and
     * a lane is only a candidate if the transport behind it is actually available — being
     * told to fail over to Bluetooth on a phone with Bluetooth off is worse than staying.
     */
    fun nextLane(from: TransportLane, candidates: Collection<TransportLane>): TransportLane? = candidates
        .filter { it != from }
        .minByOrNull { it.rank }

    fun decide(
        current: TransportLane,
        lastProgressMillis: Long,
        bytesPerSecond: Long,
        nowMillis: Long,
        candidates: Collection<TransportLane> = emptyList(),
        slowSinceMillis: Long? = null,
        stallAfterMillis: Long = STALL_AFTER_MILLIS,
        minRate: Long = MIN_RATE_BYTES_PER_SECOND,
        slowForMillis: Long = SLOW_FOR_MILLIS,
    ): LaneDecision {
        val stalled = isStalled(lastProgressMillis, nowMillis, stallAfterMillis)
        val crawling = isCrawling(bytesPerSecond, minRate) &&
            slowSinceMillis != null &&
            nowMillis - slowSinceMillis >= slowForMillis

        if (!stalled && !crawling) return LaneDecision.keep()

        val target = nextLane(current, candidates)
            ?: return LaneDecision.keep(
                if (stalled) "stalled, and there is no other lane" else "crawling, and there is no other lane",
            )

        val reason = if (stalled) {
            "no data for ${(nowMillis - lastProgressMillis) / 1000}s"
        } else {
            "under ${minRate / 1024} KB/s for ${(nowMillis - (slowSinceMillis ?: nowMillis)) / 1000}s"
        }
        return LaneDecision.moveTo(target, reason)
    }

    /**
     * The lane strip the transfer card draws: the live lane first, then the ones we left,
     * each carrying the lane it failed over from so the route line can animate.
     */
    fun activities(
        current: TransportLane,
        bytesPerSecond: Long,
        history: List<TransportLane> = emptyList(),
    ): List<LaneActivity> {
        val previous = history.filter { it != current }.distinct()
        val currentActivity = LaneActivity(
            lane = current,
            bytesPerSecond = bytesPerSecond,
            active = true,
            failedOverFrom = previous.lastOrNull(),
        )
        val abandoned = previous.reversed().mapIndexed { index, lane ->
            LaneActivity(
                lane = lane,
                bytesPerSecond = 0L,
                active = false,
                failedOverFrom = previous.reversed().getOrNull(index + 1),
            )
        }
        return listOf(currentActivity) + abandoned
    }

    /** One line for the Advanced panel: `Wi-Fi LAN → Wi-Fi Direct (no data for 5s)`. */
    fun describe(from: TransportLane, decision: LaneDecision): String? = decision.lane?.let { to ->
        "${from.label} → ${to.label}${decision.reason?.let { " ($it)" } ?: ""}"
    }
}
