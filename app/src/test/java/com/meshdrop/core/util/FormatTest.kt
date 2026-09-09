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
}
