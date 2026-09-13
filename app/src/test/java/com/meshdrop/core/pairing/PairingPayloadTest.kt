package com.meshdrop.core.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A pairing code is text in, text out, so it is tested without a device. */
class PairingPayloadTest {

    private val info = PairingInfo(
        deviceId = "9f1c2ab4-1111-2222-3333-444455556666",
        deviceName = "Pixel 8",
        port = 45821,
        fingerprint = "4f2a910c7de3",
    )

    /** A code cannot carry the separator, so ids lose their dashes on the way through. */
    private val encoded = info.copy(deviceId = "9f1c2ab4111122223333444455556666")

    @Test
    fun `a code round trips`() {
        val code = PairingPayload.encode(info)

        assertTrue(code.startsWith("MD1-"))
        assertEquals(encoded, PairingPayload.decode(code))
    }

    @Test
    fun `the device id keeps only the characters worth typing`() {
        val code = PairingPayload.encode(info)

        assertTrue(code.contains("9f1c2ab4111122223333444455556666"))
        assertFalse(code.contains("9f1c2ab4-1111"))
    }

    @Test
    fun `what a human pastes is trimmed before it is read`() {
        val code = PairingPayload.encode(info)

        assertEquals(encoded, PairingPayload.decode("  $code  "))
        assertEquals(encoded, PairingPayload.decode("$code\n"))
        // The prefix is the part people shout across a room, so its case does not matter.
        assertEquals(encoded, PairingPayload.decode("md1" + code.substring(3)))
    }

    @Test
    fun `a mistyped character is rejected`() {
        val code = PairingPayload.encode(info)
        val body = code.dropLast(4)
        val checksum = code.takeLast(4)

        val wrong = (if (checksum.startsWith("0")) "1" else "0") + checksum.substring(1)
        assertNull(PairingPayload.decode(body + wrong))
        // Changing the port changes the checksum's input, so the code no longer parses.
        assertNull(PairingPayload.decode(code.replace("45821", "45822")))
    }

    @Test
    fun `nonsense is rejected rather than half parsed`() {
        assertNull(PairingPayload.decode(""))
        assertNull(PairingPayload.decode("   "))
        assertNull(PairingPayload.decode("hello"))
        assertNull(PairingPayload.decode("MD1-abc"))
        assertNull(PairingPayload.decode("XX1-a-1-bb-c-1-0000"))
        assertNull(PairingPayload.decode("MD1-a-notanumber-bb-c-1-0000"))
        assertNull(PairingPayload.decode("MD1-a-99999-bb-c-1-0000"))
    }

    @Test
    fun `a name with a separator in it survives`() {
        val awkward = info.copy(deviceName = "Sam's - Pixel")
        val decoded = PairingPayload.decode(PairingPayload.encode(awkward))

        assertEquals("Sam's Pixel", decoded?.deviceName)
    }

    @Test
    fun `a very long name is cut, and an empty one gets a default`() {
        val long = info.copy(deviceName = "x".repeat(80))
        assertEquals(24, PairingPayload.decode(PairingPayload.encode(long))?.deviceName?.length)

        val blank = info.copy(deviceName = "   ")
        assertEquals("MeshDrop", PairingPayload.decode(PairingPayload.encode(blank))?.deviceName)
    }

    @Test
    fun `a code can confirm an identity fingerprint`() {
        val code = PairingPayload.encode(info)

        assertTrue(PairingPayload.verifies(code, "4f2a910c7de3"))
        assertTrue(PairingPayload.verifies(code, "4F2A910C7DE3"))
        assertFalse(PairingPayload.verifies(code, "000000000000"))
        assertFalse(PairingPayload.verifies("garbage", "4f2a910c7de3"))
    }

    @Test
    fun `the short fingerprint is what a human compares`() {
        val long = info.copy(fingerprint = "4f2a910c7de3ff00aa11")

        assertEquals("4f2a910c7de3", long.shortFingerprint)
        assertTrue(long.matchesFingerprint("4F2A910C7DE3FF00AA11"))
        assertFalse(long.matchesFingerprint(null))
    }

    @Test
    fun `the checksum is four hex characters and changes with the body`() {
        val checksum = PairingPayload.checksum("MD1-a-1-bb-c-1")

        assertEquals(4, checksum.length)
        assertTrue(checksum.matches(Regex("[0-9a-f]{4}")))
        assertNotEquals(checksum, PairingPayload.checksum("MD1-a-1-bb-c-2"))
    }

    @Test
    fun `the protocol version travels with the code`() {
        val versioned = info.copy(protocolVersion = 3)

        assertEquals(3, PairingPayload.decode(PairingPayload.encode(versioned))?.protocolVersion)
    }
}
