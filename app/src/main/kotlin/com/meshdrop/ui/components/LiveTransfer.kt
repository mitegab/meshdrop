package com.meshdrop.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meshdrop.core.model.FileCategory
import com.meshdrop.core.model.TransferDirection
import com.meshdrop.core.model.TransferFile
import com.meshdrop.core.model.TransferStatus
import com.meshdrop.core.util.Format
import com.meshdrop.core.util.SmoothedReading
import com.meshdrop.transfer.state.TransferSessionState
import com.meshdrop.ui.theme.MeshText
import com.meshdrop.ui.theme.MeshTheme
import com.meshdrop.ui.theme.Space

/**
 * The card that has to feel great: bytes are moving.
 *
 * Order of reading, top to bottom, is the order a person asks the questions:
 *
 *  1. *What is happening?* → the eyebrow and the serif name of the recipient.
 *  2. *How far along?* → one big mono percentage, which is the only number anybody reads
 *     during a transfer.
 *  3. *How long?* → the ETA, hedged to "about" when the link is unsteady, and an honest
 *     "waiting" when it has stalled.
 *  4. *Is it really moving?* → a bar whose head glows while bytes land and goes still when
 *     they do not.
 *  5. *What is it doing?* → the details disclosure, for the one person in ten who wants
 *     lanes, retransmits and a graph.
 *
 * Nothing here is decided locally: it renders a [TransferSessionState.Active] and calls back.
 */
@Composable
fun LiveTransferCard(
    state: TransferSessionState.Active,
    reading: SmoothedReading,
    modifier: Modifier = Modifier,
    queueLabel: String? = null,
    detailsExpanded: Boolean = false,
    onToggleDetails: () -> Unit = {},
    onCancel: () -> Unit = {},
    /** Recent throughput samples for the details panel. */
    throughputSamples: List<Long> = emptyList(),
    throughputPeak: Long = 1L,
) {
    val colors = MeshTheme.colors
    val sending = state.direction == TransferDirection.SEND
    val moving = state.bytesPerSecond > 0L
    val stalled = reading.stalled

    PaperCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow(text = if (sending) "Sending" else "Receiving")
            Spacer(modifier = Modifier.weight(1f))
            MeshPill(
                text = state.primaryLane.shortLabel,
                icon = null,
                tint = colors.inkMuted,
            )
        }

        Spacer(modifier = Modifier.height(Space.md))

        Text(
            text = state.peerName,
            style = MaterialTheme.typography.headlineMedium,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(Space.xs))
        Text(
            text = "${FileCategory.describe(state.files)} · ${Format.bytes(state.totalBytes)}",
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkMuted,
        )

        Spacer(modifier = Modifier.height(Space.lg))

        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = Format.percentText(state.bytesTransferred, state.totalBytes),
                style = MeshText.HeroReading,
                color = colors.ink,
            )
            Spacer(modifier = Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (queueLabel != null) {
                        Icon(
                            imageVector = Icons.Outlined.Timer,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = colors.inkMuted,
                        )
                        Spacer(modifier = Modifier.width(Space.xs))
                    }
                    Text(
                        text = etaLine(state, reading, queueLabel),
                        style = MeshText.Reading,
                        color = if (stalled) colors.warning else colors.inkMuted,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(Space.md))

        ProgressLine(progress = state.progress, active = moving)

        Spacer(modifier = Modifier.height(Space.md))

        Text(
            text = "${Format.bytes(state.bytesTransferred)} of ${Format.bytes(state.totalBytes)}" +
                if (moving) " · ${Format.speed(reading.bytesPerSecond)}" else "",
            style = MeshText.Reading,
            color = colors.inkMuted,
        )

        if (stalled) {
            Spacer(modifier = Modifier.height(Space.sm))
            Text(
                text = "The link has gone quiet. MeshDrop is trying another route.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.warning,
            )
        }

        Spacer(modifier = Modifier.height(Space.lg))
        Hairline()
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextAction(text = if (detailsExpanded) "Hide details" else "Details", onClick = onToggleDetails)
            Spacer(modifier = Modifier.weight(1f))
            TextAction(text = "Cancel", onClick = onCancel, color = colors.danger)
        }

        AnimatedVisibility(
            visible = detailsExpanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            TechDetails(
                state = state,
                reading = reading,
                samples = throughputSamples,
                peak = throughputPeak,
            )
        }
    }
}

