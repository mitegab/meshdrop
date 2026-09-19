package com.meshdrop.ui.drop

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.meshdrop.core.model.PeerDevice
import com.meshdrop.core.model.TransferDirection
import com.meshdrop.core.model.TransferFile
import com.meshdrop.core.model.TransferJob
import com.meshdrop.core.model.TransferStatus
import com.meshdrop.core.util.Format
import com.meshdrop.core.util.SmoothedReading
import com.meshdrop.service.TransferForegroundService
import com.meshdrop.transfer.state.TransferSessionState
import com.meshdrop.ui.components.BreathingDot
import com.meshdrop.ui.components.DashedPanel
import com.meshdrop.ui.components.Eyebrow
import com.meshdrop.ui.components.FileThumb
import com.meshdrop.ui.components.Hairline
import com.meshdrop.ui.components.LiveTransferCard
import com.meshdrop.ui.components.MeshButton
import com.meshdrop.ui.components.MeshButtonTone
import com.meshdrop.ui.components.PaperCard
import com.meshdrop.ui.components.QuietNote
import com.meshdrop.ui.components.SectionHeading
import com.meshdrop.ui.components.TextAction
import com.meshdrop.ui.components.TransferReceipt
import com.meshdrop.ui.components.WaitingCard
import com.meshdrop.ui.components.deviceColor
import com.meshdrop.ui.components.deviceInitials
import com.meshdrop.ui.theme.MeshText
import com.meshdrop.ui.theme.MeshTheme
import com.meshdrop.ui.theme.Space

/**
 * The Drop screen: one page that answers "who is around, what am I sending, and is anything
 * moving right now".
 *
 * This replaces three screens (Home, Send, Receive) with one surface and four sheets. The
 * reasoning is in `design/DESIGN.md`, but the short version is that a file drop is a single
 * act and the old split forced the user to know *before* they started whether they were the
 * sender or the receiver — which nobody knows until the other person answers.
 *
 * Reading order is fixed and always the same, so the screen is familiar before it is read:
 *
 *  1. the header and the one-line status of this phone,
 *  2. whatever is happening now (a transfer, or a receipt, or nothing),
 *  3. the tray — the thing being sent,
 *  4. the people nearby,
 *  5. what moved recently.
 */
