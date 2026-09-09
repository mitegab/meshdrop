package com.meshdrop.ui.receive

import android.content.Intent
import android.widget.Toast
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshdrop.core.model.IncomingRequest
import com.meshdrop.core.model.TransferFile
import com.meshdrop.core.model.TransferStatus
import com.meshdrop.core.util.Format
import com.meshdrop.net.LocalNetworkInfo
import com.meshdrop.ui.components.EmptyState
import com.meshdrop.ui.components.FileRow
import com.meshdrop.ui.components.SectionLabel
import com.meshdrop.ui.components.TransferJobCard

/**
 * Screen 3 of 3 — stay visible, accept or reject what arrives, watch it land in Downloads.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveScreen(
    viewModel: ReceiveViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val deviceName by viewModel.deviceName.collectAsStateWithLifecycle()
    val incoming by viewModel.incoming.collectAsStateWithLifecycle()
    val jobs by viewModel.activeJobs.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

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
                title = { Text("Receive") },
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
                VisibilityCard(deviceName = deviceName, port = viewModel.transferPort)
            }

            if (incoming.isNotEmpty()) {
                item { SectionLabel(text = "Incoming requests") }
                items(items = incoming, key = { it.batchId }) { request ->
                    IncomingRequestCard(
                        request = request,
                        onAccept = { viewModel.approve(request) },
                        onReject = { viewModel.reject(request) },
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    )
                }
            }

            val active = jobs.filter { it.isActive }
            if (active.isNotEmpty()) {
                item { SectionLabel(text = "Receiving") }
                items(items = active, key = { it.id }) { job ->
                    TransferJobCard(
                        job = job,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 5.dp),
                        onCancel = { viewModel.cancel(job.id) },
                    )
                }
            }

            val finished = jobs.filter { it.status.isFinished }
            if (incoming.isEmpty() && active.isEmpty() && finished.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Default.Downloading,
                        title = "Waiting for a transfer",
                        subtitle = "Keep this screen open. When someone nearby sends you files, you will be asked to accept them.",
                    )
                }
            }

            if (finished.isNotEmpty()) {
                item {
                    SectionLabel(
                        text = "Received",
                        trailing = {
                            Text(
                                text = "Clear",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        },
                    )
                }
                items(items = finished.reversed(), key = { it.id }) { job ->
                    ReceivedJobCard(
                        job = job,
                        onOpen = { file ->
                            val uri = file.localUri
                            if (uri.isNullOrBlank()) {
                                Toast.makeText(context, "This file is not on this device", Toast.LENGTH_SHORT).show()
                                return@ReceivedJobCard
                            }
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(android.net.Uri.parse(uri), file.mimeType ?: "*/*")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            runCatching { context.startActivity(intent) }
                                .onFailure {
                                    Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
                                }
                        },
                    )
                }
            }

            item { Spacer(modifier = Modifier.size(24.dp)) }
        }
    }
}

@Composable
private fun VisibilityCard(
    deviceName: String,
    port: Int,
    modifier: Modifier = Modifier,
) {
    val address = remember { LocalNetworkInfo.localIpAddress()?.hostAddress }
    OutlinedCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Download,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Visible as $deviceName",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (port > 0) {
                        "Listening on ${address ?: "this device"}:$port"
                    } else {
                        "Receiver is starting…"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun IncomingRequestCard(
    request: IncomingRequest,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "${request.senderName} wants to send you files",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "${request.files.size} file(s) · ${Format.bytes(request.totalBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
            )
            request.files.forEach { file ->
                FileRow(
                    file = file,
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onAccept) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("Accept")
                }
                OutlinedButton(onClick = onReject) {
                    Icon(Icons.Default.Close, contentDescription = null)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("Reject")
                }
            }
        }
    }
}

@Composable
private fun ReceivedJobCard(
    job: com.meshdrop.core.model.TransferJob,
    onOpen: (TransferFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 5.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "From ${job.peerName}",
                style = MaterialTheme.typography.titleSmall,
            )
            job.files.forEach { file ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = file.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = when (file.status) {
                                TransferStatus.COMPLETED -> Format.bytes(file.sizeBytes)
                                else -> file.status.name.lowercase().replaceFirstChar { it.uppercaseChar() }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (file.status == TransferStatus.COMPLETED) {
                        IconButton(onClick = { onOpen(file) }) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = "Open ${file.name}",
                            )
                        }
                    }
                }
            }
        }
    }
}
