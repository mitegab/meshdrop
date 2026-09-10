package com.meshdrop.core.util

import kotlin.math.abs

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
     * Cuts the decimals off a rate so the text stops twitching:
     * below 1 MB/s we keep one decimal, above it we keep whole megabytes.
     */
    fun quantise(bytesPerSecond: Double): Double = when {
        bytesPerSecond < 100_000.0 -> (bytesPerSecond / 5_000.0).toLong() * 5_000.0
        bytesPerSecond < 1_000_000.0 -> (bytesPerSecond / 50_000.0).toLong() * 50_000.0
        else -> (bytesPerSecond / 250_000.0).toLong() * 250_000.0
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

    const val DEFAULT_ALPHA = 0.25
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
