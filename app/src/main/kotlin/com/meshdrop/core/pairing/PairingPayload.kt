package com.meshdrop.core.pairing

import com.meshdrop.core.integrity.toHex
import java.security.MessageDigest

/** What one device tells another out of band so they can recognise each other. */
data class PairingInfo(
    val deviceId: String,
    val deviceName: String,
    val port: Int,
    /** Hex fingerprint of the long term identity key. */
    val fingerprint: String,
    val protocolVersion: Int = 1,
) {
    /** The part a human is asked to compare; the rest is machinery. */
    val shortFingerprint: String get() = fingerprint.take(12)

    fun matchesFingerprint(other: String?): Boolean =
        other != null && fingerprint.equals(other, ignoreCase = true)
}

/**
 * A pairing code: everything needed to recognise a device, in one line of text.
 *
 * Verification in MeshDrop is a human comparing six digits once. That works when both
 * people are holding their phones, and it is useless when the person you want to trust is
 * on the other side of the room or on a call. A pairing code is the same trust, moved to a
 * channel the two people already share — read out, messaged, pasted into a terminal — and
 * it carries the identity fingerprint, which is the thing being trusted.
 *
 * The format is deliberately plain so it can be typed by hand:
 *
 *     MD1-<device id>-<port>-<fingerprint>-<name>-<checksum>
 *
 * The checksum is two bytes of SHA-256 over everything before it, which is enough to catch
 * a mistyped digit without pretending to be authentication: an attacker who can change the
 * code can recompute the checksum, but then they have changed the fingerprint, which is
 * exactly what the humans are comparing.
 */
object PairingPayload {

    const val PREFIX = "MD1"

    private const val SEPARATOR = "-"
    private const val MAX_NAME_LENGTH = 24

    fun encode(info: PairingInfo): String {
        val body = listOf(
            PREFIX,
            info.deviceId.filter { it.isLetterOrDigit() }.ifBlank { "unknown" },
            info.port.toString(),
            info.fingerprint.filter { it.isLetterOrDigit() },
            info.deviceName
                .replace(SEPARATOR, " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(MAX_NAME_LENGTH)
                .ifBlank { "MeshDrop" },
            info.protocolVersion.toString(),
        ).joinToString(SEPARATOR)
        return body + SEPARATOR + checksum(body)
    }

    /**
     * Parses a code a human pasted or typed.
     *
     * Tolerant about whitespace, newlines and case; strict about the checksum and the
     * field count, because a code that parses wrongly is worse than one that does not
     * parse at all.
     */
    fun decode(text: String): PairingInfo? {
        // Whitespace runs (a code pasted across two lines, a name typed with a double
        // space) collapse to one space; each field is then trimmed.
        val cleaned = text.trim().replace(Regex("\\s+"), " ")
        if (cleaned.isBlank() || cleaned.length > 512) return null
        val parts = cleaned.split(SEPARATOR).map { it.trim() }
        if (parts.size != 7 || !parts.first().equals(PREFIX, ignoreCase = true)) return null

        // The prefix is part of the checksum, but people type it in whatever case they
        // heard it in, so it is normalised before the checksum is checked.
        val bodyParts = parts.dropLast(1).toMutableList().also { it[0] = PREFIX }
        val body = bodyParts.joinToString(SEPARATOR)
        val given = parts.last()
        if (!MessageDigest.isEqual(
                checksum(body).toByteArray(Charsets.US_ASCII),
                given.toByteArray(Charsets.US_ASCII),
            )
        ) {
            return null
        }

        val port = parts[2].toIntOrNull() ?: return null
        if (port !in 1..65_535) return null
        val fingerprint = parts[3]
        if (fingerprint.isBlank()) return null

        return PairingInfo(
            deviceId = parts[1],
            deviceName = parts[4].replace('_', ' '),
            port = port,
            fingerprint = fingerprint,
            protocolVersion = parts.getOrNull(5)?.toIntOrNull() ?: 1,
        )
    }

    /** Four hex characters over the encoded body. */
    fun checksum(body: String): String =
        MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8))
            .copyOfRange(0, 2)
            .toHex()

    /** True when a pasted code describes the same identity as [fingerprint]. */
    fun verifies(code: String, fingerprint: String): Boolean =
        decode(code)?.matchesFingerprint(fingerprint) == true
}
