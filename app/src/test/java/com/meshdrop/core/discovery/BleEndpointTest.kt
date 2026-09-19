package com.meshdrop.core.discovery

import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class BleEndpointTest {
    @Test fun `legacy advertisements encode exactly seven bytes`() {
        val endpoint = BleEndpoint(InetAddress.getByName("192.168.1.9") as Inet4Address, 65535)
        assertEquals(7, endpoint.encode().size)
        assertEquals(endpoint, BleEndpoint.decode(endpoint.encode()))
    }

    @Test fun `malformed advertisements are rejected`() {
        assertNull(BleEndpoint.decode(ByteArray(0)))
        assertNull(BleEndpoint.decode(ByteArray(40)))
        assertNull(BleEndpoint.decode(byteArrayOf(2, 1, 2, 3, 4, 1, 1)))
        assertNull(BleEndpoint.decode(byteArrayOf(1, 127, 0, 0, 1, 1, 1)))
        assertNull(BleEndpoint.decode(byteArrayOf(1, 10, 0, 0, 1, 0, 0)))
    }
}
