package com.meshdrop.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meshdrop.core.prefs.UserPreferences
import com.meshdrop.di.MeshController
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

data class SettingsUiState(
    val deviceName: String = "",
    val encryptionEnabled: Boolean = true,
    val requireVerification: Boolean = false,
    val autoAcceptVerified: Boolean = false,
    val vaultLock: Boolean = false,
    val transportsExpanded: Boolean = false,
    val message: String? = null,
)

/**
 * Settings, ordered the way the app thinks: who can see me, do I trust them, then plumbing.
 *
 * Nothing here talks to a server because there isn't one — the "no telemetry" row is a
 * statement about the code, not a switch.
 */
class SettingsViewModel(
    private val preferences: UserPreferences,
    private val controller: MeshController,
    private val verifiedPeers: com.meshdrop.transfer.crypto.VerifiedPeers,
    private val identityFingerprint: () -> String = { "" },
    private val bluetoothTransport: com.meshdrop.transport.BluetoothTransport? = null,
    private val usbTransport: com.meshdrop.transport.UsbTransport? = null,
    private val relay: com.meshdrop.transfer.mesh.RelayService? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())

    // `combine` tops out at five flows, so the stored preferences are merged first and the
    // screen-only state (open dialogs, transient messages) is layered on top.
    private val stored = combine(
        preferences.deviceName,
        preferences.encryptionEnabled,
        preferences.requireVerification,
        preferences.autoAcceptVerified,
        preferences.vaultLock,
    ) { name, encryption, verify, auto, vault ->
        SettingsUiState(
            deviceName = name,
            encryptionEnabled = encryption,
            requireVerification = verify,
            autoAcceptVerified = auto,
            vaultLock = vault,
        )
    }

    val uiState: StateFlow<SettingsUiState> = combine(stored, _uiState) { stored, ui ->
        ui.copy(
            deviceName = stored.deviceName,
            encryptionEnabled = stored.encryptionEnabled,
            requireVerification = stored.requireVerification,
            autoAcceptVerified = stored.autoAcceptVerified,
            vaultLock = stored.vaultLock,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    val helperMode = preferences.helperMode
    val relayOffers = relay?.incoming ?: MutableStateFlow(emptyList<com.meshdrop.transfer.mesh.RelayConsent>())
    fun setHelperMode(enabled: Boolean) { preferences.setHelperMode(enabled); if (!enabled) relay?.revokeAll() }
    fun decideRelay(id: String, accept: Boolean) { relay?.decide(id, accept) }
    fun revokeRelays() { relay?.revokeAll(); showMessage("All helper permissions revoked") }

    val localPort: Int get() = controller.localPort
    val strategyStatuses = controller.strategyStatuses

    fun bluetoothDevices() = bluetoothTransport?.pairedDevices().orEmpty()
    fun usbDevices() = usbTransport?.attached().orEmpty()
    fun connectBluetooth(address: String) { viewModelScope.launch {
        controller.start(); bluetoothTransport?.connect(address); showMessage("Connecting over RFCOMM…")
    } }
    fun connectUsb(id: String) { viewModelScope.launch {
        controller.start(); usbTransport?.connect(id); showMessage("Approve USB access on the system prompt")
    } }

    fun pairingCode(): String? {
        if (localPort <= 0 || identityFingerprint().isBlank()) return null
        return com.meshdrop.core.pairing.PairingPayload.encode(com.meshdrop.core.pairing.PairingInfo(
            preferences.deviceId, preferences.deviceName.value, localPort, identityFingerprint(),
            com.meshdrop.transfer.protocol.TransferProtocol.VERSION,
        ))
    }

    fun trustPairingCode(code: String): Boolean {
        val info = com.meshdrop.core.pairing.PairingPayload.decode(code)
        val fingerprint = info?.fingerprint?.lowercase().orEmpty()
        if (info == null || !fingerprint.matches(Regex("([a-f0-9]{12}|[a-f0-9]{64})")) ||
            !com.meshdrop.transfer.protocol.ProtocolVersions.isSupported(info.protocolVersion)) {
            showMessage("Invalid or unsupported pairing code")
            return false
        }
        verifiedPeers.remember(if (fingerprint.length == 12) fingerprint.chunked(2).joinToString(":") else fingerprint, info.deviceName)
        showMessage("Trusted ${info.deviceName}. Future transfers must prove this identity.")
        return true
    }

    /**
     * Devices we have compared safety codes with, fingerprint → label.
     *
     * Read on demand rather than flowed: it changes only when the user forgets one.
     */
    fun trustedPeers(): Map<String, String> = verifiedPeers.all()

    fun setEncryption(enabled: Boolean) {
        preferences.setEncryptionEnabled(enabled)
        showMessage(
            if (enabled) {
                "Encryption on — files are scrambled before they leave this phone"
            } else {
                "Encryption off — files will travel as plain text on this network"
            },
        )
    }

    fun setRequireVerification(required: Boolean) = preferences.setRequireVerification(required)

    fun setAutoAcceptVerified(enabled: Boolean) {
        preferences.setAutoAcceptVerified(enabled)
        showMessage(
            if (enabled) "Verified devices will be accepted automatically" else "Every transfer will ask first",
        )
    }

    fun setVaultLock(enabled: Boolean) {
        preferences.setVaultLock(enabled)
        showMessage(
            if (enabled) {
                "Vault lock on: MeshDrop will ask for your device lock before opening"
            } else {
                "Vault lock off"
            },
        )
    }

    fun setDeviceName(name: String) {
        preferences.setDeviceName(name)
        viewModelScope.launch {
            runCatching { controller.reAdvertise() }
                .onFailure { showMessage("Name saved; refresh nearby devices to advertise it") }
        }
    }

    fun forgetPeer(fingerprint: String) {
        verifiedPeers.forget(fingerprint)
        showMessage("Forgot that device — you will be asked to compare codes again")
    }

    fun toggleTransports() = _uiState.update { it.copy(transportsExpanded = !it.transportsExpanded) }

    fun showMessage(message: String) = _uiState.update { it.copy(message = message) }

    fun consumeMessage() = _uiState.update { it.copy(message = null) }

    companion object {
        fun factory(
            preferences: UserPreferences,
            controller: MeshController,
            verifiedPeers: com.meshdrop.transfer.crypto.VerifiedPeers,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SettingsViewModel(preferences, controller, verifiedPeers) as T
        }
    }
}
