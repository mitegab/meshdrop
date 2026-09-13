package com.meshdrop.ui.receive

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshdrop.core.offer.OfferPolicy
import com.meshdrop.core.offer.OfferWindow
import com.meshdrop.core.util.Format
import com.meshdrop.transfer.state.TransferSessionState
import com.meshdrop.ui.components.ActiveTransferPanel
import com.meshdrop.ui.components.EmptyState
import com.meshdrop.ui.components.FileThumb
import com.meshdrop.ui.components.RadarPulse
import com.meshdrop.ui.components.TransferFinishedCard
import com.meshdrop.ui.components.TrustIndicator
import com.meshdrop.ui.components.deviceColor
import com.meshdrop.ui.components.deviceInitials

/**
 * Screen 3 — stay visible, see what is coming, accept or decline, watch it land.
 *
 * The offer card is where the trust line earns its place: the safety code is shown *before*
 * the user has to decide, because the keys are already derived by then.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveScreen(
    viewModel: ReceiveViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val deviceName by viewModel.deviceName.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val now by viewModel.now.collectAsStateWithLifecycle()
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
            item {
                VisibilityCard(
                    deviceName = deviceName,
                    visible = uiState.isWaiting,
                    onToggle = viewModel::setWaiting,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }

            if (sessions.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Default.Download,
                        title = "Waiting for a transfer",
                        subtitle = "Keep this screen open. Anyone nearby can send you a file, and you will be asked first.",
                    )
                }
            }

            items(items = sessions, key = { it.batchIdOrNull ?: it::class.java.simpleName }) { session ->
                when (session) {
                    is TransferSessionState.IncomingOffer -> {
                        OfferCard(
                            state = session,
                            nowMillis = now,
                            trustExpanded = session.batchId in uiState.expandedTrust,
                            onToggleTrust = { viewModel.toggleTrust(session.batchId) },
                            onConfirmCode = {
                                viewModel.confirmSafetyCode(session.batchId, session.peerName)
                            },
                            onAccept = { viewModel.approve(session.batchId) },
                            onDecline = { viewModel.reject(session.batchId, session.peerName) },
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                    }

                    is TransferSessionState.Active -> {
                        ActiveTransferPanel(
                            state = session,
                            reading = viewModel.readingFor(session.batchId),
                            advancedExpanded = session.batchId in uiState.expandedAdvanced,
                            onToggleAdvanced = { viewModel.toggleAdvanced(session.batchId) },
                            throughputSamples = viewModel.samplesFor(session.batchId),
                            throughputPeak = viewModel.peakFor(session.batchId),
                            onCancel = { viewModel.cancel(session.batchId) },
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                    }

                    is TransferSessionState.Finished -> {
                        TransferFinishedCard(
                            state = session,
                            onDone = viewModel::clearFinished,
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                    }

                    else -> Unit
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

/** Am I visible? The radar says yes; the switch says no. */
@Composable
private fun VisibilityCard(
    deviceName: String,
    visible: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
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
                if (visible) {
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
                    text = if (visible) "Visible to nearby devices" else "Hidden",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "As $deviceName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = visible, onCheckedChange = onToggle)
        }
    }
}

/**
 * The accept/decline card.
 *
 * Layering: sender → what they are sending → is this really them → decide. The trust line is
 * collapsed to one row by default so the decision stays one tap.
 */
@Composable
private fun OfferCard(
    state: TransferSessionState.IncomingOffer,
    nowMillis: Long,
    trustExpanded: Boolean,
    onToggleTrust: () -> Unit,
    onConfirmCode: () -> Unit,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = deviceColor(state.batchId)
    val window = OfferWindow(state.arrivedAtMillis)
    val expired = window.isExpired(nowMillis)
    Card(
        modifier = modifier
            .fillMaxWidth()
            // The card fades as the window closes: a decision that is about to disappear
            // should not look exactly like one that has all the time in the world.
            .alpha(if (expired) 0.45f else 0.45f + 0.55f * window.fractionRemaining(nowMillis)),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(color.copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = deviceInitials(state.peerName),
                        style = MaterialTheme.typography.titleMedium,
                        color = color,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("${state.peerName} wants to send", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${state.files.size} file(s) · ${Format.bytes(state.totalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = window.countdownLabel(nowMillis),
                        style = MaterialTheme.typography.labelSmall,
                        color = when (window.state(nowMillis)) {
                            OfferPolicy.Stage.CLOSED -> MaterialTheme.colorScheme.error
                            OfferPolicy.Stage.CLOSING -> MaterialTheme.colorScheme.tertiary
                            OfferPolicy.Stage.OPEN -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            state.files.take(4).forEach { file ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FileThumb(
                        uri = file.localUri?.let { android.net.Uri.parse(it) },
                        mimeType = file.mimeType,
                        modifier = Modifier.size(36.dp),
                    )
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
                }
            }
            if (state.files.size > 4) {
                Text(
                    text = "+${state.files.size - 4} more",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.spaceWarning != null) {
                Text(
                    text = state.spaceWarning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            TrustIndicator(
                security = state.security,
                peerName = state.peerName,
                expanded = trustExpanded,
                onToggleExpanded = onToggleTrust,
                onConfirmCode = onConfirmCode,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onDecline,
                    enabled = !expired,
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("Decline") }
                Button(
                    onClick = onAccept,
                    // Accepting after the window closed would hang: the sender is already
                    // gone, so the tap would look like the app froze.
                    enabled = !expired,
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Accept")
                }
            }
        }
    }
}
