package com.meshdrop.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** Formatting is pure Kotlin, so it is unit tested on the JVM without Robolectric. */
class FormatTest {

    @Test
    fun `bytes uses binary units with one decimal below ten`() {
        assertEquals("0 B", Format.bytes(0))
        assertEquals("812 B", Format.bytes(812))
        assertEquals("1.0 KB", Format.bytes(1024))
        assertEquals("1.5 KB", Format.bytes(1536))
        assertEquals("12 KB", Format.bytes(12 * 1024))
        assertEquals("1.4 MB", Format.bytes(1_500_000))
        assertEquals("2.0 GB", Format.bytes(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun `negative sizes are shown as a placeholder`() {
        assertEquals("—", Format.bytes(-1))
    }

    @Test
    fun `speed appends per second`() {
        assertEquals("0 B/s", Format.speed(0))
        assertEquals("1.0 MB/s", Format.speed(1_048_576))
    }

    @Test
    fun `duration switches units as it grows`() {
        assertEquals("8s", Format.duration(8_000))
        assertEquals("2m 10s", Format.duration(130_000))
        assertEquals("1h 04m", Format.duration(3_870_000))
    }

    @Test
    fun `percent never overflows and survives an empty total`() {
        assertEquals(0, Format.percent(0, 0))
        assertEquals(50, Format.percent(5, 10))
        assertEquals(100, Format.percent(10, 10))
    }

    @Test
    fun `percentages are whole numbers with the sign in one place`() {
        assertEquals("0%", Format.percentText(0, 100))
        assertEquals("42%", Format.percentText(42, 100))
        assertEquals("100%", Format.percentText(200, 100))
        assertEquals("0%", Format.percentText(5, 0))
    }

    @Test
    fun `relative time is coarse on purpose`() {
        val now = 1_700_000_000_000L
        assertEquals("just now", Format.relative(now - 5_000L, now))
        assertEquals("just now", Format.relative(now + 60_000L, now))
        assertEquals("1 min ago", Format.relative(now - 60_000L, now))
        assertEquals("59 min ago", Format.relative(now - 59 * 60_000L, now))
        assertEquals("1 h ago", Format.relative(now - 60 * 60_000L, now))
        assertEquals("23 h ago", Format.relative(now - 23 * 60 * 60_000L, now))
        assertEquals("1 d ago", Format.relative(now - 24 * 60 * 60_000L, now))
        assertEquals("6 d ago", Format.relative(now - 6 * 24 * 60 * 60_000L, now))
        assertEquals("1 wk ago", Format.relative(now - 7 * 24 * 60 * 60_000L, now))
    }

    @Test
    fun `counts know about plurals`() {
        assertEquals("1 file", Format.count(1, "file"))
        assertEquals("3 files", Format.count(3, "file"))
        assertEquals("0 files", Format.count(0, "file"))
        assertEquals("1 entry", Format.count(1, "entry", "entries"))
        assertEquals("2 entries", Format.count(2, "entry", "entries"))
    }

    @Test
    fun `the count-up clock gains an hours column when it needs one`() {
        assertEquals("0:00", Format.countUp(0))
        assertEquals("0:07", Format.countUp(7_000L))
        assertEquals("1:04", Format.countUp(64_000L))
        assertEquals("59:59", Format.countUp(3_599_000L))
        assertEquals("1:00:00", Format.countUp(3_600_000L))
        assertEquals("1:02:03", Format.countUp(3_723_000L))
        assertEquals("0:00", Format.countUp(-5_000L))
    }
}
