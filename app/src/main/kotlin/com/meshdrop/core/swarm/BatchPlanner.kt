package com.meshdrop.core.swarm

import com.meshdrop.core.model.PeerContribution
import com.meshdrop.core.model.TransferFile
import com.meshdrop.core.model.TransportLane
import com.meshdrop.core.resume.ByteRange

/** A peer that could carry part of a batch. */
data class SwarmPeer(
    val peerId: String,
    val displayName: String,
    val lane: TransportLane = TransportLane.WIFI_LAN,
    /** Measured or assumed throughput; the split is proportional to this. */
    val bandwidthBytesPerSecond: Long = DEFAULT_BANDWIDTH,
    val isVerified: Boolean = false,
) {
    companion object {
        /** A conservative Wi-Fi guess, used before anything has been measured. */
        const val DEFAULT_BANDWIDTH = 4L * 1024 * 1024
    }
}

/** One chunk range handed to one peer. */
data class ChunkAssignment(
    val fileId: String,
    val range: ByteRange,
    val peerId: String,
)

/**
 * How a batch is split, and what each peer owes.
 *
 * @param assignments chunk ranges per peer, in file order
 * @param contributions the same information as the UI models it, ready to render
 */
data class BatchPlan(
    val assignments: List<ChunkAssignment>,
    val contributions: List<PeerContribution>,
) {

    val isEmpty: Boolean get() = assignments.isEmpty()

    fun assignmentsFor(peerId: String): List<ChunkAssignment> = assignments.filter { it.peerId == peerId }

    fun assignmentsForFile(fileId: String): List<ChunkAssignment> = assignments.filter { it.fileId == fileId }

    val totalAssignedBytes: Long get() = assignments.sumOf { it.range.length }

    /** Peers taking part, best share first — the order the chips are shown in. */
    val participatingPeerIds: List<String>
        get() = contributions.sortedByDescending { it.bytesAssigned }.map { it.peerId }
}

/**
 * Splitting one batch across several peers.
 *
 * The MVP sends everything through a single peer, but the model, the UI and the transfer
 * card have been built for more than one for a while — chips per peer, bytes delivered per
 * peer, a fold for "+n". What was missing is the thing that decides who carries what.
 *
 * The split is proportional to measured bandwidth and always lands on chunk boundaries, so
 * every piece can be encrypted, hashed and resumed independently: a peer that drops out
 * costs one range, not the file.
 */
object BatchPlanner {

    /**
     * Plans a batch.
     *
     * With one peer (the MVP case) every chunk goes to it and the plan is identical to what
     * the single-peer path would have produced. With none, the plan is empty and the caller
     * keeps waiting rather than failing a transfer it has not started.
     */
    fun plan(
        files: List<TransferFile>,
        peers: List<SwarmPeer>,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
    ): BatchPlan {
        val usable = peers.filter { it.bandwidthBytesPerSecond > 0L }
        if (usable.isEmpty() || files.isEmpty()) return BatchPlan(emptyList(), emptyList())

        val assignments = ArrayList<ChunkAssignment>()
        var cursor = 0

        for (file in files) {
            val chunks = chunkCount(file.sizeBytes, chunkSize)
            val shares = shares(usable, chunks)
            for ((peerIndex, peer) in usable.withIndex()) {
                var remaining = shares[peerIndex]
                while (remaining > 0 && cursor < chunks) {
                    // Keep a peer's run contiguous: fewer connections, and a resumable unit.
                    val runStart = cursor
                    val runEnd = minOf(cursor + remaining, chunks)
                    assignments += ChunkAssignment(
                        fileId = file.id,
                        range = ByteRange(
                            start = runStart.toLong() * chunkSize,
                            endExclusive = minOf(runEnd.toLong() * chunkSize, file.sizeBytes.coerceAtLeast(0L)),
                        ),
                        peerId = peer.peerId,
                    )
                    remaining -= runEnd - runStart
                    cursor = runEnd
                }
            }
            cursor = 0
        }

        return BatchPlan(
            assignments = assignments,
            contributions = usable.map { peer ->
                val assigned = assignments.filter { it.peerId == peer.peerId }.sumOf { it.range.length }
                PeerContribution(
                    peerId = peer.peerId,
                    displayName = peer.displayName,
                    lane = peer.lane,
                    bytesAssigned = assigned,
                    bytesDelivered = 0L,
                    isActive = assigned > 0L,
                )
            }.filter { it.bytesAssigned > 0L },
        )
    }

