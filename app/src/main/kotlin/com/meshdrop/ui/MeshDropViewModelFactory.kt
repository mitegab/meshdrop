package com.meshdrop.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.meshdrop.di.AppContainer
import com.meshdrop.ui.activity.ActivityViewModel
import com.meshdrop.ui.drop.DropViewModel
import com.meshdrop.ui.settings.SettingsViewModel

/**
 * Minimal factory that hands each view model the objects it needs.
 *
 * A DI framework would remove ~15 lines at the cost of a build step; for an app this size an
 * explicit factory is easier to follow — and it makes it obvious that every screen shares the
 * same application-scoped [com.meshdrop.di.MeshController].
 */
@Suppress("UNCHECKED_CAST")
class MeshDropViewModelFactory(
    private val container: AppContainer,
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
        DropViewModel::class.java -> DropViewModel(
            controller = container.meshController,
            documents = container.documentRepository,
        ) as T

        ActivityViewModel::class.java -> ActivityViewModel(
            application = container.application,
            controller = container.meshController,
            vault = container.vaultStore,
            preferences = container.userPreferences,
        ) as T

        SettingsViewModel::class.java -> SettingsViewModel(
            preferences = container.userPreferences,
            controller = container.meshController,
            verifiedPeers = container.verifiedPeers,
        ) as T

        else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
