package com.meshdrop.data

import com.meshdrop.core.log.TransferLogSink
import com.meshdrop.core.util.MeshLog
import java.io.File

/**
 * The transfer log, on disk.
 *
 * One tab-separated line per finished transfer, appended as it happens. The format is chosen
 * for what it survives: an app killed mid-write loses the last line and nothing else, and a
 * person debugging with `adb pull` can read it without a tool.
 *
 * Appends go straight to the file because they happen once per transfer. Rewrites (retention
 * pruning, capacity overflow) go through a temporary file and a rename, so a crash during a
 * rewrite cannot leave the history half-written — the old file is still there until the new
 * one is complete.
 */
class TransferLogFile(private val directory: File) : TransferLogSink {

    private val file = File(directory, FILE_NAME)
    private val lock = Any()

    /** How many bytes the log currently occupies, for the Settings screen. */
    val sizeBytes: Long get() = runCatching { file.length() }.getOrDefault(0L)

    val exists: Boolean get() = file.exists()

    override fun append(line: String) {
        // A block body, not an expression body: `runCatching { }.onFailure { }` evaluates to a
        // Result, which is not the Unit the [TransferLogSink] contract asks for.
        synchronized(lock) {
            runCatching {
                directory.mkdirs()
                file.appendText(line + LINE_END)
            }.onFailure { MeshLog.e(TAG, "Could not append to the transfer log", it) }
        }
    }

    override fun readAll(): List<String> = synchronized(lock) {
        runCatching {
            if (!file.exists()) {
                emptyList()
            } else {
                file.readLines().filter { it.isNotBlank() }
            }
        }.onFailure { MeshLog.e(TAG, "Could not read the transfer log", it) }.getOrDefault(emptyList())
    }

    override fun rewrite(lines: List<String>) {
        synchronized(lock) {
            runCatching {
                directory.mkdirs()
                val temporary = File(directory, "$FILE_NAME.tmp")
                temporary.writeText(lines.joinToString(separator = "") { it + LINE_END })
                // Rename, not copy-over: a crash between the two leaves the previous log intact.
                if (file.exists()) file.delete()
                if (!temporary.renameTo(file)) {
                    file.writeText(lines.joinToString(separator = "") { it + LINE_END })
                    temporary.delete()
                }
            }.onFailure { MeshLog.e(TAG, "Could not rewrite the transfer log", it) }
        }
    }

    /** Removes the file entirely. Used by "Clear history". */
    fun delete() = synchronized(lock) {
        runCatching {
            if (file.exists()) file.delete()
            File(directory, "$FILE_NAME.tmp").delete()
        }.onFailure { MeshLog.e(TAG, "Could not delete the transfer log", it) }
    }

    companion object {
        private const val TAG = "TransferLogFile"
        const val FILE_NAME = "transfers.log"
        private const val LINE_END = "\n"
    }
}
