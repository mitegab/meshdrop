package com.meshdrop.ui.drop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meshdrop.core.model.FileCategory
import com.meshdrop.core.offer.OfferPolicy
import com.meshdrop.core.offer.OfferWindow
import com.meshdrop.core.util.Format
import com.meshdrop.transfer.state.TransferSessionState
import com.meshdrop.ui.components.Eyebrow
import com.meshdrop.ui.components.FileThumb
import com.meshdrop.ui.components.Hairline
import com.meshdrop.ui.components.MeshButton
import com.meshdrop.ui.components.MeshButtonTone
import com.meshdrop.ui.components.MeshTextField
import com.meshdrop.ui.components.SheetGrip
import com.meshdrop.ui.components.ProgressLine
import com.meshdrop.ui.components.TextAction
import com.meshdrop.ui.components.ToggleRow
import com.meshdrop.ui.components.TrustLine
import com.meshdrop.ui.theme.HairlineWidth
import com.meshdrop.ui.theme.MeshShapes
import com.meshdrop.ui.theme.MeshText
import com.meshdrop.ui.theme.MeshTheme
import com.meshdrop.ui.theme.Space

/**
 * Sheets, not screens.
 *
 * Everything that used to be a whole destination — picking files, deciding on an offer,
 * checking the connection — is a sheet over the Drop screen. The user never loses their place
 * and the app never grows a back stack of places they did not ask for.
 *
 * [MeshSheet] is deliberately not `ModalBottomSheet`: no drag physics, no partly-expanded
 * state, no nested scroll negotiation. It slides up, it covers what it needs to, and tapping
 * outside dismisses it.
 */
@Composable
fun BoxScope.MeshSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MeshTheme.colors

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(durationMillis = 180)),
        exit = fadeOut(animationSpec = tween(durationMillis = 140)),
        modifier = modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.ink.copy(alpha = 0.32f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(
            initialOffsetY = { height -> height },
            animationSpec = tween(durationMillis = 260),
        ) + fadeIn(animationSpec = tween(durationMillis = 180)),
        exit = slideOutVertically(
            targetOffsetY = { height -> height },
            animationSpec = tween(durationMillis = 200),
        ) + fadeOut(animationSpec = tween(durationMillis = 140)),
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
            color = colors.surface,
            border = BorderStroke(HairlineWidth, colors.hairline),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = Space.gutter)
                    .padding(top = Space.md, bottom = Space.lg),
                content = content,
            )
        }
    }
}

/**
 * Where are the files?
 *
 * Three choices, in the order people actually want them, each naming the mime filter it will
 * hand to the system picker so there are no surprises about what "Files" means.
 */
@Composable
fun PickSheet(
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    SheetGrip()
    Spacer(modifier = Modifier.height(Space.lg))
    Eyebrow(text = "Add to the tray")
    Spacer(modifier = Modifier.height(Space.sm))
    Text(
        text = "What are you sending?",
        style = MaterialTheme.typography.headlineSmall,
        color = MeshTheme.colors.ink,
    )
    Spacer(modifier = Modifier.height(Space.md))

    PickOption(
        icon = Icons.Outlined.Image,
        title = "Photos",
        caption = "One or several from the gallery",
        onClick = { onPick("image/*") },
    )
    PickOption(
        icon = Icons.Outlined.VideoFile,
        title = "Videos",
        caption = "Clips and recordings",
        onClick = { onPick("video/*") },
    )
    PickOption(
        icon = Icons.Outlined.Folder,
        title = "Any file",
        caption = "Documents, archives, anything at all",
        onClick = { onPick("*/*") },
    )

    Spacer(modifier = Modifier.height(Space.sm))
    TextAction(text = "Cancel", onClick = onDismiss)
}

@Composable
private fun PickOption(
    icon: ImageVector,
    title: String,
    caption: String,
    onClick: () -> Unit,
) {
    val colors = MeshTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = colors.inkMuted,
        )
        Spacer(modifier = Modifier.width(Space.lg))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, color = colors.ink)
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkMuted,
            )
        }
    }
}

/**
 * The incoming offer, as a letter rather than an alert.
 *
 * Layer order matters and is the same everywhere in the app: who it is from, what is in it,
 * whether it is safe, then the decision. The countdown is a depleting rule at the very top —
 * visible, calm, and honest about the fact that the sender is waiting.
 */
