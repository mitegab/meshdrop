package com.meshdrop.transfer.crypto
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.meshdrop.core.util.MeshLog
import java.security.*

object AndroidDeviceIdentity {
    fun create(context: Context): DeviceIdentity = runCatching { KeystoreIdentity(context) }.getOrElse {
        MeshLog.w("DeviceIdentity", "Keystore unavailable; temporary identity", it)
        InMemoryIdentity()
    }
}

/** Identity backed by Android Keystore (P-256, sign/verify only). */
class KeystoreIdentity(context: Context) : DeviceIdentity {

    private val alias = "meshdrop_identity_v1"
    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val pair: KeyPair = loadOrCreate()

    override val fingerprint: String = IdentityVerifier.fingerprintOf(pair.public.encoded)
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

