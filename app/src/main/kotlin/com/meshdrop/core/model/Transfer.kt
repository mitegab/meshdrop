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
    /** How many times the user has asked for this batch to be tried again. */
    val retryCount: Int = 0,
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

    /** Roll-ups the screens kept recomputing inline. */
    val fileCount: Int get() = files.size

    val completedFiles: Int get() = files.count { it.status == TransferStatus.COMPLETED }

    val failedFiles: Int get() = files.count { it.status == TransferStatus.FAILED }

    val pendingFiles: Int get() = files.count { !it.status.isFinished }

    val durationMillis: Long
        get() = ((finishedAtMillis ?: System.currentTimeMillis()) - startedAtMillis).coerceAtLeast(0L)

    /**
     * Measured over the time the transfer was actually running, which is not the same as
     * [bytesPerSecond]: that one is an EMA of the last few samples and reads 0 the moment
     * a transfer stops.
     */
    val averageBytesPerSecond: Long
        get() = if (durationMillis <= 0L) 0L else bytesTransferred * 1000L / durationMillis

    /** `3 of 4 files`, or the plain count once the batch is finished. */
    val fileSummary: String
        get() = when {
            status.isFinished -> "${completedFiles} of $fileCount file(s)"
            else -> "${completedFiles} of $fileCount file(s) done"
        }

    /** The first thing that went wrong, for the failure line on the card. */
    val failureMessage: String?
        get() = files.firstOrNull { it.status == TransferStatus.FAILED }?.let { file ->
            "${file.name}: ${message ?: "failed"}"
        } ?: message
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
