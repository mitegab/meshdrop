package com.meshdrop.ui.history

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meshdrop.core.model.SecurityState
import com.meshdrop.core.model.TransferJob
import com.meshdrop.core.prefs.UserPreferences
import com.meshdrop.core.util.MeshLog
import com.meshdrop.data.VaultStore
import com.meshdrop.di.MeshController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class HistoryDay(
    val label: String,
    val jobs: List<TransferJob>,
)

data class HistoryUiState(
    val message: String? = null,
)

/**
 * Feed of finished transfers for this session, newest first and grouped by day.
 *
 * Scope note: this is the transfer log the engine keeps in memory, not a permanent archive —
 * the honest thing to show before a database exists is exactly what we know.
 */
class HistoryViewModel(
    application: Application,
    private val controller: MeshController,
    private val vault: VaultStore,
    private val preferences: UserPreferences,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState

    val groups: StateFlow<List<HistoryDay>> = controller.jobs
        .map { jobs ->
            jobs.filter { it.status.isFinished }
                .sortedByDescending { it.finishedAtMillis ?: it.startedAtMillis }
                .groupBy { job -> dayOf(job.finishedAtMillis ?: job.startedAtMillis) }
                .map { (day, list) -> HistoryDay(label = labelFor(day), jobs = list) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val security: StateFlow<Map<String, SecurityState>> = controller.security

    /** Provenance for one job: what the UI shows under "who sent this and can I trust it". */
    fun securityFor(jobId: String): SecurityState = controller.security.value[jobId] ?: SecurityState.UNKNOWN

    fun delete(job: TransferJob) {
        if (preferences.vaultedJobs.value.contains(job.id)) {
            vault.delete(job.id)
            preferences.unmarkVaulted(job.id)
            _uiState.update { it.copy(message = "Deleted from the vault") }
            return
        }
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) { deleteLocal(job) }
            controller.clearFinished()
            _uiState.update {
                it.copy(
                    message = if (deleted > 0) "Deleted $deleted file(s)" else "Nothing left on disk to delete",
                )
            }
        }
    }

    fun clearAll() = controller.clearFinished()

    /** Jobs whose files are in the vault rather than in shared storage. */
    val vaultedJobs: StateFlow<Set<String>> = preferences.vaultedJobs

    /**
     * Moves a received transfer's files into the vault.
     *
     * Callers gate this behind the device lock: the copy itself is not secret, but the vault
     * should never be a place a file arrives at by accident.
     */
    fun moveToVault(job: TransferJob) {
        viewModelScope.launch {
            val moved = withContext(Dispatchers.IO) { vault.moveIn(job) }
            if (moved) {
                preferences.markVaulted(job.id)
                _uiState.update { it.copy(message = "Moved into the vault") }
            } else {
                _uiState.update { it.copy(message = "Those files are no longer on disk") }
            }
        }
    }

    /** Shareable Uri for a vaulted file, so Open and Re-share still work from in there. */
    fun vaultUri(jobId: String): Uri? = vault.filesFor(jobId).firstOrNull()?.let(vault::contentUri)

    fun consumeMessage() = _uiState.update { it.copy(message = null) }

    private fun deleteLocal(job: TransferJob): Int {
        val resolver = getApplication<Application>().contentResolver
        var deleted = 0
        job.files.forEach { file ->
            val uri = file.localUri?.let(Uri::parse) ?: return@forEach
            val removed = runCatching { resolver.delete(uri, null, null) > 0 }
                .onFailure { MeshLog.w(TAG, "Cannot delete $uri", it) }
                .getOrDefault(false)
            if (removed) deleted++
        }
        return deleted
    }

    private fun dayOf(millis: Long): LocalDate =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

    private fun labelFor(day: LocalDate): String {
        val today = LocalDate.now()
        return when (day) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> day.format(DATE_FORMAT)
        }
    }

    companion object {
        private const val TAG = "HistoryViewModel"
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy")

        fun factory(
            application: Application,
            controller: MeshController,
            vault: VaultStore,
            preferences: UserPreferences,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                HistoryViewModel(application, controller, vault, preferences) as T
        }
    }
}
