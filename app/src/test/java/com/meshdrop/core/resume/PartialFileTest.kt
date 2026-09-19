package com.meshdrop.core.resume

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

class PartialFileTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun `matching prefix appends without duplicating bytes`() {
        val part = PartialFile(directory.root)
        part.append(0, null).use { it.write("hello".toByteArray()) }
        part.append(5, part.digest()).use { it.write(" world".toByteArray()) }
        assertEquals("hello world", part.file.readText())
        part.discard()
        assertFalse(part.file.exists())
    }

    @Test fun `wrong digest or offset cannot modify a partial`() {
        val part = PartialFile(directory.root)
        part.append(0, null).use { it.write("hello".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { part.append(5, "0".repeat(64)) }
        assertThrows(IllegalArgumentException::class.java) { part.append(4, part.digest()) }
        assertThrows(IllegalArgumentException::class.java) { part.append(-1, part.digest()) }
        assertEquals("hello", part.file.readText())
    }

    @Test fun `restarting truncates stale bytes`() {
        val part = PartialFile(directory.root)
        part.append(0, null).use { it.write(ByteArray(100)) }
        part.append(0, null).use { it.write(byteArrayOf(42)) }
        assertEquals(1L, part.size)
    }

    @Test fun `prefix hashing leaves the next byte unread`() {
        val input = ByteArrayInputStream("abcdef".toByteArray())
        assertEquals(64, PartialFile.digestPrefix(input, 3).length)
        assertEquals('d'.code, input.read())
        assertThrows(java.io.EOFException::class.java) { PartialFile.digestPrefix(input, 20) }
    }
}
