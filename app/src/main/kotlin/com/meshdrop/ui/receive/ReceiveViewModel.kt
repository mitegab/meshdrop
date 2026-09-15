package com.meshdrop.ui.receive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meshdrop.core.model.IncomingRequest
import com.meshdrop.core.model.SecurityState
import com.meshdrop.core.model.TransferDirection
import com.meshdrop.core.model.TransferJob
import com.meshdrop.core.model.TransferStatus
import com.meshdrop.core.util.SmoothedReading
import com.meshdrop.core.util.TransferTelemetry
import com.meshdrop.di.MeshController
import com.meshdrop.transfer.state.TransferSessionMapper
import com.meshdrop.transfer.state.TransferSessionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

data class ReceiveUiState(
    /** True while this screen keeps the receiver running in the foreground. */
    val isWaiting: Boolean = true,
    val message: String? = null,
    /** Batch ids whose "Network details" panel is open. */
    val expandedAdvanced: Set<String> = emptySet(),
    /** Batch ids whose trust line is open. */
    val expandedTrust: Set<String> = emptySet(),
    val readings: Map<String, SmoothedReading> = emptyMap(),
    val throughput: Map<String, List<Long>> = emptyMap(),
    val peaks: Map<String, Long> = emptyMap(),
)

/**
 * State for the Receive screen: offers waiting for a decision, live downloads, and the files
 * that have landed.
 *
 * Every batch becomes one [TransferSessionState], so the screen can render an offer, a running
 * download and a finished download through exactly the same composables as the Send screen.
 */
class ReceiveViewModel(
    private val controller: MeshController,
) : ViewModel() {

    /** One smoother per batch so several simultaneous transfers do not pollute each other. */
    private val telemetry = ConcurrentHashMap<String, TransferTelemetry>()

    val deviceName: StateFlow<String> = controller.deviceName
    val incoming: StateFlow<List<IncomingRequest>> = controller.incoming

    /**
     * One-second clock, running only while an offer is waiting.
     *
     * The countdown is derived from this rather than stored per offer: recomputing
     * `OfferWindow.countdown(now)` is free, and keeping a string in state would mean another
     * place to forget to update.
     */
    private val _now = MutableStateFlow(System.currentTimeMillis())
    val now: StateFlow<Long> = _now.asStateFlow()

    private val _uiState = MutableStateFlow(ReceiveUiState())
    val uiState: StateFlow<ReceiveUiState> = _uiState

    @OptIn(ExperimentalCoroutinesApi::class)
    val sessions: StateFlow<List<TransferSessionState>> = combine(
        controller.jobs,
        controller.incoming,
        controller.security,
        _uiState,
    ) { jobs, offers, security, _ ->
        val receiveJobs = jobs.filter { it.direction == TransferDirection.RECEIVE }
        val running = receiveJobs.filterNot { it.status.isFinished }
            .map { job ->
                TransferSessionMapper.toSessionState(
                    job = job,
                    security = security[job.id] ?: SecurityState.UNKNOWN,
                )
            }
        val finished = receiveJobs.filter { it.status.isFinished }
            .takeLast(FINISHED_KEEP)
            .map { job ->
                TransferSessionMapper.toSessionState(
                    job = job,
                    security = security[job.id] ?: SecurityState.UNKNOWN,
                )
            }
        val pending = offers.map { offer ->
            TransferSessionMapper.toIncomingOffer(
                request = offer,
                security = security[offer.batchId] ?: SecurityState.UNKNOWN,
            )
        }
        pending + running + finished
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    val transferPort: Int get() = controller.localPort

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

        viewModelScope.launch {
            controller.incoming.collect { offers ->
                if (offers.isEmpty()) return@collect
                // Only ticks while there is something to count down; an idle receive screen
                // should not be waking once a second to redraw itself.
                while (controller.incoming.value.isNotEmpty()) {
                    _now.value = System.currentTimeMillis()
                    kotlinx.coroutines.delay(1_000)
                }
            }
        }

        viewModelScope.launch {
            controller.jobs.collect { jobs ->
                val readings = LinkedHashMap<String, SmoothedReading>()
                val samples = LinkedHashMap<String, List<Long>>()
                val peaks = LinkedHashMap<String, Long>()
                val liveIds = HashSet<String>()

                jobs.filter { it.direction == TransferDirection.RECEIVE && it.status == TransferStatus.IN_PROGRESS }
                    .forEach { job ->
                        val smoother = telemetry.getOrPut(job.id) { TransferTelemetry() }
                        liveIds += job.id
                        readings[job.id] = smoother.update(job.bytesPerSecond, job.etaMillis)
                        samples[job.id] = smoother.samples()
                        peaks[job.id] = smoother.peak()
                    }

                telemetry.keys.removeAll { it !in liveIds }
                _uiState.update {
                    it.copy(readings = readings, throughput = samples, peaks = peaks)
                }
            }
        }
    }

    fun approve(batchId: String) {
        viewModelScope.launch {
            runCatching { controller.approve(batchId) }
                .onFailure { showMessage("Could not accept: ${it.message}") }
        }
    }

    fun reject(batchId: String, senderName: String) {
        controller.reject(batchId)
        showMessage("Declined $senderName")
    }

    fun cancel(jobId: String) = controller.cancel(jobId)

    fun clearFinished() {
        controller.clearFinished()
        telemetry.clear()
    }

    fun setWaiting(waiting: Boolean) = _uiState.update { it.copy(isWaiting = waiting) }

    fun showMessage(message: String) = _uiState.update { it.copy(message = message) }

    fun consumeMessage() = _uiState.update { it.copy(message = null) }

    fun toggleAdvanced(batchId: String) = _uiState.update { state ->
        val expanded = state.expandedAdvanced.toMutableSet()
        if (!expanded.add(batchId)) expanded.remove(batchId)
        state.copy(expandedAdvanced = expanded)
    }

    fun toggleTrust(batchId: String) = _uiState.update { state ->
        val expanded = state.expandedTrust.toMutableSet()
        if (!expanded.add(batchId)) expanded.remove(batchId)
        state.copy(expandedTrust = expanded)
    }

    /** The safety codes were compared out loud and matched. */
    fun confirmSafetyCode(batchId: String, peerName: String) =
        controller.confirmSafetyCode(batchId, peerName)

    fun readingFor(batchId: String): SmoothedReading =
        _uiState.value.readings[batchId] ?: SmoothedReading(0, null)

    fun samplesFor(batchId: String): List<Long> = _uiState.value.throughput[batchId] ?: emptyList()

    fun peakFor(batchId: String): Long = _uiState.value.peaks[batchId] ?: 1L

    companion object {
        /** How many finished batches the feed keeps on screen before they are cleared. */
        private const val FINISHED_KEEP = 5

        fun factory(controller: MeshController): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ReceiveViewModel(controller) as T
            }
    }
}
