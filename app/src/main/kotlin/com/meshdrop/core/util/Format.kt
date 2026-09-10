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
}
