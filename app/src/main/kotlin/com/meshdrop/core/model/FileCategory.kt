package com.meshdrop.core.model

/**
 * What kind of thing a file is, in the vocabulary the UI already uses.
 *
 * Three screens need this — the picker's category chips, the file rows in a transfer, and
 * the History feed — and until now each of them asked `mimeType?.startsWith("image/")` in
 * its own way. One enum means one answer, and it means the answer can be tested.
 *
 * The mime type is believed when there is one; the extension is the fallback, because a
 * surprising number of documents arrive as `application/octet-stream` and calling all of
 * them "File" is not helpful.
 */
enum class FileCategory(val label: String, val plural: String) {
    IMAGE("Photo", "Photos"),
    VIDEO("Video", "Videos"),
    AUDIO("Audio", "Audio"),
    DOCUMENT("Document", "Documents"),
    ARCHIVE("Archive", "Archives"),
    APP("App", "Apps"),
    OTHER("File", "Files"),
    ;

    /** `Photo` for one, `Photos` for any other number. */
    fun labelFor(count: Int): String = if (count == 1) label else plural

    companion object {

        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "dng", "raw", "svg")
        private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "mov", "avi", "webm", "3gp", "m4v", "mpg", "mpeg", "ts")
        private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "oga", "wma", "mid")
        private val DOCUMENT_EXTENSIONS = setOf(
            "pdf", "doc", "docx", "txt", "rtf", "odt", "xls", "xlsx", "ods", "csv",
            "ppt", "pptx", "odp", "md", "epub", "json", "xml", "html",
        )
        private val ARCHIVE_EXTENSIONS = setOf("zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar", "jar")
        private val APP_EXTENSIONS = setOf("apk", "apks", "xapk", "aab")

        /**
         * Classifies a file.
         *
         * @param mimeType whatever the sender or the document provider claimed; may be null
         *                 or the useless `application/octet-stream`
         * @param fileName used for the extension when the mime type says nothing
         */
        fun of(mimeType: String?, fileName: String? = null): FileCategory {
            val type = mimeType?.lowercase()?.trim().orEmpty()
            when {
                type.startsWith("image/") -> return IMAGE
                type.startsWith("video/") -> return VIDEO
                type.startsWith("audio/") -> return AUDIO
                type == "application/pdf" ||
                    type.startsWith("text/") ||
                    type in DOCUMENT_MIME_TYPES -> return DOCUMENT
                type in ARCHIVE_MIME_TYPES -> return ARCHIVE
                type == "application/vnd.android.package-archive" -> return APP
            }
            if (type.isNotBlank() && type != "application/octet-stream" && type != "*/*") {
                return OTHER
            }
            return when (extensionOf(fileName)) {
                in IMAGE_EXTENSIONS -> IMAGE
                in VIDEO_EXTENSIONS -> VIDEO
                in AUDIO_EXTENSIONS -> AUDIO
                in DOCUMENT_EXTENSIONS -> DOCUMENT
                in ARCHIVE_EXTENSIONS -> ARCHIVE
                in APP_EXTENSIONS -> APP
                else -> OTHER
            }
        }

        fun of(file: TransferFile): FileCategory = of(file.mimeType, file.name)

        /** Grouped for a header like `3 photos · 1 video`, best known category first. */
        fun group(files: List<TransferFile>): Map<FileCategory, List<TransferFile>> =
            files.groupBy { of(it) }.toSortedMap(compareBy { it.ordinal }).toMap()

        /** `3 photos · 1 video` — the one line a batch summary needs. */
        fun describe(files: List<TransferFile>): String {
            if (files.isEmpty()) return "No files"
            return group(files).entries.joinToString(" · ") { (category, list) ->
                "${list.size} ${category.labelFor(list.size).lowercase()}"
            }
        }

        fun extensionOf(fileName: String?): String? {
            val name = fileName ?: return null
            val dot = name.lastIndexOf('.')
            if (dot < 0 || dot == name.lastIndex) return null
            return name.substring(dot + 1).lowercase()
        }

        private val DOCUMENT_MIME_TYPES = setOf(
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/rtf",
            "application/epub+zip",
            "application/json",
            "application/xml",
        )

        private val ARCHIVE_MIME_TYPES = setOf(
            "application/zip",
            "application/x-tar",
            "application/gzip",
            "application/x-7z-compressed",
            "application/vnd.rar",
            "application/java-archive",
        )
    }
}
