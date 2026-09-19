package com.meshdrop.ui.drop

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meshdrop.core.model.PeerDevice
import com.meshdrop.core.model.SecurityState
import com.meshdrop.core.model.TransferFile
import com.meshdrop.core.model.TransferJob
import com.meshdrop.core.util.SmoothedReading
import com.meshdrop.core.util.TransferTelemetry
import com.meshdrop.data.DocumentRepository
import com.meshdrop.di.MeshController
import com.meshdrop.discovery.api.DiscoveryState
import com.meshdrop.transfer.state.TransferSessionMapper
import com.meshdrop.transfer.state.TransferSessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Everything the Drop screen shows, in one small state object.
 *
 * The split is deliberate: [DropUiState] is what the *user* is doing (a tray of files, a
 * chosen person, a sheet that is open), and the flows are what the *network* is doing. The
 * screen reads both and renders; it decides nothing.
 */
data class DropUiState(
    /** Files staged in the tray, in the order they were picked. */
    val staged: List<TransferFile> = emptyList(),
    /** The person the tray is addressed to, if any. */
    val peer: PeerDevice? = null,
    /** Batch id of the transfer currently on screen, for the queue readout. */
    val activeJobId: String? = null,
    /** Receipts the user has already closed, so they do not come back. */
    val dismissedReceipts: Set<String> = emptySet(),
    val detailsExpanded: Boolean = false,
    /** Which batch's safety-code panel is open, if any. */
    val trustExpandedBatchId: String? = null,
    val pickerOpen: Boolean = false,
    val connectionOpen: Boolean = false,
    val isRenaming: Boolean = false,
    val nameDraft: String = "",
    val message: String? = null,
    /** Live, smoothed throughput for the visible transfer. */
    val reading: SmoothedReading = SmoothedReading.IDLE,
    val samples: List<Long> = emptyList(),
    val peak: Long = 1L,
    /** "2nd in line", when a batch is queued behind another one. */
    val queueLabel: String? = null,
    /** False once the user pauses discovery from the connection sheet. */
    val receiving: Boolean = true,
    val connecting: Boolean = false,
) {
    val stagedBytes: Long get() = staged.sumOf { it.sizeBytes }

    /** True when there is something to send and someone to send it to. */
    val readyToSend: Boolean get() = staged.isNotEmpty() && peer != null
}

/**
 * The Drop screen's view model: the busiest one in the app, and deliberately the only one
 * that knows about both directions.
 *
 * Send and receive used to be two screens with two view models, which meant the answer to
 * "is anything happening?" lived in two places and the hub screen could only guess. A drop is
 * one act — something arrives, or something leaves — so it is one state machine, and the
 * hub can say what is going on without being told.
 */
