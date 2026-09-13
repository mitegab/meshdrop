package com.meshdrop.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** File classification is pure, so the mime/extension tables are tested on the JVM. */
class FileCategoryTest {

    private fun file(name: String, mime: String? = null, size: Long = 1_000L) =
        TransferFile(id = name, name = name, sizeBytes = size, mimeType = mime)

    @Test
    fun `the mime type is believed when there is one`() {
        assertEquals(FileCategory.IMAGE, FileCategory.of("image/jpeg", "photo.jpg"))
        assertEquals(FileCategory.VIDEO, FileCategory.of("video/mp4", "clip.mp4"))
        assertEquals(FileCategory.AUDIO, FileCategory.of("audio/flac", "track.flac"))
        assertEquals(FileCategory.DOCUMENT, FileCategory.of("application/pdf", "paper.pdf"))
        assertEquals(FileCategory.DOCUMENT, FileCategory.of("text/plain", "notes.txt"))
        assertEquals(FileCategory.ARCHIVE, FileCategory.of("application/zip", "backup.zip"))
        assertEquals(
            FileCategory.APP,
            FileCategory.of("application/vnd.android.package-archive", "app.apk"),
        )
    }

    @Test
    fun `a mime type of octet stream falls back to the extension`() {
        assertEquals(FileCategory.VIDEO, FileCategory.of("application/octet-stream", "holiday.mkv"))
        assertEquals(FileCategory.IMAGE, FileCategory.of("application/octet-stream", "shot.heic"))
        assertEquals(FileCategory.OTHER, FileCategory.of("application/octet-stream", "mystery.bin"))
    }

    @Test
    fun `the extension decides when there is no mime type at all`() {
        assertEquals(FileCategory.IMAGE, FileCategory.of(null, "IMG_0001.JPG"))
        assertEquals(FileCategory.DOCUMENT, FileCategory.of(null, "budget.xlsx"))
        assertEquals(FileCategory.ARCHIVE, FileCategory.of(null, "src.tar.gz"))
        assertEquals(FileCategory.APP, FileCategory.of(null, "build.apk"))
        assertEquals(FileCategory.OTHER, FileCategory.of(null, "unknown"))
        assertEquals(FileCategory.OTHER, FileCategory.of(null, null))
    }

    @Test
    fun `a specific mime type we do not know is not reclassified by its extension`() {
        // model/stl is a real type; calling a .stl file a "Document" would be a guess.
        assertEquals(FileCategory.OTHER, FileCategory.of("model/stl", "part.stl"))
    }

    @Test
    fun `a batch is grouped best known category first`() {
        val files = listOf(
            file("note.txt", "text/plain"),
            file("a.jpg", "image/jpeg"),
            file("b.jpg", "image/jpeg"),
            file("clip.mp4", "video/mp4"),
        )

        val grouped = FileCategory.group(files)

        assertEquals(listOf(FileCategory.IMAGE, FileCategory.VIDEO, FileCategory.DOCUMENT), grouped.keys.toList())
        assertEquals(2, grouped[FileCategory.IMAGE]?.size)
    }

    @Test
    fun `the batch summary reads like a sentence`() {
        assertEquals("No files", FileCategory.describe(emptyList()))
        assertEquals("1 photo", FileCategory.describe(listOf(file("a.jpg", "image/jpeg"))))
        assertEquals(
            "2 photos · 1 video",
            FileCategory.describe(listOf(file("a.jpg", "image/jpeg"), file("b.png", "image/png"), file("c.mp4", "video/mp4"))),
        )
        assertEquals("3 files", FileCategory.describe(listOf(file("a"), file("b"), file("c"))))
    }

    @Test
    fun `labels are singular for one and plural otherwise`() {
        assertEquals("Photo", FileCategory.IMAGE.labelFor(1))
        assertEquals("Photos", FileCategory.IMAGE.labelFor(2))
        assertEquals("Audio", FileCategory.AUDIO.labelFor(3))
        assertEquals("Files", FileCategory.OTHER.labelFor(0))
    }

    @Test
    fun `extensions are read case insensitively and only from the end`() {
        assertEquals("jpg", FileCategory.extensionOf("PHOTO.JPG"))
        assertEquals("gz", FileCategory.extensionOf("archive.tar.gz"))
        assertEquals(null, FileCategory.extensionOf("README"))
        assertEquals(null, FileCategory.extensionOf("trailing."))
        assertEquals(null, FileCategory.extensionOf(null))
    }

    @Test
    fun `a transfer file is classified from its own fields`() {
        assertEquals(FileCategory.VIDEO, FileCategory.of(file("clip.mp4", "video/mp4")))
        assertEquals(FileCategory.IMAGE, FileCategory.of(file("photo.heic")))
    }
}
