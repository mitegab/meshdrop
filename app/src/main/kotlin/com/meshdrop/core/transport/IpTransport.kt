package com.meshdrop.core.transport

import com.meshdrop.core.model.TransportLane
import java.net.InetAddress

/** A connected interface prefix; independent of Android so route labels can be tested. */
data class InterfacePrefix(val name: String, val address: InetAddress, val prefixLength: Int)

object IpTransport {
    /** Longest matching connected prefix wins. This labels IP tethering, not raw USB. */
    fun laneFor(remote: InetAddress, interfaces: List<InterfacePrefix>): TransportLane {
        val route = interfaces.filter { matches(remote.address, it.address.address, it.prefixLength) }
            .maxByOrNull { it.prefixLength } ?: return TransportLane.WIFI_LAN
        val name = route.name.lowercase()
        return when {
            name.startsWith("rndis") || name.startsWith("usb") || name.startsWith("ncm") -> TransportLane.USB
            name.startsWith("p2p") -> TransportLane.WIFI_DIRECT
            else -> TransportLane.WIFI_LAN
        }
    }

    private fun matches(remote: ByteArray, local: ByteArray, prefix: Int): Boolean {
        if (remote.size != local.size || prefix !in 1..remote.size * 8) return false
        for (bit in 0 until prefix) {
            val mask = 1 shl (7 - bit % 8)
            if ((remote[bit / 8].toInt() and mask) != (local[bit / 8].toInt() and mask)) return false
        }
        return true
    }
}
