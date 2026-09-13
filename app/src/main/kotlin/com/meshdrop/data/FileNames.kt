package com.meshdrop.data

/** What happened to a name on the way in, so the UI can say so in one line. */
data class SanitizedName(
    val name: String,
    val reasons: List<String> = emptyList(),
) {
    val changed: Boolean get() = reasons.isNotEmpty()

    /** Plain language, no security vocabulary: shown on the receive card. */
    val note: String?
        get() = when {
            reasons.isEmpty() -> null
            else -> "Name shortened to \"$name\""
        }
}

/**
 * Making a name coming off the network safe to write to disk.
 *
 * A sender picks the file name, and MeshDrop writes it into the receiver's storage. That
 * is a small trust boundary with a nasty failure mode: a name like
 * `../../../../etc/passwd`, a name containing a newline, or a 4 000 character name can
 * each turn "receive a photo" into something else. Everything here is deliberately
 * conservative — the worst case is a file called `photo (1).jpg` instead of the original.
 *
 * Pure Kotlin, so the rules are unit tested on the JVM.
 */
object FileNames {

    /** Well under the 255 byte limit of ext4/f2fs, and comfortable for MediaStore. */
    const val MAX_NAME_BYTES = 120

    const val DEFAULT_NAME = "received-file"

    private val ILLEGAL = setOf('<', '>', ':', '"', '/', '\\', '|', '?', '*')

    /** Names that mean something to a filesystem or to Windows, and are never a real file. */
    private val RESERVED = setOf(
        ".", "..", "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    )

    /**
     * Cleans a name received from a peer.
     *
     * Keeps the last path segment, drops control characters, replaces characters that no
     * common filesystem allows, trims dots and spaces from the ends, and shortens to
     * [MAX_NAME_BYTES] without eating the extension.
     */
    fun sanitize(raw: String, fallback: String = DEFAULT_NAME): SanitizedName {
        val reasons = ArrayList<String>()

        val segment = raw.substringAfterLast('/').substringAfterLast('\\')
        if (segment != raw) reasons += "path"

        val withoutControl = segment.filter { it.code >= 0x20 && it.code != 0x7F }
        if (withoutControl.length != segment.length) reasons += "control"

        val replaced = buildString(withoutControl.length) {
            withoutControl.forEach { char ->
                append(if (char in ILLEGAL) '_' else char)
            }
        }
        if (replaced != withoutControl) reasons += "character"

        val trimmed = replaced.trim().trim('.')
        if (trimmed != replaced) reasons += "edge"

        val collapsed = trimmed.replace(Regex("\\s+"), " ")
        if (collapsed != trimmed) reasons += "spacing"

        val shortened = truncate(collapsed)
        if (shortened != collapsed) reasons += "length"

        val name = if (collapsed.isBlank() || shortened.uppercase() in RESERVED) {
            reasons += "reserved"
            fallback
        } else {
            shortened
        }
        return SanitizedName(name = name, reasons = reasons.distinct())
    }

    /** Convenience wrapper for callers that only want the cleaned name. */
    fun clean(raw: String, fallback: String = DEFAULT_NAME): String = sanitize(raw, fallback).name

    /**
     * `photo (1).jpg`, `photo (2).jpg` …
     *
     * A receiver who gets the same file twice should end up with two files, not with the
     * first one silently replaced: the second copy may be a different version, and
     * overwriting is the one outcome nobody asked for.
     */
    fun unique(
        candidate: String,
        taken: Collection<String> = emptyList(),
        isTaken: (String) -> Boolean = { it in taken },
    ): String {
        if (!isTaken(candidate)) return candidate
        val split = splitExtension(candidate)
        val stem = split?.first ?: candidate
        val extension = split?.second?.let { ".$it" } ?: ""
        var index = 1
        while (index <= MAX_UNIQUE_ATTEMPTS) {
            val suffix = " ($index)"
            val budget = MAX_NAME_BYTES - suffix.toByteArray(Charsets.UTF_8).size -
                extension.toByteArray(Charsets.UTF_8).size
            val trimmed = truncate(stem, budget.coerceAtLeast(1)).trimEnd(' ', '.')
            val proposal = "$trimmed$suffix$extension"
            if (!isTaken(proposal)) return proposal
            index++
        }
        // Pathological case (a folder full of numbered copies): fall back to a timestamp.
        val stamped = truncate("$stem-${System.currentTimeMillis()}")
        return stamped + extension
    }

    /** Lowercase extension without the dot, or null when the name has none. */
    fun extension(name: String): String? = splitExtension(name)?.second?.lowercase()

    /** Name without its extension. */
    fun baseName(name: String): String = splitExtension(name)?.first ?: name

    /**
     * Splits `photo.JPG` into `photo` and `JPG`.
     *
     * A leading dot is not an extension (`.gitignore` is a whole name), and neither is a
     * trailing dot or a 40 character "extension" that is really a sentence.
     */
    private fun splitExtension(name: String): Pair<String, String>? {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.lastIndex) return null
        val extension = name.substring(dot + 1)
        if (extension.length !in 1..12 || extension.any { it.isWhitespace() }) return null
        return name.substring(0, dot) to extension
    }

    /**
     * Shortens [name] to at most [maxBytes] UTF-8 bytes, keeping the extension.
     *
     * Cuts on a character boundary so a multi-byte emoji cannot be split in half, and
     * drops a trailing space or dot left behind by the cut.
     */
    fun truncate(name: String, maxBytes: Int = MAX_NAME_BYTES): String {
        if (name.toByteArray(Charsets.UTF_8).size <= maxBytes) return name
        val extension = extension(name)?.let { ".$it" } ?: ""
        val budget = (maxBytes - extension.toByteArray(Charsets.UTF_8).size).coerceAtLeast(1)
        val stem = name.removeSuffix(extension)
        val builder = StringBuilder()
        var used = 0
        for (char in stem) {
            val size = char.toString().toByteArray(Charsets.UTF_8).size
            if (used + size > budget) break
            builder.append(char)
            used += size
        }
        return (builder.toString().trimEnd(' ', '.') + extension).ifBlank { name.take(1) }
    }

    /** True when the name a peer sent would have been written somewhere else. */
    fun looksLikeTraversal(raw: String): Boolean =
        raw.contains("..") || raw.startsWith("/") || raw.startsWith("\\") || raw.contains(':')

    private const val MAX_UNIQUE_ATTEMPTS = 999
}
