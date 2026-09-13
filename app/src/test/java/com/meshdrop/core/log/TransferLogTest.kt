package com.meshdrop.core.log

import com.meshdrop.core.model.EncryptionMode
import com.meshdrop.core.model.SecurityState
import com.meshdrop.core.model.TransferDirection
import com.meshdrop.core.model.TransferFile
import com.meshdrop.core.model.TransferJob
import com.meshdrop.core.model.TransferStatus
import com.meshdrop.core.model.TrustLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The log is pure Kotlin, so it is unit tested on the JVM without a device. */
class TransferLogTest {

    /** Stand-in for the file sink the app uses; keeps lines in memory. */
    private class MemorySink(initial: List<String> = emptyList()) : TransferLogSink {
        val lines = initial.toMutableList()
        override fun append(line: String) {
            lines += line
        }

        override fun readAll(): List<String> = lines.toList()

        override fun rewrite(lines: List<String>) {
            this.lines.clear()
            this.lines += lines
        }
    }

    private fun job(
        id: String = "job-1",
        status: TransferStatus = TransferStatus.COMPLETED,
        bytes: Long = 2_000_000L,
        startedAt: Long = 1_000L,
        finishedAt: Long = 3_000L,
        names: List<String> = listOf("photo.jpg"),
    ) = TransferJob(
        id = id,
        direction = TransferDirection.RECEIVE,
        peerName = "Pixel 8",
        peerId = "peer-1",
        files = names.mapIndexed { index, name ->
            TransferFile(id = "f$index", name = name, sizeBytes = bytes / names.size)
        },
        bytesTransferred = bytes,
        totalBytes = bytes,
        status = status,
        message = if (status == TransferStatus.FAILED) "Socket closed" else null,
        startedAtMillis = startedAt,
        finishedAtMillis = if (status.isFinished) finishedAt else null,
    )

    @Test
    fun `unfinished jobs are not logged`() {
        val store = TransferLogStore()
        assertNull(store.record(job(status = TransferStatus.IN_PROGRESS)))
        assertEquals(0, store.size)
    }

    @Test
    fun `a finished job is flattened into one entry`() {
        val security = SecurityState(
            mode = EncryptionMode.E2E_AES_256_GCM,
            safetyCode = "428 193",
            codeCompared = true,
            integrityConfirmed = true,
        )
        val entry = TransferLogStore().record(job(), security)

        assertNotNull(entry)
        entry!!
        assertEquals("job-1", entry.jobId)
        assertEquals(TransferDirection.RECEIVE, entry.direction)
        assertEquals("Pixel 8", entry.peerName)
        assertEquals(1, entry.fileCount)
        assertEquals(2_000_000L, entry.totalBytes)
        assertEquals(2_000L, entry.durationMillis)
        // 2 MB over 2 s
        assertEquals(1_000_000L, entry.averageBytesPerSecond)
        assertTrue(entry.encrypted)
        assertTrue(entry.verified)
        assertTrue(entry.integrityConfirmed)
        assertTrue(entry.succeeded)
        assertEquals(listOf("photo.jpg"), entry.fileNames)
    }

    @Test
    fun `trust level drives the verified flag`() {
        val unverified = TransferLogEntry.from(
            job(),
            SecurityState(mode = EncryptionMode.E2E_AES_256_GCM),
        )
        assertFalse(unverified.verified)
        assertTrue(unverified.encrypted)

        val plaintext = TransferLogEntry.from(job(), SecurityState())
        assertFalse(plaintext.encrypted)
        assertEquals(TrustLevel.PLAINTEXT.label, "Not encrypted")
    }

