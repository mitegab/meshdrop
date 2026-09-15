package com.meshdrop.ui.activity

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshdrop.core.model.FileCategory
import com.meshdrop.core.model.SecurityState
import com.meshdrop.core.model.TransferDirection
import com.meshdrop.core.model.TransferJob
import com.meshdrop.core.model.TransferStatus
import com.meshdrop.core.model.TrustLevel
import com.meshdrop.core.util.Format
import com.meshdrop.ui.components.Hairline
import com.meshdrop.ui.components.MeshButton
import com.meshdrop.ui.components.MeshButtonTone
import com.meshdrop.ui.components.MeshTextField
import com.meshdrop.ui.components.PaperCard
import com.meshdrop.ui.components.QuietNote
import com.meshdrop.ui.components.SheetGrip
import com.meshdrop.ui.components.TextAction
import com.meshdrop.ui.components.TrustGlyph
import com.meshdrop.ui.drop.MeshSheet
import com.meshdrop.ui.history.HistoryFiltering
import com.meshdrop.ui.history.HistoryOrder
import com.meshdrop.ui.history.HistoryScope
import com.meshdrop.ui.theme.MeshShapes
import com.meshdrop.ui.theme.MeshText
import com.meshdrop.ui.theme.MeshTheme
import com.meshdrop.ui.theme.Space

/**
 * Activity: everything that has moved, and what can be done with it now.
 *
 * The old History screen was a list you could only operate by swiping blind — actions you had
 * to know about in advance, on rows that gave no sign they were tappable. This one keeps the
 * timeline and the day groupings (they were right) and adds the three things a list of files
 * needs if it is going to be used rather than admired: a search field, filters that say what
 * they filter, and a detail sheet that shows provenance and actions in words.
 */
