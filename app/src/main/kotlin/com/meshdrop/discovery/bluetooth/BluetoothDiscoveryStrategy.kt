package com.meshdrop.discovery.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.meshdrop.core.discovery.BleEndpoint
import com.meshdrop.core.model.DiscoveryKind
import com.meshdrop.core.model.PeerDevice
import com.meshdrop.core.model.TransportLane
import com.meshdrop.discovery.api.DiscoveryStrategy
import com.meshdrop.discovery.api.LocalIdentity
import com.meshdrop.discovery.api.StrategyStatus
import com.meshdrop.net.LocalNetworkInfo
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
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** BLE-assisted IP discovery, not a Bluetooth file transport. No pairing or auto-accept. */
@SuppressLint("MissingPermission")
class BluetoothDiscoveryStrategy(context: Context, private val scope: CoroutineScope) : DiscoveryStrategy {
    private val manager = context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter get() = manager?.adapter
    override val kind = DiscoveryKind.BLUETOOTH
    override val isAvailable get() = adapter != null
    private val state = MutableStateFlow(StrategyStatus(note = "Bluetooth finds IP endpoints; files still use the local network"))
    override val status = state.asStateFlow()
    private val found = MutableStateFlow<List<PeerDevice>>(emptyList())
    override val peers = found.asStateFlow()
    private var identity: LocalIdentity? = null
    private var advertising: Job? = null
    private var scanning = false
    private val probes = ConcurrentHashMap<String, Long>()
    private var generation = 0

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settings: AdvertiseSettings) { state.update { it.copy(advertising = true, error = null) } }
        override fun onStartFailure(errorCode: Int) { state.update { it.copy(advertising = false, error = "Bluetooth advertising failed ($errorCode)") } }
    }
    private val scanCallback = object : ScanCallback() {
        override fun onScanFailed(errorCode: Int) {
            scanning = false
            state.update { it.copy(scanning = false, error = "Bluetooth scan failed ($errorCode)") }
        }
        override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach(::onScanResultInternal) }
        override fun onScanResult(callbackType: Int, result: ScanResult) { onScanResultInternal(result) }
    }

    private fun onScanResultInternal(result: ScanResult) {
        val endpoint = result.scanRecord?.getServiceData(SERVICE)?.let { BleEndpoint.decode(it) } ?: return
        val key = "${endpoint.host.hostAddress}:${endpoint.port}"
        val now = System.currentTimeMillis()
        synchronized(probes) {
            if (now - (probes[key] ?: 0L) < 10_000) return
            if (probes.size >= 64 && key !in probes) probes.entries.removeAll { now - it.value > 30_000 }
            if (probes.size >= 64 && key !in probes) return
            probes[key] = now
        }
        val scanGeneration = generation
        scope.launch(Dispatchers.IO) {
            val peer = runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(endpoint.host, endpoint.port), 1500)
                    socket.soTimeout = 1500
                    socket.getOutputStream().writeRequest("GET", TransferProtocol.PATH_PING)
                    val response = BufferedInputStream(socket.getInputStream()).readResponse()
                    require(response.isSuccessful)
                    val json = JSONObject(response.bodyText())
                    val id = json.getString("deviceId")
                    require(id.length in 1..128 && id != identity?.deviceId)
                    PeerDevice(id, json.optString("name", "MeshDrop").take(64), kind,
                        endpoint.host, endpoint.port, transferLane = TransportLane.WIFI_LAN)
                }
            }.getOrNull()
            if (peer != null && scanning && scanGeneration == generation) {
                found.update { list -> list.filterNot { it.id == peer.id } + peer }
            }
        }
    }

    override suspend fun startAdvertising(identity: LocalIdentity) {
        this.identity = identity
        advertising?.cancel()
        advertising = scope.launch(Dispatchers.IO) {
            var previous: BleEndpoint? = null
            while (isActive) {
                runCatching {
                    val advertiser = adapter?.bluetoothLeAdvertiser ?: error("Enable Bluetooth to advertise nearby")
                    val endpoint = LocalNetworkInfo.localIpAddress()?.let { BleEndpoint(it, identity.port) }
                    if (endpoint != previous || !state.value.advertising) {
                        advertiser.stopAdvertising(advertiseCallback)
                        state.update { it.copy(advertising = false) }
                        if (endpoint != null) {
                            advertiser.startAdvertising(
                                AdvertiseSettings.Builder().setConnectable(false)
                                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER).build(),
                                AdvertiseData.Builder().addServiceData(SERVICE, endpoint.encode()).build(),
                                advertiseCallback,
                            )
                        } else state.update { it.copy(note = "Join Wi-Fi or enable tethering for Bluetooth-assisted discovery") }
                        previous = endpoint
                    }
                }.onFailure { error -> state.update { it.copy(advertising = false, error = error.message) } }
                delay(10_000)
            }
        }
    }

    override suspend fun startScan() {
        if (scanning) return
        runCatching {
            val scanner = adapter?.bluetoothLeScanner ?: error("Enable Bluetooth to find nearby devices")
            scanning = true
            scanner.startScan(
                listOf(ScanFilter.Builder().setServiceData(SERVICE, byteArrayOf(1)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(), scanCallback,
            )
            state.update { it.copy(scanning = true, error = null) }
        }.onFailure { error ->
            scanning = false
            state.update { it.copy(scanning = false, error = error.message) }
        }
    }

    override suspend fun stopScan() {
        scanning = false
        generation++
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        probes.clear()
        found.value = emptyList()
        state.update { it.copy(scanning = false) }
    }

    override suspend fun stopAdvertising() {
        advertising?.cancel()
        advertising = null
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        identity = null
        state.update { it.copy(advertising = false) }
    }

    companion object {
        val SERVICE: ParcelUuid = ParcelUuid(UUID.fromString("5d6aa710-72ac-4ed9-a361-683fc8ef8021"))
    }
}
