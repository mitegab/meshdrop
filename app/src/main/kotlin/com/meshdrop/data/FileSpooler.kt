package com.meshdrop.data

import android.content.Context
import android.net.Uri
import com.meshdrop.core.util.MeshLog
import java.io.File
import java.io.FileOutputStream

/**
 * Copies a picked document into the cache directory.
 *
 * The HTTP framing we use needs `Content-Length`, and a few document providers (mostly
 * cloud backed ones) refuse to report a size up front. Spooling those to a temporary file
 * lets us measure them before the handshake.
 */
class FileSpooler(private val context: Context) {

    /**
     * @return the spooled file, or null when the document could not be read.
     */
    fun spool(uri: Uri, inputStream: java.io.InputStream, name: String): File? {
        val directory = File(context.cacheDir, SPOOL_DIRECTORY).apply { mkdirs() }
        val target = File(directory, "${System.currentTimeMillis()}-${name.take(48).replace(Regex("[/\\\\]"), "_")}")
        return runCatching {
            inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            }
            target
        }.onFailure {
            MeshLog.w(TAG, "Could not spool $uri", it)
            runCatching { target.delete() }
        }.getOrNull()
    }

    fun delete(file: File) = runCatching { file.delete() }

    companion object {
        const val TAG = "FileSpooler"
        const val SPOOL_DIRECTORY = "outgoing-spool"
    }
}
