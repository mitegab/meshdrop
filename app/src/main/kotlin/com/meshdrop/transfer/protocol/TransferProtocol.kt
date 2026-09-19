package com.meshdrop.transfer.protocol

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Constants and control messages for the MeshDrop transfer protocol.
 *
 * Transport: TCP. Framing: the minimal HTTP/1.1 dialect in [Http]. Payload encoding:
 * JSON for control messages, raw bytes for file contents.
 *
 * A transfer is a three step conversation, always initiated by the sender:
 *
 *   1. `POST /meshdrop/handshake`  – sender describes the batch, receiver asks its user
 *      and only replies once the batch has been accepted or rejected (long poll).
 *   2. `PUT  /meshdrop/upload/<batchId>/<fileId>` – one connection per file, raw bytes.
 *   3. `POST /meshdrop/complete?batchId=…` – tidy up, reports how many files landed.
 */
object TransferProtocol {

    /**
     * v1: plaintext.
     * v2: optional end-to-end encryption (ephemeral ECDH + AES-256-GCM frames). A v2 peer
     *     still talks plaintext to a v1 peer: both halves of the key exchange are optional,
     *     and the transfer only becomes encrypted when *both* sides offer keys.
     * v3: whole-file SHA-256 digests reported at completion, and resumable uploads via
     *     `Range`. Both are opt-in per session: see [ProtocolVersions.capabilities].
     *
     * Discovery advertises its own attribute version separately
     * ([com.meshdrop.discovery.nsd.MeshDropService.PROTOCOL_VERSION]); this one travels in
     * the handshake body, which is the only place both sides have already agreed to talk.
     */
    const val VERSION = ProtocolVersions.CURRENT

    const val PATH_READY = "/meshdrop/ready"
    const val PATH_PING = "/meshdrop/ping"
    const val PATH_HANDSHAKE = "/meshdrop/handshake"
    const val PATH_UPLOAD = "/meshdrop/upload"
    const val PATH_COMPLETE = "/meshdrop/complete"

    /** `GET /meshdrop/status?batchId=…` → what the receiver already holds for a batch. */
    const val PATH_STATUS = "/meshdrop/status"

    const val HEADER_BATCH_ID = "X-MeshDrop-Batch"
    /** Present on an encrypted upload: the only thing a passive observer learns. */
    const val HEADER_ENCRYPTION = "X-MeshDrop-Enc"
    const val HEADER_FILE_ID = "X-MeshDrop-File"
    const val HEADER_FILE_NAME = "X-MeshDrop-Name"
    const val HEADER_SENDER_ID = "X-MeshDrop-Sender"
    /**
     * Whole-file SHA-256, hex. Sent by the receiver in the upload ack (what it wrote) and by
     * the sender at completion (what it read); the two are compared before a batch is called
     * finished.
     */
    const val HEADER_PREFIX_DIGEST = "X-MeshDrop-Prefix-SHA256"
    const val HEADER_PLAIN_SIZE = "X-MeshDrop-Size"
    const val HEADER_DIGEST = "X-MeshDrop-Digest"
    const val QUERY_BATCH_ID = "batchId"

    /** Standard `Range: bytes=<start>-` on a resumed upload; see [Http.rangeStart]. */
    const val HEADER_RANGE = "Range"

    const val CONNECT_TIMEOUT_MS = 8_000
    const val SOCKET_TIMEOUT_MS = 30_000

    /** The receiver has this long to answer a handshake (it is waiting on a human). */
    const val HANDSHAKE_TIMEOUT_MS = 120_000

    /** Size of the copy buffer used while streaming a file. */
    const val CHUNK_BYTES = 64 * 1024

    fun uploadPath(batchId: String, fileId: String): String = "$PATH_UPLOAD/$batchId/$fileId"
}

/** Metadata for one file in a batch. Mirrors [com.meshdrop.core.model.TransferFile]. */
data class FileMeta(
    val id: String,
    val name: String,
    val size: Long,
    val mime: String?,
)

data class HandshakeRequest(
    val version: Int = TransferProtocol.VERSION,
    val batchId: String,
    val senderId: String,
    val senderName: String,
    val files: List<FileMeta>,
    /** Our half of the key exchange; absent when this peer cannot (or will not) encrypt. */
    val security: SecurityOfferDto? = null,
)

/** One side of the key exchange, base64 encoded for the JSON body. */
data class SecurityOfferDto(
    val scheme: String,
    val ephemeralPublicKey: ByteArray,
    val signature: ByteArray,
    val identityPublicKey: ByteArray,
    val fingerprint: String,
)

