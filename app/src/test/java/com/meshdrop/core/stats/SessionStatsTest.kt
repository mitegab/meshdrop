package com.meshdrop.core.stats

import com.meshdrop.core.model.TransferDirection
import com.meshdrop.core.model.TransferFile
import com.meshdrop.core.model.TransferJob
import com.meshdrop.core.model.TransferStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Session totals are a fold over jobs, so they are tested without an engine. */
class SessionStatsTest {

    private fun job(
        id: String,
        direction: TransferDirection = TransferDirection.RECEIVE,
        status: TransferStatus = TransferStatus.COMPLETED,
        bytes: Long = 1_000L,
        peer: String = "Pixel",
        startedAt: Long = 0L,
        finishedAt: Long? = 1_000L,
        rate: Long = 0L,
        retries: Int = 0,
        files: Int = 1,
    ) = TransferJob(
        id = id,
        direction = direction,
        peerName = peer,
        peerId = peer,
        files = (1..files).map {
            TransferFile(
                id = "$id-$it",
                name = "file-$it",
                sizeBytes = bytes / files,
                bytesTransferred = if (status == TransferStatus.COMPLETED) bytes / files else 0L,
                status = if (status == TransferStatus.COMPLETED) TransferStatus.COMPLETED else TransferStatus.WAITING,
            )
        },
        bytesTransferred = bytes,
        totalBytes = bytes,
        bytesPerSecond = rate,
        status = status,
        startedAtMillis = startedAt,
        finishedAtMillis = finishedAt,
        retryCount = retries,
    )

    @Test
    fun `an empty session says so`() {
        val stats = SessionStats.from(emptyList())

        assertTrue(stats.isEmpty)
        assertEquals("Nothing has moved yet", stats.headline)
        assertNull(stats.outcomeLine)
        assertNull(stats.topPeer)
    }

    @Test
    fun `sent and received bytes are kept apart`() {
        val stats = SessionStats.from(
            listOf(
                job("a", TransferDirection.SEND, bytes = 3_000L),
                job("b", TransferDirection.RECEIVE, bytes = 5_000L),
            ),
        )

        assertEquals(3_000L, stats.bytesSent)
        assertEquals(5_000L, stats.bytesReceived)
        assertEquals(8_000L, stats.totalBytes)
        assertEquals(2, stats.transfers)
        assertEquals(2, stats.files)
    }

    @Test
    fun `outcomes are counted by status`() {
        val stats = SessionStats.from(
            listOf(
                job("a"),
                job("b", status = TransferStatus.FAILED),
                job("c", status = TransferStatus.CANCELLED),
                job("d", status = TransferStatus.REJECTED),
                job("e", status = TransferStatus.IN_PROGRESS, finishedAt = null),
            ),
        )

        assertEquals(1, stats.succeeded)
        assertEquals(1, stats.failed)
        assertEquals(1, stats.cancelled)
        assertEquals(1, stats.rejected)
        assertEquals(4, stats.finished)
        assertEquals(0.25f, stats.successRate, 0.0001f)
        assertEquals("1 failed · 1 cancelled · 1 declined", stats.outcomeLine)
    }

    @Test
    fun `a clean session has no outcome line`() {
        assertNull(SessionStats.from(listOf(job("a"))).outcomeLine)
    }

    @Test
    fun `the average rate is measured over running time, not wall clock`() {
        val stats = SessionStats.from(
            listOf(job("a", bytes = 10_000L, startedAt = 0L, finishedAt = 4_000L)),
        )

        assertEquals(4_000L, stats.activeMillis)
        assertEquals(2_500L, stats.averageBytesPerSecond)
        assertEquals("1 transfer · 9.8 KB · 2.4 KB/s", stats.headline)
    }

    @Test
    fun `a running transfer counts its time so far`() {
        val now = 10_000L
        val stats = SessionStats.from(
            listOf(job("a", status = TransferStatus.IN_PROGRESS, finishedAt = null, startedAt = 4_000L)),
            nowMillis = now,
        )

        assertEquals(6_000L, stats.activeMillis)
    }

    @Test
    fun `the peak rate is the fastest anything went`() {
        val stats = SessionStats.from(
            listOf(job("a", rate = 2_000L), job("b", rate = 9_000L), job("c", rate = 100L)),
        )

        assertEquals(9_000L, stats.peakBytesPerSecond)
    }

    @Test
    fun `retries are summed across the session`() {
        val stats = SessionStats.from(listOf(job("a", retries = 2), job("b", retries = 1)))

        assertEquals(3, stats.retries)
    }

    @Test
    fun `the busiest peer is named`() {
        val stats = SessionStats.from(
            listOf(
                job("a", peer = "Pixel", bytes = 1_000L),
                job("b", peer = "Galaxy", bytes = 9_000L),
                job("c", peer = "Pixel", bytes = 500L),
            ),
        )

        assertEquals("Galaxy", stats.topPeer)
    }

    @Test
    fun `a peer with no bytes is not called the busiest`() {
        val stats = SessionStats.from(listOf(job("a", bytes = 0L, status = TransferStatus.FAILED)))

        assertNull(stats.topPeer)
        assertTrue(stats.headline.contains("0 B"))
    }
}
