package com.meshdrop.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.meshdrop.core.model.SecurityState
import com.meshdrop.core.model.TrustLevel
import com.meshdrop.ui.theme.MeshShapes
import com.meshdrop.ui.theme.MeshText
import com.meshdrop.ui.theme.MeshTheme
import com.meshdrop.ui.theme.Space

/**
 * What is protecting this transfer, in one line, in English.
 *
 * The old version was a tinted panel with a padlock and the words "Verified · encrypted",
 * which is a security badge rather than a security *answer*: it tells you a status exists and
 * leaves the meaning to you. This one says what will happen to your files and what you can do
 * about it, and the vocabulary is deliberately non-technical — "check the numbers", not
 * "compare the SAS".
 *
 * Two rules:
 *  * **Never a colour without a word.** A green padlock alone is a claim; a green padlock,
 *    "Verified", and a sentence is a fact.
 *  * **The scary state is the loud one.** Encryption off is the only state rendered in the
 *    danger colour, because it is the only one where the user needs to react.
 */
@Composable
fun TrustLine(
    security: SecurityState,
    peerName: String,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    onToggleExpanded: () -> Unit = {},
    onConfirmCode: (() -> Unit)? = null,
) {
    val colors = MeshTheme.colors
    val level = security.trustLevel
    val tint = when (level) {
        TrustLevel.VERIFIED -> colors.success
        TrustLevel.UNVERIFIED -> colors.warning
        TrustLevel.PLAINTEXT -> colors.danger
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpanded)
                .padding(vertical = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrustGlyph(level = level, tint = tint, size = 15.dp)
            Spacer(modifier = Modifier.width(Space.sm))
            Text(
                text = security.headline,
                style = MaterialTheme.typography.bodySmall,
                color = if (level == TrustLevel.PLAINTEXT) tint else colors.inkMuted,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (security.safetyCode != null) {
                Text(
                    text = if (expanded) "Hide" else "Check code",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.clay,
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = Space.sm),
                verticalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                Text(
                    text = explanation(level, peerName),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkMuted,
                )

                security.safetyCode?.let { code ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MeshShapes.small,
                        color = colors.subtle,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = Space.lg),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = code,
                                style = MeshText.SafetyCode,
                                color = colors.ink,
                            )
                            if (onConfirmCode != null && level != TrustLevel.VERIFIED) {
                                Spacer(modifier = Modifier.height(Space.md))
                                Text(
                                    text = "Both phones must show these digits.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.inkMuted,
                                )
                                Spacer(modifier = Modifier.height(Space.sm))
                                MeshButton(
                                    text = "They match",
                                    onClick = onConfirmCode,
                                    tone = MeshButtonTone.Quiet,
                                    modifier = Modifier.padding(horizontal = Space.lg),
                                )
                            }
                        }
                    }
                }

                security.mode.cipherSuite?.let { suite ->
                    Text(
                        text = "Cipher: $suite",
                        style = MeshText.Reading,
                        color = colors.inkMuted,
                    )
                }
            }
        }
    }
}

/** Just the glyph, for rows that have no space for a sentence. */
@Composable
fun TrustGlyph(
    level: TrustLevel,
    modifier: Modifier = Modifier,
    tint: Color = when (level) {
        TrustLevel.VERIFIED -> MeshTheme.colors.success
        TrustLevel.UNVERIFIED -> MeshTheme.colors.warning
        TrustLevel.PLAINTEXT -> MeshTheme.colors.danger
    },
    size: androidx.compose.ui.unit.Dp = 14.dp,
) {
    val icon: ImageVector = when (level) {
        TrustLevel.VERIFIED -> Icons.Outlined.VerifiedUser
        TrustLevel.UNVERIFIED -> Icons.Outlined.Lock
        TrustLevel.PLAINTEXT -> Icons.Outlined.LockOpen
    }
    Icon(
        imageVector = icon,
        contentDescription = level.label,
        modifier = modifier.size(size),
        tint = tint,
    )
}

/** Convenience for the many places that only have a [SecurityState]. */
@Composable
fun TrustGlyph(
    security: SecurityState,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 14.dp,
) = TrustGlyph(level = security.trustLevel, modifier = modifier, size = size)

/**
 * The sentence that makes the glyph mean something.
 *
 * Written for someone who has never heard of a man in the middle: what happens to the file,
 * who could read it, and what the numbers on screen are for.
 */
private fun explanation(level: TrustLevel, peerName: String): String = when (level) {
    TrustLevel.VERIFIED ->
        "Files are scrambled before they leave this phone, and MeshDrop has checked that " +
            "$peerName is really $peerName. The last time you compared the numbers they matched, " +
            "so nobody on this network can read or change what you send."

    TrustLevel.UNVERIFIED ->
        "Files are scrambled before they leave this phone, but this is the first time you have " +
            "sent to $peerName. That is usually fine — it just means nothing has confirmed who is " +
            "on the other end yet. Read the numbers above out loud, together: if both phones " +
            "show the same six digits, nobody is in the middle."

    TrustLevel.PLAINTEXT ->
        "These files are not scrambled. $peerName is running an older MeshDrop (or has " +
            "encryption switched off), so anything you send travels across this Wi-Fi network " +
            "in the clear and anyone else on it could read it."
}
