package com.meshdrop.core.transport

import com.meshdrop.core.model.TransportLane
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class IpTransportTest {
    private fun ip(value: String) = InetAddress.getByName(value)
    @Test fun `USB tethering and P2P labels reflect the reachable interface`() {
        val routes = listOf(InterfacePrefix("wlan0", ip("192.168.1.2"), 24),
            InterfacePrefix("rndis0", ip("192.168.42.1"), 24), InterfacePrefix("p2p-wlan0", ip("192.168.49.1"), 24))
        assertEquals(TransportLane.USB, IpTransport.laneFor(ip("192.168.42.10"), routes))
        assertEquals(TransportLane.WIFI_DIRECT, IpTransport.laneFor(ip("192.168.49.10"), routes))
        assertEquals(TransportLane.WIFI_LAN, IpTransport.laneFor(ip("192.168.1.10"), routes))
    }
    @Test fun `longest prefix wins without mislabelling unrelated networks`() {
        val routes = listOf(InterfacePrefix("usb0", ip("10.1.1.1"), 16), InterfacePrefix("wlan0", ip("10.1.2.1"), 24))
        assertEquals(TransportLane.WIFI_LAN, IpTransport.laneFor(ip("10.1.2.8"), routes))
        assertEquals(TransportLane.USB, IpTransport.laneFor(ip("10.1.3.8"), routes))
        assertEquals(TransportLane.WIFI_LAN, IpTransport.laneFor(ip("192.168.1.8"), routes))
    }
}
