package com.meshdrop.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.meshdrop.core.util.MeshLog
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * A file we are writing into, plus the bookkeeping needed to publish (or discard) it.
 *
 * Modeled after `ContentResolver.openOutputStream` + `IS_PENDING`: a transfer writes into a
 * hidden row, and only when the last byte has landed does [finish] publish it to Downloads.
 */
interface ReceivedFileSink {

    val outputStream: OutputStream

    /**
     * The name the file actually landed under.
     *
     * Not necessarily the name the sender asked for: a name from the network is cleaned, and
     * a name that already exists in Downloads gets a number. The screens show this one.
     */
    val displayName: String

    /** Publishes the file and returns the Uri to show/open/share. */
    fun finish(): Uri?

    /** Discards a partially written file (transfer failed or was cancelled). */
    fun abort()
}

/**
 * Writes incoming files into the public `Downloads/MeshDrop` folder.
 *
 *  * Android 10 (API 29)+ writes through MediaStore, so **no storage permission** is needed.
 *  * API 26–28 uses the legacy public Downloads directory, which needs
 *    `WRITE_EXTERNAL_STORAGE` (requested by the app only on those platforms).
 */
class ReceivedFileStore(private val context: Context) {

    private val resolver get() = context.contentResolver

    /**
     * Opens somewhere to write [requestedName].
     *
     * The name comes from a peer, so it is cleaned by [FileNames] before it goes anywhere
     * near a path, and a name that already exists gets a number rather than replacing the
     * file that is already there.
     */
    fun createSink(requestedName: String, mimeType: String?): ReceivedFileSink {
        val cleaned = FileNames.clean(requestedName)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            createMediaStoreSink(cleaned, mimeType)
        } else {
            createLegacySink(cleaned, mimeType)
        }
    }

    private fun createMediaStoreSink(displayName: String, mimeType: String?): ReceivedFileSink {
        val landedName = displayName
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType ?: FALLBACK_MIME)
            put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)

        if (uri == null) {
            MeshLog.w(TAG, "MediaStore insert failed for $displayName, falling back to app storage")
            return createPrivateSink(displayName, mimeType)
        }
        val stream = runCatching { resolver.openOutputStream(uri) }.getOrNull()
        if (stream == null) {
            runCatching { resolver.delete(uri, null, null) }
            MeshLog.w(TAG, "Could not open output stream for $displayName")
            return createPrivateSink(displayName, mimeType)
        }

        return object : ReceivedFileSink {
            override val outputStream: OutputStream = stream
            override val displayName: String = landedName
            override fun finish(): Uri? {
                stream.flushAndClose()
                val published = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                runCatching { resolver.update(uri, published, null, null) }
                    .onFailure { MeshLog.w(TAG, "Could not publish $displayName", it) }
                return uri
            }

            override fun abort() {
                stream.flushAndClose()
                runCatching { resolver.delete(uri, null, null) }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun createLegacySink(displayName: String, mimeType: String?): ReceivedFileSink {
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            FOLDER_NAME,
        )
        if (!runCatching { directory.mkdirs() }.getOrDefault(false) && !directory.isDirectory) {
            MeshLog.w(TAG, "Cannot create $directory, saving inside the app instead")
            return createPrivateSink(displayName, mimeType)
        }
        val target = directory.fileFor(displayName)
        val stream = runCatching { FileOutputStream(target) }.getOrNull()
            ?: return createPrivateSink(displayName, mimeType)

        return object : ReceivedFileSink {
            override val outputStream: OutputStream = stream
            override val displayName: String = target.name
            override fun finish(): Uri? {
                stream.flushAndClose()
                return runCatching {
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
                }.getOrElse {
                    MeshLog.w(TAG, "FileProvider failed for $target", it)
                    Uri.fromFile(target)
                }
            }

            override fun abort() {
                stream.flushAndClose()
                runCatching { target.delete() }
            }
        }
    }

    /** Last resort when public storage is unavailable: the app's own files directory. */
    private fun createPrivateSink(displayName: String, mimeType: String?): ReceivedFileSink {
        val directory = File(context.filesDir, FOLDER_NAME).apply { mkdirs() }
        val target = directory.fileFor(displayName)
        val stream = FileOutputStream(target)
        return object : ReceivedFileSink {
            override val outputStream: OutputStream = stream
            override val displayName: String = target.name
            override fun finish(): Uri? {
                stream.flushAndClose()
                return runCatching {
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
                }.getOrNull()
            }

            override fun abort() {
                stream.flushAndClose()
                runCatching { target.delete() }
            }
        }
    }

    private fun OutputStream.flushAndClose() {
        runCatching { flush() }
        runCatching { close() }
    }

    /**
     * A file in this directory that does not exist yet: `photo.jpg` becomes `photo (1).jpg`.
     *
     * Downloads does not allow overwriting, and silently replacing a file that is already
     * there is the one outcome nobody asked for. The naming rules live in [FileNames] so the
     * legacy path, the app-private path and the tests all agree.
     */
    private fun File.fileFor(displayName: String): File {
        val directory = this
        return File(directory, FileNames.unique(displayName) { candidate -> File(directory, candidate).exists() })
    }

    companion object {
        const val TAG = "ReceivedFileStore"
        const val FOLDER_NAME = "MeshDrop"
        val RELATIVE_PATH = "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER_NAME"
        const val FALLBACK_MIME = "application/octet-stream"
    }
}