@Composable
fun ActivityScreen(
    viewModel: ActivityViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MeshTheme.colors
    val context = LocalContext.current
    val sections by viewModel.sections.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val vaulted by viewModel.vaultedJobs.collectAsStateWithLifecycle()
    val emptyReason by viewModel.emptyReason.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var confirmingDelete by remember { mutableStateOf<TransferJob?>(null) }
    var sortOpen by remember { mutableStateOf(false) }

    // Anything that touches the vault goes through the device lock first.
    var guardedAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val unlockLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) guardedAction?.invoke()
        guardedAction = null
    }

    fun requireUnlock(action: () -> Unit) {
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val intent = keyguard?.createConfirmDeviceCredentialIntent(
            "Unlock the vault",
            "Confirm your device lock to use files in MeshDrop's vault",
        )
        if (intent != null) {
            guardedAction = action
            unlockLauncher.launch(intent)
        } else {
            // No lock screen set up: the vault is still private storage, so carry on.
            action()
        }
    }

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    val detail = sections.flatMap { it.jobs }.firstOrNull { it.id == uiState.detailJobId }

    Box(modifier = modifier.fillMaxSize().background(colors.canvas)) {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbar) },
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(bottom = Space.xxl),
            ) {
                item(key = "header") {
                    Column(modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.xl)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconAction(
                                icon = Icons.Outlined.ArrowBack,
                                contentDescription = "Back",
                                onClick = onBack,
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            if (uiState.query.isActive) {
                                IconAction(
                                    icon = Icons.Outlined.Close,
                                    contentDescription = "Clear filters",
                                    onClick = viewModel::clearQuery,
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(Space.sm))
                        Text(
                            text = "Activity",
                            style = MaterialTheme.typography.displayMedium,
                            color = colors.ink,
                        )
                        Spacer(modifier = Modifier.height(Space.xs))
                        Text(
                            text = "Everything sent and received on this phone, newest first.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.inkMuted,
                        )
                    }
                }

                item(key = "search") {
                    Column(modifier = Modifier.padding(horizontal = Space.gutter)) {
                        MeshTextField(
                            value = uiState.query.text,
                            onValueChange = viewModel::setSearchText,
                            placeholder = "Search a file or a phone",
                            leadingIcon = Icons.Outlined.Search,
                        )
                        Spacer(modifier = Modifier.height(Space.md))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(Space.sm),
                            ) {
                                HistoryScope.CHIPS.forEach { scope ->
                                    FilterPill(
                                        label = scope.label,
                                        selected = uiState.query.scope == scope,
                                        onClick = { viewModel.setScope(scope) },
                                    )
                                }
                            }
                            Box {
                                TextAction(
                                    text = uiState.query.order.label,
                                    onClick = { sortOpen = true },
                                    color = colors.inkMuted,
                                )
                                DropdownMenu(
                                    expanded = sortOpen,
                                    onDismissRequest = { sortOpen = false },
                                ) {
                                    HistoryOrder.entries.forEach { order ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = order.label,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = if (order == uiState.query.order) {
                                                        colors.clay
                                                    } else {
                                                        colors.ink
                                                    },
                                                )
                                            },
                                            onClick = {
                                                viewModel.setOrder(order)
                                                sortOpen = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(Space.lg))
                    }
                }

                if (sections.isEmpty()) {
                    item(key = "empty") {
                        Column(modifier = Modifier.padding(horizontal = Space.gutter)) {
                            QuietNote(
                                headline = if (emptyReason == null || uiState.query.isActive) {
                                    "Nothing matches that"
                                } else {
                                    "Nothing has moved yet"
                                },
                                body = HistoryFiltering.emptyMessage(emptyReason, uiState.query)
                                    ?: "Files you send and receive will be listed here, with who they " +
                                    "came from and whether they arrived intact.",
                                action = if (uiState.query.isActive) {
                                    { TextAction(text = "Clear filters", onClick = viewModel::clearQuery) }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }

                sections.forEach { day ->
                    item(key = "day-${day.label}") {
                        Column(modifier = Modifier.padding(horizontal = Space.gutter)) {
                            Spacer(modifier = Modifier.height(Space.lg))
                            Text(
                                text = day.label,
                                style = MaterialTheme.typography.headlineSmall,
                                color = colors.ink,
                            )
                            Spacer(modifier = Modifier.height(Space.sm))
                            Hairline()
                        }
                    }

                    items(items = day.jobs, key = { it.id }) { job ->
                        Column(modifier = Modifier.padding(horizontal = Space.gutter)) {
                            ActivityRow(
                                job = job,
                                security = viewModel.securityFor(job.id),
                                onClick = { viewModel.openDetail(job.id) },
                            )
                        }
                    }
                }
            }
        }

        MeshSheet(visible = detail != null, onDismiss = viewModel::closeDetail) {
            if (detail != null) {
                val uri = viewModel.uriFor(detail)
                val security = viewModel.securityFor(detail.id)
                val inVault = detail.id in vaulted
                TransferDetailSheet(
                    job = detail,
                    uri = uri,
                    security = security,
                    vaulted = inVault,
                    onOpen = {
                        if (uri != null) {
                            if (inVault) requireUnlock { openFile(context, uri) } else openFile(context, uri)
                        }
                    },
                    onShare = {
                        if (uri != null) {
                            if (inVault) requireUnlock { shareFile(context, uri) } else shareFile(context, uri)
                        }
                    },
                    onVault = { requireUnlock { viewModel.moveToVault(detail) } },
                    onDelete = { confirmingDelete = detail },
                    onDismiss = viewModel::closeDetail,
                )
            }
        }
    }

    confirmingDelete?.let { job ->
        DeleteDialog(
            target = job,
            onConfirm = {
                viewModel.delete(job)
                confirmingDelete = null
            },
            onDismiss = { confirmingDelete = null },
        )
    }
}

/**
 * A row in the timeline: what moved, with whom, how big, how long ago — and, in the meta line,
 * the two trust states worth interrupting for.
 */
@Composable
private fun ActivityRow(
    job: TransferJob,
    security: SecurityState,
    onClick: () -> Unit,
) {
    val colors = MeshTheme.colors
    val received = job.direction == TransferDirection.RECEIVE
    val (icon, tint) = statusGlyph(job.status)
    val showTrustMark = security.trustLevel != TrustLevel.UNVERIFIED

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(MeshShapes.small)
                .background(colors.subtle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (received) Icons.Outlined.FileDownload else Icons.Outlined.Send,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
                tint = colors.inkMuted,
            )
        }

        Spacer(modifier = Modifier.width(Space.md))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (received) "From ${job.peerName}" else "To ${job.peerName}",
                style = MaterialTheme.typography.titleMedium,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(Space.hair))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showTrustMark) {
                    TrustGlyph(security = security, size = 12.dp)
                    Spacer(modifier = Modifier.width(Space.xs))
                }
                Text(
                    text = listOf(
                        FileCategory.describe(job.files),
                        Format.bytes(job.totalBytes),
                        Format.relative(job.finishedAtMillis ?: job.startedAtMillis),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(modifier = Modifier.width(Space.sm))

        Icon(
            imageVector = icon,
            contentDescription = job.status.name.lowercase(),
            modifier = Modifier.size(17.dp),
            tint = tint,
        )
    }
}

/**
 * The detail sheet: provenance and actions, in words.
 *
 * This is where a file's history actually lives — which device sent it, whether that device is
 * one you checked, whether the bytes survived, and what you can do with it now (open, share,
 * hide it in the vault, delete it). Everything the swipe gesture used to hide is here, named.
 */
