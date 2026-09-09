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
        const val MAX_NAME_LENGTH = 32
    }
}
