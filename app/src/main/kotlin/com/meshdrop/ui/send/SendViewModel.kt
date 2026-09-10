package com.meshdrop.ui.send

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meshdrop.core.model.PeerDevice
import com.meshdrop.core.model.SecurityState
import com.meshdrop.core.model.TransferFile
import com.meshdrop.core.model.TransferStatus
import com.meshdrop.core.util.SmoothedReading
import com.meshdrop.core.util.TransferTelemetry
import com.meshdrop.data.DocumentRepository
import com.meshdrop.di.MeshController
import com.meshdrop.transfer.state.TransferSessionMapper
import com.meshdrop.transfer.state.TransferSessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the Send screen is doing, minus anything the transfer itself already knows. */
data class SendUiState(
    val selectedFiles: List<TransferFile> = emptyList(),
    val selectedPeer: PeerDevice? = null,
    val activeJobId: String? = null,
    /** Kept after the job ends so the summary card can still render it. */
    val finishedJobId: String? = null,
    val isSending: Boolean = false,
    /** Set once a batch finishes, to drive the summary card. */
    val lastResult: SendResult? = null,
    val error: String? = null,
    val trustExpanded: Boolean = false,
    val advancedExpanded: Boolean = false,
    val reading: SmoothedReading = SmoothedReading(0, null),
    val throughput: List<Long> = emptyList(),
    val throughputPeak: Long = 1L,
) {
    val totalBytes: Long get() = selectedFiles.sumOf { it.sizeBytes }
}

data class SendResult(
    val status: TransferStatus,
    val message: String?,
    val fileCount: Int,
    val integrityConfirmed: Boolean = false,
)

/**
 * Drives the Send screen.
 *
 * The screen renders [session] — a single sealed [TransferSessionState] — so the UI never has
 * to work out "am I picking, waiting or transferring?" from a pile of booleans. Smoothing and
 * throughput history live here too, so recomposition stays a pure function of state.
 */
class SendViewModel(
    private val controller: MeshController,
    private val documentRepository: DocumentRepository,
) : ViewModel() {

    private val telemetry = TransferTelemetry()

    private val _uiState = MutableStateFlow(SendUiState(selectedPeer = controller.selectedPeer))
    val uiState: StateFlow<SendUiState> = _uiState

    val peers: StateFlow<List<PeerDevice>> = controller.peers

    @OptIn(ExperimentalCoroutinesApi::class)
    val session: StateFlow<TransferSessionState> = combine(
        controller.jobs,
        controller.security,
        _uiState,
    ) { jobs, security, ui ->
        val watchedId = ui.activeJobId ?: ui.finishedJobId
        val job = watchedId?.let { id -> jobs.firstOrNull { it.id == id } }
        when {
            job != null -> TransferSessionMapper.toSessionState(
                job = job,
                security = security[job.id] ?: SecurityState.UNKNOWN,
            )

            // Picked files, not sent yet: describe the state the engine will describe
            // as soon as the handshake starts, so the screen has one shape either way.
            ui.selectedPeer != null && ui.selectedFiles.isNotEmpty() ->
                TransferSessionMapper.toAwaitingApproval(
                    batchId = DRAFT_BATCH_ID,
                    peerName = ui.selectedPeer.name,
                    files = ui.selectedFiles,
                )

            else -> TransferSessionMapper.discovering(lanes = emptyList())
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TransferSessionMapper.discovering(lanes = emptyList()),
    )

    init {
        viewModelScope.launch {
            controller.jobs.collect { jobs ->
                val id = _uiState.value.activeJobId ?: return@collect
                val job = jobs.firstOrNull { it.id == id } ?: return@collect

                // Smooth here rather than in the composable: recomposition must stay pure.
                val reading = telemetry.update(job.bytesPerSecond, job.etaMillis)
                _uiState.update {
                    it.copy(
                        reading = reading,
                        throughput = telemetry.samples(),
                        throughputPeak = telemetry.peak(),
                    )
                }

                if (job.status.isFinished) {
                    telemetry.reset()
                    _uiState.update {
                        it.copy(
                            activeJobId = null,
                            finishedJobId = job.id,
                            isSending = false,
                            lastResult = SendResult(
                                status = job.status,
                                message = job.message,
                                fileCount = job.files.size,
                                integrityConfirmed = controller.security.value[job.id]?.integrityConfirmed ?: false,
                            ),
                        )
                    }
                }
            }
        }
    }

    /** Called with the Uris returned by the Storage Access Framework picker. */
    fun onFilesPicked(uris: List<Uri>) {
        val picked = uris.distinct()
        if (picked.isEmpty()) return
        viewModelScope.launch {
            // ContentResolver queries are disk I/O: keep them off the main thread.
            val described = withContext(Dispatchers.IO) {
                picked.mapNotNull { uri ->
                    runCatching {
                        documentRepository.persistReadPermission(uri)
                        documentRepository.describe(uri)
                    }.getOrNull()
                }
            }
            if (described.isEmpty()) {
                _uiState.update { it.copy(error = "Those files could not be read") }
                return@launch
            }
            _uiState.update { state ->
                state.copy(
                    selectedFiles = (state.selectedFiles + described).distinctBy { it.localUri },
                    error = null,
                    lastResult = null,
                )
            }
        }
    }

    fun removeFile(fileId: String) {
        _uiState.update { state -> state.copy(selectedFiles = state.selectedFiles.filterNot { it.id == fileId }) }
    }

    fun clearFiles() = _uiState.update { it.copy(selectedFiles = emptyList()) }

    fun selectPeer(peer: PeerDevice) {
        controller.selectPeer(peer)
        _uiState.update { it.copy(selectedPeer = peer, error = null) }
    }

    fun send() {
        val state = _uiState.value
        val peer = state.selectedPeer
        val files = state.selectedFiles
        if (peer == null) {
            _uiState.update { it.copy(error = "Pick a device first") }
            return
        }
        if (files.isEmpty()) {
            _uiState.update { it.copy(error = "Pick at least one file") }
            return
        }
        telemetry.reset()
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
        _uiState.value.activeJobId?.let(controller::cancel)
        telemetry.reset()
        _uiState.update { it.copy(activeJobId = null, isSending = false) }
    }

    fun dismissResult() = _uiState.update { it.copy(lastResult = null, finishedJobId = null) }
    fun consumeError() = _uiState.update { it.copy(error = null) }
    fun toggleTrustExpanded() = _uiState.update { it.copy(trustExpanded = !it.trustExpanded) }
    fun toggleAdvanced() = _uiState.update { it.copy(advancedExpanded = !it.advancedExpanded) }

    /** The user compared the safety codes out loud and they matched. */
    fun confirmSafetyCode(peerName: String) {
        val batchId = _uiState.value.activeJobId
            ?: controller.security.value.entries
                .firstOrNull { it.value.peerFingerprint != null }?.key
            ?: return
        controller.confirmSafetyCode(batchId, peerName)
        _uiState.update { it.copy(trustExpanded = true) }
    }

    companion object {
        /** Local stand-in id for the not-yet-sent draft; it never reaches the wire. */
        private const val DRAFT_BATCH_ID = "draft"

        fun factory(
            controller: MeshController,
            documentRepository: DocumentRepository,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SendViewModel(controller, documentRepository) as T
        }
    }
}
