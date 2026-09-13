package com.meshdrop.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The safety code is pure Kotlin, so the derivation is tested without a keystore. */
class SafetyCodeTest {

    private val material = byteArrayOf(0x4f, 0x2a.toByte(), 0x91.toByte(), 0x0c, 0x7d, 0xe3.toByte(), 0x11, 0x22)

    @Test
    fun `six digits are shown as two groups of three`() {
        assertEquals("428 193", SafetyCode.format("428193"))
        assertEquals("42 81", SafetyCode.format("4281"))
        assertEquals("7", SafetyCode.format("7"))
    }

    @Test
    fun `a derived code is always six digits and deterministic`() {
        val code = SafetyCode.fromBytes(material)

        assertEquals(6, SafetyCode.normalize(code).length)
        assertTrue(code.matches(Regex("\\d{3} \\d{3}")))
        assertEquals(code, SafetyCode.fromBytes(material))
    }

    @Test
    fun `different key material gives a different code`() {
        val other = material.copyOf().also { it[0] = (it[0] + 1).toByte() }

        assertNotEquals(SafetyCode.fromBytes(material), SafetyCode.fromBytes(other))
    }

    @Test
    fun `short material is stretched instead of crashing`() {
        val code = SafetyCode.fromBytes(byteArrayOf(1))
        assertEquals(6, SafetyCode.normalize(code).length)
    }

    @Test
    fun `the v2 derivation is still reproducible for mixed version sessions`() {
        val legacy = SafetyCode.fromBytes(material, peerProtocolVersion = SafetyCode.LEGACY_PROTOCOL_VERSION)

        // byte % 10 for 4f 2a 91 0c 7d e3 -> 9 2 5 2 5 7
        assertEquals("925 257", legacy)
        assertEquals(legacy, SafetyCode.fromBytes(material, peerProtocolVersion = 2))
    }

    @Test
    fun `the current derivation differs from the legacy one`() {
        assertNotEquals(
            SafetyCode.fromBytes(material, peerProtocolVersion = SafetyCode.LEGACY_PROTOCOL_VERSION),
            SafetyCode.fromBytes(material, peerProtocolVersion = SafetyCode.PROTOCOL_VERSION),
        )
    }

    @Test
    fun `every digit is reachable across a spread of inputs`() {
        val seen = mutableSetOf<Char>()
        for (seed in 0 until 400) {
            val bytes = ByteArray(16) { ((seed * 31 + it * 17) % 251).toByte() }
            seen += SafetyCode.normalize(SafetyCode.fromBytes(bytes)).toSet()
        }

        assertEquals(('0'..'9').toSet(), seen)
    }

    @Test
    fun `what a human says is normalised before comparing`() {
        assertEquals("428193", SafetyCode.normalize("428 193"))
        assertEquals("428193", SafetyCode.normalize(" 4-2-8 1 9 3 "))
        assertEquals("428193", SafetyCode.normalize("428193"))
    }

    @Test
    fun `codes match regardless of spacing`() {
        assertTrue(SafetyCode.matches("428 193", "428193"))
        assertTrue(SafetyCode.matches("428 193", "428 193"))
        assertFalse(SafetyCode.matches("428 193", "428 194"))
        assertFalse(SafetyCode.matches("428 193", null))
        assertFalse(SafetyCode.matches(null, null))
        assertFalse(SafetyCode.matches("42 81", "428 193"))
    }

    @Test
    fun `only a full code counts as valid`() {
        assertTrue(SafetyCode.isValid("428 193"))
        assertTrue(SafetyCode.isValid("428193"))
        assertFalse(SafetyCode.isValid("4281"))
        assertFalse(SafetyCode.isValid(""))
        assertFalse(SafetyCode.isValid("4281934"))
        assertTrue(SafetyCode.isValid("42 81", digits = 4))
    }

    @Test
    fun `the entropy claim is about twenty bits`() {
        assertEquals(19.93, SafetyCode.entropyBits(), 0.01)
    }
}
