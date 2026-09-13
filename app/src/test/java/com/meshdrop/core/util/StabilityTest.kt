package com.meshdrop.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stability and stall detection are arithmetic over samples, so they run on the JVM. */
class StabilityTest {

    private val steady = List(Smoothing.STALL_WINDOW) { 12L * 1024 * 1024 }

    @Test
    fun `a flat line is perfectly stable`() {
        assertEquals(1.0, Smoothing.stability(steady), 0.0001)
        assertEquals(1.0, Smoothing.stability(listOf(5L)), 0.0001)
        assertEquals(1.0, Smoothing.stability(emptyList()), 0.0001)
    }

    @Test
    fun `a wild series is unstable`() {
        val wild = List(20) { index -> if (index % 2 == 0) 1L * 1024 * 1024 else 24L * 1024 * 1024 }

        val stability = Smoothing.stability(wild)

        assertTrue("expected an unstable reading, got $stability", stability < SmoothedReading.UNSTEADY)
    }

    @Test
    fun `a small wobble stays steady`() {
        val wobbling = List(20) { index -> 12L * 1024 * 1024 + (index % 3) * 100_000L }

        assertTrue(Smoothing.stability(wobbling) >= SmoothedReading.UNSTEADY)
    }

    @Test
    fun `an idle link has no stability to report`() {
        assertEquals(0.0, Smoothing.stability(List(10) { 0L }), 0.0001)
    }

    @Test
    fun `a stall needs a full window of nothing useful`() {
        assertFalse(Smoothing.stalled(listOf(0L, 0L)))
        assertFalse(Smoothing.stalled(steady))

        val stalled = List(Smoothing.STALL_WINDOW) { 0L }
        assertTrue(Smoothing.stalled(stalled))

        // One real sample in the window is enough to say the link is alive.
        val recovering = stalled.toMutableList().also { it[it.lastIndex] = 4L * 1024 * 1024 }
        assertFalse(Smoothing.stalled(recovering))
    }

    @Test
    fun `the stall floor is the crawl floor`() {
        assertTrue(Smoothing.stalled(List(Smoothing.STALL_WINDOW) { Smoothing.STALL_RATE - 1 }))
        assertFalse(Smoothing.stalled(List(Smoothing.STALL_WINDOW) { Smoothing.STALL_RATE }))
        assertEquals(32L * 1024, Smoothing.STALL_RATE)
    }

    @Test
    fun `a reading carries its stability and its stall flag`() {
        val idle = SmoothedReading.IDLE
        assertEquals(0L, idle.bytesPerSecond)
        assertFalse(idle.stalled)
        assertTrue(idle.isSteady)

        val shaky = SmoothedReading(
            bytesPerSecond = 1_000L,
            etaMillis = null,
            stability = 0.2,
            stalled = true,
        )
        assertFalse(shaky.isSteady)
        assertTrue(shaky.stalled)
    }

    @Test
    fun `telemetry reports a stall once the window is full of nothing`() {
        val telemetry = TransferTelemetry()

        repeat(Smoothing.STALL_WINDOW + 5) { telemetry.update(0L, null) }

        assertTrue(telemetry.stalled())
        assertEquals(0.0, telemetry.stability(), 0.0001)

        telemetry.reset()
        assertFalse(telemetry.stalled())
        assertEquals(1.0, telemetry.stability(), 0.0001)
    }

    @Test
    fun `telemetry reports a healthy link as steady`() {
        val telemetry = TransferTelemetry()

        var reading = SmoothedReading.IDLE
        repeat(Smoothing.STALL_WINDOW + 5) { reading = telemetry.update(12L * 1024 * 1024, 5_000L) }

        assertFalse(reading.stalled)
        assertTrue(reading.isSteady)
        assertTrue(reading.bytesPerSecond > 0L)
    }

    @Test
    fun `a link that dies mid transfer flips the stall flag`() {
        val telemetry = TransferTelemetry()
        repeat(Smoothing.STALL_WINDOW) { telemetry.update(12L * 1024 * 1024, 5_000L) }
        assertFalse(telemetry.stalled())

        // One window of zeros is not enough yet: the EMA still remembers the fast link, so
        // the readout falls through the floor over the next second or two. Declaring a
        // stall only once the whole window is dead air is the point - it is roughly the
        // four seconds LaneFailover allows before it moves to another lane.
        var reading = SmoothedReading.IDLE
        repeat(Smoothing.STALL_WINDOW) { reading = telemetry.update(0L, null) }
        assertFalse(reading.stalled)

        repeat(Smoothing.STALL_WINDOW + 5) { reading = telemetry.update(0L, null) }
        assertTrue(reading.stalled)
        assertTrue(telemetry.stalled())
    }
}
