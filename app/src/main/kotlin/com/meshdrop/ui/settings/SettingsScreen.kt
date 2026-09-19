package com.meshdrop.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.selection.SelectionContainer
import com.meshdrop.core.model.DiscoveryKind
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshdrop.core.model.EncryptionMode
import com.meshdrop.core.model.TransportLane
import com.meshdrop.transfer.protocol.TransferProtocol
import com.meshdrop.ui.components.Eyebrow
import com.meshdrop.ui.components.Hairline
import com.meshdrop.ui.components.RowAction
import com.meshdrop.ui.components.TextAction
import com.meshdrop.ui.components.ToggleRow
import com.meshdrop.ui.components.TransportIcon
import com.meshdrop.ui.drop.RenameDialog
import com.meshdrop.ui.theme.MeshText
import com.meshdrop.ui.theme.MeshTheme
import com.meshdrop.ui.theme.Space

/**
 * Settings, in the order the app thinks about the world.
 *
 * Privacy first and *stated as consequences* ("files travel as plain text") rather than as
 * feature names, then this phone's identity, then the devices you have chosen to trust, and
 * only then the network plumbing — which is a fact about the app, not a choice, and stays
 * folded up at the bottom.
 *
 * Every section is separated by a hairline instead of being boxed in a card: at this density
 * a stack of cards reads as a form to be filled in, and this screen is a page to be read.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val colors = MeshTheme.colors
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var renameDraft by remember { mutableStateOf<String?>(null) }
    val trusted = viewModel.trustedPeers()
    val transports by viewModel.strategyStatuses.collectAsStateWithLifecycle()
    var pairingOpen by remember { mutableStateOf(false) }
    var pairingDraft by remember { mutableStateOf("") }
    val pairing = com.meshdrop.core.pairing.PairingPayload.decode(pairingDraft)

    if (pairingOpen) {
        AlertDialog(
            onDismissRequest = { pairingOpen = false },
            title = { Text("Pair using a code") },
            text = {
                Column {
                    Text("Share this code only through a channel you trust. A code verifies identity; it does not connect networks.")
                    Spacer(Modifier.height(Space.md))
                    SelectionContainer { Text(viewModel.pairingCode() ?: "Start receiving on Drop to show your code") }
                    Spacer(Modifier.height(Space.md))
                    OutlinedTextField(value = pairingDraft, onValueChange = { pairingDraft = it.take(512) },
                        label = { Text("Other device's code") }, modifier = Modifier.fillMaxWidth())
                    Text(if (pairing != null) "Trust ${pairing.deviceName}? Fingerprint: ${pairing.fingerprint}" else "Paste a complete MeshDrop pairing code.")
                }
            },
            confirmButton = {
                TextButton(enabled = pairing != null, onClick = {
                    if (viewModel.trustPairingCode(pairingDraft)) { pairingOpen = false; pairingDraft = "" }
                }) { Text("Trust this device") }
            },
            dismissButton = { TextButton(onClick = { pairingOpen = false }) { Text("Close") } },
        )
    }

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = colors.canvas,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = Space.gutter),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = Space.xxl),
        ) {
            item(key = "header") {
                Column(modifier = Modifier.padding(top = Space.xl, bottom = Space.lg)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconAction(
                            icon = Icons.Outlined.ArrowBack,
                            contentDescription = "Back",
                            onClick = onBack,
                        )
                    }
                    Spacer(modifier = Modifier.height(Space.sm))
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.displayMedium,
                        color = colors.ink,
                    )
                }
            }

            // ---- privacy ------------------------------------------------------------------
            item(key = "privacy") {
                Eyebrow(text = "Privacy and trust")
                Spacer(modifier = Modifier.height(Space.xs))
                Text(
                    text = "What protects your files",
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.ink,
                )

                ToggleRow(
                    title = "Encrypt files end to end",
                    subtitle = if (uiState.encryptionEnabled) {
                        "On. Files are scrambled before they leave this phone."
                    } else {
                        "Off. Anything you send can be read by anyone on this Wi-Fi network."
                    },
                    checked = uiState.encryptionEnabled,
                    onCheckedChange = viewModel::setEncryption,
                )
                Hairline()
                ToggleRow(
                    title = "Check new devices with a code",
                    subtitle = "Asks you to compare six digits the first time you send to " +
                        "someone, which is how you know who is really on the other end.",
                    checked = uiState.requireVerification,
                    onCheckedChange = viewModel::setRequireVerification,
                )
                Hairline()
                ToggleRow(
                    title = "Accept automatically from devices you trust",
                    subtitle = if (uiState.autoAcceptVerified) {
                        "On. Devices you have verified send without asking."
                    } else {
                        "Off. Every transfer asks you first."
                    },
                    checked = uiState.autoAcceptVerified,
                    onCheckedChange = viewModel::setAutoAcceptVerified,
                )
                Hairline()
                ToggleRow(
                    title = "Lock the vault with your screen lock",
                    subtitle = "Files you move to the vault need your fingerprint or PIN to open.",
                    checked = uiState.vaultLock,
                    onCheckedChange = viewModel::setVaultLock,
                )
                Hairline()
            }

            item(key = "physical-links") {
                Spacer(Modifier.height(Space.lg))
                Eyebrow(text = "Without Wi-Fi")
                Text("Bluetooth and USB carry encrypted files directly, not through tethering.", style = MaterialTheme.typography.bodyMedium)
                TextAction(text = "Pair a Bluetooth device", onClick = {
                    context.startActivity(android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
                })
                TextAction(text = "Make this phone discoverable", onClick = {
                    runCatching { context.startActivity(android.content.Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                        .putExtra(android.bluetooth.BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)) }
                        .onFailure { viewModel.showMessage("Grant Bluetooth access first") }
                })
                viewModel.bluetoothDevices().forEach { (id, label) ->
                    TextAction(text = "Connect Bluetooth: $label", onClick = { viewModel.connectBluetooth(id) })
                }
                viewModel.usbDevices().forEach { (id, label) ->
                    TextAction(text = "Connect USB: $label", onClick = { viewModel.connectUsb(id) })
                }
                TextAction(text = "Refresh attached devices", onClick = { viewModel.showMessage("Attached devices refreshed") })
                Hairline()
            }

            item(key = "pairing") {
                TextAction(text = "Pair using a code", onClick = { pairingOpen = true })
                Hairline()
            }

            item(key = "statement") {
                Spacer(modifier = Modifier.height(Space.lg))
                Text(
                    text = "There is no account to create and no server to trust. MeshDrop " +
                        "sends nothing about you anywhere — your files go phone to phone over " +
                        "this Wi-Fi network, and the only thing kept on this device is the " +
                        "list of transfers you can see in Activity.",
                    style = MeshText.Note,
                    color = colors.ink,
                )
                Spacer(modifier = Modifier.height(Space.lg))
                Hairline()
            }

            // ---- this device --------------------------------------------------------------
            item(key = "device") {
                Spacer(modifier = Modifier.height(Space.xl))
                Eyebrow(text = "This phone")
                Spacer(modifier = Modifier.height(Space.xs))
                RowAction(
                    title = "Name",
                    subtitle = "How other phones see you in their list",
                    value = uiState.deviceName,
                    onClick = { renameDraft = uiState.deviceName },
                )
                Hairline()
                RowAction(
                    title = "Identity key",
                    subtitle = "Generated inside this phone's keystore. It never leaves the device.",
                    value = "P-256",
                )
                Hairline()
                RowAction(
                    title = "Activity log",
                    subtitle = "Kept on this phone for 90 days, then dropped",
                    value = "On device",
                )
                Hairline()
            }

            // ---- trusted devices ----------------------------------------------------------
            item(key = "trusted") {
                Spacer(modifier = Modifier.height(Space.xl))
                Eyebrow(text = "Devices you trust")
                Spacer(modifier = Modifier.height(Space.xs))
                if (trusted.isEmpty()) {
                    Text(
                        text = "None yet. Compare the six digits next time someone sends to you " +
                            "and that device is remembered here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.inkMuted,
                        modifier = Modifier.padding(vertical = Space.md),
                    )
                } else {
                    trusted.forEach { (fingerprint, label) ->
                        RowAction(
                            title = label,
                            subtitle = fingerprint,
                            value = "Forget",
                            onClick = { viewModel.forgetPeer(fingerprint) },
                        )
                        Hairline()
                    }
                }
            }

            // ---- network ------------------------------------------------------------------
            item(key = "network") {
                Spacer(modifier = Modifier.height(Space.xl))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = viewModel::toggleTransports)
                        .padding(vertical = Space.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Eyebrow(text = "Network")
                        Spacer(modifier = Modifier.height(Space.xs))
                        Text(
                            text = "How phones find each other",
                            style = MaterialTheme.typography.headlineSmall,
                            color = colors.ink,
                        )
                    }
                    Icon(
                        imageVector = if (uiState.transportsExpanded) {
                            Icons.Outlined.KeyboardArrowUp
                        } else {
                            Icons.Outlined.KeyboardArrowDown
                        },
                        contentDescription = if (uiState.transportsExpanded) "Hide" else "Show",
                        tint = colors.inkMuted,
                    )
                }

                if (uiState.transportsExpanded) {
                    TransportRow(
                        lane = TransportLane.WIFI_LAN,
                        title = "Wi-Fi network",
                        subtitle = if (viewModel.localPort > 0) "Listening on port ${viewModel.localPort}" else "Receiver paused",
                        active = viewModel.localPort > 0,
                    )
                    TransportRow(
                        lane = TransportLane.WIFI_DIRECT,
                        title = "Wi-Fi Direct",
                        subtitle = transports[DiscoveryKind.WIFI_DIRECT]?.let {
                            it.error ?: if (it.isActive) "Discovering nearby devices · tap a device to connect" else it.note ?: "Inactive"
                        } ?: "Not supported on this device",
                        active = transports[DiscoveryKind.WIFI_DIRECT]?.isActive == true,
                    )
                    TransportRow(
                        lane = TransportLane.BLUETOOTH,
                        title = "Bluetooth",
                        subtitle = transports[DiscoveryKind.BLUETOOTH]?.let {
                            it.error ?: if (it.isActive) "RFCOMM payloads and BLE discovery" else it.note ?: "Inactive"
                        } ?: "Not supported on this device",
                        active = transports[DiscoveryKind.BLUETOOTH]?.isActive == true,
                    )
                    TransportRow(
                        lane = TransportLane.USB,
                        title = "USB-C cable",
                        subtitle = transports[DiscoveryKind.USB]?.note ?: "Connect an AOA host/accessory above",
                        active = transports[DiscoveryKind.USB]?.isActive == true,
                    )
                    Spacer(modifier = Modifier.height(Space.sm))
                }
                Hairline()
            }

            // ---- about --------------------------------------------------------------------
            item(key = "about") {
                Spacer(modifier = Modifier.height(Space.xl))
                Eyebrow(text = "About")
                Spacer(modifier = Modifier.height(Space.xs))
                RowAction(
                    title = "Protocol",
                    subtitle = "Wire format spoken with other phones",
                    value = TransferProtocol.VERSION.toString(),
                    valueMono = true,
                )
                Hairline()
                RowAction(
                    title = "Cipher",
                    subtitle = if (uiState.encryptionEnabled) "In use when encryption is on" else "Unused",
                    value = EncryptionMode.E2E_AES_256_GCM.cipherSuite ?: "—",
                )
                Hairline()
                Spacer(modifier = Modifier.height(Space.lg))
                Text(
                    text = "MeshDrop · no accounts, no cloud, no telemetry.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkMuted,
                )
                Spacer(modifier = Modifier.height(Space.lg))
            }
        }
    }

    renameDraft?.let { draft ->
        RenameDialog(
            draft = draft,
            onDraftChange = { renameDraft = it },
            onConfirm = {
                viewModel.setDeviceName(draft)
                renameDraft = null
            },
            onDismiss = { renameDraft = null },
        )
    }
}

@Composable
private fun TransportRow(
    lane: TransportLane,
    title: String,
    subtitle: String,
    active: Boolean,
) {
    val colors = MeshTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransportIcon(
            lane = lane,
            modifier = Modifier.size(18.dp),
            tint = if (active) colors.ink else colors.inkMuted.copy(alpha = 0.55f),
        )
        Spacer(modifier = Modifier.size(Space.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (active) colors.ink else colors.inkMuted,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkMuted,
            )
        }
        if (active) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(colors.success),
            )
        }
    }
}

@Composable
private fun IconAction(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(19.dp),
            tint = MeshTheme.colors.inkMuted,
        )
    }
}
