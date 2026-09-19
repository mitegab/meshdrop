package com.meshdrop.transfer.client
import android.net.Uri
import com.meshdrop.core.model.TransferFile
import com.meshdrop.data.DocumentRepository
import com.meshdrop.data.FileSpooler
import java.io.*

class AndroidPayloadSource(private val documentRepository: DocumentRepository, private val fileSpooler: FileSpooler) : PayloadSource {
    /**
     * Opens the bytes to send. When the document provider did not report a size we spool the
     * document to the cache first, because the protocol needs `Content-Length` up front.
     */
    override fun open(file: TransferFile): Pair<InputStream, Long>? {
        val uri = file.localUri?.let { Uri.parse(it) } ?: return null
        if (file.sizeBytes > 0L) {
            val stream = documentRepository.openInputStream(uri) ?: return null
            return stream to file.sizeBytes
        }
        val original = documentRepository.openInputStream(uri) ?: return null
        val spooled: File = fileSpooler.spool(uri, original, file.name) ?: return null
        val stream = FileInputStream(spooled)
        return RememberingStream(stream, spooled) to spooled.length()
    }

    /** Deletes the spooled copy once the upload is done. */
    private inner class RememberingStream(
        private val delegate: InputStream,
        private val spooled: File,
    ) : InputStream() {
        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
        override fun available(): Int = delegate.available()
        override fun close() {
            runCatching { delegate.close() }
            fileSpooler.delete(spooled)
        }
    }

}
