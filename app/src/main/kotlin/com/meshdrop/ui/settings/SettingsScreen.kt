package com.meshdrop.ui.settings

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshdrop.core.model.EncryptionMode
import com.meshdrop.core.model.TransportLane
import com.meshdrop.transfer.protocol.TransferProtocol
import com.meshdrop.ui.components.TransportIcon

/**
 * Settings, deliberately ordered: privacy and security first, plumbing last.
 *
 * The transport section is collapsed because for most people it is a fact, not a choice —
 * it is the hook where Wi-Fi Direct, Bluetooth, USB-C or LoRa backends will appear without
 * disturbing the top of the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var renameDraft by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { TopAppBar(title = { Text("Settings") }) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            item {
                SectionHeader("Privacy & security")

                ToggleRow(
                    icon = Icons.Default.Lock,
                    iconTint = MaterialTheme.colorScheme.primary,
                    title = "End-to-end encryption",
                    subtitle = if (uiState.encryptionEnabled) {
                        "On · ${EncryptionMode.E2E_AES_256_GCM.cipherSuite}"
                    } else {
                        "Off · files would be readable by anyone on this network"
                    },
                    checked = uiState.encryptionEnabled,
                    onCheckedChange = viewModel::setEncryption,
                )

                ToggleRow(
                    icon = Icons.Default.CheckCircle,
                    iconTint = Color(0xFF4ADE80),
                    title = "Ask to verify new devices",
                    subtitle = "Compare a short code the first time you send to someone",
                    checked = uiState.requireVerification,
                    onCheckedChange = viewModel::setRequireVerification,
                )

                ToggleRow(
                    icon = Icons.Default.CheckCircle,
                    iconTint = MaterialTheme.colorScheme.primary,
                    title = "Auto-accept from verified devices",
                    subtitle = "Skip the accept prompt for devices you have verified",
                    checked = uiState.autoAcceptVerified,
                    onCheckedChange = viewModel::setAutoAcceptVerified,
                )

                ToggleRow(
                    icon = Icons.Default.Lock,
                    iconTint = MaterialTheme.colorScheme.primary,
                    title = "Vault lock",
                    subtitle = "Require your device lock before MeshDrop opens",
                    checked = uiState.vaultLock,
                    onCheckedChange = viewModel::setVaultLock,
                )

                // Not a switch: there is nothing to turn off, because nothing is collected.
                StaticRow(
                    icon = Icons.Default.CheckCircle,
                    iconTint = Color(0xFF4ADE80),
                    title = "No telemetry, ever",
                    subtitle = "No accounts, no servers, no analytics, no crash reports. " +
                        "Everything stays on these two devices.",
                )
            }

            item {
                SectionHeader("This device")
                ClickRow(
                    title = "Device name",
                    subtitle = uiState.deviceName,
                    onClick = { renameDraft = uiState.deviceName },
                )
                StaticRow(
                    icon = Icons.Default.Lock,
                    iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                    title = "Identity key",
                    subtitle = "Generated and stored in this phone's keystore · never leaves the device",
                )
            }

            item {
                SectionHeader("Verified devices")
                val trusted = viewModel.trustedPeers()
                if (trusted.isEmpty()) {
                    StaticRow(
                        icon = Icons.Default.CheckCircle,
                        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                        title = "None yet",
                        subtitle = "Compare a safety code during a transfer and that device is remembered here.",
                    )
                } else {
                    trusted.forEach { (fingerprint, label) ->
                        ClickRow(
                            title = label,
                            subtitle = fingerprint,
                            action = "Forget",
                            onClick = { viewModel.forgetPeer(fingerprint) },
                        )
                    }
                }
            }

            // Pluggable transport backends live here, collapsed.
            item {
                SectionHeader("Network")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = viewModel::toggleTransports)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Transports", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Icon(
                        imageVector = if (uiState.transportsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AnimatedVisibility(
                    visible = uiState.transportsExpanded,
                    enter = expandVertically(),
                    exit = shrinkVertically(),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                        TransportRow(
                            lane = TransportLane.WIFI_LAN,
                            title = "Wi-Fi LAN (mDNS + beacon)",
                            subtitle = "Active · port ${viewModel.localPort}",
                            enabled = true,
                        )
                        TransportRow(
                            lane = TransportLane.WIFI_DIRECT,
                            title = "Wi-Fi Direct",
                            subtitle = "Registered, not enabled on this build",
                            enabled = false,
                        )
                        TransportRow(
                            lane = TransportLane.BLUETOOTH,
                            title = "Bluetooth",
                            subtitle = "Planned",
                            enabled = false,
                        )
                        TransportRow(
                            lane = TransportLane.USB,
                            title = "USB-C cable",
                            subtitle = "Planned",
                            enabled = false,
                        )
                        TransportRow(
                            lane = TransportLane.RELAY,
                            title = "Relay through a nearby device",
                            subtitle = "Planned",
                            enabled = false,
                        )
                    }
                }
            }

            item {
                SectionHeader("Advanced")
                StaticRow(
                    icon = Icons.Default.Wifi,
                    iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                    title = "Protocol",
                    subtitle = "MeshDrop wire protocol v${TransferProtocol.VERSION}",
                )
                StaticRow(
                    icon = Icons.Default.Lock,
                    iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                    title = "Cipher",
                    subtitle = EncryptionMode.E2E_AES_256_GCM.cipherSuite
                        ?: "none",
                )
                StaticRow(
                    icon = Icons.Default.Usb,
                    iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                    title = "Frame size",
                    subtitle = "64 KB chunks, one AES-GCM tag each",
                )
            }

            item { Spacer(modifier = Modifier.height(28.dp)) }
        }
    }

    renameDraft?.let { draft ->
        AlertDialog(
            onDismissRequest = { renameDraft = null },
            title = { Text("Device name") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { renameDraft = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setDeviceName(draft)
                    renameDraft = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renameDraft = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun ToggleRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun StaticRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ClickRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    action: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        action?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun TransportRow(
    lane: TransportLane,
    title: String,
    subtitle: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportIcon(lane = lane, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (enabled) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(Color(0xFF4ADE80), androidx.compose.foundation.shape.CircleShape),
                )
            }
        }
    }
}
