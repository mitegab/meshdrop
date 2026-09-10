package com.meshdrop.core.prefs

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Local identity of this device.
 *
 * MeshDrop has no accounts: the only thing we persist is a random device id and a
 * user-chosen display name, both advertised over the local network during discovery.
 */
class UserPreferences(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _deviceName = MutableStateFlow(prefs.getString(KEY_DEVICE_NAME, null) ?: defaultName())

    /** Name shown to other devices on the network. */
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    /** Stable random id, generated on first launch. */
    val deviceId: String = prefs.getString(KEY_DEVICE_ID, null) ?: run {
        val generated = UUID.randomUUID().toString()
        prefs.edit { putString(KEY_DEVICE_ID, generated) }
        generated
    }

    /**
     * Master switch for end-to-end encryption.
     *
     * Off means we do not publish key material at all: the peer falls back to plaintext and
     * says so on its trust line. It exists because the honest version of "we encrypt" is a
     * switch the user controls, not a promise in a README.
     */
    private val _encryptionEnabled = MutableStateFlow(prefs.getBoolean(KEY_ENCRYPTION, true))
    val encryptionEnabled: StateFlow<Boolean> = _encryptionEnabled.asStateFlow()

    fun setEncryptionEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_ENCRYPTION, enabled) }
        _encryptionEnabled.value = enabled
    }

    /** Ask for a safety-code comparison with every device we have never verified. */
    private val _requireVerification = MutableStateFlow(prefs.getBoolean(KEY_REQUIRE_VERIFICATION, false))
    val requireVerification: StateFlow<Boolean> = _requireVerification.asStateFlow()

    fun setRequireVerification(required: Boolean) {
        prefs.edit { putBoolean(KEY_REQUIRE_VERIFICATION, required) }
        _requireVerification.value = required
    }

    /** Auto-accept incoming files from devices whose fingerprint we have verified. */
    private val _autoAcceptVerified = MutableStateFlow(prefs.getBoolean(KEY_AUTO_ACCEPT, false))
    val autoAcceptVerified: StateFlow<Boolean> = _autoAcceptVerified.asStateFlow()

    fun setAutoAcceptVerified(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_AUTO_ACCEPT, enabled) }
        _autoAcceptVerified.value = enabled
    }

    /** Gate MeshDrop behind the device lock screen (wired to the vault screen). */
    private val _vaultLock = MutableStateFlow(prefs.getBoolean(KEY_VAULT_LOCK, false))
    val vaultLock: StateFlow<Boolean> = _vaultLock.asStateFlow()

    fun setVaultLock(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_VAULT_LOCK, enabled) }
        _vaultLock.value = enabled
    }

    fun setDeviceName(rawName: String) {
        val cleaned = rawName
            .trim()
            .replace(Regex("[\\r\\n\\t]"), " ")
            .take(MAX_NAME_LENGTH)
            .ifBlank { defaultName() }
        prefs.edit { putString(KEY_DEVICE_NAME, cleaned) }
        _deviceName.value = cleaned
    }

    /** Short, human friendly id used in mDNS service names (`MeshDrop-a1b2c3`). */
    fun shortId(): String = deviceId.take(8)

    private fun defaultName(): String = Build.MODEL
        .replaceFirstChar { if (it.isLowerCase()) it.titlecaseChar() else it }

    private companion object {
        const val PREFS_NAME = "meshdrop_identity"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_ENCRYPTION = "encryption_enabled"
        const val KEY_REQUIRE_VERIFICATION = "require_verification"
        const val KEY_AUTO_ACCEPT = "auto_accept_verified"
        const val KEY_VAULT_LOCK = "vault_lock"
        const val MAX_NAME_LENGTH = 32
    }
}
