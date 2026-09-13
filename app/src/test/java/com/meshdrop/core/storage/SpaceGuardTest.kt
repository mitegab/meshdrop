package com.meshdrop.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Free-space policy is arithmetic, so it is tested without touching a volume. */
class SpaceGuardTest {

    private val mb = 1024L * 1024
    private val gb = 1024L * mb

    @Test
    fun `a small batch onto an empty device needs no explanation`() {
        val check = SpaceGuard.forBatch(batchBytes = 12 * mb, freeBytes = 8 * gb)

        assertEquals(SpaceVerdict.ENOUGH, check.verdict)
        assertTrue(check.fits)
        assertNull(check.message)
        assertEquals(0L, check.shortfallBytes)
    }

    @Test
    fun `a batch that fits but eats the headroom is reported as tight`() {
        val check = SpaceGuard.forBatch(batchBytes = 1 * gb, freeBytes = 1 * gb + 100 * mb)

        assertEquals(SpaceVerdict.TIGHT, check.verdict)
        assertTrue(check.fits)
        assertEquals("This will use most of the free space on this device", check.message)
    }

    @Test
    fun `a batch that does not fit says how much is missing`() {
        val check = SpaceGuard.forBatch(batchBytes = 4 * gb, freeBytes = 1 * gb)

        assertEquals(SpaceVerdict.NOT_ENOUGH, check.verdict)
        assertFalse(check.fits)
        assertEquals(3 * gb + SpaceGuard.HEADROOM_BYTES, check.shortfallBytes)
        assertTrue(check.message!!.startsWith("Not enough space — 3.3 GB short"))
    }

    @Test
    fun `spooling a file needs the batch twice over`() {
        assertEquals(2 * gb, SpaceGuard.requiredFor(1 * gb, spooled = true))
        assertEquals(1 * gb, SpaceGuard.requiredFor(1 * gb))
        assertEquals(0L, SpaceGuard.requiredFor(-5L))

        val spooled = SpaceGuard.forBatch(batchBytes = 1 * gb, freeBytes = 1 * gb + 500 * mb, spooled = true)
        assertEquals(SpaceVerdict.NOT_ENOUGH, spooled.verdict)
    }

    @Test
    fun `a big volume keeps a fractional headroom instead of a fixed one`() {
        // 5% of 64 GB is 3.2 GB, which is larger than the 256 MB floor.
        val check = SpaceGuard.forBatch(batchBytes = 1 * gb, freeBytes = 4 * gb, totalBytes = 64 * gb)

        assertEquals(64 * gb / 20, check.headroomBytes)
        assertEquals(SpaceVerdict.TIGHT, check.verdict)
    }

    @Test
    fun `the headroom never exceeds what is actually free`() {
        val check = SpaceGuard.check(requiredBytes = 1 * mb, freeBytes = 10 * mb)

        assertEquals(10 * mb, check.headroomBytes)
        assertEquals(SpaceVerdict.TIGHT, check.verdict)
    }

    @Test
    fun `an empty batch always fits`() {
        val check = SpaceGuard.forBatch(batchBytes = 0L, freeBytes = 0L)

        assertEquals(SpaceVerdict.ENOUGH, check.verdict)
        assertTrue(check.fits)
        assertEquals(0L, check.shortfallBytes)
    }

    @Test
    fun `a negative free space report is treated as zero`() {
        val check = SpaceGuard.forBatch(batchBytes = 1 * mb, freeBytes = -1L)

        assertEquals(SpaceVerdict.NOT_ENOUGH, check.verdict)
        assertEquals(0L, check.freeBytes)
    }
}
