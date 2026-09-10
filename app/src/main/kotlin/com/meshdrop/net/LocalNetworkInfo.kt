package com.meshdrop.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale

/**
 * Small helpers for the "where am I on this network" questions MeshDrop keeps asking:
 * which IPv4 address do other devices see, and where should a broadcast go.
 */
object LocalNetworkInfo {

    /**
     * The best IPv4 address of this device, preferring Wi-Fi. Returns null when the device
     * has no usable LAN address (airplane mode, mobile data only, hotspot, …).
     */
    fun localIpAddress(): Inet4Address? {
        val wifiAddresses = mutableListOf<Inet4Address>()
        val otherAddresses = mutableListOf<Inet4Address>()
        runCatching {
            for (iface in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
                val isWifi = iface.isProbablyWifi()
                for (interfaceAddress in iface.interfaceAddresses) {
                    val address = interfaceAddress.address as? Inet4Address ?: continue
                    if (address.isLinkLocalAddress || address.isLoopbackAddress) continue
                    if (isWifi) wifiAddresses += address else otherAddresses += address
                }
            }
        }
        return wifiAddresses.firstOrNull() ?: otherAddresses.firstOrNull()
    }

    /** Directed broadcast addresses for every IPv4 network we are attached to. */
    fun broadcastAddresses(): List<InetAddress> {
        val result = mutableListOf<InetAddress>()
        runCatching { result += InetAddress.getByName("255.255.255.255") }
        runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }
                .mapNotNull { it.broadcastOrNull() }
                .forEach { result += it }
        }
        return result.distinctBy { it.hostAddress }
    }

    fun isConnectedToWifi(context: Context): Boolean {
        val manager = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = manager.activeNetwork ?: return false
            val capabilities = manager.getNetworkCapabilities(network) ?: return false
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } else {
            @Suppress("DEPRECATION")
            manager.activeNetworkInfo?.type == ConnectivityManager.TYPE_WIFI
        }
    }

    /** Legacy fallback used only on very old stacks: the DHCP-derived address. */
    fun legacyDhcpAddress(context: Context): Inet4Address? {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val info = runCatching {
            @Suppress("DEPRECATION")
            wifi?.dhcpInfo
        }.getOrNull() ?: return null
        val ip = info.ipAddress
        if (ip == 0) return null
        return runCatching {
            InetAddress.getByAddress(
                byteArrayOf(
                    (ip and 0xff).toByte(),
                    (ip shr 8 and 0xff).toByte(),
                    (ip shr 16 and 0xff).toByte(),
                    (ip shr 24 and 0xff).toByte(),
                ),
            ) as Inet4Address
        }.getOrNull()
    }

    private fun InterfaceAddress.broadcastOrNull(): InetAddress? {
        val address = this.address as? Inet4Address ?: return null
        val prefix = networkPrefixLength.toInt()
        if (prefix <= 0 || prefix >= 32) return null
        val mask = (-1 shl (32 - prefix))
        val ip = address.address.fold(0) { acc, byte -> (acc shl 8) or (byte.toInt() and 0xff) }
        val broadcast = ip or mask.inv()
        return InetAddress.getByAddress(
            byteArrayOf(
                (broadcast ushr 24 and 0xff).toByte(),
                (broadcast ushr 16 and 0xff).toByte(),
                (broadcast ushr 8 and 0xff).toByte(),
                (broadcast and 0xff).toByte(),
            ),
        )
    }

    /** `wlan*`, `wifi*`, `wlp*` … interface names, plus the common vendor spellings. */
    private fun NetworkInterface.isProbablyWifi(): Boolean {
        val name = this.name.lowercase(Locale.US)
        return name.startsWith("wlan") ||
            name.startsWith("wifi") ||
            name.startsWith("wlp") ||
            name.startsWith("swlan") ||
            name.startsWith("eth0:") ||
            name.contains("ap")
    }
}
