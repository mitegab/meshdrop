package com.meshdrop.transfer.server

import com.meshdrop.core.model.IncomingRequest
import com.meshdrop.transfer.crypto.SecurityOffer
import com.meshdrop.transfer.crypto.SessionKeys

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

    /**
     * Derives this batch's session keys from the peer's key material, before the user is
     * asked to accept, so the safety code can be shown with the question.
     *
     * @return the agreed keys, or null when this transfer stays plaintext (old peer,
     *         encryption switched off, key material missing).
     */
    fun negotiateKeys(batchId: String, peerMaterial: SecurityOffer?): SessionKeys?

    /** Our own key material, echoed back to the sender when the batch is accepted. */
    fun localMaterialFor(batchId: String): SecurityOffer?

    /** Key that decrypts incoming bytes for this batch, or null when plaintext. */
    fun sessionKeyFor(batchId: String): ByteArray?

    /** A file has been accepted and is being written to disk. */
    fun onFileStarted(batchId: String, fileId: String)

    /** Progress for one file. [bytesReceived] is cumulative for that file. */
    fun onFileProgress(batchId: String, fileId: String, bytesReceived: Long, sizeBytes: Long)

    /** One file finished. [uri] is where it landed (null when it failed). */
    fun onFileCompleted(batchId: String, fileId: String, uri: String?)

    /** A file could not be written. */
    fun onFileFailed(batchId: String, fileId: String, reason: String)
}
