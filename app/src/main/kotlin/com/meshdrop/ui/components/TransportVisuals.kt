package com.meshdrop.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.DeviceHub
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.meshdrop.core.model.TransportLane

/**
 * One icon (and one colour) per transport, used identically on every screen: the Home strip,
 * the transfer lane bar, the Advanced panel and the Settings transports list.
 *
 * Consistency here is the whole point — the user should recognise "this is the Wi-Fi lane"
 * from the glyph alone, in the same way they recognise a battery icon.
 */
enum class TransportVisual(
    val lane: TransportLane,
    val icon: ImageVector,
    val tint: Color,
) {
    WIFI_LAN(TransportLane.WIFI_LAN, Icons.Default.Wifi, Color(0xFF4FC3F7)),
    WIFI_DIRECT(TransportLane.WIFI_DIRECT, Icons.Default.DeviceHub, Color(0xFF00CBB4)),
    BLUETOOTH(TransportLane.BLUETOOTH, Icons.Default.Bluetooth, Color(0xFF7E9BFF)),
    RELAY(TransportLane.RELAY, Icons.Default.Hub, Color(0xFFFFB74D)),
    USB(TransportLane.USB, Icons.Default.Usb, Color(0xFFB0BEC5)),
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
    tint: Color = TransportVisual.of(lane).tint,
) {
    Icon(
        imageVector = TransportVisual.of(lane).icon,
        contentDescription = contentDescription ?: TransportVisual.of(lane).lane.label,
        modifier = modifier,
        tint = tint,
    )
}

/** Neutral colour for a lane that is present but not currently carrying bytes. */
@Composable
fun laneIdleColor(): Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)

/**
 * Deterministic device colour, so the same peer is always the same colour across screens
 * without persisting anything.
 */
fun deviceColor(deviceId: String): Color {
    val palette = listOf(
        Color(0xFF00CBB4),
        Color(0xFF4FC3F7),
        Color(0xFFFFB74D),
        Color(0xFFBA9CFF),
        Color(0xFF81D4A0),
        Color(0xFFF58BA8),
    )
    val hash = deviceId.fold(0) { acc, char -> acc * 31 + char.code }
    return palette[Math.floorMod(hash, palette.size)]
}

/** "NP" for "Nazret's Phone". */
fun deviceInitials(name: String): String = name
    .split(Regex("\\s+"))
    .filter { it.isNotBlank() }
    .take(2)
    .map { it.first().uppercaseChar() }
    .joinToString("")
    .ifBlank { "?" }
