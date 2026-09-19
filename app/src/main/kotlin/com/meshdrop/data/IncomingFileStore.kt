package com.meshdrop.data

import com.meshdrop.core.resume.PartialFile
import java.io.OutputStream

/** Storage boundary for the receiver; network tests do not need Android's MediaStore. */
interface IncomingFileStore {
    val partialDirectory: java.io.File? get() = null
    fun createPartial(): PartialFile
    fun createSink(requestedName: String, mimeType: String?): ReceivedFileSink
}

interface ReceivedFileSink {
    val outputStream: OutputStream
    val displayName: String
    /** Publish only after the full file landed. Returns its content URI, or null on failure. */
    fun finish(): String?
    fun abort()
}
