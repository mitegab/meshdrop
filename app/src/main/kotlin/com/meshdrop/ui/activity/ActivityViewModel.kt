package com.meshdrop.ui.activity

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meshdrop.core.model.SecurityState
import com.meshdrop.core.model.TransferJob
import com.meshdrop.core.prefs.UserPreferences
import com.meshdrop.core.util.MeshLog
import com.meshdrop.data.VaultStore
import com.meshdrop.di.MeshController
import com.meshdrop.ui.history.HistoryEmptyReason
import com.meshdrop.ui.history.HistoryFiltering
import com.meshdrop.ui.history.HistoryOrder
import com.meshdrop.ui.history.HistoryQuery
import com.meshdrop.ui.history.HistoryScope
import com.meshdrop.ui.history.HistorySection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ActivityUiState(
    /** What the search box and the chips are asking for. */
    val query: HistoryQuery = HistoryQuery(),
    /** The transfer whose detail sheet is open, if any. */
    val detailJobId: String? = null,
    val message: String? = null,
)

/**
 * The feed of everything that has moved, and the ability to act on it.
 *
 * This screen was History: a passive list you could only operate by swiping blind. It is now
 * Activity, with three additions that were the actual complaints waiting to happen — search,
 * filters, and a detail sheet that shows the same actions the swipe would have revealed, so
 * nothing in the app is discoverable only by accident.
 *
 * The filtering itself lives in `HistoryFiltering` (pure, and unit tested); this class only
 * holds the query and does the IO.
 */
class ActivityViewModel(
    application: Application,
    private val controller: MeshController,
    private val vault: VaultStore,
    private val preferences: UserPreferences,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ActivityUiState())
    val uiState: StateFlow<ActivityUiState> = _uiState

    init {
        // Retention runs when the log is first read, on a background thread: pruning at app
        // start would put file IO on the critical path of showing the first screen.
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { controller.pruneLog() }
                .onFailure { MeshLog.w(TAG, "Retention pass failed", it) }
        }
    }

    /** Finished transfers, newest first: this session first, then the log. */
    private val finished: StateFlow<List<TransferJob>> = combine(
        controller.jobs,
        controller.security,
    ) { jobs, security ->
        val live = jobs.filter { it.status.isFinished }
        val liveIds = live.map { it.id }.toSet()
        val logged = controller.loggedTransfers()
            .filterNot { it.jobId in liveIds }
            .map { it.toJob() }
        (live + logged)
            .sortedByDescending { it.finishedAtMillis ?: it.startedAtMillis }
            .toList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The same list after the search box, the chips and the sort order have had their say. */
    val sections: StateFlow<List<HistorySection>> = combine(
        finished,
        _uiState,
        controller.security,
    ) { jobs, ui, security ->
        HistoryFiltering.sections(HistoryFiltering.apply(jobs, ui.query, security))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Why the list is empty, if it is.
     *
     * "Nothing has ever been sent" and "nothing matches 'pixel'" need different sentences,
     * and the difference is whether the *unfiltered* list has anything in it.
     */
    val emptyReason: StateFlow<HistoryEmptyReason?> = combine(
        finished,
        sections,
    ) { jobs, shown ->
        HistoryFiltering.emptyReason(allJobs = jobs, shown = shown.flatMap { it.jobs })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val vaultedJobs: StateFlow<Set<String>> = preferences.vaultedJobs

    // region query --------------------------------------------------------------------------

    fun setSearchText(text: String) = _uiState.update { it.copy(query = it.query.copy(text = text)) }

    fun setScope(scope: HistoryScope) = _uiState.update {
        // Tapping the active chip clears it, so "All" never needs its own button.
        it.copy(query = it.query.copy(scope = if (it.query.scope == scope) HistoryScope.ALL else scope))
    }

    fun setOrder(order: HistoryOrder) = _uiState.update { it.copy(query = it.query.copy(order = order)) }

    fun clearQuery() = _uiState.update { it.copy(query = it.query.cleared()) }

    // endregion

    // region detail sheet -------------------------------------------------------------------

    fun openDetail(jobId: String) = _uiState.update { it.copy(detailJobId = jobId) }

    fun closeDetail() = _uiState.update { it.copy(detailJobId = null) }

    /** Provenance for one job: what the detail sheet shows under "where did this come from". */
    fun securityFor(jobId: String): SecurityState =
        controller.security.value[jobId] ?: SecurityState.UNKNOWN

    /**
     * Moves a received transfer's files into the vault.
     *
     * Callers gate this behind the device lock: the copy is not secret, but the vault should
     * never be a place a file arrives at by accident.
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

    fun delete(job: TransferJob) {
        if (preferences.vaultedJobs.value.contains(job.id)) {
            vault.delete(job.id)
            preferences.unmarkVaulted(job.id)
            _uiState.update { it.copy(message = "Deleted from the vault", detailJobId = null) }
            return
        }
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) { deleteLocal(job) }
            controller.forgetTransfer(job.id)
            controller.clearFinished()
            _uiState.update {
                it.copy(
                    message = if (deleted > 0) "Deleted $deleted file(s)" else "Nothing left on disk to delete",
                    detailJobId = null,
                )
            }
        }
    }

    fun clearAll() {
        controller.clearFinished()
        controller.clearLog()
        _uiState.update { it.copy(message = "Activity cleared") }
    }

    /** Shareable Uri for a vaulted file, so Open and Re-share still work from in there. */
    fun vaultUri(jobId: String): Uri? = vault.filesFor(jobId).firstOrNull()?.let(vault::contentUri)

    /** The Uri a row should open: vault copy when it has one, the written file otherwise. */
    fun uriFor(job: TransferJob): Uri? =
        if (job.id in preferences.vaultedJobs.value) {
            vaultUri(job.id)
        } else {
            job.files.firstNotNullOfOrNull { it.localUri }?.let(Uri::parse)
        }

    fun consumeMessage() = _uiState.update { it.copy(message = null) }

    // endregion

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

    companion object {
        private const val TAG = "ActivityViewModel"

        fun factory(
            application: Application,
            controller: MeshController,
            vault: VaultStore,
            preferences: UserPreferences,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                ActivityViewModel(application, controller, vault, preferences) as T
        }
    }
}
