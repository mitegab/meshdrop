package com.meshdrop.core.util

import java.util.Locale

/**
 * Human readable formatting for sizes, speeds and ETAs.
 *
 * Deliberately pure Kotlin (no Android imports) so it can be unit tested on the JVM.
 */
object Format {

    private const val KB = 1024.0
    private val UNITS = arrayOf("KB", "MB", "GB", "TB")

    /** `0 B`, `812 B`, `1.4 KB`, `12 MB` … */
    fun bytes(value: Long): String {
        if (value < 0) return "—"
        if (value < KB) return "$value B"
        var scaled = value / KB
        var unit = 0
        while (scaled >= KB && unit < UNITS.lastIndex) {
            scaled /= KB
            unit++
        }
        val text = if (scaled < 10.0) {
            String.format(Locale.US, "%.1f", scaled)
        } else {
            String.format(Locale.US, "%.0f", scaled)
        }
        return "$text ${UNITS[unit]}"
    }

    /** `0 B/s`, `640 KB/s`, `12 MB/s`. */
    fun speed(bytesPerSecond: Long): String =
        if (bytesPerSecond <= 0L) "0 B/s" else "${bytes(bytesPerSecond)}/s"

    /** `8s`, `2m 10s`, `1h 04m`, or null when there is nothing sensible to show. */
    fun duration(millis: Long): String? {
        if (millis < 0L) return null
        val totalSeconds = millis / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return when {
            hours > 0L -> String.format(Locale.US, "%dh %02dm", hours, minutes)
            minutes > 0L -> String.format(Locale.US, "%dm %02ds", minutes, seconds)
            else -> "${seconds}s"
        }
    }

    /** Percentage in 0..100, guarding against a zero-byte total. */
    fun percent(done: Long, total: Long): Int =
        if (total <= 0L) 0 else ((done * 100L) / total).toInt().coerceIn(0, 100)

    /** `42%`. Separate from [percent] so the "%" lives in exactly one place. */
    fun percentText(done: Long, total: Long): String = "${percent(done, total)}%"

    /**
     * `just now`, `5 min ago`, `2 h ago`, `3 d ago`.
     *
     * History is read days later, and an absolute timestamp on every row is noise next to
     * the day header. Rounding is deliberately coarse: nobody needs "4 minutes 37 seconds
     * ago" and a number that ticks every second is a number nobody can read.
     */
    fun relative(millis: Long, nowMillis: Long = System.currentTimeMillis()): String {
        val delta = nowMillis - millis
        if (delta < 45_000L) return "just now"
        val minutes = delta / 60_000L
        return when {
            minutes < 60L -> "$minutes min ago"
            minutes < 60L * 24 -> "${minutes / 60L} h ago"
            minutes < 60L * 24 * 7 -> "${minutes / (60L * 24)} d ago"
            else -> "${minutes / (60L * 24 * 7)} wk ago"
        }
    }

    /** `1 file` / `3 files`, with the plural supplied when English is irregular. */
    fun count(count: Int, singular: String, plural: String = singular + "s"): String =
        "$count ${if (count == 1) singular else plural}"

    /**
     * A clock that runs upwards: `0:07`, `1:04`, `1:02:03`.
     *
     * Used for elapsed transfer time, where an ETA would be a guess and a byte count is
     * already on screen.
     */
    fun countUp(millis: Long): String {
        val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }
}
