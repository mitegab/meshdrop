package com.meshdrop.core.offer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The offer countdown is arithmetic over two timestamps, so it is tested on the JVM. */
class OfferWindowTest {

    private val arrived = 1_000_000L
    private val window = OfferWindow(arrivedAtMillis = arrived, durationMillis = 120_000L)

    @Test
    fun `a fresh offer has its whole window left`() {
        assertEquals(120_000L, window.remainingMillis(arrived))
        assertEquals(1f, window.fractionRemaining(arrived), 0.0001f)
        assertEquals(OfferPolicy.Stage.OPEN, window.state(arrived))
        assertEquals("2:00", window.countdown(arrived))
        assertEquals("2:00 left to answer", window.countdownLabel(arrived))
        assertFalse(window.isExpired(arrived))
    }

    @Test
    fun `the countdown reads like a clock`() {
        assertEquals("1:59", window.countdown(arrived + 1_000L))
        assertEquals("1:00", window.countdown(arrived + 60_000L))
        assertEquals("0:09", window.countdown(arrived + 111_000L))
        assertEquals("0:00", window.countdown(arrived + 120_000L))
    }

    @Test
    fun `the last quarter of the window closes the card`() {
        val closing = arrived + 100_000L

        assertEquals(OfferPolicy.Stage.CLOSING, window.state(closing))
        assertEquals("Closing soon", window.state(closing).label)
        assertTrue(window.fractionRemaining(closing) <= OfferPolicy.CLOSING_AT)
    }

    @Test
    fun `an expired offer stays expired and never goes negative`() {
        val late = arrived + 200_000L

        assertTrue(window.isExpired(late))
        assertEquals(0L, window.remainingMillis(late))
        assertEquals(0f, window.fractionRemaining(late), 0.0001f)
        assertEquals(OfferPolicy.Stage.CLOSED, window.state(late))
        assertEquals("Expired", window.countdownLabel(late))
    }

    @Test
    fun `the deadline is the arrival plus the window`() {
        assertEquals(arrived + 120_000L, window.deadlineMillis)
    }

    @Test
    fun `one reminder at the halfway point and never twice`() {
        val halfway = arrived + 61_000L

        assertTrue(OfferPolicy.shouldRemind(window, halfway, lastReminderAtMillis = null))
        assertFalse(OfferPolicy.shouldRemind(window, halfway, lastReminderAtMillis = halfway))
        // A reminder for an older offer does not count for this one.
        assertTrue(OfferPolicy.shouldRemind(window, halfway, lastReminderAtMillis = arrived - 1L))
        // Not yet, and not after the window closed.
        assertFalse(OfferPolicy.shouldRemind(window, arrived + 10_000L, lastReminderAtMillis = null))
        assertFalse(OfferPolicy.shouldRemind(window, arrived + 130_000L, lastReminderAtMillis = null))
    }

    @Test
    fun `the timeout wording does not blame anybody`() {
        assertEquals("No answer — the other device did not respond in time", OfferPolicy.TIMEOUT_MESSAGE)
        assertNull(OfferPolicy.TIMEOUT_MESSAGE.takeIf { it.contains("reject") })
    }

    @Test
    fun `the local notice names the peer and the wait`() {
        assertEquals(
            "No answer to Pixel 8 after 2m 00s",
            OfferPolicy.expiredNotice("Pixel 8", 120_000L),
        )
    }

    @Test
    fun `a zero length window is expired on arrival`() {
        val immediate = OfferWindow(arrivedAtMillis = arrived, durationMillis = 0L)

        assertTrue(immediate.isExpired(arrived))
        assertEquals(0f, immediate.fractionRemaining(arrived), 0.0001f)
    }
}
