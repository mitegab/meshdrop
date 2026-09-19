package com.meshdrop.transfer.crypto


import com.meshdrop.core.util.MeshLog
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * A device's long-term identity: the thing a peer remembers.
 *
 * Deliberately *not* part of the transfer session — this key lives for the lifetime of the
 * install, signs the ephemeral session key, and is what "Verified" means: the user compared
 * a safety code once and we remembered the fingerprint.
 *
 * On a real device the private key never leaves Android Keystore; [InMemoryIdentity] exists
 * for unit tests and for the rare device without a working Keystore.
 */
interface DeviceIdentity {
    /** Short, human-checkable fingerprint: `4f:2a:91:0c:7d:e3`. */
    val fingerprint: String

    /** X.509 encoded EC public key, published to peers. */
    val publicKeyBytes: ByteArray

    fun sign(data: ByteArray): ByteArray


}

private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

/** `4f:2a:91:0c:7d:e3` — the first six bytes are plenty for a human glance. */
private fun ByteArray.toFingerprint(): String =
    take(6).joinToString(":") { byte -> "%02x".format(byte) }

/** Plain in-memory P-256 identity: used by tests and as a Keystore fallback. */
class InMemoryIdentity : DeviceIdentity {

    private val pair: KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    override val fingerprint: String = sha256(pair.public.encoded).toFingerprint()
    override val publicKeyBytes: ByteArray = pair.public.encoded

    override fun sign(data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(data)
            sign()
        }
}

/**
 * Verifies that an ephemeral public key really comes from the device claiming [fingerprint].
 *
 * Runs on the JVM too, which is why it is not inside [KeystoreIdentity].
 */
object IdentityVerifier {

    /** True when [signature] over [signedData] validates against [identityPublicKey]. */
    fun verify(identityPublicKey: ByteArray, signedData: ByteArray, signature: ByteArray): Boolean =
        runCatching {
            val public = java.security.KeyFactory.getInstance("EC")
                .generatePublic(java.security.spec.X509EncodedKeySpec(identityPublicKey))
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(public)
                update(signedData)
                verify(signature)
            }
        }.getOrDefault(false)

    fun fingerprintOf(identityPublicKey: ByteArray): String =
        runCatching { sha256(identityPublicKey).toFingerprint() }.getOrDefault("unknown")
}

internal fun ByteArray.toSha256(): ByteArray = sha256(this)
