package com.meshdrop.core.discovery

import java.net.Inet4Address
import java.net.InetAddress

/** Seven bytes fit in legacy BLE advertising alongside a 128-bit service-data UUID. */
data class BleEndpoint(val host: Inet4Address, val port: Int) {
    fun encode(): ByteArray {
        require(port in 1..65535)
        require(host.isSiteLocalAddress || host.isLinkLocalAddress) { "BLE discovery needs a local IPv4 address" }
        return byteArrayOf(1) + host.address + byteArrayOf((port ushr 8).toByte(), port.toByte())
    }

    companion object {
        fun decode(bytes: ByteArray): BleEndpoint? {
            if (bytes.size != 7 || bytes[0] != 1.toByte()) return null
            val address = InetAddress.getByAddress(bytes.copyOfRange(1, 5)) as Inet4Address
            val port = ((bytes[5].toInt() and 255) shl 8) or (bytes[6].toInt() and 255)
            // Advertisements are untrusted. Never turn a radio beacon into an arbitrary
            // public-internet probe, even if the advertised port happens to answer HTTP.
            if (port == 0 || !(address.isSiteLocalAddress || address.isLinkLocalAddress)) return null
            return BleEndpoint(address, port)
        }
    }
}
