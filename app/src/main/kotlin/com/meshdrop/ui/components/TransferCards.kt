package com.meshdrop.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meshdrop.core.model.TransferDirection
import com.meshdrop.core.model.TransferStatus
import com.meshdrop.core.util.Format
import com.meshdrop.transfer.state.TransferSessionState

/**
 * The "we are done" card, shared by both directions so a finished send and a finished receive
 * read the same way.
 */
@Composable
fun TransferFinishedCard(
    state: TransferSessionState.Finished,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    /** Non-null adds a "Try again" button — only a send with files still missing gets one. */
    onRetry: (() -> Unit)? = null,
) {
    val ok = state.status == TransferStatus.COMPLETED
    val sending = state.direction == TransferDirection.SEND
    val doneCount = state.files.count { it.status == TransferStatus.COMPLETED }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (ok) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row {
                Text(
                    text = when {
                        ok && sending -> "Sent"
                        ok -> "Received"
                        state.status == TransferStatus.REJECTED -> "Declined"
                        state.status == TransferStatus.CANCELLED -> "Cancelled"
                        else -> "Did not finish"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TrustBadge(security = state.security)
            }

            Text(
                text = if (ok) {
                    "$doneCount file(s) · ${Format.bytes(state.totalBytes)} · " +
                        (Format.duration(state.durationMillis) ?: "a moment") +
                        if (sending) " to ${state.peerName}" else " from ${state.peerName}"
                } else {
                    state.message ?: "The transfer stopped before it finished."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.35f),
            ) {
                Text(
                    text = when {
                        state.security.integrityConfirmed ->
                            "Encrypted and verified — every chunk matched when it arrived."

                        state.security.isEncrypted ->
                            "Encrypted, but the integrity check has not confirmed on this device."

                        else -> "Not encrypted: this peer is using an older MeshDrop."
                    },
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onRetry != null && !ok && state.status != TransferStatus.REJECTED) {
                    TextButton(onClick = onRetry) { Text("Try again") }
                }
                TextButton(onClick = onDone) { Text("Done") }
            }
        }
    }
}

/** One line of "what is happening" used by history rows and the receive feed. */
@Composable
fun SessionHeadline(state: TransferSessionState, modifier: Modifier = Modifier) {
    val text = when (state) {
        is TransferSessionState.Idle -> "Ready"
        is TransferSessionState.Discovering -> "Looking for devices"
        is TransferSessionState.AwaitingApproval -> "Waiting for ${state.peerName} to accept"
        is TransferSessionState.IncomingOffer -> "${state.peerName} wants to send you ${state.files.size} file(s)"
        is TransferSessionState.Active -> if (state.direction == TransferDirection.SEND) {
            "Sending to ${state.peerName}"
        } else {
            "Receiving from ${state.peerName}"
        }

        is TransferSessionState.Finished -> state.status.name.lowercase().replaceFirstChar { it.uppercase() }
    }
    Text(text = text, modifier = modifier, style = MaterialTheme.typography.bodyMedium)
}
