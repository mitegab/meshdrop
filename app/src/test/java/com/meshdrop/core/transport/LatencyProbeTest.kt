package com.meshdrop.core.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The probe policy is arithmetic and ordering, so it is tested without a socket. */
class LatencyProbeTest {

    @Test
    fun `an unmeasured peer has no grade and says so`() {
        val estimate = LatencyEstimate.NONE

        assertNull(estimate.millis)
        assertEquals(LatencyGrade.UNKNOWN, estimate.grade())
        assertEquals("—", estimate.label())
        assertEquals("Unknown", estimate.grade().label)
    }

    @Test
    fun `a single sample is the average`() {
        val estimate = LatencyEstimate.NONE.observe(24L, nowMillis = 1_000L)

        assertEquals(24L, estimate.millis)
        assertEquals(1, estimate.samples)
        assertEquals("24 ms", estimate.label())
        assertEquals(24L, estimate.worstMillis)
    }

    @Test
    fun `a spike moves the average by a third of the way, not all of it`() {
        var estimate = LatencyEstimate.NONE.observe(20L, nowMillis = 0L)
        estimate = estimate.observe(200L, nowMillis = 1L)

        // 20 + (200 - 20) * 0.34 = 81.2 -> 81
        assertEquals(81L, estimate.millis)
        // and the peak is still remembered
        assertEquals(200L, estimate.worstMillis)
        // recovering pulls it back down the same way
        estimate = estimate.observe(20L, nowMillis = 2L)
        assertTrue(estimate.millis!! < 81L)
    }

    @Test
    fun `grades follow the thresholds`() {
        assertEquals(LatencyGrade.EXCELLENT, LatencyEstimate.NONE.observe(15L, 0L).grade())
        assertEquals(LatencyGrade.GOOD, LatencyEstimate.NONE.observe(16L, 0L).grade())
        assertEquals(LatencyGrade.GOOD, LatencyEstimate.NONE.observe(80L, 0L).grade())
        assertEquals(LatencyGrade.POOR, LatencyEstimate.NONE.observe(81L, 0L).grade())
        assertEquals("Excellent", LatencyGrade.EXCELLENT.label)
    }

    @Test
    fun `a number goes stale, and a fresh one is not`() {
        val measured = LatencyEstimate.NONE.observe(30L, nowMillis = 1_000L)

        assertFalse(measured.isStale(nowMillis = 1_000L))
        assertTrue(measured.isStale(nowMillis = 1_000L + ProbePolicy.STALE_AFTER_MILLIS + 1))
        // never measured is stale by definition: there is nothing to trust
        assertTrue(LatencyEstimate.NONE.isStale(nowMillis = 0L))
    }

    @Test
    fun `a peer that has never been measured is due immediately, and then waits its interval`() {
        assertTrue(ProbePolicy.isDue(LatencyEstimate.NONE, nowMillis = 0L))

        val measured = LatencyEstimate(millis = 10L, lastProbeMillis = 5L)
        assertFalse(ProbePolicy.isDue(measured, nowMillis = 6L))
        assertTrue(ProbePolicy.isDue(measured, nowMillis = 5L + ProbePolicy.PROBE_INTERVAL_MILLIS))
    }

    @Test
    fun `a refusal backs the next probe off, doubling up to a minute`() {
        assertEquals(0L, ProbePolicy.backoffMillis(0))
        assertEquals(ProbePolicy.PROBE_INTERVAL_MILLIS, ProbePolicy.backoffMillis(1))
        assertEquals(2 * ProbePolicy.PROBE_INTERVAL_MILLIS, ProbePolicy.backoffMillis(2))
        assertEquals(ProbePolicy.MAX_BACKOFF_MILLIS, ProbePolicy.backoffMillis(40))

        val failed = LatencyEstimate(millis = 30L, samples = 1, failures = 3, lastProbeMillis = 1_000L)
        // interval + 40s of backoff has not passed yet
        assertFalse(ProbePolicy.isDue(failed, nowMillis = 1_000L + ProbePolicy.PROBE_INTERVAL_MILLIS))
        assertTrue(
            ProbePolicy.isDue(
                failed,
                nowMillis = 1_000L + ProbePolicy.PROBE_INTERVAL_MILLIS + ProbePolicy.backoffMillis(3) + 1,
            ),
        )
    }

    @Test
    fun `a peer with no address or no port is never probed`() {
        assertFalse(ProbePolicy.canProbe(hasAddress = false, port = 45821))
        assertFalse(ProbePolicy.canProbe(hasAddress = true, port = 0))
        assertTrue(ProbePolicy.canProbe(hasAddress = true, port = 45821))
    }

    @Test
    fun `unmeasured peers are probed before stale ones, and fresh ones not at all`() {
        val now = 100_000L
        val estimates = mapOf(
            "fresh" to LatencyEstimate(millis = 12L, lastProbeMillis = now - 100L),
            "stale" to LatencyEstimate(millis = 12L, lastProbeMillis = 1_000L),
            "never" to LatencyEstimate.NONE,
            "oldest" to LatencyEstimate(millis = 40L, lastProbeMillis = now - 60_000L),
        )

        val due = ProbePolicy.duePeers(
            peerIds = listOf("fresh", "stale", "never", "oldest"),
            estimates = estimates,
            nowMillis = now,
        )

        assertTrue(due.contains("never"))
        assertTrue(due.contains("oldest"))
        assertFalse("a fresh number is not re-measured", due.contains("fresh"))
        // never-measured first, because a list with a dash in it is less useful than one
        // with a possibly-slightly-old number
        assertEquals("never", due.first())
    }

    @Test
    fun `the pass is capped so a big list does not mean twenty connections`() {
        val now = 100_000L
        val ids = (1..20).map { "peer$it" }

        val due = ProbePolicy.duePeers(ids, emptyMap(), nowMillis = now, limit = 4)

        assertEquals(4, due.size)
    }
}
