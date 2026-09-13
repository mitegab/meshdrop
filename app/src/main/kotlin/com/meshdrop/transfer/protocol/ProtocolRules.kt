package com.meshdrop.transfer.protocol

import com.meshdrop.core.security.SafetyCode

/**
 * Which version of the conversation two peers end up speaking.
 *
 * MeshDrop has no update server, so a phone on v2 and a phone on v3 will meet on the same
 * network for years. Every rule for that meeting lives here, in one place that can be
 * tested, rather than being spread across the client and the server:
 *
 *  * the lower of the two versions wins — nobody is forced to understand something new;
 *  * a peer that speaks something *newer* than us is refused with `426 Upgrade Required`
 *    rather than being half-parsed, because a field we do not know could be load bearing;
 *  * features are gated on the negotiated version, not on what we happen to support.
 */
object ProtocolVersions {

    /**
     * The version this build speaks. [TransferProtocol.VERSION] is an alias for it, kept
     * here so the negotiation rules do not depend on the JSON layer.
     */
    const val CURRENT = 3

    /** The oldest peer we still talk to. v1 was plaintext-only. */
    const val MINIMUM_SUPPORTED = 1

    fun negotiate(localVersion: Int, peerVersion: Int): Int =
        minOf(localVersion, peerVersion).coerceAtLeast(MINIMUM_SUPPORTED)

    fun isSupported(peerVersion: Int): Boolean = peerVersion in MINIMUM_SUPPORTED..CURRENT

    /** `426 Upgrade Required` is the honest answer to a peer from the future. */
    fun requiresUpgrade(peerVersion: Int): Boolean = peerVersion > CURRENT

    /**
     * What both sides may assume once they have agreed on [version].
     *
     * Deliberately a single set of booleans: a client that checks `version >= 3` in three
     * places will eventually check it in four, and one of them will be wrong.
     */
    fun capabilities(version: Int): Capabilities = Capabilities(
        encryption = version >= 2,
        digests = version >= 3,
        resume = version >= 3,
        safetyCodeDerivation = if (version >= SafetyCode.PROTOCOL_VERSION) {
            SafetyCodeDerivation.UNBIASED
        } else {
            SafetyCodeDerivation.LEGACY
        },
    )

    /** Which way the safety code is derived for a session on [version]. */
    fun safetyCodeDerivation(version: Int): SafetyCodeDerivation = capabilities(version).safetyCodeDerivation
}

/** The rules a negotiated version implies. */
data class Capabilities(
    val encryption: Boolean,
    val digests: Boolean,
    val resume: Boolean,
    val safetyCodeDerivation: SafetyCodeDerivation,
) {
    /** True when a whole-file SHA-256 is compared at the end of the batch. */
    val verifiesFiles: Boolean get() = digests
}

enum class SafetyCodeDerivation { LEGACY, UNBIASED }
