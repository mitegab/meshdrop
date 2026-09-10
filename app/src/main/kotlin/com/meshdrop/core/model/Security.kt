package com.meshdrop.core.model

/**
 * What the padlock actually means.
 *
 * This is deliberately not a boolean. A transfer can be encrypted but unverified (an
 * attacker on the LAN could be in the middle), or verified but not encrypted (an old peer),
 * and the UI has to be able to say so in one word.
 */
enum class EncryptionMode(val cipherSuite: String?) {
    NONE(null),
    E2E_AES_256_GCM("ECDH-P256 · AES-256-GCM"),
    ;

    val isEncrypted: Boolean get() = this != NONE
}

/** How much the user can trust the other end, in one word. */
enum class TrustLevel(val label: String) {
    /** The safety code was compared and the peer's identity key remembered. */
    VERIFIED("Verified"),

    /** Encrypted, but this is the first time we have seen this peer. */
    UNVERIFIED("Unverified"),

    /** Plaintext: anyone on the network could be reading or rewriting the bytes. */
    PLAINTEXT("Not encrypted"),
}

data class SecurityState(
    val mode: EncryptionMode = EncryptionMode.NONE,
    /** Six digit safety code, formatted "428 193". Short Authentication String. */
    val safetyCode: String? = null,
    /** True when the user compared the code in a previous session with this peer. */
    val peerRemembered: Boolean = false,
    /** True when the user tapped "codes match" in the current session. */
    val codeCompared: Boolean = false,
    /** SHA-256 fingerprint of the peer's long term identity key. */
    val peerFingerprint: String? = null,
    /** True when every chunk's authentication tag checked out (no tampering in flight). */
    val integrityConfirmed: Boolean = false,
) {
    val isEncrypted: Boolean get() = mode.isEncrypted

    val trustLevel: TrustLevel
        get() = when {
            !mode.isEncrypted -> TrustLevel.PLAINTEXT
            peerRemembered || codeCompared -> TrustLevel.VERIFIED
            else -> TrustLevel.UNVERIFIED
        }

    /** One line the UI can show without any security vocabulary. */
    val headline: String
        get() = when (trustLevel) {
            TrustLevel.VERIFIED -> "Verified · encrypted"
            TrustLevel.UNVERIFIED -> "Unverified · encrypted"
            TrustLevel.PLAINTEXT -> "Not encrypted"
        }

    companion object {
        /** Shown while the handshake has not finished yet. */
        val UNKNOWN = SecurityState()
    }
}
