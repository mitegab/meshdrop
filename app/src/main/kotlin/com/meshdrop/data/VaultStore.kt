package com.meshdrop.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.meshdrop.core.model.TransferJob
import com.meshdrop.core.util.MeshLog
import java.io.File

/**
 * The vault: files that should not sit in shared storage any more.
 *
 * "Vault" here means what the platform already gives us for free — a file inside the app's
 * private directory, which no other app (and no file manager, and no USB connection) can read
 * without root. We copy the bytes there and delete the shared copy, then hand out access only
 * through [FileProvider] and only after the device lock has been confirmed.
 */
class VaultStore(private val context: Context) {

    private val root: File get() = File(context.filesDir, VAULT_DIR).apply { mkdirs() }

    /** Copies every file of [job] into the vault and deletes the shared originals. */
    fun moveIn(job: TransferJob): Boolean {
        val target = File(root, job.id).apply { mkdirs() }
        var moved = 0
        job.files.forEach { file ->
            val uri = file.localUri?.let(Uri::parse) ?: return@forEach
            val destination = File(target, file.name)
            val copied = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                } != null
            }.onFailure { MeshLog.w(TAG, "Cannot copy $uri into the vault", it) }
                .getOrDefault(false)

            if (!copied) return@forEach
            runCatching { context.contentResolver.delete(uri, null, null) }
                .onFailure { MeshLog.w(TAG, "Copied but could not remove the original $uri", it) }
            moved++
        }
        return moved > 0
    }

    fun contains(jobId: String): Boolean = File(root, jobId).exists()

    fun filesFor(jobId: String): List<File> =
        File(root, jobId).listFiles()?.toList() ?: emptyList()

    /** Shareable (and revocable) Uri for a vaulted file, for Open / Re-share. */
    fun contentUri(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}$AUTHORITY_SUFFIX", file)

    fun delete(jobId: String) {
        File(root, jobId).deleteRecursively()
    }

    companion object {
        private const val TAG = "VaultStore"
        private const val VAULT_DIR = "vault"
        private const val AUTHORITY_SUFFIX = ".fileprovider"
    }
}
