package com.meshdrop.discovery.api

import com.meshdrop.core.model.DiscoveryKind
import com.meshdrop.core.model.PeerDevice
import kotlinx.coroutines.flow.StateFlow

/**
 * Who we are on the local network. Every strategy advertises the same identity so a peer
 * discovered over mDNS, a UDP beacon or (later) Wi-Fi Direct looks identical to the UI.
 */
data class LocalIdentity(
    val deviceId: String,
    val deviceName: String,
    /** TCP port of the local transfer server (0 when we are not accepting transfers). */
    val port: Int,
) {
    /** Short suffix used inside transport-specific service names. */
    val shortId: String get() = deviceId.take(8)
}

data class StrategyStatus(
    val advertising: Boolean = false,
    val scanning: Boolean = false,
    /** Human readable note, e.g. "Wi-Fi Direct needs to be enabled". */
    val note: String? = null,
    val error: String? = null,
) {
    val isActive: Boolean get() = advertising || scanning
}

/**
 * One way of finding peers.
 *
 * Strategies are passive state holders: they expose what they currently see as a
 * [StateFlow] and the [DiscoveryCoordinator] merges every strategy into a single list.
 * Adding Wi-Fi Direct or Bluetooth later means writing a new implementation of this
 * interface and registering it in `AppContainer` — nothing else changes.
 *
 * All methods are optional (default no-op) because not every transport can advertise and
 * scan independently: Wi-Fi Direct for example only does peer discovery.
 */
interface DiscoveryStrategy {

    val kind: DiscoveryKind

    /** Live status of this strategy, for diagnostics and the UI's scanning indicator. */
    val status: StateFlow<StrategyStatus>

    /** Peers this strategy currently believes are online. */
    val peers: StateFlow<List<PeerDevice>>

    /** True when the transport exists on this device and the app has permission to use it. */
    val isAvailable: Boolean get() = true

    /** Tell the world that we are here. */
    suspend fun startAdvertising(identity: LocalIdentity) {}

    suspend fun stopAdvertising() {}

    /** Look for other devices. */
    suspend fun startScan() {}

    suspend fun stopScan() {}

    /** User-initiated connection setup for a peer that does not have an IP endpoint yet. */
    suspend fun connect(peer: PeerDevice): PeerDevice = peer
}
