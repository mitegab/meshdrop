package com.meshdrop.core.log

import com.meshdrop.core.model.TransferDirection
import com.meshdrop.core.model.TransferStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Retention is a pure function of (entries, now, policy) — tested without a device. */
class TransferLogRetentionTest {

    private val day = RetentionPolicy.MILLIS_PER_DAY
    private val now = 100L * day

    private fun entry(
        id: String,
        daysAgo: Long,
        status: TransferStatus = TransferStatus.COMPLETED,
        bytes: Long = 1_000L,
    ) = TransferLogEntry(
        jobId = id,
        direction = TransferDirection.RECEIVE,
        peerId = "peer",
        peerName = "Pixel",
        fileCount = 1,
        totalBytes = bytes,
        status = status,
        startedAtMillis = now - daysAgo * day - 1_000L,
        finishedAtMillis = now - daysAgo * day,
    )

    @Test
    fun `entries inside the window are kept`() {
        val result = TransferLogRetention.prune(
            listOf(entry("a", 1), entry("b", 30)),
            nowMillis = now,
            policy = RetentionPolicy(maxAgeDays = 90),
        )

        assertFalse(result.changed)
        assertEquals(listOf("a", "b"), result.kept.map { it.jobId })
    }

    @Test
    fun `entries past the window are dropped, newest first`() {
        val result = TransferLogRetention.prune(
            listOf(entry("old", 120), entry("new", 5), entry("ancient", 400)),
            nowMillis = now,
            policy = RetentionPolicy(maxAgeDays = 90),
        )

        assertEquals(listOf("new"), result.kept.map { it.jobId })
        assertEquals(listOf("old", "ancient"), result.dropped.map { it.jobId })
        assertTrue(result.changed)
        assertEquals(2_000L, result.freedBytes)
    }

    @Test
    fun `a failure outlives the window but not twice the window`() {
        val policy = RetentionPolicy(maxAgeDays = 30)

        val stillUseful = TransferLogRetention.prune(
            listOf(entry("failed", 45, status = TransferStatus.FAILED)),
            nowMillis = now,
            policy = policy,
        )
        assertEquals(listOf("failed"), stillUseful.kept.map { it.jobId })

        val stale = TransferLogRetention.prune(
            listOf(entry("failed", 61, status = TransferStatus.FAILED)),
            nowMillis = now,
            policy = policy,
        )
        assertTrue(stale.kept.isEmpty())
    }

    @Test
    fun `failures can be dropped with the window`() {
        val result = TransferLogRetention.prune(
            listOf(entry("failed", 45, status = TransferStatus.FAILED)),
            nowMillis = now,
            policy = RetentionPolicy(maxAgeDays = 30, keepFailures = false),
        )

        assertTrue(result.kept.isEmpty())
    }

    @Test
    fun `pinned jobs survive any age rule`() {
        val result = TransferLogRetention.prune(
            listOf(entry("vaulted", 500)),
            nowMillis = now,
            policy = RetentionPolicy(maxAgeDays = 7),
            pinnedJobIds = setOf("vaulted"),
        )

        assertEquals(listOf("vaulted"), result.kept.map { it.jobId })
    }

    @Test
    fun `the size cap keeps the newest entries`() {
        val entries = (0 until 10).map { entry("job-$it", daysAgo = it.toLong()) }

        val result = TransferLogRetention.prune(
            entries,
            nowMillis = now,
            policy = RetentionPolicy(maxAgeDays = 365, maxEntries = 3),
        )

        assertEquals(listOf("job-0", "job-1", "job-2"), result.kept.map { it.jobId })
        assertEquals(7, result.dropped.size)
    }

    @Test
    fun `the day boundary is inclusive`() {
        val exactlyOld = entry("edge", 90)
        val onePast = TransferLogEntry(
            jobId = "edge-plus",
            direction = TransferDirection.RECEIVE,
            peerId = "peer",
            peerName = "Pixel",
            fileCount = 1,
            totalBytes = 1L,
            status = TransferStatus.COMPLETED,
            startedAtMillis = 0L,
            finishedAtMillis = now - 90 * day - 1L,
        )

        val result = TransferLogRetention.prune(
            listOf(exactlyOld, onePast),
            nowMillis = now,
            policy = RetentionPolicy(maxAgeDays = 90),
        )

        assertEquals(listOf("edge"), result.kept.map { it.jobId })
    }

    @Test
    fun `pruning a store rewrites its sink`() {
        val sink = object : TransferLogSink {
            val lines = mutableListOf<String>()
            override fun append(line: String) {
                lines += line
            }

            override fun readAll(): List<String> = lines.toList()
            override fun rewrite(lines: List<String>) {
                this.lines.clear()
                this.lines += lines
            }
        }
        val store = TransferLogStore(sink = sink)
        store.add(entry("old", 200))
        store.add(entry("new", 1))

        val result = store.prune(nowMillis = now, policy = RetentionPolicy(maxAgeDays = 30))

        assertTrue(result.changed)
        assertEquals(listOf("new"), store.all().map { it.jobId })
        assertEquals(1, sink.readAll().size)
        assertTrue(sink.readAll().single().contains("new"))
    }

    @Test
    fun `a prune that changes nothing leaves the sink alone`() {
        var rewrites = 0
        val sink = object : TransferLogSink {
            val lines = mutableListOf<String>()
            override fun append(line: String) {
                lines += line
            }

            override fun readAll(): List<String> = lines.toList()
            override fun rewrite(lines: List<String>) {
                rewrites++
                this.lines.clear()
                this.lines += lines
            }
        }
        val store = TransferLogStore(sink = sink)
        store.add(entry("new", 1))

        store.prune(nowMillis = now, policy = RetentionPolicy(maxAgeDays = 30))

        assertEquals(0, rewrites)
    }

    @Test
    fun `replaceAll reorders to newest first and persists it`() {
        val sink = object : TransferLogSink {
            val lines = mutableListOf<String>()
            override fun append(line: String) {
                lines += line
            }

            override fun readAll(): List<String> = lines.toList()
            override fun rewrite(lines: List<String>) {
                this.lines.clear()
                this.lines += lines
            }
        }
        val store = TransferLogStore(sink = sink)

        store.replaceAll(listOf(entry("b", 1), entry("a", 5)))

        assertEquals(listOf("b", "a"), store.all().map { it.jobId })
        assertEquals(2, sink.readAll().size)
    }
}
