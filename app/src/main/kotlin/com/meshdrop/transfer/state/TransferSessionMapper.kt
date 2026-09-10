package com.meshdrop.transfer.state

import com.meshdrop.core.model.IncomingRequest
import com.meshdrop.core.model.LaneActivity
import com.meshdrop.core.model.PeerContribution
import com.meshdrop.core.model.SecurityState
import com.meshdrop.core.model.TransferDirection
import com.meshdrop.core.model.TransferFile
import com.meshdrop.core.model.TransferJob
import com.meshdrop.core.model.TransportLane

/**
 * Turns engine state into screen state.
 *
 * Keeping this mapping in one pure function is what lets the active-transfer screen stay
 * dumb: it renders whatever [TransferSessionState] it is handed, and every decision about
 * "are we waiting, streaming or done" is testable without Android.
 */
object TransferSessionMapper {

    /** The only lane the MVP can use; a failover engine will fill this list in later. */
    fun toSessionState(
        job: TransferJob,
        security: SecurityState = SecurityState.UNKNOWN,
        lane: TransportLane = TransportLane.WIFI_LAN,
    ): TransferSessionState {
        val files = job.files
        val totalBytes = job.totalBytes

        if (job.status.isFinished) {
            return TransferSessionState.Finished(
                batchId = job.id,
                peerName = job.peerName,
                direction = job.direction,
                status = job.status,
                message = job.message,
                files = files,
                totalBytes = totalBytes,
                security = security,
                durationMillis = job.finishedAtMillis?.let { it - job.startedAtMillis } ?: 0L,
            )
        }

        if (job.status == com.meshdrop.core.model.TransferStatus.WAITING) {
            return TransferSessionState.AwaitingApproval(
                batchId = job.id,
                peerName = job.peerName,
                files = files,
                totalBytes = totalBytes,
                security = security,
            )
        }

        return TransferSessionState.Active(
            batchId = job.id,
            peerName = job.peerName,
            direction = job.direction,
            files = files,
            totalBytes = totalBytes,
            bytesTransferred = job.bytesTransferred,
            bytesPerSecond = job.bytesPerSecond,
            etaMillis = job.etaMillis,
            lanes = listOf(
                LaneActivity(
                    lane = lane,
                    bytesPerSecond = job.bytesPerSecond,
                    active = true,
                ),
            ),
            contributions = listOf(
                PeerContribution(
                    peerId = job.peerId,
                    displayName = job.peerName,
                    lane = lane,
                    bytesAssigned = totalBytes,
                    bytesDelivered = job.bytesTransferred,
                    isActive = true,
                ),
            ),
            security = security,
        )
    }

    /** Receiver side: an offer waiting for the user's decision. */
    fun toIncomingOffer(
        request: IncomingRequest,
        security: SecurityState = SecurityState.UNKNOWN,
    ): TransferSessionState.IncomingOffer = TransferSessionState.IncomingOffer(
        batchId = request.batchId,
        peerName = request.senderName,
        files = request.files,
        totalBytes = request.totalBytes,
        security = security,
        arrivedAtMillis = request.arrivedAtMillis,
    )

    /** Placeholder shown while nothing has happened yet. */
    fun idle(): TransferSessionState = TransferSessionState.Idle

    fun discovering(lanes: List<TransportLane>): TransferSessionState =
        TransferSessionState.Discovering(lanes)

    /** Files the user picked but has not sent yet (used by the Send screen preview). */
    fun toAwaitingApproval(
        batchId: String,
        peerName: String,
        files: List<TransferFile>,
        security: SecurityState = SecurityState.UNKNOWN,
    ): TransferSessionState.AwaitingApproval = TransferSessionState.AwaitingApproval(
        batchId = batchId,
        peerName = peerName,
        files = files,
        totalBytes = files.sumOf { it.sizeBytes },
        security = security,
    )
}
