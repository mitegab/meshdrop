package com.meshdrop.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.meshdrop.core.util.Format
import com.meshdrop.transfer.state.TransferSessionState

/**
 * The active transfer: layered progress for everybody, telemetry for whoever asks.
 *
 * Layer 1 (always) — overall percent, the assembling file bar, speed and ETA.
 * Layer 2 (always, but quiet) — the lane strip: which pipe is carrying the bytes.
 * Layer 3 (only when it matters) — peer chips, so swarm transfers stay readable.
 * Layer 4 (collapsed) — the Advanced/Network panel: live graph, route, cipher, retransmits.
 *
 * Everything is rendered from a single [TransferSessionState.Active]; nothing here decides
 * anything.
 */
@Composable
fun ActiveTransferPanel(
    state: TransferSessionState.Active,
    reading: com.meshdrop.core.util.SmoothedReading,
    modifier: Modifier = Modifier,
    advancedExpanded: Boolean = false,
    onToggleAdvanced: () -> Unit = {},
    throughputSamples: List<Long> = emptyList(),
    throughputPeak: Long = 1L,
    onCancel: (() -> Unit)? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OrbitDots(modifier = Modifier.size(34.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (state.direction == com.meshdrop.core.model.TransferDirection.SEND) {
                            "Sending to ${state.peerName}"
                        } else {
                            "Receiving from ${state.peerName}"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "${state.files.size} file(s) · ${Format.bytes(state.totalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "${Format.percent(state.bytesTransferred, state.totalBytes)}%",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // The file visually assembling rather than a flat bar.
            AssemblyBar(progress = state.progress)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "${Format.bytes(state.bytesTransferred)} / ${Format.bytes(state.totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = Format.speed(reading.bytesPerSecond),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    reading.etaMillis?.let { eta ->
                        Text(
                            text = "· ${Format.duration(eta) ?: ""} left",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            LaneStrip(lanes = state.lanes, justFailedOver = state.justFailedOver)

            if (state.contributions.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = if (state.contributions.size > 1) "Peers helping" else "Peer",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PeerChips(contributions = state.contributions)
                }
            }

            // Per-file slivers: the torrent-style list, one line each.
            state.files.take(4).forEach { file ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = file.name,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box(
                        modifier = Modifier
                            .width(64.dp)
                            .height(4.dp)
                            .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(50)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fileFraction(file.bytesTransferred, file.sizeBytes))
                                .height(4.dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)),
                        )
                    }
                }
            }

            AdvancedToggle(expanded = advancedExpanded, onClick = onToggleAdvanced)

            AnimatedVisibility(
                visible = advancedExpanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                AdvancedNetworkPanel(
                    state = state,
                    throughputSamples = throughputSamples,
                    throughputPeak = throughputPeak,
                )
            }

            if (onCancel != null) {
                Text(
                    text = "Cancel transfer",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.clickable(onClick = onCancel),
                )
            }
        }
    }
}

private fun fileFraction(done: Long, total: Long): Float =
    if (total <= 0L) 0f else (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)

@Composable
private fun AdvancedToggle(expanded: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "Network details",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = if (expanded) "hide" else "show",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

/**
 * Power-user panel: collapsed by default so it reads as a bonus rather than clutter.
 *
 * Everything shown here is real: the graph is fed by [TransferTelemetry], the cipher comes
 * from the negotiated session, and retransmits are counted by the transport when it retries.
 */
@Composable
private fun AdvancedNetworkPanel(
    state: TransferSessionState.Active,
    throughputSamples: List<Long>,
    throughputPeak: Long,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Throughput (last ${throughputSamples.size} samples)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ThroughputGraph(
            samples = throughputSamples,
            peak = throughputPeak,
            color = TransportVisual.of(state.primaryLane).tint,
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
        )

        Text(
            text = "Route",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RouteTopology(
            activeLane = state.primaryLane,
            peerName = state.peerName,
            selfName = "This device",
            hasRelay = state.lanes.any { it.lane == com.meshdrop.core.model.TransportLane.RELAY },
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
        )

        DividerLine()

        DetailRow("Protocol", "v${com.meshdrop.transfer.protocol.TransferProtocol.VERSION}")
        DetailRow("Lane", state.primaryLane.label)
        DetailRow(
            "Encryption",
            state.security.mode.cipherSuite ?: "none (plaintext)",
        )
        DetailRow("Integrity", if (state.security.integrityConfirmed) "verified" else "checking…")
        DetailRow("Retransmits", state.retransmits.toString())
        DetailRow("Chunks", "${state.files.size} file(s), 64 KB frames")
        state.security.peerFingerprint?.let { DetailRow("Peer key", it) }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun DividerLine() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)),
    )
}
