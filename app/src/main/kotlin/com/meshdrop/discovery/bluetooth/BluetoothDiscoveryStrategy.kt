package com.meshdrop.discovery.bluetooth

import com.meshdrop.core.model.DiscoveryKind
import com.meshdrop.core.model.PeerDevice
import com.meshdrop.discovery.api.DiscoveryStrategy
import com.meshdrop.discovery.api.LocalIdentity
import com.meshdrop.discovery.api.StrategyStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Placeholder for Bluetooth discovery — not part of the MVP.
 *
 * Bluetooth is useful when there is no shared Wi-Fi network at all (that is the real
 * SHAREit scenario): BLE advertises a service UUID carrying the device id, and the actual
 * payload still travels over TCP once a Wi-Fi Direct group or hotspot is established.
 *
 * Sketched implementation:
 *  1. BLE advertiser (`BluetoothLeAdvertiser`) announces a 128-bit MeshDrop service UUID
 *     with the device id encoded in the manufacturer data; a `BluetoothLeScanner` builds
 *     [PeerDevice]s from the advertisements found around us.
 *  2. RFCOMM (`BluetoothServerSocket`) is only used for the tiny "let's set up a group"
 *     control message — files keep flowing over the existing `TransferServer`.
 *  3. Permissions: `BLUETOOTH_SCAN` / `BLUETOOTH_ADVERTISE` on Android 12+,
 *     `ACCESS_FINE_LOCATION` before that; add them to `AndroidManifest.xml` and to
 *     `requiredPermissions()` in the permissions controller.
 */
class BluetoothDiscoveryStrategy : DiscoveryStrategy {

    override val kind: DiscoveryKind = DiscoveryKind.BLUETOOTH

    override val isAvailable: Boolean = false

    override val status: StateFlow<StrategyStatus> = MutableStateFlow(
        StrategyStatus(note = "Bluetooth is not enabled in the MVP build"),
    ).asStateFlow()

    override val peers: StateFlow<List<PeerDevice>> = MutableStateFlow(emptyList()).asStateFlow()

    override suspend fun startAdvertising(identity: LocalIdentity) = Unit

    override suspend fun startScan() = Unit
}
