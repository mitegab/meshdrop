package com.meshdrop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Names arrive from a peer, so the rules are adversarial on purpose and tested on the JVM.
 */
class FileNamesTest {

    @Test
    fun `an ordinary name passes through untouched`() {
        val result = FileNames.sanitize("holiday-photo.jpg")

        assertEquals("holiday-photo.jpg", result.name)
        assertFalse(result.changed)
        assertNull(result.note)
    }

    @Test
    fun `a path is reduced to its last segment`() {
        assertEquals("passwd", FileNames.clean("../../../../etc/passwd"))
        assertEquals("notes.txt", FileNames.clean("/sdcard/Download/notes.txt"))
        assertEquals("evil.sh", FileNames.clean("C:\\Windows\\evil.sh"))

        val result = FileNames.sanitize("../secret.txt")
        assertTrue(result.changed)
        assertEquals(listOf("path"), result.reasons)
        assertEquals("Name shortened to \"secret.txt\"", result.note)
    }

    @Test
    fun `control characters are dropped`() {
        assertEquals("photo.jpg", FileNames.clean("photo\u0000.jpg"))
        assertEquals("ab.jpg", FileNames.clean("a\nb.jpg"))
        assertEquals("tab.jpg", FileNames.clean("ta\tb.jpg"))
        assertTrue(FileNames.sanitize("a\u0007b").changed)
    }

    @Test
    fun `characters no filesystem likes are replaced`() {
        assertEquals("a_b_c_d.jpg", FileNames.clean("a<b>c:d.jpg"))
        assertEquals("what_.jpg", FileNames.clean("what?.jpg"))
        assertEquals("pipe_.jpg", FileNames.clean("pipe|.jpg"))
    }

    @Test
    fun `dots and spaces at the edges are trimmed`() {
        assertEquals("photo.jpg", FileNames.clean("  photo.jpg  "))
        assertEquals("photo.jpg", FileNames.clean("...photo.jpg"))
        assertEquals("photo", FileNames.clean("photo."))
    }

    @Test
    fun `runs of whitespace collapse to one space`() {
        assertEquals("my holiday photo.jpg", FileNames.clean("my    holiday   photo.jpg"))
    }

    @Test
    fun `names that mean something to a filesystem fall back`() {
        assertEquals(FileNames.DEFAULT_NAME, FileNames.clean("."))
        assertEquals(FileNames.DEFAULT_NAME, FileNames.clean(".."))
        assertEquals(FileNames.DEFAULT_NAME, FileNames.clean("///"))
        assertEquals(FileNames.DEFAULT_NAME, FileNames.clean("   "))
        assertEquals(FileNames.DEFAULT_NAME, FileNames.clean("CON"))
        assertEquals(FileNames.DEFAULT_NAME, FileNames.clean("nul"))
        assertEquals(FileNames.DEFAULT_NAME, FileNames.clean(""))
        assertEquals("fallback", FileNames.clean(".", fallback = "fallback"))
    }

    @Test
    fun `unicode survives, and long names are cut on a character boundary`() {
        assertEquals("日本 の 写真.jpg", FileNames.clean("日本 の 写真.jpg"))

        val long = "é".repeat(300) + ".jpg"
        val cleaned = FileNames.clean(long)
        assertTrue(cleaned.toByteArray(Charsets.UTF_8).size <= FileNames.MAX_NAME_BYTES)
        assertTrue(cleaned.endsWith(".jpg"))
        // No half-written character: the stem is still made of whole 'é's.
        assertTrue(cleaned.removeSuffix(".jpg").all { it == 'é' })
    }

    @Test
    fun `a duplicate gets a number before the extension`() {
        assertEquals("photo.jpg", FileNames.unique("photo.jpg", listOf("other.jpg")))
        assertEquals("photo (1).jpg", FileNames.unique("photo.jpg", listOf("photo.jpg")))
        assertEquals(
            "photo (2).jpg",
            FileNames.unique("photo.jpg", listOf("photo.jpg", "photo (1).jpg")),
        )
        assertEquals("notes (1)", FileNames.unique("notes", listOf("notes")))
        assertEquals("archive.tar (1).gz", FileNames.unique("archive.tar.gz", listOf("archive.tar.gz")))
    }

    @Test
    fun `the duplicate suffix keeps the original extension case`() {
        assertEquals("PHOTO (1).JPG", FileNames.unique("PHOTO.JPG", listOf("PHOTO.JPG")))
    }

    @Test
    fun `a duplicate of a very long name stays inside the budget`() {
        val long = "x".repeat(200) + ".jpg"
        val taken = listOf(FileNames.truncate(long))

        val unique = FileNames.unique(FileNames.truncate(long), taken)

        assertTrue(unique.toByteArray(Charsets.UTF_8).size <= FileNames.MAX_NAME_BYTES)
        assertTrue(unique.endsWith(".jpg"))
        assertTrue(unique.contains(" (1)"))
    }

    @Test
    fun `uniqueness can be answered by the filesystem instead of a list`() {
        val unique = FileNames.unique("photo.jpg", isTaken = { it == "photo.jpg" || it == "photo (1).jpg" })
        assertEquals("photo (2).jpg", unique)
    }

    @Test
    fun `extensions are recognised conservatively`() {
        assertEquals("jpg", FileNames.extension("photo.JPG"))
        assertEquals("gz", FileNames.extension("archive.tar.gz"))
        assertNull(FileNames.extension("README"))
        assertNull(FileNames.extension(".gitignore"))
        assertNull(FileNames.extension("trailing."))
        assertNull(FileNames.extension("photo.j pg"))
        assertEquals("photo", FileNames.baseName("photo.jpg"))
        assertEquals(".gitignore", FileNames.baseName(".gitignore"))
    }

    @Test
    fun `traversal is recognised before the name is cleaned`() {
        assertTrue(FileNames.looksLikeTraversal("../../etc/passwd"))
        assertTrue(FileNames.looksLikeTraversal("/absolute/path.jpg"))
        assertFalse(FileNames.looksLikeTraversal("holiday.jpg"))
    }

    @Test
    fun `the reasons list says what was wrong`() {
        val result = FileNames.sanitize("../My   Photo\u0000<1>.jpg")

        assertTrue(result.reasons.containsAll(listOf("path", "control", "character", "spacing")))
        assertEquals("My Photo_1_.jpg", result.name)
    }
}