/** Reply to a handshake: the decision, plus an optional human readable reason. */
data class HandshakeResponse(
    val decision: Decision,
    val message: String? = null,
    /** The receiver's half of the key exchange, echoed back when it accepted and can encrypt. */
    val security: SecurityOfferDto? = null,
    val requiresVerification: Boolean = false,
) {
    enum class Decision { ACCEPTED, REJECTED, TIMEOUT, ERROR }

    val accepted: Boolean get() = decision == Decision.ACCEPTED
}

data class UploadAck(
    val fileId: String,
    val bytes: Long,
    val durationMillis: Long,
    /** SHA-256 of the file as it landed, hex. Absent when the peer is on protocol v2. */
    val digest: String? = null,
    /** Bytes the receiver already had and did not need again. */
    val resumedBytes: Long = 0L,
) {
    val totalOnDisk: Long get() = resumedBytes + bytes
}

/**
 * What the receiver already holds for a batch.
 *
 * Asked for before a *retry*, so the second attempt sends only what is missing. Not asked
 * for on a first attempt: a fresh batch has nothing to report and the round trip would only
 * delay the first byte.
 */
data class BatchStatus(
    val batchId: String,
    /** File ids that landed complete, in the order they arrived. */
    val completedFileIds: List<String> = emptyList(),
    val bytesReceived: Long = 0L,
    /** Only advertised by receivers that implement verified partial-file append. */
    val partialFiles: Map<String, PartialStatus> = emptyMap(),
) {
    fun isComplete(fileId: String): Boolean = fileId in completedFileIds

    val isEmpty: Boolean get() = completedFileIds.isEmpty()
}

data class PartialStatus(val bytes: Long, val digest: String)

/** One file's outcome, reported by the sender when the batch is finished. */
data class CompleteFile(
    val fileId: String,
    val bytes: Long,
    val digest: String?,
)

/** The body of `POST /meshdrop/complete`. */
data class CompleteRequest(
    val batchId: String,
    val files: List<CompleteFile>,
)

/**
 * The receiver's verdict on the batch.
 *
 * [mismatchedFiles] is the interesting field: a file whose digest does not match was
 * modified somewhere between the two disks, and the honest thing is to say so rather than
 * to file it under Downloads and stay quiet.
 */
data class CompleteReport(
    val filesLanded: Int,
    val bytesWritten: Long,
    val mismatchedFiles: List<String> = emptyList(),
) {
    val integrityFailed: Boolean get() = mismatchedFiles.isNotEmpty()

    val message: String?
        get() = when {
            !integrityFailed -> null
            mismatchedFiles.size == 1 -> "Integrity check failed on ${mismatchedFiles.first()}"
            else -> "Integrity check failed on ${mismatchedFiles.size} files"
        }
}

/**
 * JSON encoding/decoding of the control messages.
 *
 * `org.json` ships with Android, so this costs no dependency. Every parser is total: on
 * malformed input it returns null and the caller answers `400 Bad Request`.
 */
object ControlMessages {

    private const val K_VERSION = "version"
    private const val K_BATCH = "batchId"
    private const val K_SENDER_ID = "senderId"
    private const val K_SENDER_NAME = "senderName"
    private const val K_FILES = "files"
    private const val K_ID = "id"
    private const val K_NAME = "name"
    private const val K_SIZE = "size"
    private const val K_MIME = "mime"
    private const val K_STATUS = "status"
    private const val K_MESSAGE = "message"
    private const val K_ERROR = "error"
    private const val K_BYTES = "bytes"
    private const val K_DURATION = "durationMs"
    private const val K_SECURITY = "security"
    private const val K_SCHEME = "scheme"
    private const val K_PUB = "pub"
    private const val K_SIGNATURE = "sig"
    private const val K_IDENTITY = "idpub"
    private const val K_FINGERPRINT = "idfp"
    private const val K_SHA256 = "sha256"
    private const val K_RESUMED = "resumed"
    private const val K_LANDED = "landed"
    private const val K_WRITTEN = "bytesWritten"
    private const val K_MISMATCH = "mismatch"
    private const val K_COMPLETED = "completed"
    private const val K_DEVICE_ID = "deviceId"
    private const val K_PROTOCOL = "protocol"

    fun error(message: String): String = JSONObject().put(K_ERROR, message).toString()

    fun errorMessage(body: String): String? = runCatching {
        JSONObject(body).optString(K_ERROR).takeIf { it.isNotBlank() }
    }.getOrNull()

    fun ping(deviceId: String, deviceName: String): String = JSONObject()
        .put(K_DEVICE_ID, deviceId)
        .put(K_NAME, deviceName)
        .put(K_PROTOCOL, TransferProtocol.VERSION)
        .toString()

