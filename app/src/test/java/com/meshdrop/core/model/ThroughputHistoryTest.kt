package com.meshdrop.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThroughputHistoryTest {

    @Test
    fun `a fresh history is zero padded so the graph does not slide`() {
        val history = ThroughputHistory(capacity = 5)
        val snapshot = history.snapshot()
        assertEquals(5, snapshot.size)
        assertTrue(snapshot.all { it == 0L })
    }

    @Test
    fun `samples are returned oldest first and the window is bounded`() {
        val history = ThroughputHistory(capacity = 3)
        history.add(1)
        history.add(2)
        history.add(3)
        history.add(4)

        assertEquals(listOf(2L, 3L, 4L), history.snapshot())
        assertEquals(4L, history.peak(minimum = 0L))
    }

    @Test
    fun `peak has a floor so an idle graph still has a scale`() {
        val history = ThroughputHistory(capacity = 4)
        history.add(0)
        assertEquals(ThroughputHistory.MINIMUM_PEAK, history.peak())
    }

    @Test
    fun `contributions clamp progress to a sane range`() {
        val contribution = PeerContribution(
            peerId = "a",
            displayName = "Pixel",
            lane = TransportLane.WIFI_LAN,
            bytesAssigned = 100,
            bytesDelivered = 250,
        )
        assertEquals(1f, contribution.progress, 0.0001f)
        assertEquals(0f, PeerContribution("a", "Pixel", TransportLane.WIFI_LAN).progress, 0.0001f)
    }

    @Test
    fun `trust level says what it means`() {
        assertEquals(TrustLevel.PLAINTEXT, SecurityState().trustLevel)
        assertEquals(
            TrustLevel.UNVERIFIED,
            SecurityState(mode = EncryptionMode.E2E_AES_256_GCM, safetyCode = "123 456").trustLevel,
        )
        assertEquals(
            TrustLevel.VERIFIED,
            SecurityState(mode = EncryptionMode.E2E_AES_256_GCM, peerRemembered = true).trustLevel,
        )
        assertEquals(
            TrustLevel.VERIFIED,
            SecurityState(mode = EncryptionMode.E2E_AES_256_GCM, codeCompared = true).trustLevel,
        )
    }
}
