package com.meshdrop.core.swarm

import com.meshdrop.core.model.TransferFile
import com.meshdrop.core.model.TransportLane
import com.meshdrop.core.resume.ByteRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The split is arithmetic over chunk ranges, so it is tested without a socket. */
class BatchPlannerTest {

    private val chunk = 1_024
    private val file = TransferFile(id = "f1", name = "video.mp4", sizeBytes = 10L * chunk)

    private fun peer(id: String, bandwidth: Long = SwarmPeer.DEFAULT_BANDWIDTH, lane: TransportLane = TransportLane.WIFI_LAN) =
        SwarmPeer(peerId = id, displayName = id.uppercase(), lane = lane, bandwidthBytesPerSecond = bandwidth)

    @Test
    fun `with one peer the plan is the whole file`() {
        val plan = BatchPlanner.plan(listOf(file), listOf(peer("a")), chunk)

        assertEquals(1, plan.assignments.size)
        assertEquals(ByteRange(0L, 10L * chunk), plan.assignments.single().range)
        assertEquals("a", plan.assignments.single().peerId)
        assertEquals(10L * chunk, plan.totalAssignedBytes)
        assertEquals(listOf("a"), plan.participatingPeerIds)
    }

    @Test
    fun `the split follows the measured bandwidth`() {
        val plan = BatchPlanner.plan(listOf(file), listOf(peer("fast", 3), peer("slow", 1)), chunk)

        assertEquals(8, plan.assignmentsFor("fast").sumOf { it.range.length } / chunk)
        assertEquals(2, plan.assignmentsFor("slow").sumOf { it.range.length } / chunk)
        assertEquals(listOf("fast", "slow"), plan.participatingPeerIds)
    }

    @Test
    fun `every byte is assigned exactly once and on a chunk boundary`() {
        val peers = listOf(peer("a", 5), peer("b", 3), peer("c", 2))
        val plan = BatchPlanner.plan(listOf(file), peers, chunk)

        assertEquals(10L * chunk, plan.totalAssignedBytes)
        // Coverage: sort the ranges and walk them.
        val sorted = plan.assignments.map { it.range }.sortedBy { it.start }
        var cursor = 0L
        for (range in sorted) {
            assertEquals(cursor, range.start)
            assertEquals(0L, range.length % chunk)
            cursor = range.endExclusive
        }
        assertEquals(10L * chunk, cursor)
    }

    @Test
    fun `a peer keeps a contiguous run rather than scattered chunks`() {
        val plan = BatchPlanner.plan(listOf(file), listOf(peer("a", 1), peer("b", 1)), chunk)

        assertEquals(1, plan.assignmentsFor("a").size)
        assertEquals(1, plan.assignmentsFor("b").size)
        assertEquals(ByteRange(0L, 5L * chunk), plan.assignmentsFor("a").single().range)
        assertEquals(ByteRange(5L * chunk, 10L * chunk), plan.assignmentsFor("b").single().range)
    }

    @Test
    fun `several files are split independently`() {
        val second = TransferFile(id = "f2", name = "photo.jpg", sizeBytes = 4L * chunk)
        val plan = BatchPlanner.plan(listOf(file, second), listOf(peer("a", 1), peer("b", 1)), chunk)

        assertEquals(2, plan.assignmentsForFile("f1").size)
        assertEquals(2, plan.assignmentsForFile("f2").size)
        assertEquals(14L * chunk, plan.totalAssignedBytes)
        plan.assignmentsForFile("f2").forEach { assertTrue(it.range.endExclusive <= 4L * chunk) }
    }

    @Test
    fun `no peers means no plan, and nothing is failed`() {
        assertTrue(BatchPlanner.plan(listOf(file), emptyList(), chunk).isEmpty)
        assertTrue(BatchPlanner.plan(emptyList(), listOf(peer("a")), chunk).isEmpty)
        // A peer with no measured throughput cannot carry anything.
        assertTrue(BatchPlanner.plan(listOf(file), listOf(peer("a", 0L)), chunk).isEmpty)
    }

