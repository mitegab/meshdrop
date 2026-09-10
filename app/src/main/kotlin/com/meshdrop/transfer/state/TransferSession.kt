package com.meshdrop.transfer.state

import com.meshdrop.core.model.LaneActivity
import com.meshdrop.core.model.PeerContribution
import com.meshdrop.core.model.SecurityState
import com.meshdrop.core.model.TransferDirection
import com.meshdrop.core.model.TransferFile
import com.meshdrop.core.model.TransferStatus

/**
 * One sealed value describes everything the active-transfer screen can show.
 *
 * The screen is a pure function of this: no screen-level flags, no "isLoading && !hasError"
 * gum. Because the states are explicit they also map 1:1 onto the protocol's own states
 * (offered → approved → streaming → verified → done), which is what makes formal
 * verification of the UI reachable later.
 *
 * Progressive disclosure lives here too: [Active.lanes], [Active.contributions] and
 * [Active.retransmits] are the "power user" fields — the normal UI only reads the headline
 * progress, and the Advanced panel reads everything.
 */
sealed interface TransferSessionState {

    data object Idle : TransferSessionState

    /** Scanning for peers; the Home screen shows the radar while in this state. */
    data class Discovering(
        val lanes: List<com.meshdrop.core.model.TransportLane> = listOf(com.meshdrop.core.model.TransportLane.WIFI_LAN),
    ) : TransferSessionState

    /** Sender side: files picked, handshake sent, waiting for the receiver to accept. */
    data class AwaitingApproval(
        val batchId: String,
        val peerName: String,
        val files: List<TransferFile>,
        val totalBytes: Long,
        val security: SecurityState,
    ) : TransferSessionState

    /** Receiver side: somebody is offering us files and we have to decide. */
    data class IncomingOffer(
        val batchId: String,
        val peerName: String,
        val files: List<TransferFile>,
        val totalBytes: Long,
        val security: SecurityState,
        val arrivedAtMillis: Long,
    ) : TransferSessionState

    /** Bytes are moving. This is the screen that has to feel great. */
    data class Active(
        val batchId: String,
        val peerName: String,
        val direction: TransferDirection,
        val files: List<TransferFile>,
        val totalBytes: Long,
        val bytesTransferred: Long,
        val bytesPerSecond: Long,
        val etaMillis: Long?,
        /** Lanes that are carrying bytes right now, best first. */
        val lanes: List<LaneActivity>,
        /** Peers contributing chunks (swarm mode); 1 entry on the MVP. */
        val contributions: List<PeerContribution>,
        val security: SecurityState,
        /** Chunks that had to be sent again. Zero on the MVP, surfaced in Advanced. */
        val retransmits: Int = 0,
        /** True when the last failover happened less than a moment ago. */
        val justFailedOver: Boolean = false,
    ) : TransferSessionState {
        val progress: Float
            get() = if (totalBytes <= 0L) 0f else (bytesTransferred.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)

        /** The lane currently carrying the most bytes — "the pipe in use". */
        val primaryLane: com.meshdrop.core.model.TransportLane
            get() = lanes.firstOrNull { it.active }?.lane
                ?: com.meshdrop.core.model.TransportLane.WIFI_LAN

        /** Chips the casual UI may show, already capped. */
        val visibleContributions: List<PeerContribution>
            get() = contributions.sortedByDescending { it.bytesDelivered }.take(PeerContribution.MAX_VISIBLE)
    }

    /** Terminal state: success, failure, rejection or cancellation. */
    data class Finished(
        val batchId: String,
        val peerName: String,
        val direction: TransferDirection,
        val status: TransferStatus,
        val message: String?,
        val files: List<TransferFile>,
        val totalBytes: Long,
        val security: SecurityState,
        val durationMillis: Long = 0L,
    ) : TransferSessionState {
        val succeeded: Boolean get() = status == TransferStatus.COMPLETED
    }

    val batchIdOrNull: String?
        get() = when (this) {
            Idle -> null
            is AwaitingApproval -> batchId
            is IncomingOffer -> batchId
            is Active -> batchId
            is Finished -> batchId
            is Discovering -> null
        }
}
