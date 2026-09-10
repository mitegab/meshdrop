package com.meshdrop.transfer.state

import com.meshdrop.core.model.EncryptionMode
import com.meshdrop.core.model.IncomingRequest
import com.meshdrop.core.model.SecurityState
import com.meshdrop.core.model.TransferDirection
import com.meshdrop.core.model.TransferFile
import com.meshdrop.core.model.TransferJob
import com.meshdrop.core.model.TransferStatus
import com.meshdrop.core.model.TransportLane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The active-transfer screen is a pure function of [TransferSessionState], so the mapping
 * from engine state is worth pinning down with tests.
 */
class TransferSessionMapperTest {

    private val files = listOf(
        TransferFile(id = "1", name = "photo.jpg", sizeBytes = 1_000, status = TransferStatus.WAITING),
        TransferFile(id = "2", name = "clip.mp4", sizeBytes = 3_000, status = TransferStatus.WAITING),
    )

    private fun job(
        status: TransferStatus,
        transferred: Long = 0L,
        direction: TransferDirection = TransferDirection.SEND,
    ) = TransferJob(
        id = "batch-1",
        direction = direction,
        peerName = "Pixel 8",
        peerId = "peer-1",
        files = files,
        bytesTransferred = transferred,
        totalBytes = 4_000,
        bytesPerSecond = 512_000,
        status = status,
    )

    @Test
    fun `a queued send is waiting for the other side`() {
        val state = TransferSessionMapper.toSessionState(job(TransferStatus.WAITING))
        assertTrue(state is TransferSessionState.AwaitingApproval)
        assertEquals("Pixel 8", (state as TransferSessionState.AwaitingApproval).peerName)
    }

    @Test
    fun `an in flight job exposes lanes and contributions for the progress view`() {
        val state = TransferSessionMapper.toSessionState(job(TransferStatus.IN_PROGRESS, transferred = 1_000))
        assertTrue(state is TransferSessionState.Active)

        val active = state as TransferSessionState.Active
        assertEquals(0.25f, active.progress, 0.0001f)
        assertEquals(TransportLane.WIFI_LAN, active.primaryLane)
        assertEquals(1, active.contributions.size)
        assertEquals(1_000L, active.contributions.single().bytesDelivered)
        // Nothing to hide from a casual user: the chips are already capped.
        assertTrue(active.visibleContributions.size <= com.meshdrop.core.model.PeerContribution.MAX_VISIBLE)
    }

    @Test
    fun `finished jobs carry their outcome and duration`() {
        val finished = job(TransferStatus.COMPLETED, transferred = 4_000).copy(
            finishedAtMillis = 10_000L,
            startedAtMillis = 8_000L,
        )
        val state = TransferSessionMapper.toSessionState(finished)
        assertTrue(state is TransferSessionState.Finished)

        val result = state as TransferSessionState.Finished
        assertTrue(result.succeeded)
        assertEquals(2_000L, result.durationMillis)
    }

    @Test
    fun `an incoming request becomes a decision the user has to make`() {
        val request = IncomingRequest(
            batchId = "batch-9",
            senderId = "peer-2",
            senderName = "Nazret's phone",
            senderAddress = "192.168.1.9",
            files = files,
            totalBytes = 4_000,
        )
        val state = TransferSessionMapper.toIncomingOffer(
            request,
            security = SecurityState(
                mode = EncryptionMode.E2E_AES_256_GCM,
                safetyCode = "428 193",
                peerRemembered = true,
            ),
        )

        assertEquals("Nazret's phone", state.peerName)
        assertEquals("428 193", state.security.safetyCode)
        assertEquals("Verified · encrypted", state.security.headline)
    }

    @Test
    fun `idle and discovering are distinct states`() {
        assertTrue(TransferSessionMapper.idle() is TransferSessionState.Idle)
        val discovering = TransferSessionMapper.discovering(listOf(TransportLane.WIFI_LAN))
        assertTrue(discovering is TransferSessionState.Discovering)
        assertEquals(null, discovering.batchIdOrNull)
    }
}
