package com.meshdrop.ui.history

import com.meshdrop.core.model.EncryptionMode
import com.meshdrop.core.model.SecurityState
import com.meshdrop.core.model.TransferDirection
import com.meshdrop.core.model.TransferFile
import com.meshdrop.core.model.TransferJob
import com.meshdrop.core.model.TransferStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Filtering the feed is pure, so search, chips and ordering are tested on the JVM. */
class HistoryFilterTest {

    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 9, 13)
    private val todayMillis = today.atStartOfDay(zone).toInstant().toEpochMilli() + 3_600_000L
    private val yesterdayMillis = todayMillis - 24L * 60 * 60 * 1000

    private fun job(
        id: String,
        peer: String = "Pixel 8",
        names: List<String> = listOf("photo.jpg"),
        direction: TransferDirection = TransferDirection.RECEIVE,
        status: TransferStatus = TransferStatus.COMPLETED,
        bytes: Long = 1_000L,
        startedAt: Long = todayMillis,
        finishedAt: Long? = todayMillis + 1_000L,
    ) = TransferJob(
        id = id,
        direction = direction,
        peerName = peer,
        peerId = peer,
        files = names.map { TransferFile(id = it, name = it, sizeBytes = bytes) },
        bytesTransferred = bytes,
        totalBytes = bytes,
        status = status,
        startedAtMillis = startedAt,
        finishedAtMillis = finishedAt,
    )

    private val verified = SecurityState(
        mode = EncryptionMode.E2E_AES_256_GCM,
        safetyCode = "428 193",
        peerRemembered = true,
    )

    @Test
    fun `search matches the peer, the file names and the batch id`() {
        val batch = job("batch-9", names = listOf("Holiday Photo.jpg"))

        assertTrue(HistoryFiltering.matches(batch, "pixel"))
        assertTrue(HistoryFiltering.matches(batch, "HOLIDAY"))
        assertTrue(HistoryFiltering.matches(batch, "batch-9"))
        assertTrue(HistoryFiltering.matches(batch, "   "))
        assertFalse(HistoryFiltering.matches(batch, "screenshot"))
    }

    @Test
    fun `chips select a slice of the feed`() {
        val jobs = listOf(
            job("a", direction = TransferDirection.RECEIVE),
            job("b", direction = TransferDirection.SEND),
            job("c", direction = TransferDirection.RECEIVE, status = TransferStatus.FAILED),
        )
        val security = mapOf("a" to verified)

        assertEquals(listOf("a", "c"), HistoryFiltering.apply(jobs, HistoryQuery(scope = HistoryScope.RECEIVED)).map { it.id })
        assertEquals(listOf("b"), HistoryFiltering.apply(jobs, HistoryQuery(scope = HistoryScope.SENT)).map { it.id })
        assertEquals(listOf("c"), HistoryFiltering.apply(jobs, HistoryQuery(scope = HistoryScope.FAILED)).map { it.id })
        assertEquals(listOf("a"), HistoryFiltering.apply(jobs, HistoryQuery(scope = HistoryScope.VERIFIED), security).map { it.id })
        assertEquals(3, HistoryFiltering.apply(jobs).size)
    }

    @Test
    fun `ordering is explicit`() {
        val jobs = listOf(
            job("old", bytes = 5_000L, startedAt = yesterdayMillis, finishedAt = yesterdayMillis),
            job("new", bytes = 1_000L, startedAt = todayMillis, finishedAt = todayMillis),
        )

        assertEquals(listOf("new", "old"), HistoryFiltering.apply(jobs, HistoryQuery(order = HistoryOrder.NEWEST)).map { it.id })
        assertEquals(listOf("old", "new"), HistoryFiltering.apply(jobs, HistoryQuery(order = HistoryOrder.OLDEST)).map { it.id })
        assertEquals(listOf("old", "new"), HistoryFiltering.apply(jobs, HistoryQuery(order = HistoryOrder.LARGEST)).map { it.id })
    }

    @Test
    fun `devices are ordered without caring about case`() {
        val jobs = listOf(job("a", peer = "pixel"), job("b", peer = "Galaxy"), job("c", peer = "android"))

        assertEquals(
            listOf("c", "b", "a"),
            HistoryFiltering.apply(jobs, HistoryQuery(order = HistoryOrder.NAME)).map { it.id },
        )
    }

    @Test
    fun `the feed is grouped by the device's own day`() {
        val jobs = listOf(
            job("today-a", startedAt = todayMillis, finishedAt = todayMillis),
            job("yesterday", startedAt = yesterdayMillis, finishedAt = yesterdayMillis),
            job("today-b", startedAt = todayMillis + 60_000L, finishedAt = todayMillis + 61_000L),
        )

        val sections = HistoryFiltering.sections(jobs, today = today, zone = zone)

        assertEquals(listOf("Today", "Yesterday"), sections.map { it.label })
        assertEquals(2, sections.first().count)
        assertEquals(listOf("yesterday"), sections.last().jobs.map { it.id })
    }

    @Test
    fun `older days get a real date`() {
        val older = today.minusDays(30)

        assertEquals("Today", HistoryFiltering.labelFor(today, today))
        assertEquals("Yesterday", HistoryFiltering.labelFor(today.minusDays(1), today))
        assertEquals("14 August 2026", HistoryFiltering.labelFor(older, today))
    }

    @Test
    fun `the empty state knows why it is empty`() {
        val all = listOf(job("a"))

        assertNull(HistoryFiltering.emptyReason(all, all))
        assertEquals(HistoryEmptyReason.NO_TRANSFERS, HistoryFiltering.emptyReason(emptyList(), emptyList()))
        assertEquals(HistoryEmptyReason.NO_MATCHES, HistoryFiltering.emptyReason(all, emptyList()))

        assertEquals(
            "Nothing has been sent or received yet",
            HistoryFiltering.emptyMessage(HistoryEmptyReason.NO_TRANSFERS, HistoryQuery()),
        )
        assertEquals(
            "Nothing matches “pixel”",
            HistoryFiltering.emptyMessage(HistoryEmptyReason.NO_MATCHES, HistoryQuery(text = " pixel ")),
        )
        assertEquals(
            "No transfers in this view",
            HistoryFiltering.emptyMessage(HistoryEmptyReason.NO_MATCHES, HistoryQuery(scope = HistoryScope.SENT)),
        )
        assertNull(HistoryFiltering.emptyMessage(null, HistoryQuery()))
    }

    @Test
    fun `a query knows when it is doing something`() {
        assertFalse(HistoryQuery().isActive)
        assertTrue(HistoryQuery(text = "a").isActive)
        assertTrue(HistoryQuery(scope = HistoryScope.SENT).isActive)
        assertTrue(HistoryQuery(order = HistoryOrder.LARGEST).isActive)
        assertFalse(HistoryQuery(text = "a").cleared().isActive)
    }

    @Test
    fun `the chips are the ones worth showing`() {
        assertEquals(
            listOf(HistoryScope.RECEIVED, HistoryScope.SENT, HistoryScope.FAILED, HistoryScope.VERIFIED),
            HistoryScope.CHIPS,
        )
        assertEquals("Problems", HistoryScope.FAILED.label)
    }
}
