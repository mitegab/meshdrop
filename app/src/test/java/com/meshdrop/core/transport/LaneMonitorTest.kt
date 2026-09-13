package com.meshdrop.core.transport

import com.meshdrop.core.model.TransportLane
import com.meshdrop.core.transport.LaneDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Failover thresholds are arithmetic over timestamps, so they are tested on the JVM. */
class LaneMonitorTest {

    private val now = 1_000_000L
    private val lan = TransportLane.WIFI_LAN
    private val direct = TransportLane.WIFI_DIRECT
    private val bluetooth = TransportLane.BLUETOOTH

    @Test
    fun `a lane that is moving data is kept`() {
        val decision = LaneFailover.decide(
            current = lan,
            lastProgressMillis = now - 200L,
            bytesPerSecond = 8L * 1024 * 1024,
            nowMillis = now,
            candidates = listOf(direct, bluetooth),
        )

        assertTrue(decision.stay)
        assertFalse(decision.failedOver)
        assertNull(decision.lane)
    }

    @Test
    fun `a lane that stops producing bytes is abandoned`() {
        val decision = LaneFailover.decide(
            current = lan,
            lastProgressMillis = now - LaneFailover.STALL_AFTER_MILLIS,
            bytesPerSecond = 0L,
            nowMillis = now,
            candidates = listOf(bluetooth, direct),
        )

        assertTrue(decision.failedOver)
        assertEquals(direct, decision.lane)
        assertEquals("no data for 4s", decision.reason)
    }

    @Test
    fun `with nowhere to go a stalled lane is kept and the reason is recorded`() {
        val decision = LaneFailover.decide(
            current = lan,
            lastProgressMillis = now - 30_000L,
            bytesPerSecond = 0L,
            nowMillis = now,
            candidates = emptyList(),
        )

        assertTrue(decision.stay)
        assertEquals("stalled, and there is no other lane", decision.reason)
    }

    @Test
    fun `a crawl only counts once it has gone on long enough`() {
        val justStarted = LaneFailover.decide(
            current = lan,
            lastProgressMillis = now,
            bytesPerSecond = 8L * 1024,
            nowMillis = now,
            candidates = listOf(direct),
            slowSinceMillis = now - 1_000L,
        )
        assertTrue(justStarted.stay)

        val givenUp = LaneFailover.decide(
            current = lan,
            lastProgressMillis = now,
            bytesPerSecond = 8L * 1024,
            nowMillis = now,
            candidates = listOf(direct),
            slowSinceMillis = now - LaneFailover.SLOW_FOR_MILLIS,
        )
        assertTrue(givenUp.failedOver)
        assertEquals("under 32 KB/s for 8s", givenUp.reason)
    }

    @Test
    fun `zero bytes per second is a stall, not a crawl`() {
        assertFalse(LaneFailover.isCrawling(0L))
        assertTrue(LaneFailover.isCrawling(1L))
        assertTrue(LaneFailover.isCrawling(LaneFailover.MIN_RATE_BYTES_PER_SECOND - 1))
        assertFalse(LaneFailover.isCrawling(LaneFailover.MIN_RATE_BYTES_PER_SECOND))
    }

    @Test
    fun `the stall window is inclusive`() {
        assertFalse(LaneFailover.isStalled(now - 3_999L, now))
        assertTrue(LaneFailover.isStalled(now - 4_000L, now))
    }

    @Test
    fun `the next lane is the best ranked one that is not the current one`() {
        assertEquals(direct, LaneFailover.nextLane(lan, listOf(bluetooth, direct)))
        assertEquals(bluetooth, LaneFailover.nextLane(lan, listOf(bluetooth, lan)))
        assertNull(LaneFailover.nextLane(lan, listOf(lan)))
        assertNull(LaneFailover.nextLane(lan, emptyList()))
    }

    @Test
    fun `the lane strip shows the live lane first and the abandoned ones behind it`() {
        val activities = LaneFailover.activities(
            current = bluetooth,
            bytesPerSecond = 900_000L,
            history = listOf(lan, direct),
        )

        assertEquals(3, activities.size)
        assertTrue(activities.first().active)
        assertEquals(bluetooth, activities.first().lane)
        assertEquals(900_000L, activities.first().bytesPerSecond)
        assertEquals(direct, activities.first().failedOverFrom)
        assertTrue(activities.drop(1).none { it.active })
        assertEquals(listOf(direct, lan), activities.drop(1).map { it.lane })
        assertEquals(lan, activities[1].failedOverFrom)
        assertNull(activities.last().failedOverFrom)
    }

    @Test
    fun `a lane with no history has nothing it failed over from`() {
        val activities = LaneFailover.activities(lan, 1_000L)

        assertEquals(1, activities.size)
        assertNull(activities.single().failedOverFrom)
    }

    @Test
    fun `the advanced panel gets one line describing the move`() {
        val decision = LaneDecision.moveTo(direct, "no data for 5s")

        assertEquals("Wi-Fi LAN → Wi-Fi Direct (no data for 5s)", LaneFailover.describe(lan, decision))
        assertNull(LaneFailover.describe(lan, LaneDecision.keep()))
    }
}
