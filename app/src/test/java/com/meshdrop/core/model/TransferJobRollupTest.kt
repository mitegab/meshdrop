package com.meshdrop.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The roll-ups on TransferJob are pure, so they are tested without an engine. */
class TransferJobRollupTest {

    private fun file(id: String, status: TransferStatus, size: Long = 1_000L) =
        TransferFile(id = id, name = "$id.jpg", sizeBytes = size, status = status)

    private fun job(
        files: List<TransferFile>,
        status: TransferStatus = TransferStatus.IN_PROGRESS,
        transferred: Long = files.sumOf { it.bytesTransferred },
        startedAt: Long = 1_000L,
        finishedAt: Long? = null,
        message: String? = null,
    ) = TransferJob(
        id = "job",
        direction = TransferDirection.RECEIVE,
        peerName = "Pixel",
        peerId = "peer",
        files = files,
        bytesTransferred = transferred,
        totalBytes = files.sumOf { it.sizeBytes },
        status = status,
        message = message,
        startedAtMillis = startedAt,
        finishedAtMillis = finishedAt,
    )

    @Test
    fun `files are counted by outcome`() {
        val batch = job(
            listOf(
                file("a", TransferStatus.COMPLETED),
                file("b", TransferStatus.COMPLETED),
                file("c", TransferStatus.FAILED),
                file("d", TransferStatus.IN_PROGRESS),
            ),
        )

        assertEquals(4, batch.fileCount)
        assertEquals(2, batch.completedFiles)
        assertEquals(1, batch.failedFiles)
        assertEquals(1, batch.pendingFiles)
    }

    @Test
    fun `the duration of a running job is measured against now`() {
        val finished = job(listOf(file("a", TransferStatus.COMPLETED)), TransferStatus.COMPLETED, 1_000L, 1_000L, 6_000L)
        assertEquals(5_000L, finished.durationMillis)

        val running = job(listOf(file("a", TransferStatus.IN_PROGRESS)), startedAt = System.currentTimeMillis())
        assertTrue(running.durationMillis in 0..5_000L)
    }

    @Test
    fun `the average rate covers the whole transfer, not the last sample`() {
        val batch = job(
            listOf(file("a", TransferStatus.COMPLETED, 10_000L)),
            status = TransferStatus.COMPLETED,
            transferred = 10_000L,
            startedAt = 0L,
            finishedAt = 4_000L,
        )

        assertEquals(2_500L, batch.averageBytesPerSecond)
        assertEquals(0L, batch.bytesPerSecond)
    }

    @Test
    fun `a job with no elapsed time has no average`() {
        val instant = job(
            listOf(file("a", TransferStatus.COMPLETED)),
            status = TransferStatus.COMPLETED,
            transferred = 1_000L,
            startedAt = 5L,
            finishedAt = 5L,
        )

        assertEquals(0L, instant.averageBytesPerSecond)
    }

    @Test
    fun `the file summary changes when the batch finishes`() {
        val files = listOf(file("a", TransferStatus.COMPLETED), file("b", TransferStatus.COMPLETED))

        assertEquals("2 of 2 file(s) done", job(files).fileSummary)
        assertEquals(
            "2 of 2 file(s)",
            job(files, TransferStatus.COMPLETED, 2_000L, 0L, 1_000L).fileSummary,
        )
    }

    @Test
    fun `the failure message names the file that failed`() {
        val batch = job(
            listOf(file("a", TransferStatus.COMPLETED), file("b", TransferStatus.FAILED)),
            status = TransferStatus.FAILED,
            message = "Integrity check failed",
        )

        assertEquals("b.jpg: Integrity check failed", batch.failureMessage)
    }

    @Test
    fun `a batch level failure is reported even when no file is marked failed`() {
        val batch = job(listOf(file("a", TransferStatus.WAITING)), TransferStatus.FAILED, message = "Peer went away")

        assertEquals("Peer went away", batch.failureMessage)
        assertNull(job(listOf(file("a", TransferStatus.WAITING))).failureMessage)
    }

    @Test
    fun `retries are counted on the job, not in the engine`() {
        val original = job(listOf(file("a", TransferStatus.FAILED)), TransferStatus.FAILED)
        assertEquals(0, original.retryCount)

        val retried = original.copy(status = TransferStatus.WAITING, retryCount = original.retryCount + 1)
        assertEquals(1, retried.retryCount)
        assertTrue(retried.isActive)
    }
}
