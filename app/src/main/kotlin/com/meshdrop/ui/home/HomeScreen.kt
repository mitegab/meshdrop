package com.meshdrop.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshdrop.core.model.PeerDevice
import com.meshdrop.core.model.TransportLane
import com.meshdrop.core.model.toLane
import com.meshdrop.ui.components.EmptyState
import com.meshdrop.ui.components.PermissionCard
import com.meshdrop.ui.components.RadarPulse
import com.meshdrop.ui.components.TransportIcon
import com.meshdrop.ui.components.deviceColor
import com.meshdrop.ui.components.deviceInitials

/**
 * Screen 1 — the hub.
 *
 * Progressive disclosure rule for this screen: the two things anybody came here to do
 * (send, receive) own most of the pixels and are one tap away; everything else is a single
 * row you can ignore.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onPeerSelected: (PeerDevice) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
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
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Default.History, contentDescription = "History")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
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
                PermissionCard(missing = missingPermissions, onRequest = onRequestPermissions)
            }

            // The two giant zones: never more than one tap from here to a transfer.
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    SendZone(
                        modifier = Modifier.weight(1f),
                        onClick = onSend,
                    )
                    ReceiveZone(
                        modifier = Modifier.weight(1f),
                        onClick = onReceive,
                    )
                }
            }

            item {
                DiscoveryCard(
                    deviceName = deviceName,
                    peerCount = peers.size,
                    scanning = discoveryState.scanning,
                    lanes = viewModel.activeLanes(),
                    onRename = viewModel::startRenaming,
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Nearby devices",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (peers.isNotEmpty()) {
                        Text(
                            text = "tap one to send",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                }
            }

            if (peers.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Default.WifiOff,
                        title = if (discoveryState.scanning) "Looking for devices…" else "No devices found yet",
                        subtitle = "Open MeshDrop on the other phone and keep both on the same Wi-Fi network.",
                    )
                }
            } else {
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(items = peers, key = { it.id }) { peer ->
                            DeviceChip(
                                peer = peer,
                                onClick = {
                                    viewModel.selectPeer(peer)
                                    onPeerSelected(peer)
                                },
                            )
                        }
                    }
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
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Recent",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onOpenHistory) { Text("See all") }
                    }
                }
                items(items = jobs.takeLast(3).reversed(), key = { it.id }) { job ->
                    RecentRow(
                        title = if (job.direction == com.meshdrop.core.model.TransferDirection.SEND) {
                            "To ${job.peerName}"
                        } else {
                            "From ${job.peerName}"
                        },
                        subtitle = "${job.files.size} file(s) · ${com.meshdrop.core.util.Format.bytes(job.totalBytes)}",
                        status = job.status,
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(28.dp)) }
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
private fun SendZone(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(150.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    ),
                ),
            )
            .clickable(onClick = onClick)
            .padding(18.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Send,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Column {
                Text("Send", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Pick files and push them",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ReceiveZone(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(150.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.secondaryContainer,
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                    ),
                ),
            )
            .clickable(onClick = onClick)
            .padding(18.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Column {
                Text("Receive", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Stay visible, accept files",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Who we are + "is discovery running", with the radar doing the talking. */
@Composable
private fun DiscoveryCard(
    deviceName: String,
    peerCount: Int,
    scanning: Boolean,
    lanes: List<TransportLane>,
    onRename: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(56.dp)) {
                if (scanning) {
                    RadarPulse(modifier = Modifier.fillMaxSize())
                } else {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)),
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (scanning) "Looking for nearby devices" else "Discovery paused",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = if (scanning) {
                        "$peerCount found · ${lanes.joinToString(" + ") { it.shortLabel }}"
                    } else {
                        "Visible as $deviceName"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (scanning) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        lanes.forEach { lane ->
                            TransportIcon(lane = lane, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
            TextButton(onClick = onRename) { Text("Rename") }
        }
    }
}

/** One device in the nearby strip: avatar, name, and the lane it was found on. */
@Composable
private fun DeviceChip(
    peer: PeerDevice,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = deviceColor(peer.id)
    Card(
        onClick = onClick,
        modifier = modifier.width(112.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp, horizontal = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(color.copy(alpha = 0.20f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = deviceInitials(peer.name),
                        style = MaterialTheme.typography.titleMedium,
                        color = color,
                    )
                }
            }
            Text(
                text = peer.name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TransportIcon(lane = peer.kind.toLane(), modifier = Modifier.size(12.dp))
                Text(
                    text = peer.kind.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun RecentRow(
    title: String,
    subtitle: String,
    status: com.meshdrop.core.model.TransferStatus,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (status == com.meshdrop.core.model.TransferStatus.COMPLETED) {
                    Icons.Default.Download
                } else {
                    Icons.Default.Send
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        com.meshdrop.ui.components.StatusChip(status = status)
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

/** Shared by previews and the real screen so the palette stays consistent. */
@Composable
fun HomeSurface(content: @Composable () -> Unit) {
    androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.background) { content() }
}
