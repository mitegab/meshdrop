package com.meshdrop.transfer.protocol

import com.meshdrop.core.security.SafetyCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Version negotiation decides what two phones that were updated years apart may assume about
 * each other, so the rules are tested rather than discovered on a LAN.
 */
class ProtocolVersionsTest {

    @Test
    fun `the lower of the two versions wins`() {
        assertEquals(2, ProtocolVersions.negotiate(3, 2))
        assertEquals(2, ProtocolVersions.negotiate(2, 3))
        assertEquals(1, ProtocolVersions.negotiate(3, 1))
        assertEquals(3, ProtocolVersions.negotiate(3, 3))
    }

    @Test
    fun `nothing below the minimum is spoken`() {
        assertEquals(ProtocolVersions.MINIMUM_SUPPORTED, ProtocolVersions.negotiate(3, 0))
        assertEquals(ProtocolVersions.MINIMUM_SUPPORTED, ProtocolVersions.negotiate(3, -1))
    }

    @Test
    fun `a peer from the future is refused rather than half parsed`() {
        assertTrue(ProtocolVersions.requiresUpgrade(ProtocolVersions.CURRENT + 1))
        assertFalse(ProtocolVersions.requiresUpgrade(ProtocolVersions.CURRENT))
        assertFalse(ProtocolVersions.isSupported(ProtocolVersions.CURRENT + 1))
        assertTrue(ProtocolVersions.isSupported(1))
        assertFalse(ProtocolVersions.isSupported(0))
    }

    @Test
    fun `encryption needs v2`() {
        assertFalse(ProtocolVersions.capabilities(1).encryption)
        assertTrue(ProtocolVersions.capabilities(2).encryption)
    }

    @Test
    fun `digests and resume need v3`() {
        val v2 = ProtocolVersions.capabilities(2)
        assertFalse(v2.digests)
        assertFalse(v2.resume)
        assertFalse(v2.verifiesFiles)

        val v3 = ProtocolVersions.capabilities(3)
        assertTrue(v3.digests)
        assertTrue(v3.resume)
        assertTrue(v3.verifiesFiles)
    }

    @Test
    fun `the safety code derivation follows the negotiated version`() {
        assertEquals(
            SafetyCodeDerivation.LEGACY,
            ProtocolVersions.safetyCodeDerivation(SafetyCode.LEGACY_PROTOCOL_VERSION),
        )
        assertEquals(
            SafetyCodeDerivation.UNBIASED,
            ProtocolVersions.safetyCodeDerivation(SafetyCode.PROTOCOL_VERSION),
        )
    }

    @Test
    fun `the two derivations actually disagree, which is why the version matters`() {
        val material = byteArrayOf(0x4f, 0x2a, 0x91.toByte(), 0x0c, 0x7d, 0xe3.toByte())

        val legacy = SafetyCode.fromBytes(material, peerProtocolVersion = SafetyCode.LEGACY_PROTOCOL_VERSION)
        val current = SafetyCode.fromBytes(material, peerProtocolVersion = SafetyCode.PROTOCOL_VERSION)

        assertEquals("925 257", legacy)
        assertFalse(legacy == current)
    }
}
