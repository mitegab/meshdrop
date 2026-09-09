package com.meshdrop.discovery.wifidirect

import com.meshdrop.core.model.DiscoveryKind
import com.meshdrop.core.model.PeerDevice
import com.meshdrop.discovery.api.DiscoveryStrategy
import com.meshdrop.discovery.api.LocalIdentity
import com.meshdrop.discovery.api.StrategyStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Placeholder for Wi-Fi Direct (Wi-Fi P2P) discovery — not part of the MVP.
 *
 * It is registered in `AppContainer` with [isAvailable] = false so the rest of the app can
 * already reason about it. To implement it:
 *
 *  1. `WifiP2pManager.initialize(...)` and register a `WifiP2pManager.Channel`.
 *  2. `discoverPeers(channel, listener)` and map `requestPeers` results into [PeerDevice]
 *     (there is no address yet at this point — use `kind = DiscoveryKind.WIFI_DIRECT` and
 *     leave `host` null).
 *  3. On connect, `requestGroupInfo` gives the group owner address: run the very same
 *     `TransferServer` on that address and report the port through
 *     [com.meshdrop.discovery.api.LocalIdentity].
 *
 * Nothing in `transfer/`, `ui/` or `data/` needs to change: the coordinator merges the
 * resulting peers exactly like the mDNS and beacon ones.
 */
class WifiDirectDiscoveryStrategy : DiscoveryStrategy {

    override val kind: DiscoveryKind = DiscoveryKind.WIFI_DIRECT

    override val isAvailable: Boolean = false

    override val status: StateFlow<StrategyStatus> = MutableStateFlow(
        StrategyStatus(note = "Wi-Fi Direct is not enabled in the MVP build"),
    ).asStateFlow()

    override val peers: StateFlow<List<PeerDevice>> = MutableStateFlow<List<PeerDevice>>(emptyList()).asStateFlow()

    override suspend fun startAdvertising(identity: LocalIdentity) = Unit

    override suspend fun startScan() = Unit
}