@Composable
fun DropScreen(
    viewModel: DropViewModel,
    onOpenActivity: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    missingPermissions: List<String> = emptyList(),
    onRequestPermissions: () -> Unit = {},
) {
    val colors = MeshTheme.colors
    val context = LocalContext.current

    val deviceName by viewModel.deviceName.collectAsStateWithLifecycle()
    val peers by viewModel.peers.collectAsStateWithLifecycle()
    val latencies by viewModel.latencies.collectAsStateWithLifecycle()
    val discovery by viewModel.discovery.collectAsStateWithLifecycle()
    val live by viewModel.live.collectAsStateWithLifecycle()
    val offers by viewModel.offers.collectAsStateWithLifecycle()
    val receipt by viewModel.receipt.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val now by viewModel.now.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> viewModel.onFilesPicked(uris) }

    // Receiving is the default state of this screen: opening the app is what makes the phone
    // reachable, so the foreground service starts here rather than behind a "Receive" button.
    LaunchedEffect(Unit) {
        TransferForegroundService.start(context)
        viewModel.ensureStarted()
    }

    LaunchedEffect(ui.message) {
        ui.message?.let { message ->
            snackbar.showSnackbar(message)
            viewModel.consumeMessage()
        }
    }

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
                    Header(
                        deviceName = deviceName,
                        onOpenActivity = onOpenActivity,
                        onOpenSettings = onOpenSettings,
                    )
                }

                item(key = "status") {
                    Gutter {
                        StatusRail(
                            deviceName = deviceName,
                            peerCount = peers.size,
                            receiving = ui.receiving,
                            scanning = discovery.scanning,
                            onClick = viewModel::openConnection,
                        )
                    }
                }

                if (missingPermissions.isNotEmpty()) {
                    item(key = "permissions") {
                        Gutter {
                            PermissionNote(onRequest = onRequestPermissions)
                        }
                    }
                }

                items(items = live, key = { it.batchIdOrNull ?: "live" }) { session ->
                    Gutter {
                        val isWatched = session.batchIdOrNull == ui.activeJobId
                        when (session) {
                            is TransferSessionState.Active -> LiveTransferCard(
                                state = session,
                                reading = if (isWatched) ui.reading else SmoothedReading.IDLE,
                                queueLabel = if (isWatched) ui.queueLabel else null,
                                detailsExpanded = isWatched && ui.detailsExpanded,
                                onToggleDetails = viewModel::toggleDetails,
                                onCancel = { viewModel.cancel(session.batchId) },
                                throughputSamples = if (isWatched) ui.samples else emptyList(),
                                throughputPeak = if (isWatched) ui.peak else 1L,
                            )

                            is TransferSessionState.AwaitingApproval -> WaitingCard(
                                state = session,
                                onCancel = { viewModel.cancel(session.batchId) },
                            )

                            else -> Unit
                        }
                    }
                }

                receipt?.let { finished ->
                    item(key = "receipt-${finished.batchId}") {
                        Gutter {
                            TransferReceipt(
                                state = finished,
                                onDone = { viewModel.dismissReceipt(finished.batchId) },
                                onRetry = { viewModel.retry(finished.batchId) },
                            )
                        }
                    }
                }

                item(key = "tray") {
                    Gutter {
                        TraySection(
                            staged = ui.staged,
                            stagedBytes = ui.stagedBytes,
                            peer = ui.peer,
                            onAdd = viewModel::openPicker,
                            onRemove = viewModel::removeFile,
                            onClear = viewModel::clearTray,
                            onSend = viewModel::send,
                        )
                    }
                }

                item(key = "people-heading") {
                    Gutter {
                        SectionHeading(
                            title = "Nearby",
                            eyebrow = "Send to",
                            caption = when {
                                !ui.receiving -> "Receiving is paused, so nobody can see this phone."
                                peers.isEmpty() && discovery.scanning -> "Looking for phones on this Wi-Fi…"
                                peers.isEmpty() -> "No phones yet. Open MeshDrop on the other device."
                                else -> "Tap a phone to send to it."
                            },
                            action = if (peers.isNotEmpty()) "Refresh" else null,
                            onAction = viewModel::refresh,
                            modifier = Modifier.padding(top = Space.block),
                        )
                    }
                }

                if (peers.isEmpty()) {
                    item(key = "people-empty") {
                        Gutter {
                            QuietNote(
                                headline = if (discovery.scanning) "Looking around" else "Nobody here yet",
                                body = "Both phones need to be on the same Wi-Fi network, with MeshDrop open. " +
                                    "A hotspot works too — one phone shares, the other joins.",
                            )
                        }
                    }
                } else {
                    items(items = peers, key = { it.id }) { peer ->
                        Gutter {
                            PersonRow(
                                peer = peer,
                                latencyMillis = latencies[peer.id],
                                onClick = { viewModel.choosePeer(peer) },
                            )
                        }
                    }
                }

                discovery.firstError?.let { error ->
                    item(key = "discovery-error") {
                        Gutter {
                            Spacer(modifier = Modifier.height(Space.sm))
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.danger,
                            )
                        }
                    }
                }

                if (recent.any { it.status.isFinished }) {
                    item(key = "recent-heading") {
                        Gutter {
                            SectionHeading(
                                title = "Recent",
                                caption = "Everything else is in Activity.",
                                action = "See all",
                                onAction = onOpenActivity,
                                modifier = Modifier.padding(top = Space.block),
                            )
                        }
                    }
                    items(
                        items = recent.filter { it.status.isFinished }
                            .sortedByDescending { it.finishedAtMillis ?: it.startedAtMillis }
                            .take(3),
                        key = { "recent-${it.id}" },
                    ) { job ->
                        Gutter { RecentRow(job = job, onClick = onOpenActivity) }
                    }
                }
            }
        }

        // region sheets ----------------------------------------------------------------------
        // One at a time, and only ever over the Drop screen, so there is no navigation stack
        // to unwind: dismissing a sheet puts the user back exactly where they were.
        MeshSheet(visible = ui.pickerOpen && offers.isEmpty(), onDismiss = viewModel::closePicker) {
            PickSheet(
                onPick = { mime -> picker.launch(arrayOf(mime)) },
                onDismiss = viewModel::closePicker,
            )
        }

        MeshSheet(visible = ui.connectionOpen, onDismiss = viewModel::closeConnection) {
            ConnectionSheet(
                deviceName = deviceName,
                port = viewModel.localPort,
                peerCount = peers.size,
                receiving = ui.receiving,
                onToggleReceiving = { enabled ->
                    viewModel.setReceiving(enabled)
                    if (enabled) {
                        TransferForegroundService.start(context)
                    } else {
                        TransferForegroundService.stop(context)
                    }
                },
                onRename = {
                    viewModel.closeConnection()
                    viewModel.startRenaming()
                },
                onRefresh = viewModel::refresh,
                onDismiss = viewModel::closeConnection,
            )
        }

        val offer = offers.firstOrNull()
        MeshSheet(visible = offer != null, onDismiss = { offer?.let { viewModel.reject(it.batchId, it.peerName) } }) {
            if (offer != null) {
                OfferSheet(
                    offer = offer,
                    nowMillis = now,
                    trustExpanded = ui.trustExpandedBatchId == offer.batchId,
                    onToggleTrust = { viewModel.toggleTrust(offer.batchId) },
                    onConfirmCode = { viewModel.confirmSafetyCode(offer.batchId, offer.peerName) },
                    onAccept = { viewModel.approve(offer.batchId) },
                    onDecline = { viewModel.reject(offer.batchId, offer.peerName) },
                )
            }
        }
        // endregion
    }

    if (ui.isRenaming) {
        RenameDialog(
            draft = ui.nameDraft,
            onDraftChange = viewModel::updateNameDraft,
            onConfirm = viewModel::commitRename,
            onDismiss = viewModel::cancelRenaming,
        )
    }
}

