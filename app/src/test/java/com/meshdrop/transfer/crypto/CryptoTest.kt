package com.meshdrop.transfer.crypto

import com.meshdrop.core.model.EndpointRole
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The security layer runs on the JVM because everything it needs (EC, ECDH, HMAC, AES-GCM)
 * is in the standard library — no Robolectric, no instrumentation.
 */
class CryptoTest {

    private fun negotiators(): Pair<SessionNegotiator, SessionNegotiator> {
        val alice = SessionNegotiator(InMemoryIdentity(), InMemoryTrustedPeers())
        val bob = SessionNegotiator(InMemoryIdentity(), InMemoryTrustedPeers())
        return alice to bob
    }

    // region key derivation ---------------------------------------------------------------

    @Test
    fun `hkdf is deterministic but separates different labels`() {
        val ikm = "shared secret".toByteArray()
        val salt = "transcript".toByteArray()

        val first = Hkdf.derive(ikm, salt, "meshdrop/v1/send".toByteArray(), 32)
        val second = Hkdf.derive(ikm, salt, "meshdrop/v1/send".toByteArray(), 32)
        val other = Hkdf.derive(ikm, salt, "meshdrop/v1/receive".toByteArray(), 32)

        assertArrayEquals(first, second)
        assertEquals(32, first.size)
        assertFalse(first.contentEquals(other))
    }

    @Test
    fun `hkdf can produce longer output than one hash block`() {
        val output = Hkdf.derive("ikm".toByteArray(), "salt".toByteArray(), "info".toByteArray(), 80)
        assertEquals(80, output.size)
    }

    // region framed encryption ------------------------------------------------------------

    @Test
    fun `framing overhead is exactly predictable`() {
        val plaintext = 200_000L
        val ciphertext = SecureFrames.ciphertextLength(plaintext)

        // 4 chunks: 3 x 64 KB + 1 remainder, each with a 32 byte header
        // (4 length + 12 nonce) that is followed by a 16 byte GCM tag.
        assertEquals(4L * (SecureFrames.LENGTH_BYTES + SecureFrames.NONCE_BYTES + SecureFrames.TAG_BYTES) + plaintext, ciphertext)
        assertEquals(plaintext, SecureFrames.plaintextLength(ciphertext))
    }

    @Test
    fun `a chunk survives a round trip and a tampered one does not`() {
        val key = Hkdf.derive("key".toByteArray(), info = "test".toByteArray(), length = 32)
        val salt = byteArrayOf(1, 2, 3, 4)
        val payload = "hello meshdrop".toByteArray()

        val frame = SecureFrames.encrypt(key, salt, counter = 7L, plaintext = payload)
        assertEquals(SecureFrames.frameSize(payload.size), frame.size)
        assertArrayEquals(payload, SecureFrames.decrypt(key, frame))

        val tampered = frame.copyOf()
        tampered[tampered.lastIndex] = (tampered[tampered.lastIndex] + 1).toByte()
        val failed = runCatching { SecureFrames.decrypt(key, tampered) }.isFailure
        assertTrue("a modified frame must fail its authentication tag", failed)
    }

    @Test
    fun `reusing a nonce would be caught, so counters must differ`() {
        val key = Hkdf.derive("key".toByteArray(), info = "test".toByteArray(), length = 32)
        val salt = byteArrayOf(9, 9, 9, 9)
        val payload = "same bytes".toByteArray()

        val first = SecureFrames.encrypt(key, salt, counter = 1L, plaintext = payload)
        val second = SecureFrames.encrypt(key, salt, counter = 2L, plaintext = payload)

        assertFalse(first.contentEquals(second))
    }

    // region handshake --------------------------------------------------------------------

