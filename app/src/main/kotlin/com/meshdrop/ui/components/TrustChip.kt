package com.meshdrop.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshdrop.core.model.SecurityState
import com.meshdrop.core.model.TrustLevel

/**
 * The one line where security is allowed to appear in the normal flow.
 *
 * By default it is a single row: a padlock and one word ("Verified" / "Unverified" /
 * "Not encrypted"). Tapping it expands an explanation in plain language plus the six digit
 * safety code. Nothing here uses the words handshake, ECDH, MITM or AEAD — the people who
 * want those open the Advanced panel.
 */
@Composable
fun TrustIndicator(
    security: SecurityState,
    peerName: String,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    onToggleExpanded: () -> Unit = {},
    onConfirmCode: (() -> Unit)? = null,
) {
    val level = security.trustLevel
    val (icon, tint) = when (level) {
        TrustLevel.VERIFIED -> Icons.Default.VerifiedUser to Color(0xFF4ADE80)
        TrustLevel.UNVERIFIED -> Icons.Default.Lock to MaterialTheme.colorScheme.primary
        TrustLevel.PLAINTEXT -> Icons.Default.LockOpen to MaterialTheme.colorScheme.error
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = tint.copy(alpha = 0.10f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpanded)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = security.headline, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "to $peerName",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Show less" else "Show more",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = explanation(security, peerName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (security.safetyCode != null) {
                        SafetyCodeRow(code = security.safetyCode!!)

                        if (onConfirmCode != null && level != TrustLevel.VERIFIED) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(onClick = onConfirmCode) {
                                    Text("Codes match")
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Only tap this if both devices show the same numbers.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }

                    security.cipherSuiteLabel()?.let { suite ->
                        Text(
                            text = suite,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SafetyCodeRow(code: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = code,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 4.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun explanation(security: SecurityState, peerName: String): String = when (security.trustLevel) {
    TrustLevel.VERIFIED ->
        "You checked this device before, so MeshDrop knows it is really $peerName. " +
            "Files are encrypted before they leave this phone and only $peerName can read them."

    TrustLevel.UNVERIFIED ->
        "Files are encrypted end to end, but this is the first time you have talked to " +
            "$peerName, so there is nothing yet proving who is on the other end. Compare the " +
            "numbers below out loud — if they match, $peerName is marked as verified from now on."

    TrustLevel.PLAINTEXT ->
        "$peerName is using an older MeshDrop (or has encryption switched off), so these files " +
            "travel as plain text across the Wi-Fi network. Anyone else on this network could " +
            "read them."
}

private fun SecurityState.cipherSuiteLabel(): String? =
    mode.cipherSuite?.let { suite -> "Encryption: $suite" }

/** Compact variant for lists (history entries, device rows). */
@Composable
fun TrustBadge(
    security: SecurityState,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
) {
    val level = security.trustLevel
    val (icon: ImageVector, tint: Color) = when (level) {
        TrustLevel.VERIFIED -> Icons.Default.VerifiedUser to Color(0xFF4ADE80)
        TrustLevel.UNVERIFIED -> Icons.Default.Lock to MaterialTheme.colorScheme.primary
        TrustLevel.PLAINTEXT -> Icons.Default.LockOpen to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(imageVector = icon, contentDescription = security.headline, tint = tint, modifier = Modifier.size(14.dp))
        if (showLabel) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = level.label,
                style = MaterialTheme.typography.labelSmall,
                color = tint,
            )
        }
    }
}

/** Tappable wrapper used by history rows where the whole chip opens the explanation. */
@Composable
fun TrustBadgeButton(
    security: SecurityState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(onClick = onClick, modifier = modifier) {
        TrustBadge(security = security)
    }
}