    fun SecurityOfferDto.toJsonObject(): JSONObject = JSONObject()
        .put(K_SCHEME, scheme)
        .put(K_PUB, ephemeralPublicKey.base64())
        .put(K_SIGNATURE, signature.base64())
        .put(K_IDENTITY, identityPublicKey.base64())
        .put(K_FINGERPRINT, fingerprint)

    fun parseSecurityOffer(source: JSONObject?): SecurityOfferDto? = runCatching {
        val json = source ?: return null
        val scheme = json.optString(K_SCHEME)
        if (scheme.isBlank()) return null
        SecurityOfferDto(
            scheme = scheme,
            ephemeralPublicKey = json.optString(K_PUB).decodeBase64(),
            signature = json.optString(K_SIGNATURE).decodeBase64(),
            identityPublicKey = json.optString(K_IDENTITY).decodeBase64(),
            fingerprint = json.optString(K_FINGERPRINT),
        )
    }.getOrNull()

    fun HandshakeRequest.toJson(): String = JSONObject()
        .put(K_VERSION, version)
        .put(K_BATCH, batchId)
        .put(K_SENDER_ID, senderId)
        .put(K_SENDER_NAME, senderName)
        .apply {
            security?.let { put(K_SECURITY, it.toJsonObject()) }
        }
        .put(K_FILES, JSONArray().apply {
            files.forEach { file ->
                put(
                    JSONObject()
                        .put(K_ID, file.id)
                        .put(K_NAME, file.name)
                        .put(K_SIZE, file.size)
                        .put(K_MIME, file.mime ?: ""),
                )
            }
        })
        .toString()

    fun parseHandshake(body: String): HandshakeRequest? = runCatching {
        val json = JSONObject(body)
        val batchId = json.optString(K_BATCH)
        if (!batchId.matches(Regex("[A-Za-z0-9_.-]{1,128}"))) return null
        val filesArray = json.optJSONArray(K_FILES) ?: return null
        if (filesArray.length() !in 1..1024) return null
        val files = mutableListOf<FileMeta>()
        for (index in 0 until filesArray.length()) {
            val entry = filesArray.optJSONObject(index) ?: return null
            val id = entry.optString(K_ID)
            val name = entry.optString(K_NAME)
            if (!id.matches(Regex("[A-Za-z0-9_.-]{1,128}")) || name.isBlank() || name.length > 255) return null
            if (entry.optLong(K_SIZE, -1) < 0) return null
            files += FileMeta(
                id = id,
                name = name,
                size = entry.optLong(K_SIZE, 0L),
                mime = entry.optString(K_MIME).takeIf { it.isNotBlank() },
            )
        }
        if (files.map { it.id }.distinct().size != files.size) return null
        // Catch overflow before storage budgets or progress counters see the manifest.
        files.fold(0L) { total, file -> Math.addExact(total, file.size) }
        if (json.has(K_SECURITY) && parseSecurityOffer(json.optJSONObject(K_SECURITY)) == null) return null
        HandshakeRequest(
            version = json.optInt(K_VERSION, TransferProtocol.VERSION),
            batchId = batchId,
            senderId = json.optString(K_SENDER_ID),
            senderName = json.optString(K_SENDER_NAME).ifBlank { "Unknown device" },
            files = files,
            security = parseSecurityOffer(json.optJSONObject(K_SECURITY)),
        )
    }.getOrNull()

    fun HandshakeResponse.toJson(): String = JSONObject()
        .put(K_STATUS, decision.name.lowercase())
        .put("requiresVerification", requiresVerification)
        .put(K_MESSAGE, message ?: "")
        .apply { security?.let { put(K_SECURITY, it.toJsonObject()) } }
        .toString()

    fun parseHandshakeResponse(body: String): HandshakeResponse? = runCatching {
        val json = JSONObject(body)
        val decision = HandshakeResponse.Decision.entries
            .firstOrNull { it.name.equals(json.optString(K_STATUS), ignoreCase = true) }
            ?: HandshakeResponse.Decision.ERROR
        HandshakeResponse(
            decision = decision,
            message = json.optString(K_MESSAGE).takeIf { it.isNotBlank() },
            security = parseSecurityOffer(json.optJSONObject(K_SECURITY)),
            requiresVerification = json.optBoolean("requiresVerification", false),
        )
    }.getOrNull()

    fun UploadAck.toJson(): String = JSONObject()
        .put(K_ID, fileId)
        .put(K_BYTES, bytes)
        .put(K_DURATION, durationMillis)
        .apply {
            digest?.let { put(K_SHA256, it) }
            if (resumedBytes > 0L) put(K_RESUMED, resumedBytes)
        }
        .toString()

