package com.meshdrop.transfer.crypto

import android.content.Context
import androidx.core.content.edit

/**
 * The "remembered devices" book: fingerprints the user confirmed by comparing safety codes.
 *
 * Trust-on-first-use, not a PKI: the first transfer with a peer shows *Unverified*, and the
 * padlock only becomes *Verified* after a human compares six digits. Stored locally, never
 * sent anywhere — there is no server in this system.
 */
class VerifiedPeers(context: Context) : TrustedPeers {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun isVerified(fingerprint: String): Boolean =
        runCatching { prefs.getStringSet(KEY_VERIFIED, emptySet())?.contains(fingerprint) ?: false }
            .getOrDefault(false)

    override fun remember(fingerprint: String, label: String) {
        runCatching {
            prefs.edit {
                val current = prefs.getStringSet(KEY_VERIFIED, emptySet()).orEmpty().toMutableSet()
                current += fingerprint
                putStringSet(KEY_VERIFIED, current)
                putString("$KEY_LABEL_PREFIX$fingerprint", label)
            }
        }
    }

    override fun forget(fingerprint: String) {
        runCatching {
            prefs.edit {
                val current = prefs.getStringSet(KEY_VERIFIED, emptySet()).orEmpty().toMutableSet()
                current -= fingerprint
                putStringSet(KEY_VERIFIED, current)
                remove("$KEY_LABEL_PREFIX$fingerprint")
            }
        }
    }

    /** fingerprint → display name at the time of verification. */
    fun all(): Map<String, String> {
        val fingerprints = prefs.getStringSet(KEY_VERIFIED, emptySet()).orEmpty()
        return fingerprints.associateWith { fingerprint ->
            prefs.getString("$KEY_LABEL_PREFIX$fingerprint", null) ?: fingerprint
        }
    }

    companion object {
        const val PREFS_NAME = "meshdrop_verified_peers"
        const val KEY_VERIFIED = "verified_fingerprints"
        const val KEY_LABEL_PREFIX = "label_"
    }
}