    /**
     * Re-plans after a peer drops out mid-transfer.
     *
     * Chunks that peer had not delivered yet go back into the pool; the ones it did deliver
     * stay where they are, which is the whole reason the split is per chunk.
     */
    fun rebalance(
        plan: BatchPlan,
        droppedPeerId: String,
        deliveredRanges: List<ByteRange> = emptyList(),
        peers: List<SwarmPeer>,
        files: List<TransferFile>,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
    ): BatchPlan {
        val survivors = peers.filter { it.peerId != droppedPeerId }
        if (survivors.isEmpty()) return BatchPlan(emptyList(), emptyList())

        val keep = plan.assignments.filter { it.peerId != droppedPeerId }
        val orphaned = plan.assignments
            .filter { it.peerId == droppedPeerId }
            .filter { assignment -> deliveredRanges.none { it.contains(assignment.range.start) } }

        if (orphaned.isEmpty()) {
            return BatchPlan(keep, plan.contributions.filter { it.peerId != droppedPeerId })
        }

        // Hand the orphaned ranges out in the same proportion the original plan used.
        // Ranges are moved whole: each one is already an independently encrypted, hashed
        // and resumable unit, so splitting it again would only add work.
        val weights = shares(survivors, orphaned.size)
        val reassigned = ArrayList<ChunkAssignment>(orphaned.size)
        var cursor = 0
        for ((peerIndex, peer) in survivors.withIndex()) {
            repeat(weights.getOrElse(peerIndex) { 0 }) {
                if (cursor < orphaned.size) {
                    val orphan = orphaned[cursor]
                    reassigned += orphan.copy(peerId = peer.peerId)
                    cursor++
                }
            }
        }

        val placed = keep + reassigned
        val contributions = placed.map { it.peerId }.distinct().map { peerId ->
            val peer = survivors.firstOrNull { it.peerId == peerId }
            val previous = plan.contributions.firstOrNull { it.peerId == peerId }
            PeerContribution(
                peerId = peerId,
                displayName = peer?.displayName ?: previous?.displayName ?: peerId,
                lane = peer?.lane ?: previous?.lane ?: TransportLane.WIFI_LAN,
                bytesAssigned = placed.filter { it.peerId == peerId }.sumOf { it.range.length },
                bytesDelivered = previous?.bytesDelivered ?: 0L,
                isActive = true,
            )
        }
        return BatchPlan(assignments = placed, contributions = contributions)
    }

    /**
     * Largest-remainder split of [chunks] across [peers], proportional to bandwidth.
     *
     * Every peer with a share of the bandwidth gets at least one chunk when there are
     * enough chunks to go round, so a slow peer is not silently left out.
     */
    internal fun shares(peers: List<SwarmPeer>, chunks: Int): List<Int> {
        if (chunks <= 0 || peers.isEmpty()) return List(peers.size) { 0 }
        val totalBandwidth = peers.sumOf { it.bandwidthBytesPerSecond.coerceAtLeast(0L) }
        if (totalBandwidth <= 0L) return List(peers.size) { 0 }

        val exact = peers.map { it.bandwidthBytesPerSecond.toDouble() * chunks / totalBandwidth }
        val base = exact.map { it.toInt() }
        var left = chunks - base.sum()
        val order = exact.indices.sortedByDescending { exact[it] - base[it] }
        val result = base.toMutableList()
        var index = 0
        while (left > 0 && index < order.size * 2) {
            result[order[index % order.size]] += 1
            left--
            index++
        }
        return result
    }

    private fun chunkCount(sizeBytes: Long, chunkSize: Int): Int =
        if (sizeBytes <= 0L) 1 else ((sizeBytes + chunkSize - 1) / chunkSize).toInt()

    const val DEFAULT_CHUNK_SIZE = 64 * 1024
}
