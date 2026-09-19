package com.meshdrop.core.resume

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

/** App-private staging. No caller-controlled name is used as a filesystem path. */
class PartialFile private constructor(val file: File, @Suppress("UNUSED_PARAMETER") restored: Boolean) {
    constructor(directory: File) : this(File.createTempFile("incoming-", ".part", directory.apply { mkdirs() }), false)
    val size: Long get() = file.length()

    fun digest(): String = file.inputStream().use { digestPrefix(it, size) }

    /** Verify before opening in append mode: never append to an unverified prefix. */
    fun append(offset: Long, prefixDigest: String?): FileOutputStream {
        require(offset >= 0) { "Negative offset" }
        if (offset == 0L) return FileOutputStream(file, false)
        require(offset == size && prefixDigest != null && digest().equals(prefixDigest, true)) {
            "Resume prefix changed; restart the file"
        }
        return FileOutputStream(file, true)
    }

    fun discard() { file.delete() }

    companion object {
        fun restore(directory: File, name: String): PartialFile? = runCatching {
            require(name.matches(Regex("incoming-[A-Za-z0-9-]+\\.part")))
            val file = File(directory, name)
            require(file.canonicalFile.parentFile == directory.canonicalFile && file.isFile)
            PartialFile(file, true)
        }.getOrNull()

        /** Reads exactly the prefix, leaving the stream positioned at the resume offset. */
        fun digestPrefix(input: InputStream, length: Long, onRead: () -> Unit = {}): String {
            require(length >= 0)
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var remaining = length
            while (remaining > 0) {
                onRead()
                val n = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                if (n < 0) throw java.io.EOFException("Resume prefix is longer than the source")
                if (n == 0) continue
                digest.update(buffer, 0, n)
                remaining -= n
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}
