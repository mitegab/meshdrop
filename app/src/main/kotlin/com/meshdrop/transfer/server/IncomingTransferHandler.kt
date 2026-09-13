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

    /**
     * One file finished. [uri] is where it landed (null when it failed).
     *
     * [landedName] is the name it landed *under*, which is not always the name the sender
     * asked for: a name from the network is cleaned, and a name that already exists in
     * Downloads gets a number. The screens show this one, so the user is not left looking
     * for a file under a name that does not exist.
     */
    fun onFileCompleted(batchId: String, fileId: String, uri: String?, landedName: String? = null)

    /** A file could not be written. */
    fun onFileFailed(batchId: String, fileId: String, reason: String)

    /**
     * The sender's whole-file digest did not match what landed here.
     *
     * Every chunk passed its GCM tag, so this is not a corrupted socket read - it means the
     * bytes that were picked are not the bytes that arrived. Rare, and worth saying out
     * loud: the files stay on disk but the batch is marked failed.
     */
    fun onBatchIntegrityFailed(batchId: String, fileIds: List<String>) {}
}