/** The page margin, applied per item so the list can still scroll edge to edge. */
@Composable
private fun Gutter(content: @Composable () -> Unit) {
    Box(modifier = Modifier.padding(horizontal = Space.gutter)) { content() }
}

/**
 * The masthead.
 *
 * A wordmark, and two quiet doors out of this screen. There is no bottom navigation bar on
 * purpose: this app has one job and two reference screens, and a permanent tab bar would give
 * settings the same visual weight as sending a file.
 */
@Composable
private fun Header(
    deviceName: String,
    onOpenActivity: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = Space.gutter).padding(top = Space.xl)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow(text = "MeshDrop")
            Spacer(modifier = Modifier.weight(1f))
            IconAction(
                icon = Icons.Outlined.History,
                contentDescription = "Activity",
                onClick = onOpenActivity,
            )
            Spacer(modifier = Modifier.width(Space.xs))
            IconAction(
                icon = Icons.Outlined.Settings,
                contentDescription = "Settings",
                onClick = onOpenSettings,
            )
        }
        Spacer(modifier = Modifier.height(Space.sm))
        Text(
            text = "Drop",
            style = MaterialTheme.typography.displayMedium,
            color = MeshTheme.colors.ink,
        )
        Spacer(modifier = Modifier.height(Space.xs))
        Text(
            text = "You are $deviceName on this network.",
            style = MaterialTheme.typography.bodyMedium,
            color = MeshTheme.colors.inkMuted,
        )
    }
}

@Composable
private fun IconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
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

/**
 * One honest line about this phone's place on the network, and a way into the plumbing.
 *
 * "Listening" is a state, not a badge: it carries a breathing dot while the receiver is open,
 * a warning dot when it is paused, and it is the only place on the screen that says the phone
 * can be reached at all.
 */
@Composable
private fun StatusRail(
    deviceName: String,
    peerCount: Int,
    receiving: Boolean,
    scanning: Boolean,
    onClick: () -> Unit,
) {
    val colors = MeshTheme.colors
    PaperCard(color = colors.subtle, onClick = onClick, padding = Space.lg) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BreathingDot(
                color = if (receiving) colors.success else colors.warning,
                size = 9.dp,
                breathing = receiving && scanning,
            )
            Spacer(modifier = Modifier.width(Space.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (receiving) "Listening" else "Paused",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.ink,
                )
                Spacer(modifier = Modifier.height(Space.hair))
                Text(
                    text = when {
                        !receiving -> "Nobody can send to $deviceName right now."
                        peerCount == 0 -> "Visible as $deviceName · looking for phones"
                        else -> "Visible as $deviceName · $peerCount nearby"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkMuted,
                )
            }
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = colors.inkMuted,
            )
        }
    }
}

/**
 * The tray: files waiting to go.
 *
 * This is the part of the app that gets to be beautiful, because it is the moment before a
 * transfer and it is where the user decides whether this whole thing feels trustworthy. A
 * dashed edge says "put something here"; the files land inside it as real rows with real
 * thumbnails and their real sizes; and the single clay button says exactly where they will go.
 */
