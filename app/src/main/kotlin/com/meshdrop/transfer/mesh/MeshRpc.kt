package com.meshdrop.transfer.mesh

import com.meshdrop.core.model.PeerDevice
import com.meshdrop.transfer.crypto.DeviceIdentity
import com.meshdrop.transfer.link.ConnectionFactory
import com.meshdrop.transfer.protocol.Http
import com.meshdrop.transfer.protocol.Http.readResponse
import com.meshdrop.transfer.protocol.Http.writeRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.util.UUID

/** Bounded request/response transport, shared by TCP, physical links, helper routes and the CLI. */
class MeshRpc(private val connections: ConnectionFactory = ConnectionFactory.TCP) : java.io.Closeable {
    private val active = java.util.concurrent.ConcurrentHashMap.newKeySet<com.meshdrop.transfer.link.DuplexConnection>()
    @Volatile private var closed = false
    fun abortActive() { active.forEach { runCatching { it.close() } }; active.clear() }
    override fun close() { closed = true; abortActive() }
    fun call(peer: PeerDevice, path: String, body: JSONObject = JSONObject(), authorize: () -> Boolean = { true }): JSONObject {
        check(!closed) { "Request cancelled" }
        val connection = connections.open(peer) { active.add(it); if (closed || !authorize()) { it.close(); error("Request cancelled") } }
        try { return connection.use {
            it.soTimeout = 125_000
            val bytes = body.toString().toByteArray()
            require(bytes.size <= MAX_BYTES) { "Mesh request exceeds its quota" }
            it.getOutputStream().writeRequest("POST", path, bytes)
            val response = BufferedInputStream(it.getInputStream()).readResponse(MAX_BYTES.toLong())
            check(response.isSuccessful) { "${peer.name}: ${response.bodyText().take(256)} (HTTP ${response.status})" }
            JSONObject(response.bodyText())
        } } finally { active.remove(connection) }
    }
    companion object {
        const val MAX_BYTES = 4 * 1024 * 1024
        const val PREFIX = "/meshdrop/mesh/"
        const val RELAY = "/meshdrop/relay/"
    }
}

/** Ordered, loop-free IDs; only the first hop's address is needed by the source. */
class RelayRoute(
    private val rpc: MeshRpc,
    private val identity: DeviceIdentity,
    private val first: PeerDevice,
    val nodes: List<String>, // helpers followed by the final destination
    private val grants: List<String>,
) {
    init { require(nodes.size in 2..4 && nodes.distinct().size == nodes.size && grants.size < nodes.size) }

    fun call(path: String, body: JSONObject, stage: Boolean = false): JSONObject {
        var request = JSONObject().put("path", path).put("body", body)
        var firstPacket: JSONObject? = null
        for (position in grants.indices.reversed()) {
            val packet = SignedJson.sign(JSONObject().put("id", UUID.randomUUID().toString()).put("created", System.currentTimeMillis())
                .put("grant", grants[position]).put("route", JSONArray(nodes)).put("position", position).put("request", request), identity)
            firstPacket = packet
            request = JSONObject().put("path", MeshRpc.RELAY + "forward").put("body", packet)
        }
        if (stage && firstPacket != null) {
            val reply = rpc.call(first, MeshRpc.RELAY + "stage", firstPacket)
            val stagedId = reply.getString("id")
            val command = SignedJson.sign(JSONObject().put("id", UUID.randomUUID().toString()).put("created", System.currentTimeMillis())
                .put("grant", grants.first()).put("staged", stagedId), identity)
            return rpc.call(first, MeshRpc.RELAY + "dispatch", command)
        }
        return rpc.call(first, request.getString("path"), request.getJSONObject("body"))
    }

    companion object {
        fun approve(rpc: MeshRpc, identity: DeviceIdentity, first: PeerDevice, nodes: List<String>, bytes: Long): RelayRoute {
            require(nodes.size in 2..4 && nodes.distinct().size == nodes.size)
            val grants = mutableListOf<String>()
            for (position in 0 until nodes.lastIndex) {
                val offer = SignedJson.sign(JSONObject().put("id", UUID.randomUUID().toString()).put("created", System.currentTimeMillis())
                    .put("route", JSONArray(nodes)).put("position", position).put("bytes", bytes)
                    .put("expires", System.currentTimeMillis() + 30 * 60_000L), identity)
                val response = if (position == 0) rpc.call(first, MeshRpc.RELAY + "offer", offer)
                    else RelayRoute(rpc, identity, first, nodes, grants.toList()).call(MeshRpc.RELAY + "offer", offer)
                grants += response.getString("grant")
            }
            return RelayRoute(rpc, identity, first, nodes, grants)
        }
    }
}
