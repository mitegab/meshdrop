package com.meshdrop.transfer.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF (RFC 5869) built on HMAC-SHA256 — the standard way to turn an ECDH shared secret
 * into several independent keys (one per direction, one for the safety code).
 *
 * No dependency: `javax.crypto.Mac` ships with Android and with the JVM, so this is unit
 * tested without Robolectric.
 */
object Hkdf {

    private const val MAC_ALGORITHM = "HmacSHA256"
    private const val HASH_LENGTH = 32

    fun derive(ikm: ByteArray, salt: ByteArray? = null, info: ByteArray = ByteArray(0), length: Int = HASH_LENGTH): ByteArray {
        require(length > 0 && length <= 255 * HASH_LENGTH) { "Unsupported output length: $length" }

        val pseudoRandomKey = hmac(salt ?: ByteArray(HASH_LENGTH), ikm)

        val output = ByteArray(length)
        var generated = 0
        var block = ByteArray(0)
        var counter = 1

        while (generated < length) {
            block = hmac(pseudoRandomKey, block + info + byteArrayOf(counter.toByte()))
            val toCopy = minOf(block.size, length - generated)
            System.arraycopy(block, 0, output, generated, toCopy)
            generated += toCopy
            counter++
        }
        return output
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance(MAC_ALGORITHM).run {
            init(SecretKeySpec(key, MAC_ALGORITHM))
            doFinal(data)
        }
}
