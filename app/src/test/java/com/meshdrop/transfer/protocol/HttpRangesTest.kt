package com.meshdrop.transfer.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Range handling is text in, numbers out, so it is tested without a socket. */
class HttpRangesTest {

    @Test
    fun `the request header is an open ended byte range`() {
        assertEquals("bytes=0-", HttpRanges.header(0L))
        assertEquals("bytes=1048576-", HttpRanges.header(1024L * 1024))
    }

    @Test
    fun `the response header says what is now held`() {
        assertEquals("bytes 0-1023/4096", HttpRanges.contentRange(0L, 1023L, 4096L))
        assertEquals("bytes 1024-4095/4096", HttpRanges.contentRange(1024L, 4095L, 4096L))
    }

    @Test
    fun `a start offset is read back`() {
        assertEquals(0L, HttpRanges.startOf("bytes=0-"))
        assertEquals(1024L, HttpRanges.startOf("bytes=1024-"))
        assertEquals(1024L, HttpRanges.startOf("  BYTES = 1024-  "))
    }

    @Test
    fun `anything we do not speak is treated as no range`() {
        assertNull(HttpRanges.startOf(null))
        assertNull(HttpRanges.startOf(""))
        assertNull(HttpRanges.startOf("bytes"))
        assertNull(HttpRanges.startOf("items=10-"))
        // Suffix ranges and closed ranges are not part of our dialect.
        assertNull(HttpRanges.startOf("bytes=-500"))
        assertNull(HttpRanges.startOf("bytes=10-20"))
        assertNull(HttpRanges.startOf("bytes=abc-"))
        assertNull(HttpRanges.startOf("bytes=-"))
    }

    @Test
    fun `asking for the whole file is the same as asking for nothing`() {
        assertTrue(HttpRanges.isWholeFile("bytes=0-"))
        assertFalse(HttpRanges.isWholeFile("bytes=1-"))
        assertFalse(HttpRanges.isWholeFile(null))
    }

    @Test
    fun `a content range is only believed when it makes sense`() {
        assertEquals(1024L, HttpRanges.contentRangeStart("bytes 1024-4095/4096"))
        assertEquals(0L, HttpRanges.contentRangeStart("bytes 0-0/1"))
        assertNull(HttpRanges.contentRangeStart("bytes 4096-1023/4096"))
        assertNull(HttpRanges.contentRangeStart("items 0-1/2"))
        assertNull(HttpRanges.contentRangeStart("bytes 0-"))
        assertNull(HttpRanges.contentRangeStart(null))
    }

    @Test
    fun `remaining bytes never go negative or past the file`() {
        assertEquals(3072L, HttpRanges.remaining(1024L, 4096L))
        assertEquals(0L, HttpRanges.remaining(4096L, 4096L))
        assertEquals(0L, HttpRanges.remaining(9999L, 4096L))
        assertEquals(4096L, HttpRanges.remaining(-5L, 4096L))
        assertEquals(0L, HttpRanges.remaining(10L, 0L))
    }

    @Test
    fun `a sliver is not worth resuming`() {
        assertFalse(HttpRanges.worthResuming(0L, 1_000_000L))
        assertFalse(HttpRanges.worthResuming(1_024L, 1_000_000L))
        assertTrue(HttpRanges.worthResuming(64L * 1024, 1_000_000L))
        // Already complete: there is nothing to resume.
        assertFalse(HttpRanges.worthResuming(1_000_000L, 1_000_000L))
        assertFalse(HttpRanges.worthResuming(10L, 0L))
    }

    @Test
    fun `a resume and a fresh start agree on the last chunk`() {
        val total = 3L * 64 * 1024 + 500
        val have = 2L * 64 * 1024

        assertEquals(total - have, HttpRanges.remaining(have, total))
        assertTrue(HttpRanges.worthResuming(have, total))
        assertEquals("bytes=131072-", HttpRanges.header(have))
        assertEquals("bytes 131072-${total - 1}/$total", HttpRanges.contentRange(have, total - 1, total))
    }
}
