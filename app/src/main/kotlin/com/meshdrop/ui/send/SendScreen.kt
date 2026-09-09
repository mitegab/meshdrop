package com.meshdrop.ui.send

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshdrop.core.util.Format
import com.meshdrop.ui.components.EmptyState
import com.meshdrop.ui.components.PeerRow
import com.meshdrop.ui.components.SectionLabel
import com.meshdrop.ui.components.StatusChip
import com.meshdrop.ui.components.TransferJobCard

/**
 * Screen 2 of 3 — pick documents, pick a device, watch it go.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendScreen(
    viewModel: SendViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val peers by viewModel.peers.collectAsStateWithLifecycle()
    val activeJob by viewModel.activeJob.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Storage Access Framework picker: gives us content Uris, so no storage permission.
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> viewModel.onFilesPicked(uris) }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeError()
        }
    }
    LaunchedEffect(uiState.lastResult) {
        uiState.lastResult?.let { result ->
            snackbarHostState.showSnackbar(result.message ?: "Transfer ${result.status.name.lowercase()}")
            viewModel.dismissResult()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Send") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Close, contentDescription = "Back")
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
                SectionLabel(text = "1 · Choose files")
                FilePickerCard(
                    files = uiState.selectedFiles,
                    totalBytes = uiState.totalBytes,
                    onPick = { filePicker.launch(arrayOf("*/*")) },
                    onClear = viewModel::clearFiles,
                    onRemove = viewModel::removeFile,
                )
            }

            item {
                SectionLabel(text = "2 · Choose a device (${peers.size})")
            }

            if (peers.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Default.WifiOff,
                        title = "Nobody nearby yet",
                        subtitle = "Open MeshDrop on the other device and make sure both are on the same Wi-Fi network.",
                    )
                }
            } else {
                items(items = peers, key = { it.id }) { peer ->
                    PeerRow(
                        peer = peer,
                        selected = uiState.selectedPeer?.id == peer.id,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 5.dp),
                        onClick = { viewModel.selectPeer(peer) },
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.size(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = viewModel::send,
                        enabled = uiState.canSend,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Send, contentDescription = null)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(if (uiState.isSending) "Sending…" else "Send ${Format.bytes(uiState.totalBytes)}")
                    }
                }
                uiState.selectedPeer?.let { peer ->
                    Text(
                        text = "→ ${peer.name}",
                        modifier = Modifier.padding(horizontal = 20.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            activeJob?.let { job ->
                item {
                    SectionLabel(text = "3 · Progress")
                    TransferJobCard(
                        job = job,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 5.dp),
                        onCancel = viewModel::cancel,
                    )
                }
            }

            uiState.lastResult?.let { result ->
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = result.message ?: when (result.status) {
                                    com.meshdrop.core.model.TransferStatus.COMPLETED ->
                                        "${result.fileCount} file(s) delivered"
                                    com.meshdrop.core.model.TransferStatus.REJECTED -> "The receiver declined"
                                    com.meshdrop.core.model.TransferStatus.CANCELLED -> "Cancelled"
                                    else -> "Transfer failed"
                                },
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            StatusChip(status = result.status)
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.size(24.dp)) }
        }
    }
}

@Composable
private fun FilePickerCard(
    files: List<com.meshdrop.core.model.TransferFile>,
    totalBytes: Long,
    onPick: () -> Unit,
    onClear: () -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (files.isEmpty()) {
                Text(
                    text = "No files selected",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                files.forEach { file ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = file.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = if (file.sizeBytes > 0L) Format.bytes(file.sizeBytes) else "unknown size",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onRemove(file.id) }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Remove ${file.name}",
                            )
                        }
                    }
                }
                Text(
                    text = "${files.size} file(s) · ${Format.bytes(totalBytes)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onPick) { Text("Choose files") }
                if (files.isNotEmpty()) {
                    OutlinedButton(onClick = onClear) { Text("Clear") }
                }
            }
        }
    }
}