class DropViewModel(
    private val controller: MeshController,
    private val documents: DocumentRepository,
) : ViewModel() {

    /** One smoother per batch, so two simultaneous transfers do not average each other. */
    private val telemetry = ConcurrentHashMap<String, TransferTelemetry>()

    private val _ui = MutableStateFlow(DropUiState(peer = controller.selectedPeer))
    val ui: StateFlow<DropUiState> = _ui.asStateFlow()

    val deviceName: StateFlow<String> = controller.deviceName
    val peers: StateFlow<List<PeerDevice>> = controller.rankedPeers
    val latencies: StateFlow<Map<String, Long>> = controller.peerLatencies
    val discovery: StateFlow<DiscoveryState> = controller.discoveryState

    /** TCP port we are listening on, or 0 when the receiver is paused. */
    val localPort: Int get() = controller.localPort

    /**
     * One-second clock, running only while an offer is waiting.
     *
     * The countdown is derived from this rather than stored per offer: recomputing the
     * remaining time is free, and a stored string is one more thing to forget to update.
     */
    private val _now = MutableStateFlow(System.currentTimeMillis())
    val now: StateFlow<Long> = _now.asStateFlow()

    /** Transfers in flight, oldest first. Normally zero or one; two is legal. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val live: StateFlow<List<TransferSessionState>> = combine(
        controller.jobs,
        controller.security,
    ) { jobs, security ->
        jobs.filterNot { it.status.isFinished }
            .sortedBy { it.startedAtMillis }
            .map { job ->
                TransferSessionMapper.toSessionState(
                    job = job,
                    security = security[job.id] ?: SecurityState.UNKNOWN,
                )
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Incoming offers, as screen state, in arrival order. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val offers: StateFlow<List<TransferSessionState.IncomingOffer>> = combine(
        controller.incoming,
        controller.security,
    ) { incoming, security ->
        incoming.map { request ->
            TransferSessionMapper.toIncomingOffer(
                request = request,
                security = security[request.batchId] ?: SecurityState.UNKNOWN,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The most recent finished transfer that the user has not closed yet. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val receipt: StateFlow<TransferSessionState.Finished?> = combine(
        controller.jobs,
        controller.security,
        _ui,
    ) { jobs, security, ui ->
        jobs.filter { it.status.isFinished }
            .sortedByDescending { it.finishedAtMillis ?: it.startedAtMillis }
            .firstOrNull { it.id !in ui.dismissedReceipts }
            ?.let { job ->
                TransferSessionMapper.toSessionState(
                    job = job,
                    security = security[job.id] ?: SecurityState.UNKNOWN,
                )
            } as? TransferSessionState.Finished
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Finished transfers for the "Recent" strip, newest first. */
    val recent: StateFlow<List<TransferJob>> = controller.jobs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var started = false

    init {
        // Smoothing lives here, not in a composable: recomposition must stay a pure function
        // of state, and a reading that only changes when it means something is the whole
        // point of TransferTelemetry.
        viewModelScope.launch {
            controller.jobs.collect { jobs ->
                val running = jobs.filter { !it.status.isFinished }
                val first = running.firstOrNull()

                _ui.update { state ->
                    val batchId = first?.id
                    val smoother = batchId?.let { telemetry.getOrPut(it) { TransferTelemetry() } }
                    if (first == null || smoother == null) {
                        state.copy(
                            activeJobId = null,
                            reading = SmoothedReading.IDLE,
                            samples = emptyList(),
                            peak = 1L,
                            queueLabel = null,
                        )
                    } else {
                        state.copy(
                            activeJobId = batchId,
                            reading = smoother.update(first.bytesPerSecond, first.etaMillis),
                            samples = smoother.samples(),
                            peak = smoother.peak(),
                            queueLabel = controller.queueLabelFor(batchId),
                        )
                    }
                }

                // Drop the smoother of anything that finished, or the map grows for the
                // lifetime of the process.
                val alive = running.map { it.id }.toSet()
                telemetry.keys.retainAll(alive)
            }
        }

        viewModelScope.launch {
            controller.incoming.collect { offers ->
                if (offers.isEmpty()) return@collect
                while (controller.incoming.value.isNotEmpty()) {
                    _now.value = System.currentTimeMillis()
                    delay(1_000)
                }
            }
        }
    }

    /** Starts the receiver and discovery exactly once per view model instance. */
    fun ensureStarted() {
        if (started) return
        started = true
        start()
    }

    fun start() {
        viewModelScope.launch {
            runCatching { controller.start() }
                .onSuccess { _ui.update { it.copy(receiving = true) } }
                .onFailure { error ->
                    _ui.update {
                        it.copy(message = error.message ?: "Could not open the receiver")
                    }
                }
        }
    }

    /**
     * Pausing is a real state, not a cosmetic switch: it stops the transfer server and
     * withdraws us from discovery, so "paused" means nobody can reach this phone.
     */
    fun setReceiving(enabled: Boolean) {
        if (enabled) {
            start()
            return
        }
        viewModelScope.launch {
            runCatching { controller.stop() }
            _ui.update { it.copy(receiving = false, message = "Paused — nobody can send to you") }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { controller.refresh() }
                .onFailure { _ui.update { state -> state.copy(message = "Could not refresh") } }
        }
    }

    // region the tray -----------------------------------------------------------------------

    fun openPicker() = _ui.update { it.copy(pickerOpen = true) }

    fun closePicker() = _ui.update { it.copy(pickerOpen = false) }

    /** Called with the Uris the Storage Access Framework picker returned. */
    fun onFilesPicked(uris: List<Uri>) {
        val picked = uris.distinct()
        _ui.update { it.copy(pickerOpen = false) }
        if (picked.isEmpty()) return
        viewModelScope.launch {
            // ContentResolver queries are disk I/O; keep them off the main thread.
            val described = withContext(Dispatchers.IO) {
                picked.mapNotNull { uri ->
                    runCatching {
                        documents.persistReadPermission(uri)
                        documents.describe(uri)
                    }.getOrNull()
                }
            }
            if (described.isEmpty()) {
                _ui.update { it.copy(message = "Those files could not be read") }
                return@launch
            }
            _ui.update { state ->
                state.copy(
                    staged = (state.staged + described).distinctBy { it.localUri },
                    message = null,
                )
            }
        }
    }

    fun removeFile(fileId: String) =
        _ui.update { state -> state.copy(staged = state.staged.filterNot { it.id == fileId }) }

    fun clearTray() = _ui.update { it.copy(staged = emptyList()) }

    // endregion

    // region addressing and sending ---------------------------------------------------------

    fun selectPeer(peer: PeerDevice) {
        controller.selectPeer(peer)
        _ui.update { it.copy(peer = peer) }
    }

    /**
     * Tapping a person in the list.
     *
     * With an empty tray the only thing left to ask is "what?", so the picker opens straight
     * away; with files already staged there is nothing left to ask at all, so they go.
     */
    fun choosePeer(peer: PeerDevice) {
        selectPeer(peer)
        if (_ui.value.staged.isEmpty()) openPicker() else send()
    }

    fun send() {
        val state = _ui.value
        if (state.connecting) return
        val peer = state.peer
        val files = state.staged
        when {
            peer == null -> _ui.update { it.copy(message = "Pick a phone first") }
            files.isEmpty() -> _ui.update { it.copy(message = "Pick at least one file") }
            else -> viewModelScope.launch {
                _ui.update { it.copy(connecting = true, message = if (peer.isReachable()) null else "Connecting to ${peer.name}; approve the system prompt on the other device") }
                runCatching { controller.send(peer, files) }
                    .onSuccess { jobId ->
                        // The tray is emptied the moment the bytes are the engine's problem:
                        // leaving the files staged invites a second copy of the same photo.
                        _ui.update { it.copy(staged = emptyList(), activeJobId = jobId, connecting = false) }
                    }
                    .onFailure { error ->
                        _ui.update {
                            it.copy(connecting = false, message = error.message ?: "Could not start the transfer")
                        }
                    }
            }
        }
    }

    fun cancel(jobId: String) {
        controller.cancel(jobId)
        _ui.update { it.copy(activeJobId = null) }
    }

    fun retry(jobId: String) {
        if (!controller.retry(jobId)) {
            _ui.update { it.copy(message = "Nothing left to resend") }
        }
    }

    // endregion

    // region receiving ----------------------------------------------------------------------

    fun approve(batchId: String) {
        viewModelScope.launch {
            runCatching { controller.approve(batchId) }
                .onFailure { _ui.update { it.copy(message = "Could not accept: ${it.message}") } }
        }
    }

    fun reject(batchId: String, senderName: String) {
        controller.reject(batchId)
        _ui.update { it.copy(message = "Declined $senderName") }
    }

    /** The safety codes were read out and matched. */
    fun confirmSafetyCode(batchId: String, peerName: String) {
        controller.confirmSafetyCode(batchId, peerName)
        _ui.update { it.copy(trustExpandedBatchId = batchId) }
    }

    // endregion

    // region chrome -------------------------------------------------------------------------

    fun toggleDetails() = _ui.update { it.copy(detailsExpanded = !it.detailsExpanded) }

    fun toggleTrust(batchId: String) = _ui.update { state ->
        state.copy(trustExpandedBatchId = if (state.trustExpandedBatchId == batchId) null else batchId)
    }

    fun openConnection() = _ui.update { it.copy(connectionOpen = true) }

    fun closeConnection() = _ui.update { it.copy(connectionOpen = false) }

    fun dismissReceipt(batchId: String) = _ui.update { state ->
        state.copy(dismissedReceipts = state.dismissedReceipts + batchId)
    }

    fun startRenaming() = _ui.update { it.copy(isRenaming = true, nameDraft = controller.deviceName.value) }

    fun cancelRenaming() = _ui.update { it.copy(isRenaming = false) }

    fun updateNameDraft(value: String) = _ui.update { it.copy(nameDraft = value) }

    fun commitRename() {
        val draft = _ui.value.nameDraft.trim()
        _ui.update { it.copy(isRenaming = false) }
        if (draft.isEmpty()) return
        controller.setDeviceName(draft)
        viewModelScope.launch {
            runCatching { controller.reAdvertise() }
                .onFailure { _ui.update { it.copy(message = "Name saved; re-advertising failed") } }
        }
    }

    fun consumeMessage() = _ui.update { it.copy(message = null) }

    /** True while an offer is on screen, so the screen can hide the tray behind it. */
    fun hasOffers(): Boolean = controller.incoming.value.isNotEmpty()

    companion object {
        fun factory(
            controller: MeshController,
            documents: DocumentRepository,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                DropViewModel(controller, documents) as T
        }
    }
}
