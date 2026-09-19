package com.meshdrop.core.persistence

import com.meshdrop.core.model.*
import com.meshdrop.transfer.protocol.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress

/** File metadata only; session keys and acceptance decisions are deliberately NEVER persisted. */
object JournalCodec {
    fun files(files: List<TransferFile>): JSONArray = JSONArray().apply { files.forEach { f -> put(JSONObject()
        .put("id", f.id).put("name", f.name).put("size", f.sizeBytes).put("mime", f.mimeType ?: "")
        .put("uri", f.localUri ?: "").put("status", f.status.name)) } }
    fun files(json: JSONArray): List<TransferFile> {
        require(json.length() in 1..1024)
        return (0 until json.length()).map { index -> json.getJSONObject(index).let {
            TransferFile(it.getString("id"), it.getString("name"), it.getLong("size"),
                it.optString("mime").ifBlank { null }, localUri = it.optString("uri").ifBlank { null },
                status = TransferStatus.valueOf(it.optString("status", "WAITING")))
        } }.also { require(it.all { f -> f.sizeBytes >= 0 && f.id.matches(Regex("[A-Za-z0-9_.-]{1,128}")) }) }
    }
}

data class QueuedSnapshot(val id: String, val peer: PeerDevice, val files: List<TransferFile>, val routes: List<List<String>> = emptyList())

class SendJournal(directory: File) {
    private val store = AtomicJson(File(directory, "outgoing.json"))
    private val records = linkedMapOf<String, QueuedSnapshot>()
    init {
        runCatching {
            val array = store.read()?.getJSONArray("jobs") ?: return@runCatching
            require(array.length() <= 256)
            for (i in 0 until array.length()) {
                val json = array.getJSONObject(i)
                val peer = PeerDevice(json.getString("peerId"), json.getString("peerName"), DiscoveryKind.valueOf(json.getString("kind")),
                    host = json.optString("host").takeIf { it.isNotBlank() }?.let { InetAddress.getByName(it) },
                    port = json.optInt("port"), linkId = json.optString("link").ifBlank { null })
                val routes = json.optJSONArray("routes") ?: JSONArray()
                val routeList = (0 until routes.length()).map { r -> val route = routes.getJSONArray(r); (0 until route.length()).map { route.getString(it) } }
                val value = QueuedSnapshot(json.getString("id"), peer, JournalCodec.files(json.getJSONArray("files")), routeList)
                records[value.id] = value
            }
        }.onFailure { records.clear() } // Fail closed, never auto-send a corrupt queue.
    }
    @Synchronized fun all(): List<QueuedSnapshot> = records.values.toList()
    @Synchronized fun put(value: QueuedSnapshot) { require(records.size < 256 || records.containsKey(value.id)); records[value.id] = value; save() }
    @Synchronized fun remove(id: String) { records.remove(id); save() }
    private fun save() { store.write(JSONObject().put("version", 1).put("jobs", JSONArray().apply {
        records.values.forEach { v -> put(JSONObject().put("id", v.id).put("peerId", v.peer.id).put("peerName", v.peer.name)
            .put("kind", v.peer.kind.name).put("host", v.peer.host?.hostAddress ?: "").put("port", v.peer.port)
            .put("link", v.peer.linkId ?: "").put("files", JournalCodec.files(v.files))
            .put("routes", JSONArray().apply { v.routes.forEach { put(JSONArray(it)) } })) }
    })) }
}

/** Individual per-batch receiver journals are named by hashes, not by network-controlled paths. */
class ReceiveJournal(private val directory: File) {
    private fun store(id: String) = AtomicJson(File(directory, "batch-${AtomicJson.digest(id.toByteArray())}.json"))
    fun read(id: String): JSONObject? = store(id).read()?.takeIf {
        it.optInt("version") == 1 && it.optString("id") == id &&
            System.currentTimeMillis() - it.optLong("touched") in 0..RETENTION_MS
    }
    @Synchronized fun write(id: String, json: JSONObject) { store(id).write(json.put("version", 1).put("id", id)) }
    @Synchronized fun remove(id: String) = store(id).delete()
    companion object { const val RETENTION_MS = 24 * 60 * 60_000L }
}
