package com.meshdrop.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.meshdrop.di.AppContainer
import com.meshdrop.service.TransferForegroundService
import com.meshdrop.ui.history.HistoryScreen
import com.meshdrop.ui.history.HistoryViewModel
import com.meshdrop.ui.home.HomeScreen
import com.meshdrop.ui.home.HomeViewModel
import com.meshdrop.ui.navigation.Route
import com.meshdrop.ui.receive.ReceiveScreen
import com.meshdrop.ui.receive.ReceiveViewModel
import com.meshdrop.ui.send.SendScreen
import com.meshdrop.ui.send.SendViewModel
import com.meshdrop.ui.settings.SettingsScreen
import com.meshdrop.ui.settings.SettingsViewModel
import com.meshdrop.ui.theme.MeshDropTheme
import com.meshdrop.util.AppPermissions

/**
 * Root composable: Material theme, navigation graph and the one-time permission gate.
 *
 * Every screen shares the same [AppContainer], and therefore the same
 * [com.meshdrop.di.MeshController]: a transfer started on Send keeps running while the user
 * walks back to Home, over to Receive, or into History.
 *
 * Dark is the default because this is a systems tool that people use at night, in aeroplane
 * mode, next to a router — not a pastel productivity app.
 */
@Composable
fun MeshDropRoot(container: AppContainer) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val factory = remember(container) { MeshDropViewModelFactory(container) }

    var missingPermissions by remember {
        mutableStateOf(AppPermissions.criticalMissing(context))
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ -> missingPermissions = AppPermissions.criticalMissing(context) }

    LaunchedEffect(Unit) {
        if (missingPermissions.isNotEmpty()) {
            permissionLauncher.launch(AppPermissions.runtimePermissions().toTypedArray())
        }
    }

    MeshDropTheme {
        NavHost(navController = navController, startDestination = Route.Home.path) {
            composable(Route.Home.path) {
                val homeViewModel: HomeViewModel = viewModel(factory = factory)
                HomeScreen(
                    viewModel = homeViewModel,
                    onSend = { navController.navigate(Route.Send.path) },
                    onReceive = {
                        TransferForegroundService.start(context)
                        navController.navigate(Route.Receive.path)
                    },
                    // Tapping a device in the strip goes straight to the file picker: the
                    // device is already chosen, so the only thing left to ask is "what?".
                    onPeerSelected = {
                        navController.navigate("send?${Route.ARG_AUTO_PICK}=true")
                    },
                    onOpenHistory = { navController.navigate(Route.History.path) },
                    onOpenSettings = { navController.navigate(Route.Settings.path) },
                    missingPermissions = missingPermissions,
                    onRequestPermissions = {
                        permissionLauncher.launch(AppPermissions.runtimePermissions().toTypedArray())
                    },
                )
            }

            composable(
                route = Route.SEND_ROUTE,
                arguments = listOf(
                    navArgument(Route.ARG_AUTO_PICK) {
                        type = NavType.BoolType
                        defaultValue = false
                    },
                ),
            ) { backStackEntry ->
                val sendViewModel: SendViewModel = viewModel(factory = factory)
                SendScreen(
                    viewModel = sendViewModel,
                    onClose = { navController.popBackStack() },
                    autoOpenPicker = backStackEntry.arguments?.getBoolean(Route.ARG_AUTO_PICK) ?: false,
                )
            }

            composable(Route.Receive.path) {
                val receiveViewModel: ReceiveViewModel = viewModel(factory = factory)
                ReceiveScreen(
                    viewModel = receiveViewModel,
                    onClose = {
                        TransferForegroundService.stop(context)
                        navController.popBackStack()
                    },
                )
            }

            composable(Route.History.path) {
                val historyViewModel: HistoryViewModel = viewModel(factory = factory)
                HistoryScreen(
                    viewModel = historyViewModel,
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
