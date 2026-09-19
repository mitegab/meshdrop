package com.meshdrop.core.model

/**
 * The pipe the bytes are currently travelling through.
 *
 * Direct IP, native byte streams and consented helper delivery share this vocabulary:
 * one enum, one icon set, one colour. Discovery does not determine the payload lane.
 */
enum class TransportLane(val label: String, val shortLabel: String) {
    WIFI_LAN("Wi-Fi LAN", "Wi-Fi"),
    WIFI_DIRECT("Wi-Fi Direct", "Wi-Fi Direct"),
    BLUETOOTH("Bluetooth", "Bluetooth"),
    RELAY("Relay hop", "Relay"),
    USB("USB-C cable", "USB"),
    ;

    /** Rough ordering for "best lane first" display. */
    val rank: Int get() = when (this) {
        WIFI_DIRECT -> 0
        WIFI_LAN -> 1
        RELAY -> 2
        BLUETOOTH -> 3
        USB -> 4
    }
}

/**
 * Live activity for one lane during a transfer.
 *
 * @param bytesPerSecond current share of the throughput going through this lane
 * @param active false once the lane has been abandoned (a failover happened)
 * @param failedOverFrom the lane we were using before, when [active] is false — drives the
 *                       "route line moved" animation in the UI
 */
data class LaneActivity(
    val lane: TransportLane,
    val bytesPerSecond: Long = 0L,
    val active: Boolean = true,
    val failedOverFrom: TransportLane? = null,
)

/**
 * One peer's share of a batch (swarm mode).
 *
 * Direct sends report the destination; swarm sends report actual helper assignments and
 * destination-confirmed delivery. The UI shows up to [MAX_VISIBLE] contribution chips.
 */
data class PeerContribution(
    val peerId: String,
    val displayName: String,
    val lane: TransportLane,
    val bytesAssigned: Long = 0L,
    val bytesDelivered: Long = 0L,
    val isActive: Boolean = true,
) {
    val progress: Float
        get() = if (bytesAssigned <= 0L) 0f else (bytesDelivered.toFloat() / bytesAssigned.toFloat()).coerceIn(0f, 1f)

    companion object {
        /** Casual users should never have to read more than this many chips. */
        const val MAX_VISIBLE = 4
    }
}

/** Which side of the conversation we are on. */
enum class EndpointRole { SENDER, RECEIVER }

/** Which lane a discovery transport normally carries bytes over. */
fun DiscoveryKind.toLane(): TransportLane = when (this) {
    DiscoveryKind.WIFI_DIRECT -> TransportLane.WIFI_DIRECT
    DiscoveryKind.BLUETOOTH -> TransportLane.BLUETOOTH
    DiscoveryKind.USB -> TransportLane.USB
    DiscoveryKind.LAN_NSD, DiscoveryKind.LAN_BEACON -> TransportLane.WIFI_LAN
}
