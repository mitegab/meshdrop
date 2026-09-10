package com.meshdrop.transfer.crypto

import android.content.Context

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.meshdrop.core.util.MeshLog
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
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

    companion object {
        const val TAG = "DeviceIdentity"

        fun create(context: Context): DeviceIdentity = runCatching {
            KeystoreIdentity(context)
        }.getOrElse { error ->
            MeshLog.w(TAG, "Android Keystore unavailable, using an in-memory identity", error)
            InMemoryIdentity()
        }
    }
}

private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

/** `4f:2a:91:0c:7d:e3` — the first six bytes are plenty for a human glance. */
private fun ByteArray.toFingerprint(): String =
    take(6).joinToString(":") { byte -> "%02x".format(byte) }

/** Identity backed by Android Keystore (P-256, sign/verify only). */
class KeystoreIdentity(context: Context) : DeviceIdentity {

    private val alias = "meshdrop_identity_v1"
    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val pair: KeyPair = loadOrCreate()

    override val fingerprint: String = sha256(pair.public.encoded).toFingerprint()
    override val publicKeyBytes: ByteArray = pair.public.encoded

    override fun sign(data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(data)
            sign()
        }

    private fun loadOrCreate(): KeyPair {
        val existing = runCatching {
            val entry = keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
            entry?.let { KeyPair(it.certificate.publicKey, it.privateKey) }
        }.getOrNull()
        if (existing != null) return existing

        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
        generator.initialize(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKeyPair()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
    }
}

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