/** `about 12s left`, `waiting for the radio`, `stalled`. Never a bare number without a unit. */
private fun etaLine(
    state: TransferSessionState.Active,
    reading: SmoothedReading,
    queueLabel: String?,
): String {
    if (queueLabel != null) return queueLabel
    if (reading.stalled) return "stalled"
    val eta = reading.etaMillis
    if (reading.bytesPerSecond <= 0L || eta == null) return "starting"
    val duration = Format.duration(eta) ?: return "almost done"
    // Below the stability threshold the ETA is a guess, and saying so is the difference
    // between a delay and a lie.
    return if (reading.isSteady) "$duration left" else "about $duration left"
}

/** The collapsed power-user panel: honest numbers, no decoration. */
@Composable
private fun TechDetails(
    state: TransferSessionState.Active,
    reading: SmoothedReading,
    samples: List<Long>,
    peak: Long,
) {
    val colors = MeshTheme.colors
    Column(modifier = Modifier.fillMaxWidth().padding(top = Space.sm)) {
        DetailRow("Throughput", Format.speed(reading.bytesPerSecond))
        DetailRow("Steadiness", "${(reading.stability * 100).toInt()}%")
        DetailRow(
            label = "Lane",
            value = state.lanes.joinToString(", ") { lane ->
                lane.lane.shortLabel + if (lane.active) "" else " (given up)"
            },
        )
        if (state.contributions.size > 1) {
            DetailRow(
                label = "Peers",
                value = state.contributions.joinToString(", ") { contribution ->
                    "${contribution.displayName} ${Format.bytes(contribution.bytesDelivered)}"
                },
            )
        }
        DetailRow("Encryption", state.security.mode.cipherSuite ?: "off")
        DetailRow("Retransmits", state.retransmits.toString())
        DetailRow("Batch", state.batchId.take(8))

        Spacer(modifier = Modifier.height(Space.md))
        Eyebrow(text = "Last few seconds")
        Spacer(modifier = Modifier.height(Space.sm))
        ThroughputLine(samples = samples, peak = peak)
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
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
        Text(
            text = value,
            style = MeshText.Reading,
            color = MeshTheme.colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Sender side, before a byte moves: the handshake is out and a human on the other end has to
 * answer. The card says exactly that, because "waiting" with no reason is how people force
 * quit apps.
 */
@Composable
fun WaitingCard(
    state: TransferSessionState.AwaitingApproval,
    modifier: Modifier = Modifier,
    onCancel: () -> Unit = {},
) {
    val colors = MeshTheme.colors
    PaperCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BreathingDot(color = colors.clay, size = 8.dp)
            Spacer(modifier = Modifier.width(Space.sm))
            Eyebrow(text = "Waiting for an answer")
        }
        Spacer(modifier = Modifier.height(Space.md))
        Text(
            text = state.peerName,
            style = MaterialTheme.typography.headlineMedium,
            color = colors.ink,
        )
        Spacer(modifier = Modifier.height(Space.xs))
        Text(
            text = "${FileCategory.describe(state.files)} · ${Format.bytes(state.totalBytes)}",
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkMuted,
        )
        Spacer(modifier = Modifier.height(Space.md))
        Text(
            text = "MeshDrop asked them to accept. The files stay on this phone until they do.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkMuted,
        )
        Spacer(modifier = Modifier.height(Space.lg))
        Hairline()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(modifier = Modifier.weight(1f))
            TextAction(text = "Cancel", onClick = onCancel, color = colors.danger)
        }
    }
}

/**
 * The receipt.
 *
 * A finished transfer deserves a proper ending: what moved, how long it took, whether it
 * arrived intact — and one way out. The previous version said "Sent" inside a green card and
 * left the user to guess whether "Sent" meant "delivered" or "we tried".
 */
