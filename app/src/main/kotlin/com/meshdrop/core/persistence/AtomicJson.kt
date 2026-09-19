package com.meshdrop.core.persistence

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Checksummed, fsync'd atomic replacement. Never deserialize a truncated or oversized journal. */
class AtomicJson(private val file: File, private val maxBytes: Long = 4 * 1024 * 1024) {
    @Synchronized fun read(): JSONObject? = runCatching {
        if (!file.isFile || file.length() !in 1..maxBytes) return null
        val outer = JSONObject(file.readText())
        val payload = outer.getString("payload")
        require(outer.getString("sha256") == digest(payload.toByteArray()))
        JSONObject(payload)
    }.getOrNull()

    @Synchronized fun write(value: JSONObject) {
        file.parentFile?.mkdirs()
        val payload = value.toString()
        val bytes = JSONObject().put("payload", payload).put("sha256", digest(payload.toByteArray())).toString().toByteArray()
        require(bytes.size <= maxBytes) { "Journal exceeds its quota" }
        val temp = File.createTempFile("journal-", ".tmp", file.parentFile)
        try {
            temp.setReadable(false, false); temp.setWritable(false, false)
            temp.setReadable(true, true); temp.setWritable(true, true)
            FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
            try { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally { temp.delete() }
    }
    @Synchronized fun delete() { file.delete() }
    companion object {
        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
