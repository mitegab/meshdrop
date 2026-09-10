package com.meshdrop.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.meshdrop.di.AppContainer
import com.meshdrop.ui.history.HistoryViewModel
import com.meshdrop.ui.home.HomeViewModel
import com.meshdrop.ui.receive.ReceiveViewModel
import com.meshdrop.ui.send.SendViewModel
import com.meshdrop.ui.settings.SettingsViewModel

/**
 * Minimal factory that hands each ViewModel the objects it needs.
 *
 * A DI framework would remove ~20 lines at the cost of a build step; for an MVP of this
 * size an explicit factory is easier to follow — and it makes it obvious that every screen
 * shares the same application-scoped [com.meshdrop.di.MeshController].
 */
@Suppress("UNCHECKED_CAST")
class MeshDropViewModelFactory(
    private val container: AppContainer,
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
        HomeViewModel::class.java -> HomeViewModel(container.meshController) as T
        SendViewModel::class.java -> SendViewModel(container.meshController, container.documentRepository) as T
        ReceiveViewModel::class.java -> ReceiveViewModel(container.meshController) as T
        HistoryViewModel::class.java -> HistoryViewModel(
            container.application,
            container.meshController,
            container.vaultStore,
            container.userPreferences,
        ) as T
        SettingsViewModel::class.java -> SettingsViewModel(
            container.userPreferences,
            container.meshController,
            container.verifiedPeers,
        ) as T

        else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