@Composable
private fun TraySection(
    staged: List<TransferFile>,
    stagedBytes: Long,
    peer: PeerDevice?,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
    onSend: () -> Unit,
) {
    val colors = MeshTheme.colors

    SectionHeading(
        title = "The tray",
        eyebrow = "What you are sending",
        caption = when {
            staged.isEmpty() -> "Files stay on this phone until the other end accepts them."
            peer == null -> "Now pick a phone below."
            else -> "Ready for ${peer.name}."
        },
        modifier = Modifier.padding(top = Space.block),
    )

    Spacer(modifier = Modifier.height(Space.md))

    PaperCard {
        if (staged.isEmpty()) {
            DashedPanel {
                Text(
                    text = "Nothing in here yet",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.ink,
                )
                Spacer(modifier = Modifier.height(Space.xs))
                Text(
                    text = "Photos, videos, or any file at all.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkMuted,
                )
                Spacer(modifier = Modifier.height(Space.lg))
                MeshButton(
                    text = "Choose files",
                    onClick = onAdd,
                    icon = Icons.Outlined.Add,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            staged.forEach { file ->
                TrayFileRow(file = file, onRemove = { onRemove(file.id) })
            }

            Spacer(modifier = Modifier.height(Space.sm))
            Hairline()
            Spacer(modifier = Modifier.height(Space.sm))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${FileCategory.describe(staged)} · ${Format.bytes(stagedBytes)}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkMuted,
                )
                TextAction(text = "Add more", onClick = onAdd)
                TextAction(text = "Clear", onClick = onClear, color = colors.inkMuted)
            }

            Spacer(modifier = Modifier.height(Space.md))

            MeshButton(
                text = if (peer != null) "Send to ${peer.name}" else "Pick a phone first",
                onClick = onSend,
                enabled = peer != null,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun TrayFileRow(file: TransferFile, onRemove: () -> Unit) {
    val colors = MeshTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileThumb(
            uri = file.localUri?.let { android.net.Uri.parse(it) },
            mimeType = file.mimeType,
            modifier = Modifier.size(36.dp),
        )
        Spacer(modifier = Modifier.width(Space.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = Format.bytes(file.sizeBytes),
                style = MeshText.Reading,
                color = colors.inkMuted,
            )
        }
        Spacer(modifier = Modifier.width(Space.sm))
        IconAction(
            icon = Icons.Outlined.Close,
            contentDescription = "Remove ${file.name}",
            onClick = onRemove,
        )
    }
}

/**
 * A nearby phone.
 *
 * The avatar is tinted from the device id, so the same phone is the same colour every time
 * without storing anything, and the caption carries the only two facts that change a
 * decision: how it was found, and how close it is.
 */
@Composable
private fun PersonRow(
    peer: PeerDevice,
    latencyMillis: Long?,
    onClick: () -> Unit,
) {
    val colors = MeshTheme.colors
    val tint = deviceColor(peer.id)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = AVATAR_WASH)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = deviceInitials(peer.name),
                style = MaterialTheme.typography.titleMedium,
                color = tint,
            )
        }
        Spacer(modifier = Modifier.width(Space.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = peer.name,
                style = MaterialTheme.typography.titleMedium,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(Space.hair))
            Text(
                text = caption(peer, latencyMillis),
                style = MaterialTheme.typography.bodySmall,
                color = if (peer.isReachable()) colors.inkMuted else colors.warning,
            )
        }
        Icon(
            imageVector = Icons.Outlined.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = colors.inkMuted,
        )
    }
}

private fun caption(peer: PeerDevice, latencyMillis: Long?): String {
    if (!peer.isReachable()) return if (peer.kind == com.meshdrop.core.model.DiscoveryKind.WIFI_DIRECT)
        "Wi-Fi Direct · tap to connect" else "${peer.kind.label} · finding an address"
    val latency = latencyMillis?.let { "$it ms" }
    return listOfNotNull(peer.transferLane.shortLabel, latency, "tap to send").joinToString(" · ")
}

/** One finished transfer, on its way to the full Activity feed. */
@Composable
private fun RecentRow(job: TransferJob, onClick: () -> Unit) {
    val colors = MeshTheme.colors
    val (icon, tint) = statusGlyph(job.status)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(colors.subtle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (job.direction == TransferDirection.SEND) {
                    Icons.Outlined.Send
                } else {
                    Icons.Outlined.FileDownload
                },
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = colors.inkMuted,
            )
        }
        Spacer(modifier = Modifier.width(Space.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (job.direction == TransferDirection.SEND) {
                    "To ${job.peerName}"
                } else {
                    "From ${job.peerName}"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(Space.hair))
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
        Spacer(modifier = Modifier.width(Space.sm))
        Icon(
            imageVector = icon,
            contentDescription = job.status.name.lowercase(),
            modifier = Modifier.size(17.dp),
            tint = tint,
        )
    }
}

/** The glyph and colour for a finished transfer: always paired, never colour alone. */
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

/**
 * The permissions note.
 *
 * Not a red card: a missing permission is a normal step, not a failure, and the copy says
 * exactly what the permission is for and what stops working without it.
 */
@Composable
private fun PermissionNote(onRequest: () -> Unit) {
    val colors = MeshTheme.colors
    PaperCard(color = colors.subtle, padding = Space.lg, modifier = Modifier.padding(top = Space.lg)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.WifiOff,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = colors.warning,
            )
            Spacer(modifier = Modifier.width(Space.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Some optional access is off",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.ink,
                )
                Spacer(modifier = Modifier.height(Space.hair))
                Text(
                    text = "Nearby-device access enables additional discovery methods; notifications show incoming offers. " +
                        "LAN transfers can still work without these permissions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkMuted,
                )
            }
        }
        Spacer(modifier = Modifier.height(Space.md))
        MeshButton(
            text = "Grant access",
            onClick = onRequest,
            tone = MeshButtonTone.Quiet,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** How much of a device's tint shows through behind its initials. */
private const val AVATAR_WASH = 0.12f