@Composable
private fun TransferDetailSheet(
    job: TransferJob,
    uri: Uri?,
    security: SecurityState,
    vaulted: Boolean,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onVault: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MeshTheme.colors
    val received = job.direction == TransferDirection.RECEIVE
    val ok = job.status == TransferStatus.COMPLETED

    SheetGrip()
    Spacer(modifier = Modifier.height(Space.lg))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Eyebrow(
            text = when {
                vaulted -> "In the vault"
                received -> "Received"
                else -> "Sent"
            },
            color = if (ok) colors.inkMuted else colors.danger,
        )
        Spacer(modifier = Modifier.weight(1f))
        TrustGlyph(security = security, size = 14.dp)
    }

    Spacer(modifier = Modifier.height(Space.sm))

    Text(
        text = job.peerName,
        style = MaterialTheme.typography.displaySmall,
        color = colors.ink,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(modifier = Modifier.height(Space.xs))
    Text(
        text = listOf(
            FileCategory.describe(job.files),
            Format.bytes(job.totalBytes),
            Format.relative(job.finishedAtMillis ?: job.startedAtMillis),
        ).joinToString(" · "),
        style = MaterialTheme.typography.bodyMedium,
        color = colors.inkMuted,
    )

    job.message?.takeIf { !ok }?.let { message ->
        Spacer(modifier = Modifier.height(Space.md))
        Text(text = message, style = MaterialTheme.typography.bodySmall, color = colors.danger)
    }

    Spacer(modifier = Modifier.height(Space.lg))
    Hairline()
    Spacer(modifier = Modifier.height(Space.sm))

    job.files.take(6).forEach { file ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = file.name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (file.sizeBytes > 0L) Format.bytes(file.sizeBytes) else "—",
                style = MeshText.Reading,
                color = colors.inkMuted,
            )
        }
    }

    if (job.files.size > 6) {
        Text(
            text = "and ${job.files.size - 6} more",
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkMuted,
        )
    }

    Spacer(modifier = Modifier.height(Space.lg))
    Hairline()
    Spacer(modifier = Modifier.height(Space.md))

    ProvenanceRow(
        label = "Security",
        value = when {
            security.integrityConfirmed -> "Encrypted, every chunk verified"
            security.isEncrypted -> "Encrypted end to end"
            else -> "Not encrypted"
        },
        tint = if (security.isEncrypted) colors.success else colors.danger,
    )
    ProvenanceRow(
        label = "Transport",
        value = job.lane.label + if (job.previousLanes.isNotEmpty()) " (after a switch)" else "",
        tint = colors.ink,
    )
    ProvenanceRow(label = "Batch", value = job.id.take(12), tint = colors.ink)

    Spacer(modifier = Modifier.height(Space.lg))

    MeshButton(
        text = when {
            uri == null -> "File is no longer on this phone"
            vaulted -> "Open from the vault"
            received -> "Open"
            else -> "Open a copy"
        },
        onClick = onOpen,
        enabled = uri != null,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(modifier = Modifier.height(Space.sm))

    Row(verticalAlignment = Alignment.CenterVertically) {
        TextAction(text = "Share", onClick = onShare, enabled = uri != null)
        TextAction(
            text = if (vaulted) "In the vault" else "Move to vault",
            onClick = onVault,
            enabled = !vaulted,
        )
        Spacer(modifier = Modifier.weight(1f))
        TextAction(text = "Delete", onClick = onDelete, color = colors.danger)
    }

    Spacer(modifier = Modifier.height(Space.xs))
    TextAction(text = "Close", onClick = onDismiss, color = colors.inkMuted)
}

@Composable
private fun ProvenanceRow(label: String, value: String, tint: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MeshTheme.colors.inkMuted,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(text = value, style = MeshText.Reading, color = tint)
    }
}

/** One filter, as a pill that fills when it is the active one. */
@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MeshTheme.colors
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) colors.clayWash else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = Space.md, vertical = Space.sm),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) colors.clay else colors.inkMuted,
        )
    }
}

@Composable
private fun IconAction(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(19.dp),
            tint = MeshTheme.colors.inkMuted,
        )
    }
}

@Composable
private fun Eyebrow(text: String, color: Color) {
    Text(text = text.uppercase(), style = MeshText.Eyebrow, color = color)
}

/** Deleting files is the one action in the app that asks first. */
@Composable
private fun DeleteDialog(target: TransferJob, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = MeshTheme.colors
    val received = target.direction == TransferDirection.RECEIVE
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        shape = MeshShapes.large,
        title = {
            Text(
                text = if (received) "Delete these files?" else "Forget this transfer?",
                style = MaterialTheme.typography.headlineSmall,
                color = colors.ink,
            )
        },
        text = {
            Text(
                text = if (received) {
                    "The files are removed from this phone. This cannot be undone — if you want " +
                        "to keep them, move them to the vault instead."
                } else {
                    "The files were already deleted when they were sent, unless you still have " +
                        "them. This only removes the row from Activity."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkMuted,
            )
        },
        confirmButton = {
            MeshButton(text = "Delete", onClick = onConfirm, tone = MeshButtonTone.Primary)
        },
        dismissButton = {
            TextAction(text = "Keep", onClick = onDismiss, color = colors.inkMuted)
        },
    )
}

@Composable
private fun statusGlyph(status: TransferStatus): Pair<ImageVector, Color> {
    val colors = MeshTheme.colors
    return when (status) {
        TransferStatus.COMPLETED -> Icons.Outlined.Check to colors.success
        TransferStatus.FAILED -> Icons.Outlined.Warning to colors.danger
        TransferStatus.REJECTED -> Icons.Outlined.Close to colors.danger
        TransferStatus.CANCELLED -> Icons.Outlined.Close to colors.inkMuted
        else -> Icons.Outlined.HourglassEmpty to colors.inkMuted
    }
}

private fun openFile(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(intent) }
}

private fun shareFile(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = context.contentResolver.getType(uri) ?: "*/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "Share")) }
}
