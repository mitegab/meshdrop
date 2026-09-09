package com.meshdrop.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshdrop.core.model.PeerDevice
import com.meshdrop.core.model.TransferJob
import com.meshdrop.ui.components.BigActionCard
import com.meshdrop.ui.components.EmptyState
import com.meshdrop.ui.components.PeerRow
import com.meshdrop.ui.components.PermissionCard
import com.meshdrop.ui.components.SectionLabel
import com.meshdrop.ui.components.TransferJobCard

/**
 * Screen 1 of 3 — the hub: who we are, the two things you can do, and who is around.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onPeerSelected: (PeerDevice) -> Unit,
    modifier: Modifier = Modifier,
    missingPermissions: List<String> = emptyList(),
    onRequestPermissions: () -> Unit = {},
) {
    val deviceName by viewModel.deviceName.collectAsStateWithLifecycle()
    val peers by viewModel.peers.collectAsStateWithLifecycle()
    val discoveryState by viewModel.discoveryState.collectAsStateWithLifecycle()
    val jobs by viewModel.recentJobs.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { viewModel.ensureStarted() }
    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("MeshDrop") },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Scan again")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            item {
                PermissionCard(
                    missing = missingPermissions,
                    onRequest = onRequestPermissions,
                )
            }

            item { IdentityCard(deviceName = deviceName, onRename = viewModel::startRenaming) }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    BigActionCard(
                        title = "Send",
                        subtitle = "Pick files and push them to a nearby device",
                        icon = Icons.Default.Send,
                        onClick = onSend,
                        modifier = Modifier.weight(1f),
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    BigActionCard(
                        title = "Receive",
                        subtitle = "Stay visible and accept what others send",
                        icon = Icons.Default.Download,
                        onClick = onReceive,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            item {
                SectionLabel(
                    text = "Nearby devices (${peers.size})",
                    trailing = {
                        if (discoveryState.scanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    },
                )
            }

            if (peers.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Default.Wifi,
                        title = if (discoveryState.scanning) "Looking for devices…" else "No devices found",
                        subtitle = "Put both phones on the same Wi-Fi network and keep MeshDrop open on the other one.",
                    )
                }
            } else {
                items(items = peers, key = { it.id }) { peer ->
                    PeerRow(
                        peer = peer,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 5.dp),
                        onClick = {
                            viewModel.selectPeer(peer)
                            onPeerSelected(peer)
                        },
                    )
                }
            }

            discoveryState.firstError?.let { error ->
                item {
                    Text(
                        text = error,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (jobs.isNotEmpty()) {
                item { SectionLabel(text = "Recent activity") }
                items(items = jobs.takeLast(3).reversed(), key = { it.id }) { job ->
                    TransferJobCard(
                        job = job,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 5.dp),
                    )
                }
            }

            item { Spacer(modifier = Modifier.padding(24.dp)) }
        }
    }

    if (uiState.isRenaming) {
        RenameDialog(
            draft = uiState.nameDraft,
            onDraftChange = viewModel::updateNameDraft,
            onConfirm = viewModel::commitRename,
            onDismiss = viewModel::cancelRenaming,
        )
    }
}

@Composable
private fun IdentityCard(
    deviceName: String,
    onRename: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "You are",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(text = deviceName, style = MaterialTheme.typography.titleLarge)
            }
            TextButton(onClick = onRename) { Text("Change") }
        }
    }
}

@Composable
private fun RenameDialog(
    draft: String,
    onDraftChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Device name") },
        text = {
            Column {
                Text(
                    "This is how other devices see you on the network.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    singleLine = true,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Small helper so the preview/UI reads well even without a Surface wrapper. */
@Composable
fun HomeSurface(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) { content() }
}
