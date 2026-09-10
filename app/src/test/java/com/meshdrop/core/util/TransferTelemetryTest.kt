package com.meshdrop.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The readouts on the active-transfer screen are not allowed to flicker, and they are not
 * allowed to lie either. These tests pin both halves of that: the number settles on the real
 * rate (rather than one rung below it) and refuses to repaint for a change nobody would see.
 */
class TransferTelemetryTest {

    @Test
    fun `the readout settles on the real rate instead of one rung below it`() {
        val telemetry = TransferTelemetry()

        repeat(30) { telemetry.update(600_000L, 9_000L) }

        // An EMA only ever approaches the sample, so truncating here would show 550 KB/s
        // for a steady 600 KB/s link — a permanent 8% under-read.
        assertEquals(600_000L, telemetry.update(600_000L, 9_000L).bytesPerSecond)
    }

    @Test
    fun `a rate that has not really moved is not repainted`() {
        val telemetry = TransferTelemetry()
        repeat(30) { telemetry.update(600_000L, 9_000L) }

        val steady = telemetry.update(600_000L, 9_000L).bytesPerSecond
        val nudged = telemetry.update(604_000L, 9_000L).bytesPerSecond

        assertEquals(steady, nudged)
    }

    @Test
    fun `an eta that barely moved is not repainted, one that jumped is`() {
        val telemetry = TransferTelemetry()
        telemetry.update(600_000L, 10_000L)

        assertEquals(10_000L, telemetry.update(600_000L, 10_400L).etaMillis)
        assertEquals(6_000L, telemetry.update(600_000L, 6_000L).etaMillis)
    }

    @Test
    fun `an unknown eta clears instead of sticking on the last guess`() {
        val telemetry = TransferTelemetry()
        telemetry.update(600_000L, 10_000L)

        assertEquals(null, telemetry.update(600_000L, null).etaMillis)
    }

    @Test
    fun `the graph window is bounded and its peak matches the samples`() {
        val telemetry = TransferTelemetry(historyCapacity = 5)
        repeat(10) { index -> telemetry.update(100_000L * (index + 1), null) }

        assertEquals(5, telemetry.samples().size)
        assertEquals(telemetry.samples().maxOrNull(), telemetry.peak())
        assertTrue(telemetry.peak() > 0L)
    }

    @Test
    fun `reset clears the eta and the window, so a second transfer starts clean`() {
        val telemetry = TransferTelemetry()
        repeat(10) { telemetry.update(600_000L, 9_000L) }

        telemetry.reset()

        assertEquals(0L, telemetry.update(0L, null).bytesPerSecond)
        assertEquals(null, telemetry.update(0L, null).etaMillis)
    }
}
