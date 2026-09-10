package com.meshdrop.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmoothingTest {

    @Test
    fun `ema pulls the value towards the sample without jumping`() {
        val smoothed = Smoothing.ema(previous = 10.0, sample = 20.0, alpha = 0.5)
        assertEquals(15.0, smoothed, 0.0001)

        val gentle = Smoothing.ema(previous = 10.0, sample = 20.0, alpha = 0.1)
        assertEquals(11.0, gentle, 0.0001)
    }

    @Test
    fun `quantising removes the twitch from a readout`() {
        assertEquals(0.0, Smoothing.quantise(1_234.0), 0.0)
        assertEquals(500_000.0, Smoothing.quantise(512_345.0), 0.0)
        assertEquals(3_000_000.0, Smoothing.quantise(3_112_233.0), 0.0)
    }

    @Test
    fun `an eta that barely moved is not repainted`() {
        assertEquals(12_000L, Smoothing.stableEta(previous = 12_000L, candidate = 12_400L))
        assertEquals(9_000L, Smoothing.stableEta(previous = 12_000L, candidate = 9_000L))
    }

    @Test
    fun `a null candidate clears the eta, a null previous accepts anything`() {
        assertEquals(null, Smoothing.stableEta(previous = 5_000L, candidate = null))
        assertEquals(5_000L, Smoothing.stableEta(previous = null, candidate = 5_000L))
    }

    @Test
    fun `rate smoother keeps state between samples`() {
        val smoother = RateSmoother(alpha = 0.5)
        smoother.submit(0.0)
        val first = smoother.submit(100.0)
        val second = smoother.submit(100.0)
        assertTrue(first < second)
        assertTrue(second <= 100.0)

        smoother.reset(0.0)
        assertEquals(0.0, smoother.value, 0.0)
    }
}
