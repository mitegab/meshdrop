package com.meshdrop.core.util

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Display smoothing.
 *
 * Raw throughput samples are noisy: they come from 64 KB chunks measured over a few hundred
 * milliseconds, so a naive readout flickers between 3.1 MB/s and 18 MB/s. The fix is two
 * sided — average the rate, and refuse to repaint a number that has not meaningfully moved.
 *
 * Pure Kotlin, so it is unit tested on the JVM.
 */
object Smoothing {

    /** Exponential moving average. [alpha] is the weight of the newest sample (0..1). */
    fun ema(previous: Double, sample: Double, alpha: Double = DEFAULT_ALPHA): Double {
        val clamped = alpha.coerceIn(0.0, 1.0)
        return previous * (1.0 - clamped) + sample * clamped
    }

    /**
     * Snaps a rate onto a coarse ladder so the text stops twitching: below 100 KB/s in
     * 5 KB/s steps, below 1 MB/s in 50 KB/s steps, above that in 250 KB/s steps.
     *
     * Rounded rather than floored: an EMA approaches the true rate from below and never
     * quite reaches it, so truncating would leave the readout permanently one rung low
     * (a steady 600 KB/s reading as 550 KB/s).
     */
    fun quantise(bytesPerSecond: Double): Double = when {
        bytesPerSecond < 100_000.0 -> (bytesPerSecond / 5_000.0).roundToLong() * 5_000.0
        bytesPerSecond < 1_000_000.0 -> (bytesPerSecond / 50_000.0).roundToLong() * 50_000.0
        else -> (bytesPerSecond / 250_000.0).roundToLong() * 250_000.0
    }

    /**
     * Only accepts a new ETA when it moved by more than [absoluteFloor] *and* more than
     * [relativeFloor] of the previous value. Returns the previous value otherwise, so a
     * countdown ticks smoothly instead of jittering between 12s and 9s.
     */
    fun stableEta(
        previous: Long?,
        candidate: Long?,
        absoluteFloor: Long = 1_000L,
        relativeFloor: Double = 0.15,
    ): Long? {
        if (candidate == null) return null
        if (previous == null) return candidate
        val delta = abs(candidate - previous)
        if (delta < absoluteFloor) return previous
        if (delta < previous * relativeFloor) return previous
        return candidate
    }

    /**
     * How steady a series of throughput samples is, from 0 (wild) to 1 (flat line).
     *
     * One minus the coefficient of variation, clamped. It exists because "12 MB/s" and
     * "12 MB/s but it has been 3 MB/s twice in the last second" are different facts, and
     * the transfer card should be able to say the second one without a second number:
     * an unsteady link is why the ETA keeps moving.
     */
    fun stability(samples: List<Long>): Double {
        if (samples.size < 2) return 1.0
        val mean = samples.average()
        if (mean <= 0.0) return 0.0
        val variance = samples.sumOf { (it - mean) * (it - mean) } / samples.size
        val deviation = kotlin.math.sqrt(variance)
        return (1.0 - deviation / mean).coerceIn(0.0, 1.0)
    }

    /**
     * True when every sample in the window is below [minimum]: the link is up and
     * producing nothing, which is the state that needs a failover rather than a spinner.
     *
     * A full window is required so the first second of a transfer is not called a stall.
     */
    fun stalled(samples: List<Long>, minimum: Long = STALL_RATE, window: Int = STALL_WINDOW): Boolean {
        if (samples.size < window) return false
        return samples.takeLast(window).all { it < minimum }
    }

    const val DEFAULT_ALPHA = 0.25

    /** Below this, a lane is producing nothing useful. Matches LaneFailover's crawl floor. */
    const val STALL_RATE = 32L * 1024

    /** Roughly the last two seconds at the UI's ~10 updates a second. */
    const val STALL_WINDOW = 20
}

/** Stateful wrapper around [Smoothing.ema] for callers that hold onto a value. */
class RateSmoother(
    initial: Double = 0.0,
    private val alpha: Double = Smoothing.DEFAULT_ALPHA,
) {
    var value: Double = initial
        private set

    /** Feeds a new sample and returns the smoothed value. */
    fun submit(sample: Double): Double {
        value = Smoothing.ema(value, sample, alpha)
        return value
    }

    /** Jumps straight to a value (used when a transfer restarts or the lane changes). */
    fun reset(to: Double = 0.0) {
        value = to
    }
}

/**
 * Everything an active-transfer screen needs that the raw job state does not give it:
 * a non-flickering speed, a non-jittering ETA, and a history window for the graph.
 *
 * Pure Kotlin so the behaviour is unit tested without a device.
 */
class TransferTelemetry(
    private val historyCapacity: Int = com.meshdrop.core.model.ThroughputHistory.DEFAULT_CAPACITY,
) {

    private val smoother = RateSmoother()
    private val history = com.meshdrop.core.model.ThroughputHistory(historyCapacity)

    @Volatile
    private var stableEta: Long? = null

    /**
     * Feeds one progress sample and returns what should be *displayed*.
     *
     * @param bytesPerSecond raw measured rate
     * @param etaMillis raw estimate, or null when unknown
     */
    fun update(bytesPerSecond: Long, etaMillis: Long?): SmoothedReading {
        val smoothed = smoother.submit(bytesPerSecond.toDouble())
        val quantised = Smoothing.quantise(smoothed)
        history.add(quantised.toLong())
        stableEta = Smoothing.stableEta(stableEta, etaMillis)
        val samples = history.recent()
        return SmoothedReading(
            bytesPerSecond = quantised.toLong(),
            etaMillis = stableEta,
            stability = Smoothing.stability(samples),
            stalled = Smoothing.stalled(samples),
        )
    }

    /** 0..1, how flat the recent throughput has been. */
    fun stability(): Double = Smoothing.stability(history.recent())

    /** True when the link has been producing nothing useful for the whole stall window. */
    fun stalled(): Boolean = Smoothing.stalled(history.recent())

    /** Oldest → newest samples for the throughput graph. */
    fun samples(): List<Long> = history.snapshot()

    fun peak(): Long = history.peak()

    fun reset() {
        smoother.reset()
        history.clear()
        stableEta = null
    }
}

/** What the UI should print right now. */
data class SmoothedReading(
    val bytesPerSecond: Long,
    val etaMillis: Long?,
    /** 0..1; below [UNSTEADY] the ETA line is allowed to hedge ("about"). */
    val stability: Double = 1.0,
    /** True when nothing useful has moved for the stall window. */
    val stalled: Boolean = false,
) {
    /** Below this the readout is a guess and should not be presented as a promise. */
    val isSteady: Boolean get() = stability >= UNSTEADY

    companion object {
        const val UNSTEADY = 0.6

        val IDLE = SmoothedReading(bytesPerSecond = 0L, etaMillis = null)
    }
}
