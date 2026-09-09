package com.meshdrop.ui.components

import androidx.compose.animation.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meshdrop.core.model.DiscoveryKind
import com.meshdrop.core.model.PeerDevice
import com.meshdrop.core.model.TransferFile
import com.meshdrop.core.model.TransferJob
import com.meshdrop.core.model.TransferStatus
import com.meshdrop.core.util.Format

/** Title above a group of cards. */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        trailing?.invoke()
    }
}

/** One of the two big tiles on the Home screen. */
@Composable
fun BigActionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(contentColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = contentColor)
            }
            Text(text = title, style = MaterialTheme.typography.titleLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor.copy(alpha = 0.75f),
            )
        }
    }
}

/** A nearby device. */
@Composable
fun PeerRow(
    peer: PeerDevice,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val container = when {
        selected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.PhoneAndroid,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = peer.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = peer.endpoint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TransportChip(kind = peer.kind)
        }
    }
}

@Composable
private fun TransportChip(kind: DiscoveryKind) {
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(kind.label, style = MaterialTheme.typography.labelSmall) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Wifi,
                contentDescription = null,
                modifier = Modifier.size(AssistChipDefaults.IconSize),
            )
        },
    )
}

/** Progress + status for one transfer job (used on Send and Receive). */
@Composable
fun TransferJobCard(
    job: TransferJob,
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null,
) {
    val progress by animateFloatAsState(targetValue = job.progress, label = "progress")
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (job.direction == com.meshdrop.core.model.TransferDirection.SEND) {
                            "Sending to ${job.peerName}"
                        } else {
                            "Receiving from ${job.peerName}"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${Format.bytes(job.bytesTransferred)} of ${Format.bytes(job.totalBytes)}" +
                            job.etaMillis?.let { " · ${Format.duration(it) ?: ""} left" }.orEmpty() +
                            if (job.bytesPerSecond > 0L) " · ${Format.speed(job.bytesPerSecond)}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusChip(status = job.status)
            }

            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(50)),
            )

            job.files.forEach { file -> FileRow(file = file) }

            job.message?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (job.status == TransferStatus.FAILED || job.status == TransferStatus.REJECTED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            if (onCancel != null && job.isActive) {
                Text(
                    text = "Cancel",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .clickable(onClick = onCancel)
                        .padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
fun FileRow(file: TransferFile, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Description,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = file.name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = when {
                file.status.isFinished -> file.status.name.lowercase().replaceFirstChar { it.uppercaseChar() }
                else -> Format.bytes(file.bytesTransferred)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun StatusChip(status: TransferStatus) {
    val (icon, tint) = when (status) {
        TransferStatus.WAITING -> Icons.Default.HourglassEmpty to MaterialTheme.colorScheme.onSurfaceVariant
        TransferStatus.IN_PROGRESS -> Icons.Default.HourglassEmpty to MaterialTheme.colorScheme.primary
        TransferStatus.COMPLETED -> Icons.Default.CheckCircle to successColor()
        TransferStatus.FAILED -> Icons.Default.Error to MaterialTheme.colorScheme.error
        TransferStatus.REJECTED -> Icons.Default.Close to MaterialTheme.colorScheme.error
        TransferStatus.CANCELLED -> Icons.Default.Close to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = tint.copy(alpha = 0.14f),
        contentColor = tint,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(
                text = status.name.lowercase().replaceFirstChar { it.uppercaseChar() },
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
fun successColor(): Color = MaterialTheme.colorScheme.primary

/** Friendly placeholder for "nothing here yet" states. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Text(text = title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        action?.invoke()
    }
}