@Composable
fun OfferSheet(
    offer: TransferSessionState.IncomingOffer,
    nowMillis: Long,
    trustExpanded: Boolean,
    onToggleTrust: () -> Unit,
    onConfirmCode: () -> Unit,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val colors = MeshTheme.colors
    val window = OfferWindow(offer.arrivedAtMillis)
    val expired = window.isExpired(nowMillis)
    val closing = window.state(nowMillis) == OfferPolicy.Stage.CLOSING

    ProgressLine(
        progress = window.fractionRemaining(nowMillis),
        active = false,
        height = 3.dp,
        color = if (closing) colors.warning else colors.ember,
    )
    Spacer(modifier = Modifier.height(Space.lg))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Eyebrow(text = "Incoming", color = colors.clay)
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = window.countdownLabel(nowMillis),
            style = MeshText.Reading,
            color = if (closing) colors.warning else colors.inkMuted,
        )
    }

    Spacer(modifier = Modifier.height(Space.md))

    Text(
        text = "${offer.peerName} wants to send you ${FileCategory.describe(offer.files).lowercase()}.",
        style = MaterialTheme.typography.displaySmall,
        color = colors.ink,
    )

    Spacer(modifier = Modifier.height(Space.sm))

    Text(
        text = "${Format.bytes(offer.totalBytes)} over Wi-Fi",
        style = MaterialTheme.typography.bodyMedium,
        color = colors.inkMuted,
    )

    Spacer(modifier = Modifier.height(Space.lg))

    offer.files.take(MAX_OFFER_ROWS).forEach { file ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FileThumb(
                uri = file.localUri?.let { android.net.Uri.parse(it) },
                mimeType = file.mimeType,
                modifier = Modifier.size(32.dp),
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

    if (offer.files.size > MAX_OFFER_ROWS) {
        Text(
            text = "and ${offer.files.size - MAX_OFFER_ROWS} more",
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkMuted,
        )
    }

    offer.spaceWarning?.let { warning ->
        Spacer(modifier = Modifier.height(Space.md))
        Text(
            text = warning,
            style = MaterialTheme.typography.bodySmall,
            color = colors.danger,
        )
    }

    Spacer(modifier = Modifier.height(Space.md))

    TrustLine(
        security = offer.security,
        peerName = offer.peerName,
        expanded = trustExpanded,
        onToggleExpanded = onToggleTrust,
        onConfirmCode = onConfirmCode,
    )

    Spacer(modifier = Modifier.height(Space.lg))

    Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
        MeshButton(
            text = "Decline",
            onClick = onDecline,
            enabled = !expired,
            tone = MeshButtonTone.Quiet,
            modifier = Modifier.weight(1f),
        )
        MeshButton(
            text = if (expired) "Too late" else "Receive",
            onClick = onAccept,
            // Answering after the window closed would hang: the sender is gone, so the tap
            // would just look like the app froze.
            enabled = !expired,
            modifier = Modifier.weight(1f),
        )
    }
}

private const val MAX_OFFER_ROWS = 4

/**
 * The connection sheet: who this phone is, whether it can be reached, and how to stop being
 * reachable. All the plumbing that used to sit in the middle of the home screen, one tap
 * away, said in plain words.
 */
@Composable
fun ConnectionSheet(
    deviceName: String,
    port: Int,
    peerCount: Int,
    receiving: Boolean,
    onToggleReceiving: (Boolean) -> Unit,
    onRename: () -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MeshTheme.colors
    SheetGrip()
    Spacer(modifier = Modifier.height(Space.lg))

    Eyebrow(text = "This phone")
    Spacer(modifier = Modifier.height(Space.sm))
    Text(
        text = deviceName.ifBlank { "Unnamed phone" },
        style = MaterialTheme.typography.headlineSmall,
        color = colors.ink,
    )

    Spacer(modifier = Modifier.height(Space.lg))

    ToggleRow(
        title = if (receiving) "Visible to nearby phones" else "Hidden",
        subtitle = if (receiving) {
            "Anyone on this Wi-Fi can see this phone and send you files."
        } else {
            "Nobody can reach this phone, and nothing can be sent to you."
        },
        checked = receiving,
        onCheckedChange = onToggleReceiving,
    )

    Hairline()

    ConnectionDetail("Listening on", if (receiving) "Wi-Fi · port $port" else "—")
    ConnectionDetail("Phones found", "$peerCount nearby")
    ConnectionDetail("Discovery", "mDNS + broadcast beacon, every 3s")
    ConnectionDetail("Server", "none — this is device to device")

    Spacer(modifier = Modifier.height(Space.lg))
    Hairline()
    Spacer(modifier = Modifier.height(Space.sm))

    Row(verticalAlignment = Alignment.CenterVertically) {
        TextAction(text = "Rename", onClick = onRename)
        TextAction(text = "Look again", onClick = onRefresh)
        Spacer(modifier = Modifier.weight(1f))
        TextAction(text = "Close", onClick = onDismiss, color = colors.inkMuted)
    }
}

@Composable
private fun ConnectionDetail(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MeshTheme.colors.inkMuted,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = value,
            style = MeshText.Reading,
            color = MeshTheme.colors.ink,
        )
    }
}

/** Renaming this phone: one field, one sentence explaining who sees it. */
@Composable
fun RenameDialog(
    draft: String,
    onDraftChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MeshTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        shape = MeshShapes.large,
        title = {
            Text(
                text = "Name this phone",
                style = MaterialTheme.typography.headlineSmall,
                color = colors.ink,
            )
        },
        text = {
            Column {
                Text(
                    text = "Other phones see this name in their list. Keep it short so it fits.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkMuted,
                )
                Spacer(modifier = Modifier.height(Space.lg))
                MeshTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    placeholder = "e.g. Kavi's Pixel",
                )
            }
        },
        confirmButton = {
            MeshButton(text = "Save", onClick = onConfirm)
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "Cancel", color = colors.inkMuted)
            }
        },
    )
}
