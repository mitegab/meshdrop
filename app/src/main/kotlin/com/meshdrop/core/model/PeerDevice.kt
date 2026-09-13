package com.meshdrop.core.model

import java.net.InetAddress

/**
 * How a peer was found.
 *
 * The MVP only ships [LAN_NSD] and [LAN_BEACON]; [WIFI_DIRECT] and [BLUETOOTH] exist so the
 * UI and the coordinator can already talk about them, and so a future strategy has nothing
 * to change outside of `discovery/`.
 */
enum class DiscoveryKind(val label: String) {
    LAN_NSD("Wi-Fi"),
    LAN_BEACON("Wi-Fi"),
    WIFI_DIRECT("Wi-Fi Direct"),
    BLUETOOTH("Bluetooth"),
}

/**
 * Which transport a discovery kind actually rides.
 *
 * Two kinds can be the same lane: mDNS and the UDP beacon both describe "on the Wi-Fi
 * network", so failing over from one to the other is not a failover at all — it is the same
 * pipe with a different way of finding it.
 */
val DiscoveryKind.lane: TransportLane
    get() = when (this) {
        DiscoveryKind.LAN_NSD, DiscoveryKind.LAN_BEACON -> TransportLane.WIFI_LAN
        DiscoveryKind.WIFI_DIRECT -> TransportLane.WIFI_DIRECT
        DiscoveryKind.BLUETOOTH -> TransportLane.BLUETOOTH
    }

/**
 * A nearby device we can send files to.
 *
 * @param id Stable identity advertised by the peer. The same physical device may be reported
 *           by several strategies (for example both mDNS and a UDP beacon); the coordinator
 *           merges those into a single [PeerDevice] using this id.
 * @param host Resolved address of the peer's transfer server, when the strategy knows it.
 * @param port TCP port the peer is listening on.
 * @param lastSeenMillis Wall clock of the last advertisement, used to expire stale peers.
 */
data class PeerDevice(
    val id: String,
    val name: String,
    val kind: DiscoveryKind,
    val host: InetAddress? = null,
    val port: Int = 0,
    val lastSeenMillis: Long = System.currentTimeMillis(),
) {
    /** Address in the form `192.168.1.7:45821`, for display and logs. */
    val endpoint: String
        get() = host?.hostAddress?.let { address ->
            if (port > 0) "$address:$port" else address
        } ?: "resolving…"

    fun isReachable(): Boolean = host != null && port > 0
}
