package com.meshdrop.discovery.nsd

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.meshdrop.core.model.DiscoveryKind
import com.meshdrop.core.model.PeerDevice
import com.meshdrop.core.util.MeshLog
import com.meshdrop.discovery.api.DiscoveryStrategy
import com.meshdrop.discovery.api.LocalIdentity
import com.meshdrop.discovery.api.StrategyStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Peer discovery over mDNS / DNS-SD (`NsdManager`), the same mechanism printers and
 * Chromecasts use. Works on any ordinary Wi-Fi network with no internet access and no
 * special permissions on Android 13+.
 *
 * Notes for future maintainers:
 *  * `NsdManager` resolves one service at a time, so resolutions go through a queue.
 *  * A multicast lock is required on many devices, otherwise mDNS replies are filtered.
 *  * `NsdManager` callbacks are delivered on the main thread — every call below is
 *    dispatched with [Dispatchers.Main].
 */
class NsdDiscoveryStrategy(
    private val context: Context,
    private val scope: CoroutineScope,
    private val now: () -> Long = { System.currentTimeMillis() },
) : DiscoveryStrategy {

    override val kind: DiscoveryKind = DiscoveryKind.LAN_NSD

    private val appContext = context.applicationContext

    private val nsdManager: NsdManager? by lazy {
        appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    }

    private val wifiManager: WifiManager? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appContext.getSystemService(WifiManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        }
    }

    private val _status = MutableStateFlow(StrategyStatus())
    override val status: StateFlow<StrategyStatus> = _status.asStateFlow()

    private val _peers = MutableStateFlow<List<PeerDevice>>(emptyList())
    override val peers: StateFlow<List<PeerDevice>> = _peers.asStateFlow()

    private var multicastLock: WifiManager.MulticastLock? = null
    private var identity: LocalIdentity? = null

    private var registered = false
    private var scanning = false
    private var refreshJob: Job? = null

    /** serviceName → deviceId, so `onServiceLost` can remove the right peer. */
    private val serviceToId = LinkedHashMap<String, String>()

    /** serviceName → last known info, refreshed periodically so peers do not expire. */
    private val knownServices = LinkedHashMap<String, NsdServiceInfo>()

    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    // region advertising ------------------------------------------------------------------

    override suspend fun startAdvertising(identity: LocalIdentity) = withContext(Dispatchers.Main) {
        val manager = nsdManager
        if (manager == null) {
            _status.update { it.copy(error = "mDNS is not available on this device") }
            return@withContext
        }
        this@NsdDiscoveryStrategy.identity = identity
        acquireMulticastLock()

        if (registered) {
            // The advertised port can change if the transfer server was restarted.
            runCatching { manager.unregisterService(registrationListener) }
                .onFailure { MeshLog.w(TAG, "Re-registration: unregister failed", it) }
            registered = false
        }

        val info = NsdServiceInfo().apply {
            serviceName = MeshDropService.serviceName(identity)
            serviceType = MeshDropService.SERVICE_TYPE
            port = identity.port
            setAttribute(MeshDropService.ATTR_ID, identity.deviceId)
            setAttribute(MeshDropService.ATTR_NAME, identity.deviceName)
            setAttribute(MeshDropService.ATTR_PORT, identity.port.toString())
            setAttribute(MeshDropService.ATTR_VERSION, MeshDropService.PROTOCOL_VERSION.toString())
        }

        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener) }
            .onFailure { error ->
                MeshLog.w(TAG, "registerService failed", error)
                _status.update { it.copy(error = "Could not make this device discoverable") }
            }
        Unit
    }

    override suspend fun stopAdvertising() = withContext(Dispatchers.Main) {
        identity = null
        if (registered) {
            runCatching { nsdManager?.unregisterService(registrationListener) }
                .onFailure { MeshLog.w(TAG, "unregisterService failed", it) }
            registered = false
        }
        _status.update { it.copy(advertising = false) }
        releaseMulticastLockIfIdle()
        Unit
    }

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            MeshLog.w(TAG, "Registration failed: ${describeError(errorCode)}")
            registered = false
            _status.update { it.copy(advertising = false, error = describeError(errorCode)) }
        }

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            MeshLog.w(TAG, "Unregistration failed: ${describeError(errorCode)}")
        }

        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            MeshLog.i(TAG, "Advertising as ${serviceInfo.serviceName} on port ${serviceInfo.port}")
            registered = true
            _status.update { it.copy(advertising = true, error = null) }
        }

        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
            MeshLog.i(TAG, "Stopped advertising ${serviceInfo.serviceName}")
            registered = false
            _status.update { it.copy(advertising = false) }
        }
    }

    // endregion

    // region scanning ---------------------------------------------------------------------

    override suspend fun startScan() = withContext(Dispatchers.Main) {
        val manager = nsdManager
        if (manager == null) {
            _status.update { it.copy(error = "mDNS is not available on this device") }
            return@withContext
        }
        acquireMulticastLock()
        if (scanning) return@withContext

        runCatching { manager.discoverServices(MeshDropService.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener) }
            .onFailure { error ->
                MeshLog.w(TAG, "discoverServices failed", error)
                _status.update { it.copy(scanning = false, error = "Could not scan for devices") }
                return@withContext
            }
        scanning = true
        _status.update { it.copy(scanning = true, error = null) }
        startRefreshLoop()
        Unit
    }

    override suspend fun stopScan() = withContext(Dispatchers.Main) {
        refreshJob?.cancel()
        refreshJob = null
        resolveQueue.clear()
        if (scanning) {
            runCatching { nsdManager?.stopServiceDiscovery(discoveryListener) }
                .onFailure { MeshLog.w(TAG, "stopServiceDiscovery failed", it) }
            scanning = false
        }
        knownServices.clear()
        serviceToId.clear()
        _peers.value = emptyList()
        _status.update { it.copy(scanning = false) }
        releaseMulticastLockIfIdle()
        Unit
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            MeshLog.w(TAG, "Discovery start failed: ${describeError(errorCode)}")
            scanning = false
            _status.update { it.copy(scanning = false, error = describeError(errorCode)) }
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            MeshLog.w(TAG, "Discovery stop failed: ${describeError(errorCode)}")
            scanning = false
        }

        override fun onDiscoveryStarted(serviceType: String) {
            MeshLog.i(TAG, "Scanning for $serviceType")
            scanning = true
            _status.update { it.copy(scanning = true) }
        }

        override fun onDiscoveryStopped(serviceType: String) {
            MeshLog.i(TAG, "Stopped scanning for $serviceType")
            scanning = false
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            knownServices[serviceInfo.serviceName] = serviceInfo
            enqueueResolve(serviceInfo)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            val id = knownServices.remove(serviceInfo.serviceName)?.let { serviceToId[it.serviceName] }
                ?: serviceToId[serviceInfo.serviceName]
            serviceToId.remove(serviceInfo.serviceName)
            if (id != null) {
                MeshLog.i(TAG, "Peer left: ${serviceInfo.serviceName}")
                _peers.update { list -> list.filterNot { it.id == id } }
            }
        }
    }

    /**
     * mDNS only re-announces when something changes, so we re-resolve everything we know
     * about periodically. That keeps `lastSeenMillis` fresh (the coordinator expires peers
     * after ~20s) and quietly drops devices that stopped answering.
     */
    private fun startRefreshLoop() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            while (true) {
                delay(REFRESH_INTERVAL_MS)
                if (!scanning) continue
                knownServices.values.toList().forEach { enqueueResolve(it) }
            }
        }
    }

    private fun enqueueResolve(serviceInfo: NsdServiceInfo) {
        if (resolveQueue.any { it.serviceName == serviceInfo.serviceName }) return
        resolveQueue.addLast(serviceInfo)
        drainResolveQueue()
    }

    private fun drainResolveQueue() {
        if (resolving) return
        val next = resolveQueue.removeFirstOrNull() ?: return
        resolving = true
        runCatching { nsdManager?.resolveService(next, resolveListener) }
            .onFailure {
                resolving = false
                MeshLog.w(TAG, "resolveService threw", it)
                drainResolveQueue()
            }
    }

    private val resolveListener = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            resolving = false
            MeshLog.w(TAG, "Resolve failed for ${serviceInfo.serviceName}: ${describeError(errorCode)}")
            drainResolveQueue()
        }

        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            resolving = false
            val peer = serviceInfo.toPeerDevice(identity)
            if (peer == null) {
                MeshLog.v(TAG, "Ignoring service ${serviceInfo.serviceName} (self or malformed)")
            } else {
                knownServices[serviceInfo.serviceName] = serviceInfo
                serviceToId[serviceInfo.serviceName] = peer.id
                MeshLog.v(TAG, "Resolved ${peer.name} @ ${peer.endpoint}")
                _peers.update { list -> list.filterNot { it.id == peer.id } + peer }
            }
            drainResolveQueue()
        }
    }

    // endregion

    // region helpers ----------------------------------------------------------------------

    private fun NsdServiceInfo.toPeerDevice(self: LocalIdentity?): PeerDevice? {
        val attributes = runCatching { this.attributes }.getOrNull() ?: emptyMap()
        val id = attributes[MeshDropService.ATTR_ID]?.asUtf8()?.trim().orEmpty()
        if (id.isEmpty()) return null
        if (self != null && id == self.deviceId) return null

        val name = attributes[MeshDropService.ATTR_NAME]?.asUtf8()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: serviceName
        val port = attributes[MeshDropService.ATTR_PORT]?.asUtf8()?.toIntOrNull()
            ?: this.port
        val version = attributes[MeshDropService.ATTR_VERSION]?.asUtf8()?.toIntOrNull()
        if (version != null && version > MeshDropService.PROTOCOL_VERSION) {
            MeshLog.w(TAG, "Ignoring $name: speaks protocol v$version")
            return null
        }
        return PeerDevice(
            id = id,
            name = name,
            kind = DiscoveryKind.LAN_NSD,
            host = host,
            port = port,
            lastSeenMillis = now(),
            transferLane = com.meshdrop.net.LocalNetworkInfo.laneForRemote(host),
        )
    }

    private fun ByteArray.asUtf8(): String = String(this, Charsets.UTF_8)

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        runCatching {
            val lock = wifiManager?.createMulticastLock("meshdrop-discovery")
            lock?.setReferenceCounted(true)
            lock?.acquire()
            multicastLock = lock
            if (lock == null) MeshLog.w(TAG, "Multicast lock unavailable")
        }.onFailure { MeshLog.w(TAG, "Could not acquire multicast lock", it) }
    }

    private fun releaseMulticastLockIfIdle() {
        if (registered || scanning) return
        runCatching { multicastLock?.release() }
            .onFailure { MeshLog.w(TAG, "Could not release multicast lock", it) }
        multicastLock = null
    }

    private fun describeError(errorCode: Int): String = when (errorCode) {
        NsdManager.FAILURE_ALREADY_ACTIVE -> "Another discovery is already running"
        NsdManager.FAILURE_MAX_LIMIT -> "Too many mDNS requests, try again in a moment"
        NsdManager.FAILURE_BAD_PARAMETERS -> "Invalid mDNS parameters"
        NsdManager.FAILURE_INTERNAL_ERROR -> "Wi-Fi stack error"
        else -> "mDNS error ($errorCode)"
    }

    companion object {
        const val TAG = "NsdDiscovery"
        private const val REFRESH_INTERVAL_MS = 12_000L
    }
}
