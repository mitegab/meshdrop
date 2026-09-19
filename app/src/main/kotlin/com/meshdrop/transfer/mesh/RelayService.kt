package com.meshdrop.transfer.mesh

import com.meshdrop.core.model.PeerDevice
import com.meshdrop.core.persistence.AtomicJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

data class RelayConsent(val id: String, val senderFingerprint: String, val nextPeer: String, val destinationId: String, val bytes: Long, val expires: Long)

/** Explicit per-route consent. No CONNECT, no URLs/ports from packets, no unrestricted proxy. */
class RelayService(
    private val localId: String,
    private val directory: File,
    private val peers: () -> List<PeerDevice>,
    private val rpc: MeshRpc,
    private val enabled: () -> Boolean,
    private val maxQuota: Long = 1024L * 1024 * 1024,
) {
    private data class Grant(val owner: String, val route: List<String>, val position: Int, val expires: Long, var remaining: Long)
    private data class Staged(val grant: String, val file: File, val hash: String)
    private val grants = ConcurrentHashMap<String, Grant>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val offers = MutableStateFlow<List<RelayConsent>>(emptyList())
    val incoming = offers.asStateFlow()
    private val staged = ConcurrentHashMap<String, Staged>()
    private val consumed = ConcurrentHashMap<String, Long>()
    private val slots = Semaphore(2)
    init { directory.mkdirs(); directory.listFiles()?.forEach { it.delete() } } // grants never survive process death

    fun decide(id: String, accept: Boolean) { pending[id]?.complete(accept) }
    fun revokeAll() { grants.clear(); pending.values.forEach { it.complete(false) }; staged.values.forEach { it.file.delete() }; staged.clear() }

    private fun verified(envelope: JSONObject): SignedJson.Verified {
        check(enabled()) { "Helper mode is off" }
        val verified = SignedJson.verify(envelope)
        val age = System.currentTimeMillis() - verified.payload.getLong("created")
        require(age in -30_000..300_000) { "Expired relay request" }
        val id = verified.payload.getString("id")
        require(id.matches(Regex("[A-Za-z0-9-]{1,128}")))
        return verified
    }
    private fun route(json: JSONObject): List<String> = json.getJSONArray("route").let { array ->
        require(array.length() in 2..4)
        (0 until array.length()).map { array.getString(it) }.also { list ->
            require(list.distinct().size == list.size && list.all { it.matches(Regex("[A-Za-z0-9_.-]{1,128}")) }) { "Routing loop or invalid node" }
        }
    }
    private fun peer(id: String): PeerDevice = peers().firstOrNull { it.id == id && it.isReachable() }
        ?: error("Approved next hop is no longer available")

    suspend fun handle(operation: String, envelope: JSONObject): JSONObject {
        val sender = verified(envelope)
        val body = sender.payload
        val now = System.currentTimeMillis()
        grants.entries.removeAll { it.value.expires < now }
        consumed.entries.removeAll { it.value < now - 30 * 60_000L }
        require(consumed.size < 32768) { "Relay request budget exhausted" }
        if (operation == "offer") {
            require(pending.size < 8 && grants.size < 16) { "Too many helper requests" }
            val nodes = route(body); val position = body.getInt("position")
            require(position in 0 until nodes.lastIndex && nodes[position] == localId)
            val next = peer(nodes[position + 1])
            val bytes = body.getLong("bytes"); require(bytes in 1..maxQuota) { "Requested relay quota is too large" }
            val expires = body.getLong("expires"); require(expires in now..(now + 30 * 60_000L))
            val id = body.getString("id"); val decision = CompletableDeferred<Boolean>()
            require(pending.putIfAbsent(id, decision) == null) { "Duplicate offer" }
            offers.update { it + RelayConsent(id, sender.fingerprint, next.name, nodes.last(), bytes, expires) }
            val accepted = try { withTimeoutOrNull(120_000) { decision.await() } == true }
                finally { pending.remove(id); offers.update { it.filterNot { offer -> offer.id == id } } }
            check(accepted && enabled()) { "Relay permission declined or expired" }
            val token = SignedJson.b64(ByteArray(32).also { SecureRandom().nextBytes(it) })
            grants[token] = Grant(sender.fullFingerprint, nodes, position, expires, bytes)
            return JSONObject().put("grant", token).put("expires", expires).put("bytes", bytes)
        }
        val token = body.getString("grant")
        val grant = grants[token] ?: error("Relay permission expired or revoked")
        require(grant.owner == sender.fullFingerprint) { "Permission belongs to another sender" }
        if (operation == "dispatch") {
            val item = staged.remove(body.getString("staged")) ?: error("No staged assignment")
            require(item.grant == token)
            try {
                require(item.file.length() <= MeshRpc.MAX_BYTES)
                val text = item.file.readText()
                require(AtomicJson.digest(text.toByteArray()) == item.hash) { "Staged assignment was modified" }
                return forward(grant, SignedJson.verify(JSONObject(text)).payload.getJSONObject("request"))
            } finally { item.file.delete() }
        }
        require(operation == "forward" || operation == "stage")
        val nodes = route(body)
        require(nodes == grant.route && body.getInt("position") == grant.position)
        val request = body.getJSONObject("request")
        validateNext(grant, request, sender.publicKey)
        val id = body.getString("id")
        require(consumed.putIfAbsent(id, now) == null) { "Replayed relay assignment" }
        val bytes = envelope.toString().toByteArray()
        synchronized(grant) {
            require(bytes.size <= grant.remaining) { "Relay byte quota exhausted" }
            grant.remaining -= bytes.size
        }
        if (operation == "stage") {
            require(staged.size < 16 && staged.values.sumOf { it.file.length() } + bytes.size <= 32 * 1024 * 1024L) { "Relay cache is full" }
            val file = File.createTempFile("assignment-", ".json", directory)
            file.outputStream().use { it.write(bytes) }
            staged[id] = Staged(token, file, AtomicJson.digest(bytes))
            return JSONObject().put("id", id).put("stored", bytes.size)
        }
        return forward(grant, request)
    }

    private fun validateNext(grant: Grant, request: JSONObject, owner: ByteArray) {
        val path = request.getString("path")
        if (grant.position == grant.route.lastIndex - 1) {
            require(path in setOf("identity", "offer", "status", "chunk", "complete").map { MeshRpc.PREFIX + it }) { "Not a destination MeshDrop RPC" }
        } else {
            require(path == MeshRpc.RELAY + "forward" || path == MeshRpc.RELAY + "offer") { "Not a permitted relay hop" }
            val inner = SignedJson.verify(request.getJSONObject("body"), owner).payload
            require(route(inner) == grant.route && inner.getInt("position") == grant.position + 1) { "Route was altered" }
        }
    }

    private suspend fun forward(grant: Grant, request: JSONObject): JSONObject = slots.withPermit {
        check(enabled() && grant.expires >= System.currentTimeMillis()) { "Relay permission revoked" }
        rpc.call(peer(grant.route[grant.position + 1]), request.getString("path"), request.getJSONObject("body"))
    }
}
