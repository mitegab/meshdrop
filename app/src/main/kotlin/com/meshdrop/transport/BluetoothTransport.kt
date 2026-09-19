package com.meshdrop.transport

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.*
import com.meshdrop.core.model.DiscoveryKind
import com.meshdrop.transfer.link.LinkRegistry
import kotlinx.coroutines.*
import java.io.Closeable
import java.util.UUID

/** Paired/discoverable Classic Bluetooth, carrying both HTTP control and file bytes over RFCOMM. */
@SuppressLint("MissingPermission")
class BluetoothTransport(context: Context, private val scope: CoroutineScope, private val links: LinkRegistry) {
    private val context = context.applicationContext
    private val adapter get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private var listener: BluetoothServerSocket? = null
    private var accepting: Job? = null
    private val connecting = java.util.concurrent.ConcurrentHashMap<String, BluetoothSocket>()

    fun pairedDevices(): List<Pair<String, String>> = runCatching {
        adapter?.bondedDevices.orEmpty().map { it.address to (it.name ?: it.address) }.sortedBy { it.second }
    }.getOrDefault(emptyList())

    fun start() {
        if (accepting?.isActive == true) return
        accepting = scope.launch(Dispatchers.IO) {
            runCatching {
                val server = adapter?.listenUsingRfcommWithServiceRecord("MeshDrop", SERVICE)
                    ?: error("Enable Bluetooth and grant Bluetooth access")
                listener = server
                links.status(DiscoveryKind.BLUETOOTH, "RFCOMM ready; select a paired device in Settings", true)
                while (isActive) attach(server.accept(), false)
            }.onFailure { links.status(DiscoveryKind.BLUETOOTH, it.message ?: "Bluetooth stopped") }
            runCatching { listener?.close() }; listener = null
        }
    }

    fun connect(address: String) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                require(BluetoothAdapter.checkBluetoothAddress(address)) { "Invalid Bluetooth address" }
                val radio = requireNotNull(adapter)
                radio.cancelDiscovery()
                val socket = radio.getRemoteDevice(address).createRfcommSocketToServiceRecord(SERVICE)
                connecting.put(address, socket)?.close()
                val watchdog = scope.launch { delay(30_000); connecting.remove(address, socket).takeIf { it }?.let { socket.close() } }
                try { socket.connect(); connecting.remove(address, socket); attach(socket, true) }
                catch (error: Throwable) { socket.close(); throw error }
                finally { watchdog.cancel(); connecting.remove(address, socket) }
            }.onFailure { links.status(DiscoveryKind.BLUETOOTH, it.message ?: "Could not connect") }
        }
    }

    private fun attach(socket: BluetoothSocket, initiator: Boolean) {
        links.attach("bt:${socket.remoteDevice.address}", DiscoveryKind.BLUETOOTH,
            socket.inputStream, socket.outputStream, Closeable { socket.close() }, initiator)
    }

    fun stop() {
        accepting?.cancel(); accepting = null
        runCatching { listener?.close() }; listener = null
        connecting.values.forEach { runCatching { it.close() } }; connecting.clear()
        links.peers.value.filter { it.linkId?.startsWith("bt:") == true }.forEach { links.detach(it.linkId!!) }
    }

    companion object { val SERVICE: UUID = UUID.fromString("bb906069-6cd4-40c1-9395-31c79a32a349") }
}
