package com.meshdrop.discovery.wifidirect

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import com.meshdrop.core.model.DiscoveryKind
import com.meshdrop.core.model.PeerDevice
import com.meshdrop.discovery.api.DiscoveryStrategy
import com.meshdrop.discovery.api.LocalIdentity
import com.meshdrop.discovery.api.StrategyStatus
import com.meshdrop.transfer.protocol.Http.readResponse
import com.meshdrop.transfer.protocol.Http.writeRequest
import com.meshdrop.transfer.protocol.TransferProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** DNS-SD over Wi-Fi P2P. Groups are formed only after the user selects a device. */
@SuppressLint("MissingPermission") // Platform calls fail into status; LAN remains available.
class WifiDirectDiscoveryStrategy(
    context: Context,
    private val scope: CoroutineScope,
) : DiscoveryStrategy {
    private val context = context.applicationContext
    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var service: WifiP2pDnsSdServiceInfo? = null
    private var request: WifiP2pDnsSdServiceRequest? = null
    private var scanner: Job? = null
    private var identity: LocalIdentity? = null
    private val addresses = ConcurrentHashMap<String, String>()
    private var madeConnection = false

    override val kind = DiscoveryKind.WIFI_DIRECT
    override val isAvailable get() = manager != null && context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
    private val state = MutableStateFlow(StrategyStatus(note = "Select a Wi-Fi Direct device to create a local group"))
    override val status = state.asStateFlow()
    private val found = MutableStateFlow<List<PeerDevice>>(emptyList())
    override val peers = found.asStateFlow()

    private fun channel(): WifiP2pManager.Channel {
        channel?.let { return it }
        val manager = requireNotNull(manager) { "Wi-Fi Direct is not supported" }
        return manager.initialize(context, context.mainLooper) {
            channel = null
            state.value = StrategyStatus(error = "Wi-Fi Direct disconnected; refresh to reconnect")
        }.also { connection ->
            channel = connection
            manager.setDnsSdResponseListeners(connection, { _, _, _ -> }, { domain, txt, device ->
                if (!domain.startsWith("meshdrop.", ignoreCase = true)) return@setDnsSdResponseListeners
                val id = txt["id"] ?: return@setDnsSdResponseListeners
                val port = txt["port"]?.toIntOrNull() ?: return@setDnsSdResponseListeners
                if (id == identity?.deviceId || id.length !in 1..128 || port !in 1..65535) return@setDnsSdResponseListeners
                addresses[id] = device.deviceAddress
                val peer = PeerDevice(id, txt["name"].orEmpty().take(64).ifBlank { "MeshDrop" }, kind, port = port)
                found.update { list -> list.filterNot { it.id == id } + peer }
            })
        }
    }

    private suspend fun action(block: (WifiP2pManager.ActionListener) -> Unit) {
        withTimeout(10_000) {
            suspendCancellableCoroutine<Unit> { continuation ->
                block(object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { if (continuation.isActive) continuation.resume(Unit) }
                    override fun onFailure(reason: Int) {
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException(when (reason) {
                            WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct is not supported"
                            WifiP2pManager.BUSY -> "Wi-Fi Direct is busy; try again"
                            else -> "Enable Wi-Fi and grant nearby-device access (error $reason)"
                        }))
                    }
                })
            }
        }
    }

    override suspend fun startAdvertising(identity: LocalIdentity) = withContext(Dispatchers.Main.immediate) {
        this@WifiDirectDiscoveryStrategy.identity = identity
        runCatching {
            val connection = channel()
            service?.let { old -> action { manager!!.removeLocalService(connection, old, it) } }
            val advertisement = WifiP2pDnsSdServiceInfo.newInstance("meshdrop", "_meshdrop._tcp", mapOf(
                "id" to identity.deviceId, "name" to identity.deviceName.take(64), "port" to identity.port.toString(),
            ))
            action { manager!!.addLocalService(connection, advertisement, it) }
            service = advertisement
            state.update { it.copy(advertising = true, error = null) }
        }.onFailure { error -> state.update { it.copy(advertising = false, error = error.message) } }
        Unit
    }

    override suspend fun startScan() = withContext(Dispatchers.Main.immediate) {
        if (scanner?.isActive == true) return@withContext
        scanner = scope.launch(Dispatchers.Main.immediate) {
            try {
                val connection = channel()
                if (request == null) {
                    val discovery = WifiP2pDnsSdServiceRequest.newInstance()
                    action { manager!!.addServiceRequest(connection, discovery, it) }
                    request = discovery
                }
                while (isActive) {
                    action { manager!!.discoverServices(connection, it) }
                    state.update { it.copy(scanning = true, error = null) }
                    delay(12_000)
                }
            } catch (error: Exception) {
                state.update { it.copy(scanning = false, error = if (error is kotlinx.coroutines.CancellationException) it.error else error.message) }
            }
        }
    }

    override suspend fun connect(peer: PeerDevice): PeerDevice = withContext(Dispatchers.Main.immediate) {
        val address = addresses[peer.id] ?: error("Device expired; refresh nearby devices")
        val connection = channel()
        val config = WifiP2pConfig().apply {
            deviceAddress = address
            wps.setup = WpsInfo.PBC
            groupOwnerIntent = 0 // Prefer the receiver as owner; never assume this is guaranteed.
        }
        action { manager!!.connect(connection, config, it) }
        madeConnection = true
        state.update { it.copy(note = "Approve the Wi-Fi Direct connection on the other device", error = null) }
        withTimeout(35_000) {
            var resolved: PeerDevice? = null
            while (resolved == null) {
                val info = suspendCancellableCoroutine<WifiP2pInfo> { continuation ->
                    manager!!.requestConnectionInfo(connection) { if (continuation.isActive) continuation.resume(it) }
                }
                if (info.groupFormed) {
                    if (info.isGroupOwner) return@withTimeout peer // Coordinator waits for peer's UDP beacon.
                    val candidate = peer.copy(host = info.groupOwnerAddress, lastSeenMillis = System.currentTimeMillis())
                    // A group owner need not be the selected peer. Check identity before returning its address.
                    val matches = withContext(Dispatchers.IO) {
                        runCatching {
                            Socket().use { socket ->
                                socket.connect(InetSocketAddress(candidate.host, candidate.port), 1500)
                                socket.soTimeout = 1500
                                socket.getOutputStream().writeRequest("GET", TransferProtocol.PATH_PING)
                                val reply = BufferedInputStream(socket.getInputStream()).readResponse()
                                reply.isSuccessful && JSONObject(reply.bodyText()).optString("deviceId") == peer.id
                            }
                        }.getOrDefault(false)
                    }
                    if (matches) resolved = candidate
                }
                if (resolved == null) delay(500)
            }
            requireNotNull(resolved)
        }
    }

    override suspend fun stopScan() = withContext(Dispatchers.Main.immediate) {
        scanner?.cancel()
        scanner = null
        channel?.let { connection ->
            runCatching { manager?.stopPeerDiscovery(connection, null) }
            request?.let { runCatching { manager?.removeServiceRequest(connection, it, null) } }
        }
        request = null
        found.value = emptyList()
        addresses.clear()
        state.update { it.copy(scanning = false) }
    }

    override suspend fun stopAdvertising() = withContext(Dispatchers.Main.immediate) {
        channel?.let { connection ->
            service?.let { runCatching { manager?.removeLocalService(connection, it, null) } }
            if (madeConnection) {
                runCatching { manager?.cancelConnect(connection, null) }
                runCatching { manager?.removeGroup(connection, null) }
            }
        }
        service = null
        madeConnection = false
        identity = null
        state.update { it.copy(advertising = false) }
    }
}