@Composable
fun TransferReceipt(
    state: TransferSessionState.Finished,
    modifier: Modifier = Modifier,
    onDone: () -> Unit = {},
    onRetry: (() -> Unit)? = null,
) {
    val colors = MeshTheme.colors
    val sending = state.direction == TransferDirection.SEND
    val ok = state.status == TransferStatus.COMPLETED

    PaperCard(modifier = modifier) {
        Eyebrow(
            text = when {
                ok && sending -> "Sent"
                ok -> "Received"
                state.status == TransferStatus.REJECTED -> "Declined"
                state.status == TransferStatus.CANCELLED -> "Cancelled"
                else -> "Did not finish"
            },
            color = if (ok) colors.success else colors.danger,
        )
        Spacer(modifier = Modifier.height(Space.sm))
        Text(
            text = headline(state, sending),
            style = MaterialTheme.typography.displaySmall,
            color = colors.ink,
        )
        Spacer(modifier = Modifier.height(Space.sm))
        Text(
            text = summary(state, sending),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkMuted,
        )

        if (ok && state.files.size <= MAX_LISTED_FILES) {
            Spacer(modifier = Modifier.height(Space.lg))
            state.files.forEach { file -> ReceiptFileRow(file = file) }
        }

        Spacer(modifier = Modifier.height(Space.lg))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TrustGlyph(security = state.security, size = 14.dp)
            Spacer(modifier = Modifier.width(Space.sm))
            Text(
                text = integrityLine(state, sending),
                style = MaterialTheme.typography.bodySmall,
                color = if (state.security.isEncrypted) colors.inkMuted else colors.danger,
            )
        }

        Spacer(modifier = Modifier.height(Space.lg))
        Hairline()
        Spacer(modifier = Modifier.height(Space.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!ok && onRetry != null && state.status != TransferStatus.REJECTED) {
                TextAction(text = "Try again", onClick = onRetry)
            }
            Spacer(modifier = Modifier.weight(1f))
            TextAction(text = "Done", onClick = onDone, color = colors.clay)
        }
    }
}

private const val MAX_LISTED_FILES = 4

@Composable
private fun ReceiptFileRow(file: TransferFile) {
    val colors = MeshTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileThumb(
            uri = file.localUri?.let { android.net.Uri.parse(it) },
            mimeType = file.mimeType,
            modifier = Modifier.size(30.dp),
        )
        Spacer(modifier = Modifier.width(Space.md))
        Text(
            text = file.name,
            modifier = Modifier.weight(1f),
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
}

private fun headline(state: TransferSessionState.Finished, sending: Boolean): String = when {
    state.status == TransferStatus.COMPLETED && sending -> "Sent to ${state.peerName}"
    state.status == TransferStatus.COMPLETED -> "Saved from ${state.peerName}"
    state.status == TransferStatus.REJECTED -> "${state.peerName} said no"
    state.status == TransferStatus.CANCELLED -> "Stopped"
    else -> "That did not finish"
}

private fun summary(state: TransferSessionState.Finished, sending: Boolean): String {
    if (state.status == TransferStatus.COMPLETED) {
        val duration = Format.duration(state.durationMillis)
        return buildString {
            append(FileCategory.describe(state.files))
            append(" · ")
            append(Format.bytes(state.totalBytes))
            if (duration != null) append(" in $duration")
            append(if (sending) " · every file delivered" else " · in your Downloads")
        }
    }
    return state.message ?: when (state.status) {
        TransferStatus.REJECTED -> "They declined, so nothing was sent."
        TransferStatus.CANCELLED -> "You stopped this one."
        else -> "The connection dropped before the last file arrived."
    }
}

private fun integrityLine(state: TransferSessionState.Finished, sending: Boolean): String = when {
    state.security.integrityConfirmed ->
        if (sending) "Encrypted end to end, and every chunk arrived intact." else
            "Encrypted end to end, and every chunk arrived intact on this phone."

    state.security.isEncrypted -> "Encrypted end to end."
    else -> "Not encrypted — these files travelled in the clear."
}