    fun parseUploadAck(body: String): UploadAck? = runCatching {
        val json = JSONObject(body)
        UploadAck(
            fileId = json.optString(K_ID),
            bytes = json.optLong(K_BYTES, 0L),
            durationMillis = json.optLong(K_DURATION, 0L),
            digest = json.optString(K_SHA256).takeIf { it.isNotBlank() },
            resumedBytes = json.optLong(K_RESUMED, 0L),
        )
    }.getOrNull()

    fun CompleteRequest.toJson(): String = JSONObject()
        .put(K_BATCH, batchId)
        .put(K_FILES, JSONArray().apply {
            files.forEach { file ->
                put(
                    JSONObject()
                        .put(K_ID, file.fileId)
                        .put(K_BYTES, file.bytes)
                        .put(K_SHA256, file.digest ?: ""),
                )
            }
        })
        .toString()

    /** Tolerates a v2 peer's empty completion body, which carries nothing but the batch id. */
    fun parseCompleteRequest(body: String, fallbackBatchId: String? = null): CompleteRequest? =
        runCatching {
            if (body.isBlank()) {
                return@runCatching fallbackBatchId?.let { CompleteRequest(it, emptyList()) }
            }
            val json = JSONObject(body)
            val batchId = json.optString(K_BATCH).ifBlank { fallbackBatchId.orEmpty() }
            if (batchId.isBlank()) return@runCatching null
            val array = json.optJSONArray(K_FILES)
            val files = mutableListOf<CompleteFile>()
            for (index in 0 until (array?.length() ?: 0)) {
                val entry = array?.optJSONObject(index) ?: continue
                val id = entry.optString(K_ID)
                if (id.isBlank()) continue
                files += CompleteFile(
                    fileId = id,
                    bytes = entry.optLong(K_BYTES, 0L),
                    digest = entry.optString(K_SHA256).takeIf { it.isNotBlank() },
                )
            }
            CompleteRequest(batchId = batchId, files = files)
        }.getOrNull()

    fun BatchStatus.toJson(): String = JSONObject()
        .put(K_BATCH, batchId)
        .put(K_BYTES, bytesReceived)
        .put(K_COMPLETED, JSONArray().apply { completedFileIds.forEach { put(it) } })
        .put("partials", JSONObject().apply {
            partialFiles.forEach { (id, part) ->
                put(id, JSONObject().put(K_BYTES, part.bytes).put(K_SHA256, part.digest))
            }
        })
        .toString()

    fun parseBatchStatus(body: String): BatchStatus? = runCatching {
        val json = JSONObject(body)
        val array = json.optJSONArray(K_COMPLETED)
        val completed = mutableListOf<String>()
        for (index in 0 until (array?.length() ?: 0)) {
            array?.optString(index)?.takeIf { it.isNotBlank() }?.let(completed::add)
        }
        BatchStatus(
            batchId = json.optString(K_BATCH),
            completedFileIds = completed,
            bytesReceived = json.optLong(K_BYTES, 0L),
            partialFiles = buildMap {
                val partials = json.optJSONObject("partials")
                partials?.keys()?.forEach { id ->
                    val part = partials.optJSONObject(id)
                    val bytes = part?.optLong(K_BYTES, -1) ?: -1
                    val digest = part?.optString(K_SHA256).orEmpty()
                    if (bytes > 0 && digest.matches(Regex("[a-fA-F0-9]{64}"))) {
                        put(id, PartialStatus(bytes, digest))
                    }
                }
            },
        )
    }.getOrNull()

    fun CompleteReport.toJson(): String = JSONObject()
        .put(K_LANDED, filesLanded)
        .put(K_WRITTEN, bytesWritten)
        .put(K_MISMATCH, JSONArray().apply { mismatchedFiles.forEach { put(it) } })
        .toString()

    fun parseCompleteReport(body: String): CompleteReport? = runCatching {
        val json = JSONObject(body)
        val array = json.optJSONArray(K_MISMATCH)
        val mismatched = mutableListOf<String>()
        for (index in 0 until (array?.length() ?: 0)) {
            array?.optString(index)?.takeIf { it.isNotBlank() }?.let(mismatched::add)
        }
        CompleteReport(
            filesLanded = json.optInt(K_LANDED, 0),
            bytesWritten = json.optLong(K_WRITTEN, 0L),
            mismatchedFiles = mismatched,
        )
    }.getOrNull()

    private fun ByteArray.base64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.decodeBase64(): ByteArray = Base64.decode(this, Base64.DEFAULT)
}
