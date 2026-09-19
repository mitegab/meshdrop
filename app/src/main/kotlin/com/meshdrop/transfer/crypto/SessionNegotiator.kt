package com.meshdrop.transfer.crypto

import com.meshdrop.core.model.EndpointRole
import com.meshdrop.core.model.EncryptionMode
import com.meshdrop.core.util.MeshLog
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Arrays
import javax.crypto.KeyAgreement

/** What a peer publishes so we can build a shared secret with it. */
data class PeerKeyMaterial(
    val ephemeralPublicKey: ByteArray,
    val signature: ByteArray,
    val identityPublicKey: ByteArray,
    val fingerprint: String,
)

/** Our half of the exchange: the private half stays in this object, never on the wire. */
data class LocalKeyOffer(
    val ephemeralPrivateKey: PrivateKey,
    val material: PeerKeyMaterial,
)

/** The result of a successful handshake. */
data class SessionKeys(
    /** Key we encrypt outgoing bytes with. */
    val sendKey: ByteArray,
    /** Key we decrypt incoming bytes with. */
    val receiveKey: ByteArray,
    /** 4 byte per-session prefix mixed into every nonce. */
    val nonceSalt: ByteArray,
    /** Six digits, formatted "428 193". */
    val safetyCode: String,
    val peerFingerprint: String,
    /** True when we already trusted this fingerprint in an earlier session. */
    val peerRemembered: Boolean,
    /** True when the peer proved it owns the identity key it claims. */
    val signatureValid: Boolean,
    val cipherSuite: String,
    /** Full identity pin; the short fingerprint is only a human-facing label. */
    val peerIdentityFingerprint: String? = null,
)

/**
 * Turns an unauthenticated LAN into an encrypted channel — and, once the user compares the
 * safety code, an authenticated one.
 *
 * The handshake (all of it piggy-backing on the existing HTTP handshake):
 *
 *  1. both sides generate an ephemeral P-256 key pair;
 *  2. each signs its ephemeral public key with its long-term identity key;
 *  3. each verifies the other's signature against the claimed identity key;
 *  4. ECDH gives a shared secret, HKDF stretches it into one key per direction plus the
 *     safety code, with a transcript hash of both public keys as the salt (so neither side
 *     can be tricked into using a transcript the other did not see);
 *  5. the safety code is displayed; if it matches on both devices, the peer is remembered.
 *
 * Step 5 is what upgrades "encrypted but anybody could be in the middle" into "Verified".
 * We never claim the latter without it.
 *
 * When either side is an older/plain peer the whole thing is skipped and the transfer stays
 * plaintext — the UI then says "Not encrypted" instead of pretending.
 */