    @Test
    fun `both sides agree on the safety code and swap complementary keys`() {
        val (alice, bob) = negotiators()
        val aliceOffer = alice.createOffer()
        val bobOffer = bob.createOffer()

        val aliceKeys = alice.establish(EndpointRole.SENDER, aliceOffer, bobOffer.material)!!
        val bobKeys = bob.establish(EndpointRole.RECEIVER, bobOffer, aliceOffer.material)!!

        assertEquals(aliceKeys.safetyCode, bobKeys.safetyCode)
        assertTrue(aliceKeys.safetyCode.matches(Regex("\\d{3} \\d{3}")))

        // Alice encrypts with the key Bob decrypts with, and vice versa.
        assertArrayEquals(aliceKeys.sendKey, bobKeys.receiveKey)
        assertArrayEquals(aliceKeys.receiveKey, bobKeys.sendKey)
    }

    @Test
    fun `a peer without key material stays plaintext`() {
        val (alice, _) = negotiators()
        assertNull(alice.establish(EndpointRole.SENDER, alice.createOffer(), null))
    }

    @Test
    fun `a valid peer signature plus a remembered fingerprint means verified`() {
        val trustedPeers = InMemoryTrustedPeers()
        val alice = SessionNegotiator(InMemoryIdentity(), trustedPeers)
        val bobIdentity = InMemoryIdentity()

        val firstContact = alice.establish(
            EndpointRole.SENDER,
            alice.createOffer(),
            SessionNegotiator(bobIdentity, InMemoryTrustedPeers()).createOffer().material,
        )!!
        assertTrue(firstContact.signatureValid)
        assertFalse("first contact must never be reported as verified", firstContact.peerRemembered)

        alice.trustPeer(firstContact.peerFingerprint, "Bob")

        val secondContact = alice.establish(
            EndpointRole.SENDER,
            alice.createOffer(),
            SessionNegotiator(bobIdentity, InMemoryTrustedPeers()).createOffer().material,
        )!!
        assertEquals(firstContact.peerFingerprint, secondContact.peerFingerprint)
        assertTrue(secondContact.peerRemembered)
    }

    @Test
    fun `a forged signature is detected`() {
        val alice = SessionNegotiator(InMemoryIdentity(), InMemoryTrustedPeers())
        val offer = alice.createOffer()
        // A signature that is well formed but wrong: flip the last byte of a real one.
        val mangled = offer.material.signature.copyOf()
        mangled[mangled.lastIndex] = (mangled[mangled.lastIndex] + 1).toByte()
        val forged = offer.material.copy(signature = mangled)

        val keys = alice.establish(EndpointRole.SENDER, alice.createOffer(), forged)
        assertNotNull("encryption still happens, but the peer is not authenticated", keys)
        assertFalse("a wrong signature must not count as authentication", keys!!.signatureValid)
        assertFalse("an unauthenticated peer is never remembered", keys.peerRemembered)
    }

    @Test
    fun `identity fingerprints are stable and distinct`() {
        val identity = InMemoryIdentity()
        assertEquals(identity.fingerprint, IdentityVerifier.fingerprintOf(identity.publicKeyBytes))
        assertNotEquals(identity.fingerprint, InMemoryIdentity().fingerprint)
        assertTrue(identity.fingerprint.matches(Regex("([0-9a-f]{2}:){5}[0-9a-f]{2}")))
    }
    @Test
    fun `claiming a trusted fingerprint with an attackers key cannot auto authenticate`() {
        val trusted = InMemoryTrustedPeers()
        val victim = InMemoryIdentity()
        trusted.remember(victim.fingerprint, "Victim")
        val alice = SessionNegotiator(InMemoryIdentity(), trusted)
        val attacker = SessionNegotiator(InMemoryIdentity(), InMemoryTrustedPeers()).createOffer().material
        val keys = alice.establish(EndpointRole.SENDER, alice.createOffer(), attacker.copy(fingerprint = victim.fingerprint))!!
        assertFalse(keys.signatureValid)
        assertFalse(keys.peerRemembered)
        assertNotEquals(victim.fingerprint, keys.peerFingerprint)
    }

}
