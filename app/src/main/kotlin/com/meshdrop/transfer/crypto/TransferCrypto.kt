package com.meshdrop.transfer.crypto

import com.meshdrop.core.model.EndpointRole
import com.meshdrop.core.util.MeshLog

/** Wire-level view of one side of the key exchange (base64 happens at the JSON boundary). */
data class SecurityOffer(
    val scheme: String = SCHEME,
    val ephemeralPublicKey: ByteArray,
    val signature: ByteArray,
    val identityPublicKey: ByteArray,
    val fingerprint: String,
) {
    companion object {
        /** Only scheme the MVP speaks: ephemeral ECDH-P256 + AES-256-GCM frames. */
        const val SCHEME = "v1-aes-gcm"
    }
}

/**
 * The crypto a single transfer needs.
 *
 * Kept behind an interface so [com.meshdrop.transfer.client.TransferClient] and
 * [com.meshdrop.transfer.server.TransferServer] never import key management: they just
 * publish material and ask for a key.
 */
interface TransferCrypto {
    /** Our half of the exchange, generating an ephemeral key pair on first use. */
    fun localMaterial(): SecurityOffer?

    /** Derives the session keys once the peer's material is known. Null = stay plaintext. */
    fun establish(peerMaterial: SecurityOffer?): SessionKeys?

    /** Keys agreed so far, or null when this transfer is not encrypted. */
    fun sessionKeys(): SessionKeys?
}

/** Per-batch glue between [SessionNegotiator] and the transfer client/server. */
class BatchCrypto(
    private val negotiator: SessionNegotiator,
    private val role: EndpointRole,
) : TransferCrypto {

    private var local: LocalKeyOffer? = null
    private var keys: SessionKeys? = null

    override fun localMaterial(): SecurityOffer? {
        val offer = local ?: negotiator.createOffer().also { local = it }
        return offer.material.toSecurityOffer()
    }

    override fun establish(peerMaterial: SecurityOffer?): SessionKeys? {
        val offer = local ?: negotiator.createOffer().also { local = it }
        keys = negotiator.establish(role, offer, peerMaterial?.toPeerKeyMaterial())
        MeshLog.d(TAG, "Handshake crypto: ${if (keys != null) "encrypted (${role})" else "plaintext"}")
        return keys
    }

    override fun sessionKeys(): SessionKeys? = keys

    companion object {
        const val TAG = "BatchCrypto"
    }
}

fun com.meshdrop.transfer.protocol.SecurityOfferDto.toOffer(): SecurityOffer = SecurityOffer(
    scheme = scheme,
    ephemeralPublicKey = ephemeralPublicKey,
    signature = signature,
    identityPublicKey = identityPublicKey,
    fingerprint = fingerprint,
)

fun SecurityOffer.toDto(): com.meshdrop.transfer.protocol.SecurityOfferDto =
    com.meshdrop.transfer.protocol.SecurityOfferDto(
        scheme = scheme,
        ephemeralPublicKey = ephemeralPublicKey,
        signature = signature,
        identityPublicKey = identityPublicKey,
        fingerprint = fingerprint,
    )

fun PeerKeyMaterial.toSecurityOffer(): SecurityOffer = SecurityOffer(
    ephemeralPublicKey = ephemeralPublicKey,
    signature = signature,
    identityPublicKey = identityPublicKey,
    fingerprint = fingerprint,
)

fun SecurityOffer.toPeerKeyMaterial(): PeerKeyMaterial = PeerKeyMaterial(
    ephemeralPublicKey = ephemeralPublicKey,
    signature = signature,
    identityPublicKey = identityPublicKey,
    fingerprint = fingerprint,
)
