package com.meshdrop

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.meshdrop.ui.theme.MeshDropTheme

/**
 * Single-Activity host for the whole MVP: every screen is a Compose destination
 * rendered inside this activity.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MeshDropTheme {
                Scaffold { innerPadding ->
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        // Placeholder: replaced by the MeshDrop navigation graph in the next step.
                        Text(
                            text = "MeshDrop",
                            style = MaterialTheme.typography.headlineLarge,
                            modifier = Modifier,
                        )
                    }
                }
            }
        }
    }
}
