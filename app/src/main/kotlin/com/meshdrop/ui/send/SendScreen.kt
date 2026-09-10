package com.meshdrop.ui.send

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshdrop.core.model.PeerDevice
import com.meshdrop.core.util.Format
import com.meshdrop.transfer.state.TransferSessionState
import com.meshdrop.ui.components.ActiveTransferPanel
import com.meshdrop.ui.components.EmptyState
import com.meshdrop.ui.components.FileThumb
import com.meshdrop.ui.components.StatusChip
import com.meshdrop.ui.components.TrustIndicator
import com.meshdrop.ui.components.TransferFinishedCard
import com.meshdrop.ui.components.deviceColor
import com.meshdrop.ui.components.deviceInitials

/**
 * Screen 2 — pick files, confirm who you are sending to, watch it go.
 *
 * The trust line is the only security copy on this screen and it is one line until tapped.
 * Network internals live inside [ActiveTransferPanel]'s collapsed section.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendScreen(
    viewModel: SendViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    autoOpenPicker: Boolean = false,
) {
    val peers by viewModel.peers.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocument(),
    ) { uris -> viewModel.onFilesPicked(uris) }

    LaunchedEffect(autoOpenPicker) {
        if (autoOpenPicker && uiState.selectedFiles.isEmpty()) {
            runCatching { picker.launch(arrayOf("*/*")) }
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Send") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Who am I sending to?
            item {
                SectionLabel("Send to")
                if (peers.isEmpty()) {
                    EmptyState(
                        icon = Icons.Default.Close,
                        title = "No devices nearby",
                        subtitle = "Open MeshDrop on the other phone and it will appear here.",
                    )
                } else {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(items = peers, key = { it.id }) { peer ->
                            PeerPill(
                                peer = peer,
                                selected = peer.id == uiState.selectedPeer?.id,
                                onClick = { viewModel.selectPeer(peer) },
                            )
                        }
                    }
                }
            }

            // The picker.
            item {
                SectionLabel("Files")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PickerButton(
                        icon = Icons.Default.Image,
                        label = "Photos",
                        modifier = Modifier.weight(1f),
                        onClick = { picker.launch(arrayOf("image/*")) },
                    )
                    PickerButton(
                        icon = Icons.Default.VideoFile,
                        label = "Videos",
                        modifier = Modifier.weight(1f),
                        onClick = { picker.launch(arrayOf("video/*")) },
                    )
                    PickerButton(
                        icon = Icons.Default.InsertDriveFile,
                        label = "Files",
                        modifier = Modifier.weight(1f),
                        onClick = { picker.launch(arrayOf("*/*")) },
                    )
                }
            }

            if (uiState.selectedFiles.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Default.InsertDriveFile,
                        title = "Nothing picked yet",
                        subtitle = "Pick photos, videos or any file — you can select several at once.",
                    )
                }
            } else {
                items(items = uiState.selectedFiles, key = { it.id }) { file ->
                    FileRow(
                        file = file,
                        onRemove = { viewModel.removeFile(file.id) },
                    )
                }
            }

            // One line of trust, expandable.
            uiState.selectedPeer?.let { peer ->
                item {
                    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                        TrustIndicator(
                            security = securityOf(session),
                            peerName = peer.name,
                            expanded = uiState.trustExpanded,
                            onToggleExpanded = viewModel::toggleTrustExpanded,
                            onConfirmCode = { viewModel.confirmSafetyCode(peer.name) },
                        )
                    }
                }
            }

            item {
                when (val state = session) {
                    is TransferSessionState.Active -> {
                        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                            ActiveTransferPanel(
                                state = state,
                                reading = uiState.reading,
                                advancedExpanded = uiState.advancedExpanded,
                                onToggleAdvanced = viewModel::toggleAdvanced,
                                throughputSamples = uiState.throughput,
                                throughputPeak = uiState.throughputPeak,
                                onCancel = viewModel::cancel,
                            )
                        }
                    }

                    is TransferSessionState.Finished -> {
                        TransferFinishedCard(
                            state = state,
                            onDone = viewModel::dismissResult,
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                    }

                    else -> Unit
                }
            }

            item {
                val nothingPicked = uiState.selectedFiles.isEmpty()
                val noPeer = uiState.selectedPeer == null
                val active = session is TransferSessionState.Active
                Button(
                    onClick = viewModel::send,
                    enabled = !nothingPicked && !noPeer && !active,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 20.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Icon(Icons.Default.Send, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when {
                            active -> "Sending…"
                            nothingPicked -> "Pick files first"
                            noPeer -> "Pick a device first"
                            else -> "Send ${uiState.selectedFiles.size} file(s) · " +
                                Format.bytes(uiState.selectedFiles.sumOf { it.sizeBytes })
                        },
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

private fun securityOf(session: TransferSessionState) = when (session) {
    is TransferSessionState.Active -> session.security
    is TransferSessionState.AwaitingApproval -> session.security
    is TransferSessionState.IncomingOffer -> session.security
    is TransferSessionState.Finished -> session.security
    else -> com.meshdrop.core.model.SecurityState.UNKNOWN
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PeerPill(
    peer: PeerDevice,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = deviceColor(peer.id)
    val background = if (selected) color.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceVariant
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.28f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(deviceInitials(peer.name), style = MaterialTheme.typography.labelMedium, color = color)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(peer.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(peer.kind.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PickerButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun FileRow(
    file: TransferFile,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FileThumb(
                uri = file.localUri?.let(Uri::parse),
                mimeType = file.mimeType,
                modifier = Modifier.size(44.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = Format.bytes(file.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (file.status != com.meshdrop.core.model.TransferStatus.WAITING) {
                StatusChip(status = file.status)
                Spacer(modifier = Modifier.width(8.dp))
            }
            TextButton(onClick = onRemove) { Text("Remove") }
        }
    }
}

/** Kept for previews: builds a screen state without a controller. */
@Composable
fun SendScreenPreviewSurface(content: @Composable () -> Unit) {
    androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.background) { content() }
}

internal fun uriOf(value: String): Uri = Uri.parse(value)
