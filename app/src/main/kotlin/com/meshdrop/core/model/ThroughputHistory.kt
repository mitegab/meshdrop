package com.meshdrop.core.model

/**
 * Ring buffer of throughput samples for the Advanced panel's live graph.
 *
 * Kept in the domain (not in the UI) because it is plain data with an obvious meaning, and
 * because it lets the graph be a pure function of `snapshot()`.
 */
class ThroughputHistory(private val capacity: Int = DEFAULT_CAPACITY) {

    private val samples = ArrayDeque<Long>(capacity)

    fun add(bytesPerSecond: Long) {
        samples.addLast(bytesPerSecond.coerceAtLeast(0L))
        while (samples.size > capacity) samples.removeFirst()
    }

    /**
     * Oldest → newest, always [capacity] long: missing history is zero padded at the front so
     * the graph does not slide around while it fills up.
     */
    fun snapshot(): List<Long> {
        val result = ArrayList<Long>(capacity)
        val padding = (capacity - samples.size).coerceAtLeast(0)
        repeat(padding) { result += 0L }
        result += samples
        return result
    }

    /**
     * The samples actually collected, oldest → newest, with no zero padding.
     *
     * [snapshot] pads so the graph does not slide around while it fills up; anything doing
     * arithmetic on the series wants the truth instead, or a transfer looks unstable for
     * its first two seconds.
     */
    fun recent(limit: Int = Int.MAX_VALUE): List<Long> =
        if (limit >= samples.size) samples.toList() else samples.takeLast(limit)

    /** Peak of the window, with a floor so an idle graph does not divide by zero. */
    fun peak(minimum: Long = MINIMUM_PEAK): Long =
        (samples.maxOrNull() ?: 0L).coerceAtLeast(minimum)

    fun clear() = samples.clear()

    val size: Int get() = samples.size

    companion object {
        const val DEFAULT_CAPACITY = 120
        const val MINIMUM_PEAK = 256 * 1024L
    }
}
