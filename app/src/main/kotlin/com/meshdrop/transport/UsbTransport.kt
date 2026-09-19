package com.meshdrop.transport

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.*
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import com.meshdrop.core.model.DiscoveryKind
import com.meshdrop.transfer.link.LinkRegistry
import kotlinx.coroutines.*
import java.io.*
import java.util.concurrent.atomic.AtomicBoolean

/** Android Open Accessory (AOA) host AND accessory roles. These streams never use tethering/IP. */
class UsbTransport(context: Context, private val scope: CoroutineScope, private val links: LinkRegistry) {
    private val context = context.applicationContext
    private val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val permissionAction = "${context.packageName}.USB_PERMISSION"
    private var registered = false
    @Volatile private var running = false
    private val opened = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!running) return
            when (intent.action) {
                permissionAction -> {
                    if (!intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        links.status(DiscoveryKind.USB, "USB permission denied"); return
                    }
                    @Suppress("DEPRECATION") val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    @Suppress("DEPRECATION") val accessory = intent.getParcelableExtra<UsbAccessory>(UsbManager.EXTRA_ACCESSORY)
                    device?.let { openDevice(it) }; accessory?.let { openAccessory(it) }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED, UsbManager.ACTION_USB_ACCESSORY_DETACHED -> disconnect()
                UsbManager.ACTION_USB_DEVICE_ATTACHED, UsbManager.ACTION_USB_ACCESSORY_ATTACHED ->
                    links.status(DiscoveryKind.USB, "USB attached; connect it in Settings")
            }
        }
    }

    fun start() {
        running = true
        if (!registered) {
            val filter = IntentFilter(permissionAction).apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED); addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
            }
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
        }
    }

    fun attached(): List<Pair<String, String>> = buildList {
        manager.accessoryList.orEmpty().forEachIndexed { index, accessory -> add("accessory:$index" to "${accessory.manufacturer} ${accessory.model}") }
        manager.deviceList.values.forEach { add(it.deviceName to (it.productName ?: "USB ${it.vendorId}:${it.productId}")) }
    }

    /** Only an explicit user action asks the OS for access or switches a device to AOA mode. */
    fun connect(id: String) {
        start()
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val permission = PendingIntent.getBroadcast(context, 0, Intent(permissionAction).setPackage(context.packageName), flags)
        if (id.startsWith("accessory:")) {
            val accessory = manager.accessoryList?.getOrNull(id.substringAfter(':').toIntOrNull() ?: -1) ?: return
            if (manager.hasPermission(accessory)) openAccessory(accessory) else manager.requestPermission(accessory, permission)
        } else {
            val device = manager.deviceList[id] ?: return
            if (manager.hasPermission(device)) openDevice(device) else manager.requestPermission(device, permission)
        }
    }

    private fun openAccessory(accessory: UsbAccessory) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                require(accessory.manufacturer == "MeshDrop" && accessory.model == "MeshDrop") { "Not a MeshDrop accessory host" }
                val id = "usb:accessory"
                if (!opened.add(id)) return@launch
                val descriptor = manager.openAccessory(accessory) ?: error("Could not open USB accessory")
                val input = ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor))
                val output = ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor))
                descriptor.close()
                links.attach(id, DiscoveryKind.USB, input, output, Closeable {
                    runCatching { input.close() }; runCatching { output.close() }; opened.remove(id)
                }, false)
            }.onFailure { opened.remove("usb:accessory"); links.status(DiscoveryKind.USB, it.message ?: "USB failed") }
        }
    }

    private fun openDevice(device: UsbDevice) {
        scope.launch(Dispatchers.IO) {
            val id = "usb:${device.deviceName}"
            var connection: UsbDeviceConnection? = null
            runCatching {
                if (!opened.add(id)) return@launch
                val usb = manager.openDevice(device) ?: error("Could not open USB device")
                connection = usb
                val aoa = device.vendorId == 0x18d1 && device.productId in setOf(0x2d00, 0x2d01, 0x2d04, 0x2d05)
                if (!aoa) {
                    val version = ByteArray(2)
                    require(usb.controlTransfer(0xc0, 51, 0, 0, version, 2, 2000) == 2 && version[0] >= 1) { "This device does not support Android Open Accessory" }
                    listOf("MeshDrop", "MeshDrop", "Encrypted file transfer", "1", "https://github.com/mitegab/meshdrop", "meshdrop-host").forEachIndexed { index, text ->
                        val bytes = (text + '\u0000').toByteArray(Charsets.UTF_8)
                        require(usb.controlTransfer(0x40, 52, 0, index, bytes, bytes.size, 2000) == bytes.size)
                    }
                    require(usb.controlTransfer(0x40, 53, 0, 0, null, 0, 2000) >= 0)
                    usb.close(); opened.remove(id)
                    links.status(DiscoveryKind.USB, "Accessory mode requested. After USB reappears, connect the new device in Settings.")
                    return@launch
                }
                val iface = (0 until device.interfaceCount).map { device.getInterface(it) }.firstOrNull { candidate ->
                    (0 until candidate.endpointCount).map { candidate.getEndpoint(it) }.count { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK } >= 2
                } ?: error("No accessory bulk interface")
                require(usb.claimInterface(iface, true)) { "USB interface is busy" }
                val endpoints = (0 until iface.endpointCount).map { iface.getEndpoint(it) }.filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
                val incoming = endpoints.first { it.direction == UsbConstants.USB_DIR_IN }
                val outgoing = endpoints.first { it.direction == UsbConstants.USB_DIR_OUT }
                val closed = AtomicBoolean()
                val closer = Closeable { if (closed.compareAndSet(false, true)) { usb.releaseInterface(iface); usb.close(); opened.remove(id) } }
                val input = object : InputStream() {
                    override fun read(): Int { val b = ByteArray(1); return if (read(b) < 0) -1 else b[0].toInt() and 255 }
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (len == 0) return 0
                        val block = ByteArray(minOf(len, 16 * 1024))
                        var lastData = System.nanoTime()
                        while (!closed.get()) {
                            val count = usb.bulkTransfer(incoming, block, block.size, 1000)
                            if (count > 0) { block.copyInto(b, off, 0, count); return count }
                            // A detached/broken driver can return immediately instead of waiting
                            // for the bulk timeout. Never spin or retain it indefinitely.
                            if (System.nanoTime() - lastData > 120_000_000_000L) throw IOException("USB read timed out")
                            Thread.sleep(10)
                        }
                        return -1
                    }
                }
                val output = object : OutputStream() {
                    override fun write(value: Int) = write(byteArrayOf(value.toByte()))
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        var cursor = off
                        while (cursor < off + len) {
                            if (closed.get()) throw IOException("USB detached")
                            val block = b.copyOfRange(cursor, minOf(cursor + 16 * 1024, off + len))
                            val count = usb.bulkTransfer(outgoing, block, block.size, 5000)
                            if (count <= 0) throw IOException("USB write failed")
                            cursor += count
                        }
                    }
                }
                links.attach(id, DiscoveryKind.USB, input, output, closer, true)
            }.onFailure { runCatching { connection?.close() }; opened.remove(id); links.status(DiscoveryKind.USB, it.message ?: "USB failed") }
        }
    }

    private fun disconnect() { opened.toList().forEach { links.detach(it) }; opened.clear() }
    fun stop() { running = false; disconnect(); if (registered) { context.unregisterReceiver(receiver); registered = false } }
}
