package com.meshdrop.transfer.protocol

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

    /** Bump together with [com.meshdrop.discovery.nsd.MeshDropService.PROTOCOL_VERSION]. */
    const val VERSION = 1

    const val PATH_PING = "/meshdrop/ping"
    const val PATH_HANDSHAKE = "/meshdrop/handshake"
    const val PATH_UPLOAD = "/meshdrop/upload"
    const val PATH_COMPLETE = "/meshdrop/complete"

    const val HEADER_BATCH_ID = "X-MeshDrop-Batch"
    const val HEADER_FILE_ID = "X-MeshDrop-File"
    const val HEADER_FILE_NAME = "X-MeshDrop-Name"
    const val HEADER_SENDER_ID = "X-MeshDrop-Sender"
    const val QUERY_BATCH_ID = "batchId"

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
)

/** Reply to a handshake: the decision, plus an optional human readable reason. */
data class HandshakeResponse(
    val decision: Decision,
    val message: String? = null,
) {
    enum class Decision { ACCEPTED, REJECTED, TIMEOUT, ERROR }

    val accepted: Boolean get() = decision == Decision.ACCEPTED
}

data class UploadAck(
    val fileId: String,
    val bytes: Long,
    val durationMillis: Long,
)

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

    fun HandshakeRequest.toJson(): String = JSONObject()
        .put(K_VERSION, version)
        .put(K_BATCH, batchId)
        .put(K_SENDER_ID, senderId)
        .put(K_SENDER_NAME, senderName)
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
        if (batchId.isBlank()) return null
        val filesArray = json.optJSONArray(K_FILES) ?: return null
        val files = mutableListOf<FileMeta>()
        for (index in 0 until filesArray.length()) {
            val entry = filesArray.optJSONObject(index) ?: continue
            val id = entry.optString(K_ID)
            val name = entry.optString(K_NAME)
            if (id.isBlank() || name.isBlank()) continue
            files += FileMeta(
                id = id,
                name = name,
                size = entry.optLong(K_SIZE, 0L),
                mime = entry.optString(K_MIME).takeIf { it.isNotBlank() },
            )
        }
        if (files.isEmpty()) return null
        HandshakeRequest(
            version = json.optInt(K_VERSION, TransferProtocol.VERSION),
            batchId = batchId,
            senderId = json.optString(K_SENDER_ID),
            senderName = json.optString(K_SENDER_NAME).ifBlank { "Unknown device" },
            files = files,
        )
    }.getOrNull()

    fun HandshakeResponse.toJson(): String = JSONObject()
        .put(K_STATUS, decision.name.lowercase())
        .put(K_MESSAGE, message ?: "")
        .toString()

    fun parseHandshakeResponse(body: String): HandshakeResponse? = runCatching {
        val json = JSONObject(body)
        val decision = HandshakeResponse.Decision.entries
            .firstOrNull { it.name.equals(json.optString(K_STATUS), ignoreCase = true) }
            ?: HandshakeResponse.Decision.ERROR
        HandshakeResponse(decision, json.optString(K_MESSAGE).takeIf { it.isNotBlank() })
    }.getOrNull()

    fun UploadAck.toJson(): String = JSONObject()
        .put(K_ID, fileId)
        .put(K_BYTES, bytes)
        .put(K_DURATION, durationMillis)
        .toString()

    fun parseUploadAck(body: String): UploadAck? = runCatching {
        val json = JSONObject(body)
        UploadAck(
            fileId = json.optString(K_ID),
            bytes = json.optLong(K_BYTES, 0L),
            durationMillis = json.optLong(K_DURATION, 0L),
        )
    }.getOrNull()
}
