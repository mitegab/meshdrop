package com.meshdrop.transfer.protocol

/**
 * `Range` and `Content-Range`, the two headers that make a resumed upload possible.
 *
 * MeshDrop uses the smallest useful subset of RFC 9110 §14: a single open-ended byte range
 * (`bytes=1048576-`) meaning "send me everything from here on", and the matching
 * `Content-Range` in the response. Multi-range bodies, suffix ranges and the `If-Range`
 * validator are all deliberately unsupported — a resumable file transfer does not need
 * them, and every one of them is a way for two implementations to disagree.
 *
 * Parsing lives here rather than in [Http] so it can be tested without a socket, and so
 * the client and the server cannot drift apart: both sides call the same function.
 */
object HttpRanges {

    const val UNIT = "bytes"

    /** `bytes=1024-` — everything from offset 1024 to the end of the file. */
    fun header(start: Long): String = "$UNIT=$start-"

    /** `bytes=1024-2047/4096`, as the receiver reports what it now holds. */
    fun contentRange(start: Long, endInclusive: Long, total: Long): String =
        "$UNIT $start-$endInclusive/$total"

    /**
     * The start offset a peer asked for, or null when there is no usable range.
     *
     * Anything we do not understand is treated as "no range", which makes the sender
     * restart the file. That is slower than being clever and infinitely safer than
     * guessing an offset and appending bytes to the wrong place.
     */
    fun startOf(headerValue: String?): Long? {
        val value = headerValue?.trim().orEmpty()
        if (value.isEmpty()) return null
        val (unit, range) = value.split("=", limit = 2).let { parts ->
            if (parts.size != 2) return null
            parts[0].trim().lowercase() to parts[1].trim()
        }
        if (unit != UNIT) return null
        if (!range.endsWith("-")) return null // suffix ranges and closed ranges are not ours
        val start = range.dropLast(1).trim().toLongOrNull() ?: return null
        return if (start >= 0L) start else null
    }

    /** True when [headerValue] asks for the whole file, which is the same as asking for nothing. */
    fun isWholeFile(headerValue: String?): Boolean = startOf(headerValue) == 0L

    /**
     * Reads a `Content-Range` back: the first byte offset the receiver confirmed.
     *
     * Used by a resuming sender to sanity check that the peer really has the prefix it
     * claimed, rather than trusting the number it asked for.
     */
    fun contentRangeStart(headerValue: String?): Long? {
        val value = headerValue?.trim().orEmpty()
        if (value.isEmpty()) return null
        val parts = value.split(" ", limit = 2)
        if (parts.size != 2 || parts[0].trim().lowercase() != UNIT) return null
        val range = parts[1].substringBefore('/')
        val start = range.substringBefore('-').trim().toLongOrNull() ?: return null
        val end = range.substringAfter('-', "").trim().toLongOrNull() ?: return null
        return if (start in 0..end) start else null
    }

    /**
     * How many bytes of a [totalBytes] file still have to be sent when [have] are already
     * on the receiving side. Never negative, and never more than the file.
     */
    fun remaining(have: Long, totalBytes: Long): Long =
        (totalBytes - have.coerceIn(0L, totalBytes.coerceAtLeast(0L))).coerceAtLeast(0L)

    /**
     * True when a resume is worth doing.
     *
     * Restarting is cheaper than resuming when only a sliver landed: a fresh connection,
     * a fresh encryption session and a seek cost more than the few kilobytes saved.
     */
    fun worthResuming(have: Long, totalBytes: Long, minimumUsefulBytes: Long = MINIMUM_USEFUL_BYTES): Boolean {
        if (totalBytes <= 0L || have <= 0L) return false
        if (have >= totalBytes) return false
        return have >= minimumUsefulBytes
    }

    /** One chunk: below that, a resume saves less than it costs to set up. */
    const val MINIMUM_USEFUL_BYTES = 64L * 1024
}
