package com.meshdrop.core.security

import java.security.MessageDigest

/**
 * The six digits two humans read aloud to each other.
 *
 * A safety code is a Short Authentication String: too short to authenticate a channel on
 * its own, long enough that an attacker who is in the middle cannot guess it in time. Six
 * decimal digits is the point where "read this out across a room" and "one in a million"
 * meet, and it is why the code is split in two groups — people read `428 193` correctly
 * and `428193` incorrectly.
 *
 * Two derivations live here because the first one shipped in protocol v2 and cannot be
 * changed under people's feet: a v2 peer and a v3 peer would otherwise read out different
 * numbers for the same session and conclude, correctly but confusingly, that somebody was
 * listening. [fromBytes] takes the peer's protocol version and picks the matching rule.
 */
object SafetyCode {

    const val DIGITS = 6

    /** Digits in a v2 session: one decimal digit per derived byte, `byte % 10`. */
    const val LEGACY_PROTOCOL_VERSION = 2

    /**
     * Turns derived key material into `428 193`.
     *
     * The current rule is unbiased: it reads three byte windows and rejects any window that
     * would make some digits more likely than others (`2^24 mod 10^6 != 0`), re-hashing the
     * material if every window is rejected. The legacy rule is a plain `byte % 10`, which
     * favours the digits 0-5 by one part in 256 — harmless in practice, but kept only so
     * that mixed-version sessions still agree on a code.
     */
    fun fromBytes(
        bytes: ByteArray,
        digits: Int = DIGITS,
        peerProtocolVersion: Int = PROTOCOL_VERSION,
    ): String {
        require(digits in 1..9) { "a safety code longer than 9 digits is not readable aloud" }
        val value = if (peerProtocolVersion <= LEGACY_PROTOCOL_VERSION) {
            legacyValue(bytes, digits)
        } else {
            uniformValue(bytes, digits)
        }
        return format(value.toString().padStart(digits, '0'))
    }

    /** `428193` → `428 193`. Groups of three, at most two groups. */
    fun format(digits: String): String = when {
        digits.length <= 3 -> digits
        else -> "${digits.substring(0, digits.length / 2)} ${digits.substring(digits.length / 2)}"
    }

    /** What a human actually types or says: `4 2 8 - 1 9 3` → `428193`. */
    fun normalize(input: String): String = input.filter { it.isDigit() }

    fun isValid(input: String, digits: Int = DIGITS): Boolean = normalize(input).length == digits

    /**
     * Compares two codes as the user sees them.
     *
     * Tolerates spaces and dashes, and compares in constant time: the codes are public, but
     * the answer to "do they match" is what an attacker would be probing for.
     */
    fun matches(local: String?, remote: String?): Boolean {
        if (local == null || remote == null) return false
        val left = normalize(local)
        val right = normalize(remote)
        if (left.length != right.length) return false
        return MessageDigest.isEqual(
            left.toByteArray(Charsets.US_ASCII),
            right.toByteArray(Charsets.US_ASCII),
        )
    }

    /** Rough entropy of a code this long, for the "what does Verified mean" panel. */
    fun entropyBits(digits: Int = DIGITS): Double = digits * LOG2_10

    /**
     * Legacy `byte % 10`, one digit per byte.
     *
     * Kept byte-for-byte compatible with protocol v2; do not use for a new scheme.
     */
    private fun legacyValue(bytes: ByteArray, digits: Int): Long {
        val builder = StringBuilder(digits)
        var index = 0
        while (builder.length < digits) {
            val byte = bytes.getOrElse(index) { (index * 31).toByte() }
            builder.append(((byte.toInt() and 0xFF) % 10).toString())
            index++
        }
        return builder.toString().toLong()
    }

    /** Rejection sampling: every code is exactly as likely as every other. */
    private fun uniformValue(bytes: ByteArray, digits: Int): Long {
        val modulus = pow10(digits)
        val windowBytes = windowSizeFor(digits)
        val windowSpace = 1L shl (windowBytes * 8)
        val usable = windowSpace - (windowSpace % modulus)
        var material = bytes
        while (true) {
            var offset = 0
            while (offset + windowBytes <= material.size) {
                var value = 0L
                for (index in 0 until windowBytes) {
                    value = (value shl 8) or (material[offset + index].toLong() and 0xFF)
                }
                offset += windowBytes
                if (value < usable) return value % modulus
            }
            // Every window was rejected: stretch the material and try again.
            material = MessageDigest.getInstance("SHA-256").digest(material)
        }
    }

    private fun windowSizeFor(digits: Int): Int {
        var bytes = 1
        while ((1L shl (bytes * 8)) < pow10(digits) * 2) bytes++
        return bytes
    }

    private fun pow10(digits: Int): Long {
        var result = 1L
        repeat(digits) { result *= 10L }
        return result
    }

    private const val LOG2_10 = 3.3219280948873626

    /** Bumped when the derivation changes; the handshake negotiates the lower of the two. */
    const val PROTOCOL_VERSION = 3
}
