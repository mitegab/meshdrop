package com.meshdrop.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.meshdrop.ui.theme.HairlineWidth
import com.meshdrop.ui.theme.MeshShapes
import com.meshdrop.ui.theme.MeshText
import com.meshdrop.ui.theme.MeshTheme
import com.meshdrop.ui.theme.Space

/**
 * The kit every screen is built from.
 *
 * Two ideas run through all of it:
 *
 *  * **Chrome is a hairline, never a shadow.** Elevation is a lie on a flat screen and it
 *    makes a list of ten cards look like a pile of paper plates. A 1dp rule in the hairline
 *    colour separates things just as well and stays quiet.
 *  * **One loud thing per screen.** [MeshButton] with [MeshButtonTone.Primary] is the only
 *    filled control in the app. Everything else — secondary actions, "see all", "cancel" — is
 *    a [TextAction] or a [MeshButtonTone.Quiet] outline, so the eye never has to choose.
 */

/** The little bar at the top of a sheet, so it is obvious it can be dismissed. */
@Composable
fun SheetGrip() {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(36.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(50))
                .background(MeshTheme.colors.hairline),
        )
    }
}

/** A 1dp rule. The only separator in the app. */
@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = MeshTheme.colors.hairline) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(HairlineWidth)
            .background(color),
    )
}

/** `SENDING`, `PRIVACY`, `THIS DEVICE` — the small caps line above a headline. */
@Composable
fun Eyebrow(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MeshTheme.colors.inkMuted,
) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        style = MeshText.Eyebrow,
        color = color,
    )
}

/**
 * A section of the page: eyebrow, serif title, optional caption and one trailing action.
 *
 * Use this instead of inventing a header per screen, so every section on every screen starts
 * at the same height and the same left edge.
 */
@Composable
fun SectionHeading(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    caption: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (eyebrow != null) {
            Eyebrow(text = eyebrow)
            Spacer(modifier = Modifier.height(Space.label))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.headlineSmall,
                color = MeshTheme.colors.ink,
            )
            if (action != null && onAction != null) {
                TextAction(text = action, onClick = onAction)
            }
        }
        if (caption != null) {
            Spacer(modifier = Modifier.height(Space.xs))
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = MeshTheme.colors.inkMuted,
            )
        }
    }
}

/**
 * A panel of content: surface, hairline border, 16dp radius, no shadow.
 *
 * Pass [onClick] to make the whole panel tappable; it then presses in by 1% instead of
 * flashing a ripple, which suits paper.
 */
@Composable
fun PaperCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: Dp = Space.lg,
    color: Color = MeshTheme.colors.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "card-press",
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            ),
        shape = MeshShapes.medium,
        color = color,
        border = BorderStroke(HairlineWidth, MeshTheme.colors.hairline),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding),
            content = content,
        )
    }
}

/** How loud a button is. There is exactly one [Primary] per screen. */
enum class MeshButtonTone { Primary, Quiet }

/**
 * The app's button.
 *
 * 50dp tall, 12dp radius, one line of `labelLarge`. The primary tone is the only filled clay
 * surface in the product, and its label says what will happen ("Send 3 files"), because
 * "OK" and "Continue" are how people end up sending a file to the wrong phone.
 */
@Composable
fun MeshButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: MeshButtonTone = MeshButtonTone.Primary,
    icon: ImageVector? = null,
) {
    val colors = MeshTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.98f else 1f,
        animationSpec = tween(durationMillis = 110),
        label = "button-press",
    )

    val background = when {
        !enabled -> colors.subtle
        tone == MeshButtonTone.Primary -> colors.clay
        else -> Color.Transparent
    }
    val content = when {
        !enabled -> colors.inkMuted.copy(alpha = 0.6f)
        tone == MeshButtonTone.Primary -> colors.onClay
        else -> colors.ink
    }
    val border = when {
        tone == MeshButtonTone.Primary || !enabled -> null
        else -> BorderStroke(HairlineWidth, colors.hairline)
    }

    Surface(
        modifier = modifier
            .heightIn(min = 50.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        shape = MeshShapes.small,
        color = background,
        contentColor = content,
        border = border,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.lg, vertical = Space.md),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = content,
                )
                Spacer(modifier = Modifier.width(Space.sm))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A tappable line of text: "See all", "Cancel", "Rename". */
