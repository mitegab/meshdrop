package com.meshdrop.core.queue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Queueing rules are pure, so they are tested without an engine. */
class TransferQueueTest {

    private val now = 1_000_000L

    private fun queued(id: String, peer: String = "peer-a", ageMillis: Long = 0L, priority: Int = 0) =
        QueuedTransfer(
            jobId = id,
            peerId = peer,
            totalBytes = 1_000L,
            enqueuedAtMillis = now - ageMillis,
            priority = priority,
        )

    private fun running(id: String, peer: String = "peer-a", done: Long = 0L, total: Long = 1_000L) =
        RunningTransfer(jobId = id, peerId = peer, bytesTransferred = done, totalBytes = total)

    @Test
    fun `an empty queue starts nothing`() {
        val plan = TransferScheduler.schedule(running = emptyList(), waiting = emptyList())

        assertTrue(plan.start.isEmpty())
        assertTrue(plan.waiting.isEmpty())
        assertFalse(plan.limitReached)
    }

    @Test
    fun `the first job in line starts when nothing is running`() {
        val plan = TransferScheduler.schedule(emptyList(), listOf(queued("a"), queued("b", ageMillis = -1L)))

        assertEquals(listOf("a"), plan.startedIds)
        assertEquals(listOf("b"), plan.waiting.map { it.jobId })
    }

    @Test
    fun `whoever has been waiting longest goes first`() {
        val plan = TransferScheduler.schedule(
            running = emptyList(),
            waiting = listOf(queued("new"), queued("old", ageMillis = 5_000L)),
        )

        assertEquals(listOf("old"), plan.startedIds)
    }

    @Test
    fun `a job the user is watching jumps the queue`() {
        val plan = TransferScheduler.schedule(
            running = emptyList(),
            waiting = listOf(queued("old", ageMillis = 9_000L), queued("urgent", priority = 1)),
        )

        assertEquals(listOf("urgent"), plan.startedIds)
    }

    @Test
    fun `the concurrency limit is respected`() {
        val plan = TransferScheduler.schedule(
            running = listOf(running("one")),
            waiting = listOf(queued("a"), queued("b", peer = "peer-b"), queued("c", peer = "peer-c")),
            maxConcurrent = 2,
        )

        // "a" targets the peer that is already busy, so it is skipped rather than dropped
        // and the slot goes to the next job in line.
        assertEquals(listOf("b"), plan.startedIds)
        assertEquals(listOf("a", "c"), plan.waiting.map { it.jobId })
        assertTrue(plan.limitReached)
    }

    @Test
    fun `a peer only carries one batch at a time`() {
        val plan = TransferScheduler.schedule(
            running = listOf(running("busy", peer = "peer-a")),
            waiting = listOf(queued("same-peer", peer = "peer-a"), queued("other", peer = "peer-b")),
            maxConcurrent = 4,
        )

        assertEquals(listOf("other"), plan.startedIds)
        assertEquals(listOf("same-peer"), plan.waiting.map { it.jobId })
    }

    @Test
    fun `a skipped job stays in the queue instead of being dropped`() {
        val plan = TransferScheduler.schedule(
            running = listOf(running("busy", peer = "peer-a")),
            waiting = listOf(queued("held", peer = "peer-a")),
        )

        assertTrue(plan.start.isEmpty())
        assertEquals(1, plan.waiting.size)
        assertEquals(1, plan.positionOf("held"))
        assertEquals("Next in line", plan.labelFor("held"))
    }

    @Test
    fun `positions read like ordinals`() {
        val waiting = (1..5).map { queued("job-$it", peer = "peer-$it") }
        val plan = TransferScheduler.schedule(listOf(running("busy")), waiting, maxConcurrent = 1)

        assertEquals("2nd in line", plan.labelFor("job-2"))
        assertEquals("3rd in line", plan.labelFor("job-3"))
        assertEquals("5th in line", plan.labelFor("job-5"))
        assertEquals("Next in line", plan.labelFor("job-1"))
    }

    @Test
    fun `eleven is not onestieth`() {
        val waiting = (1..13).map { queued("job-$it", peer = "peer-$it", ageMillis = (20 - it).toLong()) }
        val plan = TransferScheduler.schedule(listOf(running("busy")), waiting, maxConcurrent = 1)

        assertEquals("11th in line", plan.labelFor("job-11"))
        assertEquals("12th in line", plan.labelFor("job-12"))
        assertEquals("13th in line", plan.labelFor("job-13"))
    }

    @Test
    fun `a wait estimate needs something moving and a measured rate`() {
        val running = listOf(running("busy", done = 0L, total = 10_000_000L))

        assertEquals(10_000L, TransferScheduler.estimatedWaitMillis(1, running, bytesPerSecond = 1_000_000L))
        assertEquals(20_000L, TransferScheduler.estimatedWaitMillis(2, running, bytesPerSecond = 1_000_000L))
        assertNull(TransferScheduler.estimatedWaitMillis(0, running, bytesPerSecond = 1_000_000L))
        assertNull(TransferScheduler.estimatedWaitMillis(1, emptyList(), bytesPerSecond = 1_000_000L))
        assertNull(TransferScheduler.estimatedWaitMillis(1, running, bytesPerSecond = 0L))
    }

    @Test
    fun `the status line only appears when something is waiting`() {
        val plan = TransferScheduler.schedule(listOf(running("busy")), listOf(queued("a"), queued("b", peer = "p2")))

        assertNull(TransferScheduler.statusLine(TransferScheduler.schedule(emptyList(), emptyList()), 0L, emptyList()))
        assertEquals(
            "1 job waiting · next in about 1s",
            TransferScheduler.statusLine(plan, bytesPerSecond = 1_000L, running = listOf(running("busy", total = 1_000L))),
        )
        assertEquals("1 job waiting", TransferScheduler.statusLine(plan, bytesPerSecond = 0L, running = emptyList()))
    }
}
