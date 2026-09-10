package com.meshdrop.discovery.udp

import com.meshdrop.core.model.DiscoveryKind
import com.meshdrop.core.model.PeerDevice
import com.meshdrop.core.util.MeshLog
import com.meshdrop.discovery.api.DiscoveryStrategy
import com.meshdrop.discovery.api.LocalIdentity
import com.meshdrop.discovery.api.StrategyStatus
import com.meshdrop.net.LocalNetworkInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Fallback discovery: a tiny UDP beacon broadcast on the local subnet.
 *
 * mDNS is the primary mechanism ([com.meshdrop.discovery.nsd.NsdDiscoveryStrategy]) but it
 * is blocked or throttled on some routers, guest networks and Android skins. A one-packet
 * beacon every few seconds is cheap, needs no system service, and makes the MVP work in
 * those environments.
 *
 * Wire format (UTF-8, pipe separated):
 *
 *      MESHDROP/1|<deviceId>|<port>|<displayName>
 */
class UdpBeaconDiscoveryStrategy(
    private val scope: CoroutineScope,
    private val now: () -> Long = { System.currentTimeMillis() },
) : DiscoveryStrategy {

    override val kind: DiscoveryKind = DiscoveryKind.LAN_BEACON

    private val _status = MutableStateFlow(StrategyStatus())
    override val status: StateFlow<StrategyStatus> = _status.asStateFlow()

    private val _peers = MutableStateFlow<List<PeerDevice>>(emptyList())
    override val peers: StateFlow<List<PeerDevice>> = _peers.asStateFlow()

    @Volatile
    private var socket: DatagramSocket? = null
    private var receiverJob: Job? = null
    private var senderJob: Job? = null

    @Volatile
    private var identity: LocalIdentity? = null

    override suspend fun startAdvertising(identity: LocalIdentity) {
        this.identity = identity
        // Both directions share one socket: advertising and scanning are the same UDP port.
        startScan()
    }

    override suspend fun stopAdvertising() {
        identity = null
        if (_status.value.scanning) return
        stopScan()
    }

    override suspend fun startScan() {
        val current = socket
        if (current != null && !current.isClosed) {
            _status.update { it.copy(scanning = true, advertising = true) }
            return
        }

        val opened = runCatching {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(BEACON_PORT))
            }
        }.getOrElse { error ->
            MeshLog.w(TAG, "Could not open beacon socket", error)
            _status.update { it.copy(scanning = false, error = "Local network is unavailable") }
            return
        }
        socket = opened
        _status.update { it.copy(scanning = true, advertising = true, error = null) }

        receiverJob = scope.launch(Dispatchers.IO) { receiveLoop(opened) }
        senderJob = scope.launch(Dispatchers.IO) { advertiseLoop(opened) }
        MeshLog.i(TAG, "Beacon listening on UDP $BEACON_PORT")
    }

    override suspend fun stopScan() {
        receiverJob?.cancel()
        senderJob?.cancel()
        receiverJob = null
        senderJob = null
        runCatching { socket?.close() }
        socket = null
        _peers.value = emptyList()
        _status.update { it.copy(scanning = false, advertising = false) }
    }

    private suspend fun advertiseLoop(socket: DatagramSocket) {
        while (scope.isActive && !socket.isClosed) {
            val self = identity
            if (self != null) {
                val payload = encode(self)
                val data = payload.toByteArray(Charsets.UTF_8)
                LocalNetworkInfo.broadcastAddresses().forEach { address ->
                    runCatching {
                        socket.send(DatagramPacket(data, data.size, address, BEACON_PORT))
                    }.onFailure { error ->
                        MeshLog.v(TAG, "Beacon send to $address failed: ${error.message}")
                    }
                }
            }
            delay(ADVERTISE_INTERVAL_MS)
        }
    }

    private suspend fun receiveLoop(socket: DatagramSocket) {
        val buffer = ByteArray(MAX_PACKET_BYTES)
        while (scope.isActive && !socket.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            val received = runCatching { socket.receive(packet) }
            if (received.isFailure) {
                if (!socket.isClosed) MeshLog.w(TAG, "Beacon receive failed", received.exceptionOrNull())
                break
            }
            val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
            val peer = decode(text, packet.address)
            if (peer != null) {
                _peers.update { list -> list.filterNot { it.id == peer.id } + peer }
            }
        }
    }

    private fun encode(identity: LocalIdentity): String = listOf(
        MAGIC,
        identity.deviceId,
        identity.port.toString(),
        sanitize(identity.deviceName),
    ).joinToString(SEPARATOR)

    private fun decode(raw: String, address: InetAddress): PeerDevice? {
        val parts = raw.split(SEPARATOR, limit = 4)
        if (parts.size < 4 || parts[0] != MAGIC) return null
        val id = parts[1]
        val port = parts[2].toIntOrNull() ?: return null
        val name = parts[3].ifBlank { id.take(8) }
        val self = identity
        if (self != null && id == self.deviceId) return null
        if (port <= 0) return null
        return PeerDevice(
            id = id,
            name = name,
            kind = DiscoveryKind.LAN_BEACON,
            host = address,
            port = port,
            lastSeenMillis = now(),
        )
    }

    /** Beacons are a single line of text: keep control characters out of it. */
    private fun sanitize(name: String): String = name
        .replace(SEPARATOR, " ")
        .replace(Regex("[\\r\\n\\t]"), " ")
        .trim()
        .take(48)
        .ifEmpty { "MeshDrop device" }

    companion object {
        const val TAG = "BeaconDiscovery"

        /** Chosen to be unlikely to collide with anything else on a home network. */
        const val BEACON_PORT = 45821

        private const val MAGIC = "MESHDROP/1"
        private const val SEPARATOR = "|"
        private const val ADVERTISE_INTERVAL_MS = 3_000L
        private const val MAX_PACKET_BYTES = 512
    }
}
