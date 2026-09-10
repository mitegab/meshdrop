package com.meshdrop.ui.history

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshdrop.core.model.TransferDirection
import com.meshdrop.core.model.TransferJob
import com.meshdrop.core.model.TransferStatus
import com.meshdrop.core.util.Format
import com.meshdrop.ui.components.FileThumb
import com.meshdrop.ui.components.StatusChip
import com.meshdrop.ui.components.SwipeAction
import com.meshdrop.ui.components.SwipeRow
import com.meshdrop.ui.components.TrustBadge
import com.meshdrop.ui.components.deviceColor

/**
 * History — a day-grouped feed of what moved, with where it came from.
 *
 * Every row carries provenance: who sent it, whether that device is verified, and whether the
 * bytes survived the trip (the integrity tick comes from the AEAD tags, not from a hope).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

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
                title = { Text("History") },
                actions = {
                    if (groups.isNotEmpty()) {
                        TextButton(onClick = viewModel::clearAll) { Text("Clear") }
                    }
                },
            )
        },
    ) { innerPadding ->
        if (groups.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(48.dp),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text("Nothing here yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Files you send and receive in this session show up here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            groups.forEach { day ->
                item(key = "day-${day.label}") {
                    Text(
                        text = day.label,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                items(items = day.jobs, key = { it.id }) { job ->
                    val security = remember(job.id, groups) { viewModel.securityFor(job.id) }
                    HistoryRow(
                        job = job,
                        security = security,
                        onOpen = { uri ->
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                )
                            }
                        },
                        onShare = { uri ->
                            runCatching {
                                context.startActivity(
                                    Intent.createChooser(
                                        Intent(Intent.ACTION_SEND).apply {
                                            type = "*/*"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        },
                                        "Re-share",
                                    ),
                                )
                            }
                        },
                        onDelete = { viewModel.delete(job) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun HistoryRow(
    job: TransferJob,
    security: com.meshdrop.core.model.SecurityState,
    onOpen: (Uri) -> Unit,
    onShare: (Uri) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = deviceColor(job.peerId.ifBlank { job.peerName })
    val received = job.direction == TransferDirection.RECEIVE
    val firstUri = job.files.firstNotNullOfOrNull { it.localUri }?.let(Uri::parse)

    SwipeRow(
        modifier = modifier.height(84.dp),
        actions = listOf(
            SwipeAction(
                icon = Icons.Default.Share,
                label = "Re-share",
                tint = MaterialTheme.colorScheme.primary,
                onClick = { firstUri?.let(onShare) },
            ),
            SwipeAction(
                icon = Icons.Default.Delete,
                label = "Delete",
                tint = MaterialTheme.colorScheme.error,
                onClick = onDelete,
            ),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .clickable(enabled = firstUri != null) { firstUri?.let(onOpen) }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(contentAlignment = Alignment.Center) {
                FileThumb(
                    uri = firstUri,
                    mimeType = job.files.firstOrNull()?.mimeType,
                    modifier = Modifier.size(48.dp),
                )
                if (job.files.size > 1) {
                    Text(
                        text = "+${job.files.size - 1}",
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = job.files.firstOrNull()?.name
                        ?: "${job.files.size} files",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (received) Icons.Default.Download else Icons.Default.Share,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (received) "from ${job.peerName}" else "to ${job.peerName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TrustBadge(security = security)
                    if (security.integrityConfirmed) {
                        Icon(
                            imageVector = Icons.Default.VerifiedUser,
                            contentDescription = "Integrity verified",
                            tint = Color(0xFF4ADE80),
                            modifier = Modifier.size(12.dp),
                        )
                        Text(
                            text = "integrity checked",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF4ADE80),
                        )
                    }
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = Format.bytes(job.totalBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                StatusChip(status = job.status)
            }
        }
    }
}
