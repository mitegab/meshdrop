package com.meshdrop.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.meshdrop.core.model.TransferFile
import com.meshdrop.core.util.MeshLog
import java.io.InputStream
import java.util.UUID

/**
 * Everything the sender needs to know about the documents the user picked.
 *
 * Files are opened through the Storage Access Framework, so we deal in content Uris: no
 * `READ_EXTERNAL_STORAGE`, no `READ_MEDIA_*`, and it works for Drive/Downloads/any document
 * provider the user has installed.
 */
class DocumentRepository(private val context: Context) {

    private val resolver get() = context.contentResolver

    /** Reads name, size and mime type of a document Uri. */
    fun describe(uri: Uri): TransferFile? {
        val metadata = runCatching {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                val size = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else -1L
                name to size
            }
        }.getOrNull()

        val fallbackName = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        val name = metadata?.first ?: fallbackName
        val size = (metadata?.second ?: -1L).coerceAtLeast(0L)
        val mime = resolver.getType(uri) ?: guessMime(name)

        return TransferFile(
            id = UUID.randomUUID().toString(),
            name = name,
            sizeBytes = size,
            mimeType = mime,
            localUri = uri.toString(),
        )
    }

    fun openInputStream(uri: Uri): InputStream? = runCatching {
        resolver.openInputStream(uri)
    }.onFailure { MeshLog.w(TAG, "Cannot open $uri", it) }.getOrNull()

    /**
     * Keeps access to a picked document across process restarts, so a queued transfer is
     * still readable after the user (or the OS) rotates the activity.
     */
    fun persistReadPermission(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }.onFailure { MeshLog.v(TAG, "Uri permission is not persistable: $uri") }
    }

    fun releaseReadPermission(uri: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private fun guessMime(name: String): String? {
        val extension = name.substringAfterLast('.', "").takeIf { it.isNotBlank() } ?: return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
    }

    companion object {
        const val TAG = "DocumentRepository"
    }
}
