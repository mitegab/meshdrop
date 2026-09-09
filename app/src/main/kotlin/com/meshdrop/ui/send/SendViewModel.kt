package com.meshdrop.ui.send

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshdrop.core.model.PeerDevice
import com.meshdrop.core.model.TransferFile
import com.meshdrop.core.model.TransferJob
import com.meshdrop.core.model.TransferStatus
import com.meshdrop.data.DocumentRepository
import com.meshdrop.di.MeshController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class SendUiState(
    val selectedFiles: List<TransferFile> = emptyList(),
    val selectedPeer: PeerDevice? = null,
    val activeJobId: String? = null,
    val isSending: Boolean = false,
    /** Set once a batch finishes, to drive the summary snackbar/card. */
    val lastResult: SendResult? = null,
    val error: String? = null,
) {
    val totalBytes: Long get() = selectedFiles.sumOf { it.sizeBytes }
    val canSend: Boolean get() = selectedFiles.isNotEmpty() && selectedPeer != null && !isSending
}

data class SendResult(
    val status: TransferStatus,
    val message: String?,
    val fileCount: Int,
)

/**
 * Driving the Send screen: pick documents, pick a peer, push and watch the job.
 */
class SendViewModel(
    private val controller: MeshController,
    private val documentRepository: DocumentRepository,
) : ViewModel() {

    val peers: StateFlow<List<PeerDevice>> = controller.peers
    val jobs: StateFlow<List<TransferJob>> = controller.jobs

    private val _uiState = MutableStateFlow(
        SendUiState(selectedPeer = controller.selectedPeer),
    )
    val uiState: StateFlow<SendUiState> = _uiState.asStateFlow()

    /** The job we are currently following, with live progress. */
    val activeJob: StateFlow<TransferJob?> = controller.jobs
        .mapToActiveJob()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // Keep following a job that was started before this screen was opened.
        viewModelScope.launch {
            controller.jobs.collect { jobs ->
                val jobId = _uiState.value.activeJobId ?: return@collect
                val job = jobs.firstOrNull { it.id == jobId } ?: return@collect
                if (job.status.isFinished) {
                    _uiState.update {
                        it.copy(
                            activeJobId = null,
                            isSending = false,
                            lastResult = SendResult(job.status, job.message, job.files.size),
                        )
                    }
                }
            }
        }
    }

    /** Called with the Uris returned by the Storage Access Framework picker. */
    fun onFilesPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val described = uris.mapNotNull { uri ->
                runCatching {
                    documentRepository.persistReadPermission(uri)
                    documentRepository.describe(uri)
                }.getOrNull()
            }
            if (described.isEmpty()) {
                _uiState.update { it.copy(error = "Those files could not be read") }
                return@launch
            }
            _uiState.update { state ->
                val merged = state.selectedFiles + described
                state.copy(selectedFiles = merged.distinctBy { it.localUri }, error = null, lastResult = null)
            }
        }
    }

    fun removeFile(fileId: String) {
        _uiState.update { state -> state.copy(selectedFiles = state.selectedFiles.filterNot { it.id == fileId }) }
    }

    fun clearFiles() {
        _uiState.update { it.copy(selectedFiles = emptyList()) }
    }

    fun selectPeer(peer: PeerDevice) {
        controller.selectPeer(peer)
        _uiState.update { it.copy(selectedPeer = peer, error = null) }
    }

    fun send() {
        val state = _uiState.value
        val peer = state.selectedPeer
        val files = state.selectedFiles
        if (peer == null || files.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSending = true, error = null, lastResult = null) }
            val jobId = runCatching { controller.send(peer, files) }
                .getOrElse { error ->
                    _uiState.update {
                        it.copy(isSending = false, error = error.message ?: "Could not start the transfer")
                    }
                    return@launch
                }
            _uiState.update { it.copy(activeJobId = jobId) }
        }
    }

    fun cancel() {
        val jobId = _uiState.value.activeJobId ?: return
        controller.cancel(jobId)
        _uiState.update { it.copy(activeJobId = null, isSending = false) }
    }

    fun dismissResult() {
        _uiState.update { it.copy(lastResult = null) }
    }

    fun consumeError() {
        _uiState.update { it.copy(error = null) }
    }

    /** Convenience used by the UI when the user picks "send to this device" from Home. */
    fun generateBatchId(): String = UUID.randomUUID().toString()

    private fun StateFlow<List<TransferJob>>.mapToActiveJob(): Flow<TransferJob?> = map { jobs ->
        val id = _uiState.value.activeJobId ?: return@map null
        jobs.firstOrNull { it.id == id }
    }
}
