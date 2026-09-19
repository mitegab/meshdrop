package com.meshdrop.transfer.mesh

import com.meshdrop.core.model.*
import com.meshdrop.core.persistence.AtomicJson
import com.meshdrop.transfer.client.*
import com.meshdrop.transfer.crypto.*
import com.meshdrop.transfer.link.ConnectionFactory
import com.meshdrop.transfer.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Push-swarm scheduler: each helper stores its assigned encrypted chunk, then becomes its
 * delivery source. Only the distinct final destination can decrypt/reassemble it. Every
 * successful assignment requires the destination's signed receipt, not a helper's claim.
 */
class MeshSender(
    private val cache: File,
    private val source: PayloadSource,
    private val identity: DeviceIdentity,
    private val trusted: TrustedPeers,
    private val connections: ConnectionFactory,
    private val peers: () -> List<PeerDevice>,
) {
    private data class Assignment(val file: MeshFile, val index: Int, val route: Int, val bytes: Long)
    fun discard(id: String) { File(cache, AtomicJson.digest(id.toByteArray())).deleteRecursively() }

    suspend fun send(
        peer: PeerDevice, batchId: String, senderId: String, senderName: String, files: List<TransferFile>,
        helperRoutes: List<List<String>>, crypto: TransferCrypto,
        isCancelled: () -> Boolean = { false }, onSecurity: suspend () -> Unit = {},
        onProgress: (String, Long, Long, List<PeerContribution>) -> Unit = { _, _, _, _ -> },
    ): SendOutcome = coroutineScope {
        require(helperRoutes.size in 1..4)
        val root = File(cache, AtomicJson.digest(batchId.toByteArray())).apply { mkdirs() }
        val rpc = MeshRpc(connections)
        val watcher = launch { while (isActive) { delay(100); if (isCancelled()) { rpc.close(); return@launch } } }
        try {
            val estimated = files.sumOf { it.sizeBytes.coerceAtLeast(0) }
            val routes = helperRoutes.map { helpers ->
                require(helpers.isNotEmpty() && helpers.size <= 3 && peer.id !in helpers && senderId !in helpers)
                val first = peers().firstOrNull { it.id == helpers.first() && it.isReachable() } ?: error("Reconnect the first helper")
                // Includes framing, base64, offers, receipts and retried control traffic.
                RelayRoute.approve(rpc, identity, first, helpers + peer.id, maxOf(16 * 1024 * 1024L, estimated * 2 + 4 * 1024 * 1024L))
            }
            val remote = SignedJson.verify(routes.first().call(MeshRpc.PREFIX + "identity", JSONObject()))
            require(remote.payload.getString("deviceId") == peer.id) { "The route ended at another device" }
            check(trusted.isVerified(remote.fullFingerprint)) { "Pair with the final destination before using helpers (${remote.fingerprint})" }
            val manifestStore = AtomicJson(File(root, "manifest.json"))
            val saved = manifestStore.read()
            val manifest = if (saved != null) {
                MeshManifest.parse(saved).also {
                    require(it.destination == remote.fullFingerprint && it.senderId == senderId)
                    require(it.files.map { f -> f.id } == files.map { f -> f.id })
                    it.files.forEach { f ->
                        val cached = payload(root, f.id)
                        require(cached.isFile && cached.length() == f.size && cached.inputStream().use { stream ->
                            com.meshdrop.core.resume.PartialFile.digestPrefix(stream, cached.length()) { check(!isCancelled()) { "Cancelled" } }
                        } == f.digest) { "Cached source is missing or changed; pick the files again" }
                    }
                }
            } else {
                val prepared = files.map { f -> prepare(root, f, isCancelled) }
                require(prepared.sumOf { it.chunks.size } <= MeshManifest.MAX_CHUNKS) { "Mesh batch exceeds the chunk budget" }
                MeshManifest(batchId, senderId, senderName, remote.fullFingerprint, System.currentTimeMillis() + 24 * 60 * 60_000L, prepared)
                    .also { manifestStore.write(it.toJson()) }
            }
            val offer = JSONObject().put("manifest", SignedJson.sign(manifest.toJson(), identity))
                .put("security", with(ControlMessages) { requireNotNull(crypto.localMaterial()).toDto().toJsonObject() })
            val answer = SignedJson.verify(routes.first().call(MeshRpc.PREFIX + "offer", offer), remote.publicKey).payload
            require(answer.getString("manifest") == manifest.hash && answer.getBoolean("accepted"))
            val keys = crypto.establish(ControlMessages.parseSecurityOffer(answer.getJSONObject("security"))?.toOffer())
                ?: error("The destination did not establish encryption")
            require(keys.signatureValid && keys.peerFingerprint == remote.fingerprint)
            onSecurity()
            val token = answer.getString("token")
            fun control() = JSONObject().put("batchId", batchId).put("token", token)
            val status = SignedJson.verify(routes.first().call(MeshRpc.PREFIX + "status", control()), remote.publicKey).payload
            require(status.getString("manifest") == manifest.hash)
            val inventory = status.getJSONObject("files")
            val all = mutableListOf<Assignment>()
            var cursor = 0
            manifest.files.forEach { f -> f.chunks.indices.forEach { index -> all += Assignment(f, index, cursor++ % routes.size,
                minOf(MeshManifest.CHUNK_BYTES.toLong(), f.size - index.toLong() * MeshManifest.CHUNK_BYTES)) } }
            val delivered = ConcurrentHashMap<String, Long>()
            val helperBytes = LongArray(routes.size)
            val reportLock = Any()
            fun report(item: Assignment) = synchronized(reportLock) {
                delivered[item.file.id] = (delivered[item.file.id] ?: 0) + item.bytes
                helperBytes[item.route] += item.bytes
                onProgress(item.file.id, delivered[item.file.id] ?: 0, item.file.size, routes.indices.map { index ->
                    val id = helperRoutes[index].first()
                    PeerContribution(id, peers().firstOrNull { it.id == id }?.name ?: id, TransportLane.RELAY,
                        all.filter { it.route == index }.sumOf { it.bytes }, helperBytes[index], true)
                })
            }
            val missing = all.filter { item ->
                val fileStatus = inventory.getJSONObject(item.file.id)
                val received = fileStatus.getJSONArray("chunks")
                val present = fileStatus.getBoolean("complete") || (0 until received.length()).any { received.getInt(it) == item.index }
                if (present) report(item)
                !present
            }
            val limit = Semaphore(4)
            missing.map { item -> async(Dispatchers.IO) { limit.withPermit {
                check(!isCancelled()) { "Cancelled" }
                val plaintext = ByteArray(item.bytes.toInt())
                RandomAccessFile(payload(root, item.file.id), "r").use { it.seek(item.index.toLong() * MeshManifest.CHUNK_BYTES); it.readFully(plaintext) }
                require(AtomicJson.digest(plaintext) == item.file.chunks[item.index]) { "Source chunk changed" }
                val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.sendKey, "AES"), GCMParameterSpec(128, nonce))
                cipher.updateAAD(MeshReceiver.aad(manifest.hash, item.file.id, item.index))
                val packet = control().put("fileId", item.file.id).put("index", item.index)
                    .put("nonce", SignedJson.b64(nonce)).put("data", SignedJson.b64(cipher.doFinal(plaintext)))
                var accepted = false
                var failure: Throwable? = null
                // Reassign a failed helper to another approved route; chunk receipts are idempotent.
                for (attempt in routes.indices) {
                    check(!isCancelled()) { "Cancelled" }
                    val route = (item.route + attempt) % routes.size
                    try {
                        val ack = SignedJson.verify(routes[route].call(MeshRpc.PREFIX + "chunk", packet, stage = true), remote.publicKey).payload
                        require(ack.getString("manifest") == manifest.hash && ack.getString("fileId") == item.file.id &&
                            ack.getInt("index") == item.index && ack.getString("digest") == item.file.chunks[item.index] && ack.getBoolean("stored"))
                        report(item.copy(route = route)); accepted = true; break
                    } catch (error: Exception) { failure = error }
                }
                check(accepted) { "Every helper route failed: ${failure?.message}" }
            } } }.awaitAll()
            val completed = SignedJson.verify(routes.first().call(MeshRpc.PREFIX + "complete", control()), remote.publicKey).payload
            require(completed.getString("manifest") == manifest.hash && completed.getBoolean("complete"))
            val receipts = completed.getJSONObject("files")
            val outcomes = manifest.files.map { f ->
                require(receipts.getJSONObject(f.id).getString("digest") == f.digest)
                FileOutcome(f.id, true, f.size, null, f.digest)
            }
            discard(batchId)
            SendOutcome(true, null, outcomes, CompleteReport(outcomes.size, manifest.totalBytes))
        } finally { watcher.cancel(); rpc.close() }
    }

    private fun payload(root: File, id: String) = File(root, "${AtomicJson.digest(id.toByteArray())}.payload")
    private fun prepare(root: File, file: TransferFile, cancelled: () -> Boolean): MeshFile {
        val (input, _) = source.open(file) ?: error("Cannot reopen ${file.name}; select it again to grant access")
        val target = payload(root, file.id)
        val hash = MessageDigest.getInstance("SHA-256")
        val chunks = mutableListOf<String>()
        var size = 0L
        input.use { source -> FileOutputStream(target).use { output ->
            val block = ByteArray(MeshManifest.CHUNK_BYTES)
            while (true) {
                check(!cancelled()) { "Cancelled" }
                var count = 0
                while (count < block.size) { val n = source.read(block, count, block.size - count); if (n < 0) break; count += n }
                if (count == 0) break
                require(chunks.size < MeshManifest.MAX_CHUNKS) { "File exceeds mesh chunk budget" }
                output.write(block, 0, count); hash.update(block, 0, count); size += count
                chunks += AtomicJson.digest(block.copyOf(count))
            }
            output.fd.sync()
        } }
        return MeshFile(file.id, file.name, size, file.mimeType, hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }, chunks)
    }
}
