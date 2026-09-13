package com.meshdrop.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshdrop.core.model.PeerDevice
import com.meshdrop.core.model.toLane
import com.meshdrop.core.model.TransferJob
import com.meshdrop.discovery.api.DiscoveryState
import com.meshdrop.discovery.api.StrategyStatus
import com.meshdrop.di.MeshController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    /** True while the "rename this device" dialog is open. */
    val isRenaming: Boolean = false,
    val nameDraft: String = "",
    /** Transient message shown in a snackbar. */
    val message: String? = null,
)

/**
 * State for the hub screen: who we are, who is around, and what moved recently.
 */
class HomeViewModel(
    private val controller: MeshController,
) : ViewModel() {

    val deviceName: StateFlow<String> = controller.deviceName
    /** Nearest and freshest first, unreachable last — see [com.meshdrop.core.model.PeerRanking]. */
    val peers: StateFlow<List<PeerDevice>> = controller.rankedPeers
    val discoveryState: StateFlow<DiscoveryState> = controller.discoveryState
    val strategyStatuses: StateFlow<Map<com.meshdrop.core.model.DiscoveryKind, StrategyStatus>> =
        controller.strategyStatuses

    val recentJobs: StateFlow<List<TransferJob>> = controller.jobs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var started = false

    /** Starts the receiver + discovery exactly once per ViewModel instance. */
    fun ensureStarted() {
        if (started) return
        started = true
        viewModelScope.launch {
            runCatching { controller.start() }
                .onFailure { showMessage(it.message ?: "Discovery could not be started") }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { controller.refresh() }
                .onFailure { showMessage("Could not refresh: ${it.message}") }
        }
    }

    fun startRenaming() {
        _uiState.update { it.copy(isRenaming = true, nameDraft = controller.deviceName.value) }
    }

    fun cancelRenaming() {
        _uiState.update { it.copy(isRenaming = false) }
    }

    fun updateNameDraft(value: String) {
        _uiState.update { it.copy(nameDraft = value) }
    }

    fun commitRename() {
        val draft = _uiState.value.nameDraft
        if (draft.isBlank()) {
            _uiState.update { it.copy(isRenaming = false) }
            return
        }
        controller.setDeviceName(draft)
        _uiState.update { it.copy(isRenaming = false) }
        viewModelScope.launch {
            runCatching { controller.reAdvertise() }
                .onFailure { showMessage("Name changed, but re-advertising failed") }
        }
    }

    /** Lanes discovery is currently using, for the radar card's "how am I looking" line. */
    fun activeLanes(): List<com.meshdrop.core.model.TransportLane> = strategyStatuses.value
        .filter { (_, status) -> status.scanning || status.advertising }
        .keys
        .map { it.toLane() }
        .distinct()
        .ifEmpty { listOf(com.meshdrop.core.model.TransportLane.WIFI_LAN) }

    /** Remembers the device the user tapped, so the Send screen can pre-select it. */
    fun selectPeer(peer: PeerDevice) = controller.selectPeer(peer)

    fun showMessage(message: String) {
        _uiState.update { it.copy(message = message) }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
