package com.meshdrop.transfer.server

import com.meshdrop.core.model.IncomingRequest

/**
 * Bridge between the socket-level code in [TransferServer] and the app (UI + notifications).
 *
 * The server never talks to the UI directly: it asks this handler, which keeps the
 * implementation testable and lets us swap "ask the user" for "auto-accept from a trusted
 * device" later.
 */
interface IncomingTransferHandler {

    /**
     * Suspends until the user accepts or rejects [request]. The server answers the peer's
     * handshake with whatever this returns, and applies its own timeout on top.
     */
    suspend fun requestApproval(request: IncomingRequest): Boolean

    /** A file has been accepted and is being written to disk. */
    fun onFileStarted(batchId: String, fileId: String)

    /** Progress for one file. [bytesReceived] is cumulative for that file. */
    fun onFileProgress(batchId: String, fileId: String, bytesReceived: Long, sizeBytes: Long)

    /** One file finished. [uri] is where it landed (null when it failed). */
    fun onFileCompleted(batchId: String, fileId: String, uri: String?)

    /** A file could not be written. */
    fun onFileFailed(batchId: String, fileId: String, reason: String)
}
