package com.meshdrop.core.model

/**
 * A single file inside a transfer, plus its live progress.
 *
 * The same model is used on both sides of the wire: on the sender `localUri` points at the
 * document the user picked, on the receiver it is filled in with the MediaStore row once the
 * payload has been written to disk.
 */
data class TransferFile(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val mimeType: String? = null,
    val bytesTransferred: Long = 0L,
    val status: TransferStatus = TransferStatus.WAITING,
    val localUri: String? = null,
)

enum class TransferDirection { SEND, RECEIVE }

/**
 * Lifecycle of a transfer (or of one file inside it).
 *
 * WAITING → IN_PROGRESS → COMPLETED
 *                      ↘ FAILED / CANCELLED / REJECTED
 */
enum class TransferStatus {
    /** Queued, or (on the receiver) waiting for the user to accept the batch. */
    WAITING,

    IN_PROGRESS,

    COMPLETED,

    FAILED,

    /** The receiver declined the batch. */
    REJECTED,

    /** Cancelled locally by either side. */
    CANCELLED,
    ;

    val isFinished: Boolean
        get() = this == COMPLETED || this == FAILED || this == REJECTED || this == CANCELLED

    val isSuccess: Boolean
        get() = this == COMPLETED
}

data class TransferJob(
    val id: String,
    val direction: TransferDirection,
    val peerName: String,
    val peerId: String,
    val files: List<TransferFile> = emptyList(),
    val bytesTransferred: Long = 0L,
    val totalBytes: Long = 0L,
    val bytesPerSecond: Long = 0L,
    val status: TransferStatus = TransferStatus.WAITING,
    val message: String? = null,
    val startedAtMillis: Long = System.currentTimeMillis(),
    val finishedAtMillis: Long? = null,
) {
    val progress: Float
        get() = if (totalBytes <= 0L) 0f else (bytesTransferred.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)

    val remainingBytes: Long
        get() = (totalBytes - bytesTransferred).coerceAtLeast(0L)

    val etaMillis: Long?
        get() {
            if (bytesPerSecond <= 0L) return null
            val remaining = remainingBytes
            if (remaining <= 0L) return 0L
            return remaining * 1000L / bytesPerSecond
        }

    val isActive: Boolean
        get() = !status.isFinished
}

/**
 * A batch a remote peer wants to push to us. Lives until the user accepts or rejects it.
 */
data class IncomingRequest(
    val batchId: String,
    val senderId: String,
    val senderName: String,
    val senderAddress: String,
    val files: List<TransferFile>,
    val totalBytes: Long,
    val arrivedAtMillis: Long = System.currentTimeMillis(),
)