    @Test
    fun `a zero byte file still gets one empty chunk`() {
        val empty = TransferFile(id = "empty", name = "note.txt", sizeBytes = 0L)
        val plan = BatchPlanner.plan(listOf(empty), listOf(peer("a")), chunk)

        assertEquals(1, plan.assignments.size)
        assertEquals(ByteRange(0L, 0L), plan.assignments.single().range)
    }

    @Test
    fun `a short last chunk keeps its real length`() {
        val odd = TransferFile(id = "odd", name = "clip.mp4", sizeBytes = 2L * chunk + 500L)
        val plan = BatchPlanner.plan(listOf(odd), listOf(peer("a")), chunk)

        assertEquals(2L * chunk + 500L, plan.totalAssignedBytes)
        assertEquals(ByteRange(0L, 2L * chunk + 500L), plan.assignments.single().range)
    }

    @Test
    fun `shares use the largest remainder rule and never lose a chunk`() {
        val peers = listOf(peer("a", 1), peer("b", 1), peer("c", 1))

        assertEquals(listOf(4, 3, 3), BatchPlanner.shares(peers, 10))
        assertEquals(listOf(1, 1, 1), BatchPlanner.shares(peers, 3))
        assertEquals(listOf(1, 0, 0), BatchPlanner.shares(peers, 1))
        assertEquals(listOf(0, 0, 0), BatchPlanner.shares(peers, 0))
    }

    @Test
    fun `a dropped peer hands its undelivered ranges to the survivors`() {
        val peers = listOf(peer("a", 1), peer("b", 1))
        val original = BatchPlanner.plan(listOf(file), peers, chunk)
        assertEquals(2, original.assignments.size)

        val rebalanced = BatchPlanner.rebalance(
            plan = original,
            droppedPeerId = "b",
            deliveredRanges = emptyList(),
            peers = peers,
            files = listOf(file),
            chunkSize = chunk,
        )

        assertTrue(rebalanced.assignmentsFor("b").isEmpty())
        assertEquals(10L * chunk, rebalanced.totalAssignedBytes)
        assertEquals(listOf("a"), rebalanced.contributions.map { it.peerId })
    }

    @Test
    fun `ranges a dropped peer already delivered are not handed out again`() {
        val peers = listOf(peer("a", 1), peer("b", 1))
        val original = BatchPlanner.plan(listOf(file), peers, chunk)
        val delivered = original.assignmentsFor("b").map { ByteRange(it.range.start, it.range.start + chunk) }

        val rebalanced = BatchPlanner.rebalance(
            plan = original,
            droppedPeerId = "b",
            deliveredRanges = delivered,
            peers = peers,
            files = listOf(file),
            chunkSize = chunk,
        )

        // Everything b owed is already on disk: nothing left to reassign.
        assertEquals(original.assignmentsFor("a"), rebalanced.assignments)
        assertFalse(rebalanced.contributions.any { it.peerId == "b" })
    }

    @Test
    fun `with nobody left the plan is empty rather than a failed transfer`() {
        val original = BatchPlanner.plan(listOf(file), listOf(peer("a")), chunk)

        val rebalanced = BatchPlanner.rebalance(
            plan = original,
            droppedPeerId = "a",
            peers = listOf(peer("a")),
            files = listOf(file),
            chunkSize = chunk,
        )

        assertTrue(rebalanced.isEmpty)
    }

    @Test
    fun `contributions carry the lane the chips are drawn with`() {
        val plan = BatchPlanner.plan(
            files = listOf(file),
            peers = listOf(peer("a", 2, TransportLane.WIFI_DIRECT), peer("b", 1, TransportLane.BLUETOOTH)),
            chunkSize = chunk,
        )

        assertEquals(TransportLane.WIFI_DIRECT, plan.contributions.first { it.peerId == "a" }.lane)
        assertEquals(TransportLane.BLUETOOTH, plan.contributions.first { it.peerId == "b" }.lane)
        assertTrue(plan.contributions.all { it.bytesDelivered == 0L })
        assertTrue(plan.contributions.all { it.isActive })
    }
}