@Composable
fun TextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MeshTheme.colors.clay,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .heightIn(min = 36.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Space.xs, vertical = Space.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) color else MeshTheme.colors.inkMuted,
            maxLines = 1,
        )
    }
}

/**
 * A quiet chip: the lane a peer was found on, a file count, a transparency statement.
 *
 * Never used for status that matters — that always comes with a glyph and a word.
 */
@Composable
fun MeshPill(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tint: Color = MeshTheme.colors.inkMuted,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MeshTheme.colors.subtle,
        contentColor = tint,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Space.md, vertical = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = tint,
                )
                Spacer(modifier = Modifier.width(Space.xs))
            }
            Text(text = text, style = MaterialTheme.typography.labelSmall, color = tint)
        }
    }
}

/**
 * "Nothing here yet", said properly.
 *
 * An empty screen is the first thing a new user sees, so it gets a serif line that explains
 * what will fill it and exactly how, plus at most one action to make that happen.
 */
@Composable
fun QuietNote(
    headline: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Space.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = headline,
            style = MaterialTheme.typography.titleMedium,
            color = MeshTheme.colors.ink,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(Space.sm))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MeshTheme.colors.inkMuted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(horizontal = Space.lg),
        )
        if (action != null) {
            Spacer(modifier = Modifier.height(Space.lg))
            action()
        }
    }
}

/**
 * A text field with no Material box around it.
 *
 * Fields in this app are either a search line or a device name, and both want to look like a
 * line of text on paper: sunken fill, 10dp corners, no floating label, no outline.
 */
@Composable
fun MeshTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    singleLine: Boolean = true,
) {
    val colors = MeshTheme.colors
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MeshShapes.small,
        color = colors.subtle,
        border = BorderStroke(HairlineWidth, colors.hairline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Space.md, vertical = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = colors.inkMuted,
                )
                Spacer(modifier = Modifier.width(Space.sm))
            }
            Box(modifier = Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.inkMuted,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = singleLine,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.ink),
                    cursorBrush = SolidColor(colors.clay),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * One setting: what it does, what it will do to your files, and a switch.
 *
 * The subtitle is not decoration. "End-to-end encryption" means nothing to a person, so every
 * switch here says what changes in the world when it moves.
 */
@Composable
fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MeshTheme.colors
    val subtitleColor by animateColorAsState(
        targetValue = if (checked) colors.inkMuted else colors.warning,
        animationSpec = tween(durationMillis = 180),
        label = "toggle-subtitle",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = colors.ink,
            )
            Spacer(modifier = Modifier.height(Space.hair))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                // A switch that is *off* is a promise we are not keeping, so its explanation
                // is allowed to be the warning colour.
                color = if (checked) subtitleColor else colors.warning,
            )
        }
        Spacer(modifier = Modifier.width(Space.lg))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onClay,
                checkedTrackColor = colors.clay,
                checkedBorderColor = colors.clay,
                uncheckedThumbColor = colors.inkMuted,
                uncheckedTrackColor = colors.subtle,
                uncheckedBorderColor = colors.hairline,
            ),
        )
    }
}

/** A row that opens something, or a pair of label/value lines for a readout. */
@Composable
fun RowAction(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    value: String? = null,
    valueMono: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = MeshTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = colors.ink)
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(Space.hair))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkMuted,
                )
            }
        }
        if (value != null) {
            Spacer(modifier = Modifier.width(Space.md))
            Text(
                text = value,
                style = if (valueMono) MeshText.Reading else MaterialTheme.typography.bodyMedium,
                color = colors.inkMuted,
            )
        }
        trailing?.invoke()
    }
}

/**
 * A dashed outline: the tray files are staged in, and the shape of "drop something here".
 *
 * Dashes are the one place this app allows a non-solid rule, because a dashed line is a
 * well-understood signal for "this area is for dropping things into".
 */
@Composable
fun DashedPanel(
    modifier: Modifier = Modifier,
    color: Color = MeshTheme.colors.hairline,
    radius: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                drawRoundRect(
                    color = color,
                    cornerRadius = CornerRadius(radius.toPx(), radius.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = HairlineWidth.toPx(),
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(6.dp.toPx(), 6.dp.toPx()),
                            0f,
                        ),
                    ),
                )
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Space.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}
