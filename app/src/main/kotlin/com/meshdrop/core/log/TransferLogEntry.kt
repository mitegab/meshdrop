package com.meshdrop.core.log

import com.meshdrop.core.model.SecurityState
import com.meshdrop.core.model.TransferDirection
import com.meshdrop.core.model.TransferJob
import com.meshdrop.core.model.TransferStatus

/**
 * One finished transfer, flattened into something that can be written to a file.
 *
 * The engine's job list is the *live* view: it exists in memory and is deliberately short
 * lived. A log entry is the same information after the transfer is over, reduced to the
 * fields a human actually asks about later — what moved, with whom, how fast, and whether
 * it was encrypted and intact.
 *
 * The wire format is one tab separated line per entry ([toLine] / [fromLine]) rather than
 * JSON: it appends atomically, it survives a truncated last line, and `adb pull` + `cat`
 * is enough to read it. Unknown fields are ignored and missing ones fall back, so an
 * older log file still parses after an upgrade.
 */
data class TransferLogEntry(
    val jobId: String,
    val direction: TransferDirection,
    val peerId: String,
    val peerName: String,
    val fileCount: Int,
    val totalBytes: Long,
    val status: TransferStatus,
    val startedAtMillis: Long,
    val finishedAtMillis: Long,
    val message: String? = null,
    val averageBytesPerSecond: Long = 0L,
    val encrypted: Boolean = false,
    val verified: Boolean = false,
    val integrityConfirmed: Boolean = false,
    val fileNames: List<String> = emptyList(),
) {

    val durationMillis: Long get() = (finishedAtMillis - startedAtMillis).coerceAtLeast(0L)

    val succeeded: Boolean get() = status == TransferStatus.COMPLETED

    /** `12 MB in 8s` style summary, computed from the recorded numbers only. */
    val summary: String
        get() = "${com.meshdrop.core.util.Format.bytes(totalBytes)} · $fileCount file(s)"

    fun toLine(): String = listOf(
        VERSION,
        jobId,
        direction.name,
        peerId,
        peerName,
        fileCount.toString(),
        totalBytes.toString(),
        status.name,
        startedAtMillis.toString(),
        finishedAtMillis.toString(),
        message ?: "",
        averageBytesPerSecond.toString(),
        flag(encrypted),
        flag(verified),
        flag(integrityConfirmed),
        fileNames.joinToString(UNIT_SEPARATOR) { it.escape() },
    ).joinToString(SEPARATOR) { it.escape() }

    companion object {

        /** Bumped when the column layout changes; older lines are still readable. */
        const val VERSION = "1"

        private const val SEPARATOR = "\t"
        private const val UNIT_SEPARATOR = "\u0001"

        private fun flag(value: Boolean): String = if (value) "1" else "0"

        /**
         * Parses one stored line. Returns null when the line is not a log entry at all
         * (a truncated write, a hand-edited file), so the caller can skip it.
         */
        fun fromLine(line: String): TransferLogEntry? {
            val columns = line.split(SEPARATOR)
            if (columns.size < 10) return null
            val jobId = columns[1].unescape()
            if (jobId.isBlank()) return null
            return runCatching {
                TransferLogEntry(
                    jobId = jobId,
                    direction = runCatching { TransferDirection.valueOf(columns[2].unescape()) }
                        .getOrDefault(TransferDirection.RECEIVE),
                    peerId = columns[3].unescape(),
                    peerName = columns[4].unescape(),
                    fileCount = columns[5].unescape().toIntOrNull() ?: 0,
                    totalBytes = columns[6].unescape().toLongOrNull() ?: 0L,
                    status = runCatching { TransferStatus.valueOf(columns[7].unescape()) }
                        .getOrDefault(TransferStatus.FAILED),
                    startedAtMillis = columns[8].unescape().toLongOrNull() ?: 0L,
                    finishedAtMillis = columns[9].unescape().toLongOrNull() ?: 0L,
                    message = columns.getOrNull(10)?.unescape()?.takeIf { it.isNotBlank() },
                    averageBytesPerSecond = columns.getOrNull(11)?.unescape()?.toLongOrNull() ?: 0L,
                    encrypted = columns.getOrNull(12)?.unescape() == "1",
                    verified = columns.getOrNull(13)?.unescape() == "1",
                    integrityConfirmed = columns.getOrNull(14)?.unescape() == "1",
                    fileNames = columns.getOrNull(15)
                        ?.unescape()
                        ?.split(UNIT_SEPARATOR)
                        ?.map { it.unescape() }
                        ?.filter { it.isNotBlank() }
                        ?: emptyList(),
                )
            }.getOrNull()
        }

        /** Flattens a finished job (plus what we knew about its security) into an entry. */
        fun from(
            job: TransferJob,
            security: SecurityState = SecurityState.UNKNOWN,
            finishedAtMillis: Long = job.finishedAtMillis ?: System.currentTimeMillis(),
        ): TransferLogEntry {
            val duration = (finishedAtMillis - job.startedAtMillis).coerceAtLeast(0L)
            return TransferLogEntry(
                jobId = job.id,
                direction = job.direction,
                peerId = job.peerId,
                peerName = job.peerName,
                fileCount = job.files.size,
                totalBytes = job.totalBytes,
                status = job.status,
                startedAtMillis = job.startedAtMillis,
                finishedAtMillis = finishedAtMillis,
                message = job.message,
                averageBytesPerSecond = if (duration <= 0L) 0L else job.bytesTransferred * 1000L / duration,
                encrypted = security.isEncrypted,
                verified = security.trustLevel == com.meshdrop.core.model.TrustLevel.VERIFIED,
                integrityConfirmed = security.integrityConfirmed,
                fileNames = job.files.map { it.name },
            )
        }
    }
}

private fun String.escape(): String = replace("\\", "\\\\")
    .replace("\t", "\\t")
    .replace("\r", "\\r")
    .replace("\n", "\\n")

private fun String.unescape(): String {
    val source = this
    return buildString(source.length) {
        var index = 0
        while (index < source.length) {
            val char = source[index]
            if (char == '\\' && index + 1 < source.length) {
                when (val next = source[index + 1]) {
                    't' -> append('\t')
                    'r' -> append('\r')
                    'n' -> append('\n')
                    '\\' -> append('\\')
                    else -> append(next)
                }
                index += 2
            } else {
                append(char)
                index++
            }
        }
    }
}
