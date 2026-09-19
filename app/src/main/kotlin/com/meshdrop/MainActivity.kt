package com.meshdrop

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.meshdrop.di.AppContainer
import com.meshdrop.service.TransferForegroundService
import com.meshdrop.ui.MeshDropRoot
import kotlinx.coroutines.launch

/**
 * Single-Activity host for the whole MVP: every screen is a Compose destination rendered
 * inside this activity.
 */
class MainActivity : ComponentActivity() {

    private val container: AppContainer
        get() = (application as MeshDropApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            MeshDropRoot(container = container)
        }
    }

    override fun onStart() {
        super.onStart()
        // Permissions can be revoked (or granted) while we are in the background, so the
        // mesh is (re)started every time the user comes back to the app.
        // Optional radio/notification permissions must never disable ordinary LAN sockets.
        lifecycleScope.launch { runCatching { container.meshController.start() } }
    }

    override fun onDestroy() {
        if (isFinishing) {
            TransferForegroundService.stop(this)
        }
        super.onDestroy()
    }

    companion object {
        const val TAG = "MainActivity"
    }
}