    @Test
    fun `entries survive a round trip through the line format`() {
        val original = TransferLogEntry(
            jobId = "job-9",
            direction = TransferDirection.SEND,
            peerId = "peer-9",
            peerName = "Sam's\tPhone\n",
            fileCount = 2,
            totalBytes = 1_048_576L,
            status = TransferStatus.FAILED,
            startedAtMillis = 10L,
            finishedAtMillis = 20L,
            message = "Integrity check failed: the bytes were modified in transit",
            averageBytesPerSecond = 104_857L,
            encrypted = true,
            verified = false,
            integrityConfirmed = false,
            fileNames = listOf("weird\tname.txt", "line\nbreak.pdf"),
        )

        val parsed = TransferLogEntry.fromLine(original.toLine())

        assertEquals(original, parsed)
    }

    @Test
    fun `malformed lines are skipped instead of throwing`() {
        assertNull(TransferLogEntry.fromLine(""))
        assertNull(TransferLogEntry.fromLine("garbage"))
        assertNull(TransferLogEntry.fromLine("1\t\ta\tb"))
    }

    @Test
    fun `a truncated line from an older version still parses`() {
        // Ten columns is the minimum: everything after the timestamps is optional.
        val legacy = listOf(
            "1", "job-1", "SEND", "peer", "Pixel", "1", "10", "COMPLETED", "0", "5",
        ).joinToString("\t")

        val parsed = TransferLogEntry.fromLine(legacy)

        assertNotNull(parsed)
        assertEquals("job-1", parsed!!.jobId)
        assertEquals(0L, parsed.averageBytesPerSecond)
        assertFalse(parsed.encrypted)
        assertTrue(parsed.fileNames.isEmpty())
    }

    @Test
    fun `the same job finishing twice replaces its entry`() {
        val store = TransferLogStore()
        store.record(job(status = TransferStatus.FAILED))
        store.record(job(status = TransferStatus.COMPLETED))

        assertEquals(1, store.size)
        assertTrue(store.forJob("job-1")!!.succeeded)
    }

    @Test
    fun `entries come back newest first`() {
        val store = TransferLogStore()
        store.record(job(id = "a", startedAt = 1L, finishedAt = 2L))
        store.record(job(id = "b", startedAt = 3L, finishedAt = 4L))

        assertEquals(listOf("b", "a"), store.all().map { it.jobId })
        assertTrue(store.contains("a"))
    }

    @Test
    fun `the store is bounded and drops the oldest entry`() {
        val store = TransferLogStore(capacity = 3)
        repeat(5) { index -> store.record(job(id = "job-$index", startedAt = index.toLong(), finishedAt = index.toLong() + 1)) }

        assertEquals(3, store.size)
        assertEquals(listOf("job-4", "job-3", "job-2"), store.all().map { it.jobId })
    }

    @Test
    fun `a new store reloads what the sink already holds`() {
        val sink = MemorySink()
        TransferLogStore(sink = sink).record(job(id = "job-1"))
        TransferLogStore(sink = sink).record(job(id = "job-2", startedAt = 5L, finishedAt = 6L))

        val reopened = TransferLogStore(sink = sink)
        assertEquals(listOf("job-2", "job-1"), reopened.all().map { it.jobId })

        reopened.clear()
        assertEquals(0, reopened.size)
        assertTrue(sink.readAll().isEmpty())
    }

    @Test
    fun `reloading a log longer than the capacity prunes it on disk`() {
        val sink = MemorySink()
        val writer = TransferLogStore(capacity = 100, sink = sink)
        repeat(10) { writer.record(job(id = "job-$it", startedAt = it.toLong(), finishedAt = it.toLong() + 1)) }

        val reopened = TransferLogStore(capacity = 4, sink = sink)

        assertEquals(4, reopened.size)
        assertEquals(4, sink.readAll().size)
        assertEquals(listOf("job-9", "job-8", "job-7", "job-6"), reopened.all().map { it.jobId })
    }

    @Test
    fun `the summary line mentions size and file count`() {
        val entry = TransferLogEntry.from(job(names = listOf("a.jpg", "b.jpg"), bytes = 4_000_000L))
        assertEquals("3.8 MB · 2 file(s)", entry.summary)
    }
}
