package com.meshdrop.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.DeviceHub
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.meshdrop.core.model.TransportLane
import com.meshdrop.ui.theme.MeshTheme

/**
 * One glyph per transport, used identically everywhere: the lane chip on a live transfer, the
 * peer row, and the network section in Settings.
 *
 * The glyph carries the meaning; the tint does not. The previous version gave each lane its
 * own saturated colour (sky blue for Wi-Fi, teal for Direct, amber for relay), which made the
 * transfer card look like a network diagram and put four competing accents on a screen that
 * has one. Now every lane is the same quiet ink, and the only colour that appears during a
 * transfer is the clay of the bytes actually moving.
 */
enum class TransportVisual(
    val lane: TransportLane,
    val icon: ImageVector,
) {
    WIFI_LAN(TransportLane.WIFI_LAN, Icons.Outlined.Wifi),
    WIFI_DIRECT(TransportLane.WIFI_DIRECT, Icons.Outlined.DeviceHub),
    BLUETOOTH(TransportLane.BLUETOOTH, Icons.Outlined.Bluetooth),
    RELAY(TransportLane.RELAY, Icons.Outlined.Hub),
    USB(TransportLane.USB, Icons.Outlined.Usb),
    ;

    companion object {
        fun of(lane: TransportLane): TransportVisual = entries.firstOrNull { it.lane == lane } ?: WIFI_LAN
    }
}

@Composable
fun TransportIcon(
    lane: TransportLane,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    tint: Color = MeshTheme.colors.inkMuted,
) {
    Icon(
        imageVector = TransportVisual.of(lane).icon,
        contentDescription = contentDescription ?: TransportVisual.of(lane).lane.label,
        modifier = modifier,
        tint = tint,
    )
}

/**
 * A deterministic tint for a device, so the same phone is the same colour every time without
 * storing anything.
 *
 * Tints come from the palette's warm ramp, which means a list of devices reads as a set of
 * tints of the same paper rather than a bag of stickers.
 */
@Composable
fun deviceColor(deviceId: String): Color {
    val palette = MeshTheme.colors.deviceTints
    if (palette.isEmpty()) return MeshTheme.colors.inkMuted
    val hash = deviceId.fold(0) { acc, char -> acc * 31 + char.code }
    return palette[Math.floorMod(hash, palette.size)]
}

/** "AP" for "Ana's Pixel". */
fun deviceInitials(name: String): String = name
    .split(Regex("\\s+"))
    .filter { it.isNotBlank() }
    .take(2)
    .map { it.first().uppercaseChar() }
    .joinToString("")
    .ifBlank { "?" }
