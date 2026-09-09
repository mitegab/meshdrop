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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.meshdrop.di.AppContainer
import com.meshdrop.service.TransferForegroundService
import com.meshdrop.ui.home.HomeScreen
import com.meshdrop.ui.home.HomeViewModel
import com.meshdrop.ui.navigation.Route
import com.meshdrop.ui.receive.ReceiveScreen
import com.meshdrop.ui.receive.ReceiveViewModel
import com.meshdrop.ui.send.SendScreen
import com.meshdrop.ui.send.SendViewModel
import com.meshdrop.ui.theme.MeshDropTheme
import com.meshdrop.util.AppPermissions

/**
 * Root composable: Material theme, the three-screen navigation graph and the one-time
 * permission gate.
 *
 * Every screen shares the same [AppContainer], and therefore the same
 * [com.meshdrop.di.MeshController]: a transfer started on Send keeps running while the user
 * walks back to Home or over to Receive.
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
                    onPeerSelected = { navController.navigate(Route.Send.path) },
                    missingPermissions = missingPermissions,
                    onRequestPermissions = {
                        permissionLauncher.launch(AppPermissions.runtimePermissions().toTypedArray())
                    },
                )
            }

            composable(Route.Send.path) {
                val sendViewModel: SendViewModel = viewModel(factory = factory)
                SendScreen(viewModel = sendViewModel, onBack = { navController.popBackStack() })
            }

            composable(Route.Receive.path) {
                val receiveViewModel: ReceiveViewModel = viewModel(factory = factory)
                ReceiveScreen(
                    viewModel = receiveViewModel,
                    onBack = {
                        TransferForegroundService.stop(context)
                        navController.popBackStack()
                    },
                )
            }
        }
    }
}