class SessionNegotiator(
    private val identity: DeviceIdentity,
    private val rememberedPeers: TrustedPeers,
) {

    /** Generates our ephemeral pair and the signed material to publish. */
    fun createOffer(): LocalKeyOffer {
        val ephemeral = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec(EC_CURVE))
        }.generateKeyPair()
        val encoded = ephemeral.public.encoded
        val signature = identity.sign(encoded)

        return LocalKeyOffer(
            ephemeralPrivateKey = ephemeral.private,
            material = PeerKeyMaterial(
                ephemeralPublicKey = encoded,
                signature = signature,
                identityPublicKey = identity.publicKeyBytes,
                fingerprint = identity.fingerprint,
            ),
        )
    }

    /**
     * Derives the session keys, or returns null when encryption is not possible (peer did not
     * offer key material, curve mismatch, …) so the caller can fall back to plaintext.
     */
    fun establish(role: EndpointRole, local: LocalKeyOffer, peer: PeerKeyMaterial?): SessionKeys? {
        if (peer == null) return null

        val sharedSecret = runCatching { ecdh(local.ephemeralPrivateKey, peer.ephemeralPublicKey) }
            .onFailure { MeshLog.w(TAG, "ECDH failed, falling back to plaintext", it) }
            .getOrNull() ?: return null

        // A claimed fingerprint is not proof of identity. Derive it from the key whose
        // signature is checked, or an attacker could name any remembered fingerprint.
        val fingerprint = IdentityVerifier.fingerprintOf(peer.identityPublicKey)
        val signatureValid = fingerprint.equals(peer.fingerprint, ignoreCase = true) && IdentityVerifier.verify(
            identityPublicKey = peer.identityPublicKey,
            signedData = peer.ephemeralPublicKey,
            signature = peer.signature,
        )
        if (!signatureValid) {
            MeshLog.w(TAG, "Peer signature did not verify; the channel is encrypted but unauthenticated")
        }

        val transcript = transcript(local.material, peer)
        val prefix = "meshdrop/v1/".toByteArray(Charsets.US_ASCII)
        val sendLabel = if (role == EndpointRole.SENDER) "send" else "receive"
        val receiveLabel = if (role == EndpointRole.SENDER) "receive" else "send"

        val sendKey = Hkdf.derive(sharedSecret, transcript, prefix + sendLabel.toByteArray(Charsets.US_ASCII) + transcript)
        val receiveKey = Hkdf.derive(sharedSecret, transcript, prefix + receiveLabel.toByteArray(Charsets.US_ASCII) + transcript)
        val sasBytes = Hkdf.derive(sharedSecret, transcript, prefix + "sas".toByteArray(Charsets.US_ASCII) + transcript, length = 6)

        Arrays.fill(sharedSecret, 0.toByte())

        return SessionKeys(
            sendKey = sendKey,
            receiveKey = receiveKey,
            nonceSalt = transcript.copyOfRange(0, 4),
            safetyCode = safetyCode(sasBytes),
            peerFingerprint = fingerprint,
            peerRemembered = signatureValid && (rememberedPeers.isVerified(fingerprint) ||
                rememberedPeers.isVerified(com.meshdrop.core.persistence.AtomicJson.digest(peer.identityPublicKey))),
            signatureValid = signatureValid,
            cipherSuite = EncryptionMode.E2E_AES_256_GCM.cipherSuite ?: "unknown",
            peerIdentityFingerprint = com.meshdrop.core.persistence.AtomicJson.digest(peer.identityPublicKey),
        )
    }

    /** Call once the user confirms the codes match on both devices. */
    fun trustPeer(fingerprint: String, label: String) = rememberedPeers.remember(fingerprint, label)

    private fun ecdh(privateKey: PrivateKey, peerPublicKey: ByteArray): ByteArray {
        val peerPublic = KeyFactory.getInstance(EC_ALGORITHM)
            .generatePublic(X509EncodedKeySpec(peerPublicKey))
        return KeyAgreement.getInstance("ECDH").run {
            init(privateKey)
            doPhase(peerPublic, true)
            generateSecret()
        }
    }

    /**
     * Hash of both sides' key material, ordered canonically so sender and receiver derive the
     * same salt regardless of who spoke first.
     */
    private fun transcript(local: PeerKeyMaterial, peer: PeerKeyMaterial): ByteArray {
        val mine = local.ephemeralPublicKey + local.identityPublicKey
        val theirs = peer.ephemeralPublicKey + peer.identityPublicKey
        val ordered = if (lexicographicCompare(mine, theirs) <= 0) mine + theirs else theirs + mine
        return ordered.toSha256()
    }

    private fun lexicographicCompare(left: ByteArray, right: ByteArray): Int {
        val limit = minOf(left.size, right.size)
        for (index in 0 until limit) {
            val diff = (left[index].toInt() and 0xff) - (right[index].toInt() and 0xff)
            if (diff != 0) return diff
        }
        return left.size - right.size
    }

    /** Six decimal digits in two groups: easy to read aloud across a room. */
    private fun safetyCode(bytes: ByteArray): String {
        val digits = bytes.take(6).joinToString("") { byte -> ((byte.toInt() and 0xff) % 10).toString() }
        return "${digits.substring(0, 3)} ${digits.substring(3, 6)}"
    }

    private companion object {
        const val TAG = "SessionNegotiator"
        const val EC_CURVE = "secp256r1"
        const val EC_ALGORITHM = "EC"
    }
}
