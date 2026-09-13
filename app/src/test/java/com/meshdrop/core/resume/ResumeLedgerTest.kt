package com.meshdrop.core.resume

import com.meshdrop.core.integrity.ChunkHashTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Resume bookkeeping is pure arithmetic, so it is tested without a socket. */
class ResumeLedgerTest {

    private val chunk = 64 * 1024

    /** 5 full chunks + a 1 KB tail. */
    private val total = 5L * chunk + 1_024L

    private fun hash(index: Int) = ChunkHashTree.leaf("chunk-$index".toByteArray())

    private fun ledgerWith(vararg confirmed: Int): ResumeLedger = ResumeLedger(chunk).apply {
        confirmed.forEach { confirmChunk("file", it, total, hash(it)) }
    }

    @Test
    fun `an unknown file has to start from scratch`() {
        val plan = ResumeLedger(chunk).plan("file", total)

        assertEquals(6, plan.chunkCount)
        assertEquals(listOf(ByteRange(0L, total)), plan.ranges)
        assertEquals(total, plan.bytesRemaining)
        assertEquals(0L, plan.confirmedBytes)
        assertTrue(plan.startsFromScratch)
        assertFalse(plan.isComplete)
        assertEquals("starting over", plan.headline)
    }

    @Test
    fun `missing chunks in the middle become two ranges`() {
        val ledger = ledgerWith(0, 1, 4, 5)

        val plan = ledger.plan("file", total)

        assertEquals(listOf(ByteRange(2L * chunk, 4L * chunk)), plan.ranges)
        assertEquals(2 * chunk.toLong(), plan.bytesRemaining)
        assertEquals(3L * chunk + 1_024L, plan.confirmedBytes)
        assertFalse(plan.startsFromScratch)
    }

    @Test
    fun `adjacent missing chunks are merged into one range`() {
        val plan = ledgerWith(0, 5).plan("file", total)

        assertEquals(listOf(ByteRange(chunk.toLong(), 5L * chunk)), plan.ranges)
        assertEquals("resuming from 65 KB", plan.headline)
    }

    @Test
    fun `a confirmed tail only leaves the head to send`() {
        val plan = ledgerWith(3, 4, 5).plan("file", total)

        assertEquals(listOf(ByteRange(0L, 3L * chunk)), plan.ranges)
        assertEquals(3L * chunk, plan.bytesRemaining)
        assertEquals(2L * chunk + 1_024L, plan.savedBytes)
    }

    @Test
    fun `a fully confirmed file has nothing left to send`() {
        val plan = ledgerWith(0, 1, 2, 3, 4, 5).plan("file", total)

        assertTrue(plan.isComplete)
        assertTrue(plan.ranges.isEmpty())
        assertEquals(total, plan.confirmedBytes)
        assertEquals(0L, plan.bytesRemaining)
        assertEquals("already complete", plan.headline)
    }

    @Test
    fun `the short final chunk counts its real length`() {
        val ledger = ledgerWith(5)

        assertEquals(1_024L, ledger.confirmedBytes("file", total))
        assertEquals(ByteRange(5L * chunk, total), ByteRange.ofChunk(5, chunk, total))
    }

    @Test
    fun `a contradicting hash is rejected and leaves the chunk open`() {
        val ledger = ledgerWith(2)

        val same = ledger.confirmChunk("file", 2, total, hash(2))
        val different = ledger.confirmChunk("file", 2, total, hash(99))

        assertTrue(same)
        assertFalse(different)
        assertFalse(ledger.isConfirmed("file", 3))
        assertTrue(ledger.isConfirmed("file", 2))
        assertEquals(listOf(0, 1, 3, 4, 5), ledger.missingChunks("file", total))
    }

    @Test
    fun `chunk indexes outside the file are ignored`() {
        val ledger = ResumeLedger(chunk)

        assertFalse(ledger.confirmChunk("file", -1, total, hash(0)))
        assertFalse(ledger.confirmChunk("file", 6, total, hash(0)))
        assertTrue(ledger.confirmedChunks("file").isEmpty())
    }

    @Test
    fun `invalidating a chunk reopens its range`() {
        val ledger = ledgerWith(0, 1, 2, 3, 4, 5)
        assertTrue(ledger.plan("file", total).isComplete)

        ledger.invalidate("file", 4)

        assertEquals(listOf(ByteRange(4L * chunk, 5L * chunk)), ledger.plan("file", total).ranges)
    }

    @Test
    fun `forgetting a file drops everything we knew about it`() {
        val ledger = ledgerWith(0, 1)
        ledger.forget("file")

        assertEquals(0L, ledger.confirmedBytes("file", total))
        assertNull(ledger.root("file"))
        assertTrue(ledger.plan("file", total).startsFromScratch)
    }

    @Test
    fun `the merkle root covers exactly the confirmed chunks in order`() {
        val ledger = ledgerWith(1, 0)
        val expected = ChunkHashTree.rootHex(listOf(hash(0), hash(1)))

        assertEquals(expected, ledger.rootHex("file"))
        assertNotEquals(expected, ledgerWith(0, 1, 2).rootHex("file"))
    }

    @Test
    fun `a zero byte file needs one empty chunk`() {
        val ledger = ResumeLedger(chunk)
        val plan = ledger.plan("empty", 0L)

        assertEquals(1, plan.chunkCount)
        assertEquals(listOf(ByteRange(0L, 0L)), plan.ranges)
        assertEquals(0L, plan.bytesRemaining)

        ledger.confirmChunk("empty", 0, 0L, hash(0))
        assertTrue(ledger.plan("empty", 0L).isComplete)
    }

    @Test
    fun `ledgers are per file`() {
        val ledger = ResumeLedger(chunk)
        ledger.confirmChunk("a", 0, total, hash(0))
        ledger.confirmChunk("b", 3, total, hash(3))

        assertEquals(setOf(0), ledger.confirmedChunks("a"))
        assertEquals(setOf(3), ledger.confirmedChunks("b"))
        assertEquals(chunk.toLong(), ledger.confirmedBytes("a", total))
        assertEquals(chunk.toLong(), ledger.confirmedBytes("b", total))

        ledger.forgetAll()
        assertTrue(ledger.confirmedChunks("a").isEmpty())
        assertTrue(ledger.confirmedChunks("b").isEmpty())
    }
}
