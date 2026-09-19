package com.meshdrop.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.meshdrop.di.AppContainer
import com.meshdrop.ui.activity.ActivityScreen
import com.meshdrop.ui.activity.ActivityViewModel
import com.meshdrop.ui.drop.DropScreen
import com.meshdrop.ui.drop.DropViewModel
import com.meshdrop.ui.navigation.Route
import com.meshdrop.ui.settings.SettingsScreen
import com.meshdrop.ui.settings.SettingsViewModel
import com.meshdrop.ui.theme.MeshDropTheme
import com.meshdrop.util.AppPermissions

/**
 * Root composable: theme, navigation graph, and the one-time permission ask.
 *
 * Three destinations and no bottom bar. A permanent tab bar is a promise that the app has
 * several equally important places to be, and this one does not: it has one screen where
 * things are sent, and two reference pages that are opened on purpose and closed again.
 *
 * Every screen shares the same [AppContainer] and therefore the same
 * [com.meshdrop.di.MeshController], so a transfer started on Drop keeps running while the
 * user wanders into Activity or Settings.
 */
@Composable
fun MeshDropRoot(container: AppContainer) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val factory = remember(container) { MeshDropViewModelFactory(container) }

    val permissionScope = rememberCoroutineScope()
    var missingPermissions by remember {
        mutableStateOf(AppPermissions.missing(context))
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        missingPermissions = AppPermissions.missing(context)
        permissionScope.launch {
            if (container.meshController.localPort > 0) runCatching { container.meshController.refresh() }
        }
    }

    LaunchedEffect(Unit) {
        if (missingPermissions.isNotEmpty()) {
            permissionLauncher.launch(AppPermissions.runtimePermissions().toTypedArray())
        }
    }

    MeshDropTheme {
        NavHost(navController = navController, startDestination = Route.Drop.path) {
            composable(Route.Drop.path) {
                val dropViewModel: DropViewModel = viewModel(factory = factory)
                DropScreen(
                    viewModel = dropViewModel,
                    onOpenActivity = { navController.navigate(Route.Activity.path) },
                    onOpenSettings = { navController.navigate(Route.Settings.path) },
                    missingPermissions = missingPermissions,
                    onRequestPermissions = {
                        permissionLauncher.launch(AppPermissions.runtimePermissions().toTypedArray())
                    },
                )
            }

            composable(Route.Activity.path) {
                val activityViewModel: ActivityViewModel = viewModel(factory = factory)
                ActivityScreen(
                    viewModel = activityViewModel,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Route.Settings.path) {
                val settingsViewModel: SettingsViewModel = viewModel(factory = factory)
                SettingsScreen(
                    viewModel = settingsViewModel,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
