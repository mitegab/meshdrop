package com.meshdrop.ui.receive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshdrop.core.model.IncomingRequest
import com.meshdrop.core.model.TransferDirection
import com.meshdrop.core.model.TransferJob
import com.meshdrop.di.MeshController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ReceiveUiState(
    /** True while this screen keeps the receiver running in the foreground. */
    val isWaiting: Boolean = true,
    val message: String? = null,
)

/**
 * State for the Receive screen: incoming requests waiting for a decision, live downloads,
 * and the files that have already landed.
 */
class ReceiveViewModel(
    private val controller: MeshController,
) : ViewModel() {

    val deviceName: StateFlow<String> = controller.deviceName
    val incoming: StateFlow<List<IncomingRequest>> = controller.incoming

    val activeJobs: StateFlow<List<TransferJob>> = controller.jobs
        .map { jobs -> jobs.filter { it.direction == TransferDirection.RECEIVE } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val transferPort: Int get() = controller.localPort

    private val _uiState = MutableStateFlow(ReceiveUiState())
    val uiState: StateFlow<ReceiveUiState> = _uiState.asStateFlow()

    init {
        // Arriving on this screen means "I want to receive": make sure we are discoverable.
        viewModelScope.launch {
            runCatching { controller.start() }
                .onFailure { error ->
                    _uiState.update { state ->
                        state.copy(message = error.message ?: "Could not start the receiver")
                    }
                }
        }
    }

    fun approve(request: IncomingRequest) {
        viewModelScope.launch {
            runCatching { controller.approve(request.batchId) }
                .onFailure { showMessage("Could not accept: ${it.message}") }
        }
    }

    fun reject(request: IncomingRequest) {
        controller.reject(request.batchId)
        showMessage("Declined ${request.senderName}")
    }

    fun cancel(jobId: String) = controller.cancel(jobId)

    fun clearFinished() = controller.clearFinished()

    fun setWaiting(waiting: Boolean) {
        _uiState.update { it.copy(isWaiting = waiting) }
    }

    fun showMessage(message: String) {
        _uiState.update { it.copy(message = message) }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
