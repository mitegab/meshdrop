package com.meshdrop.transfer.mesh

import com.meshdrop.core.model.*
import com.meshdrop.core.persistence.AtomicJson
import com.meshdrop.data.IncomingFileStore
import com.meshdrop.transfer.crypto.*
import com.meshdrop.transfer.protocol.*
import com.meshdrop.transfer.server.IncomingTransferHandler
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Authenticated chunk destination. Helpers never receive the ECDH-derived content key. */
class MeshReceiver(
    private val directory: File,
    private val localId: String,
    private val localName: () -> String,
    private val identity: DeviceIdentity,
    private val files: IncomingFileStore,
    private val handler: IncomingTransferHandler,
) {
    private class Session(val manifest: MeshManifest, val owner: String, val root: File, val key: ByteArray, val token: String) {
        @Volatile var active = true
        val receipts = JSONObject()
        val verified = ConcurrentHashMap<String, MutableSet<Int>>()
        val state get() = AtomicJson(File(root, "receipts.json"))
    }
    private val sessions = ConcurrentHashMap<String, Session>()
    private val offering = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicBoolean>()
    init {
        directory.mkdirs()
        directory.listFiles().orEmpty().filter { it.isDirectory && System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60_000L }
            .forEach { it.deleteRecursively() }
    }
    fun identity(): JSONObject = SignedJson.sign(JSONObject().put("deviceId", localId).put("name", localName()).put("version", 1), identity)
    private fun root(id: String) = File(directory, AtomicJson.digest(id.toByteArray())).apply { mkdirs() }
    private fun chunk(session: Session, file: MeshFile, index: Int) = File(session.root, "${AtomicJson.digest(file.id.toByteArray())}-$index.chunk")
    private fun present(session: Session, file: MeshFile, index: Int): Boolean {
        val verified = session.verified.getOrPut(file.id) { mutableSetOf() }
        if (index in verified) return true
        val path = chunk(session, file, index)
        if (!path.isFile) return false
        val expectedSize = minOf(MeshManifest.CHUNK_BYTES.toLong(), file.size - index.toLong() * MeshManifest.CHUNK_BYTES)
        val valid = path.length() == expectedSize && path.inputStream().use { com.meshdrop.core.resume.PartialFile.digestPrefix(it, path.length()) { check(session.active) { "Session stopped" } } } == file.chunks[index]
        if (!valid) path.delete() else verified.add(index)
        return valid
    }
    private fun receipt(session: Session, value: JSONObject): JSONObject = SignedJson.sign(value.put("batchId", session.manifest.id)
        .put("manifest", session.manifest.hash).put("destination", AtomicJson.digest(identity.publicKeyBytes)), identity)

    suspend fun handle(operation: String, body: JSONObject): JSONObject {
        if (operation == "identity") return identity()
        if (operation == "offer") return offer(body)
        val session = sessions[body.getString("batchId")] ?: error("Re-offer the manifest after reconnecting")
        require(java.security.MessageDigest.isEqual(body.getString("token").toByteArray(), session.token.toByteArray())) { "Invalid session capability" }
        check(session.manifest.expires >= System.currentTimeMillis()) { "Manifest expired" }
        check(handler.awaitVerification(session.manifest.id)) { "Compare safety codes before delivery" }
        return synchronized(session) {
            check(session.active) { "Session cancelled or stopped" }
            when (operation) {
                "status" -> status(session)
                "chunk" -> acceptChunk(session, body)
                "complete" -> {
                    session.manifest.files.forEach { finishIfReady(session, it) }
                    check(session.manifest.files.all { session.receipts.has(it.id) }) { "Batch is incomplete" }
                    receipt(session, JSONObject().put("complete", true).put("files", session.receipts))
                }
                else -> error("Unknown mesh operation")
            }
        }
    }

    private suspend fun offer(body: JSONObject): JSONObject {
        val signed = SignedJson.verify(body.getJSONObject("manifest"))
        val manifest = MeshManifest.parse(signed.payload)
        require(manifest.destination == AtomicJson.digest(identity.publicKeyBytes)) { "This manifest belongs to another destination" }
        val cancelled = java.util.concurrent.atomic.AtomicBoolean()
        require(offering.putIfAbsent(manifest.id, cancelled) == null) { "An offer is already pending" }
        try {
            require(sessions.size < 32 || sessions.containsKey(manifest.id)) { "Too many mesh sessions" }
            val root = root(manifest.id)
            val durable = AtomicJson(File(root, "manifest.json"))
            durable.read()?.let { old ->
                val previous = SignedJson.verify(old)
                require(previous.fullFingerprint == signed.fullFingerprint && SignedJson.canonical(previous.payload) == SignedJson.canonical(signed.payload)) { "Manifest or owner changed" }
            }
            val material = ControlMessages.parseSecurityOffer(body.getJSONObject("security"))?.toOffer() ?: error("Encryption is required")
            require(material.identityPublicKey.contentEquals(signed.publicKey)) { "Offer has a different signing key" }
            val keys = handler.negotiateKeys(manifest.id, material) ?: error("Encryption must be enabled for mesh delivery")
            require(keys.signatureValid) { "Invalid ephemeral-key signature" }
            val request = IncomingRequest(manifest.id, manifest.senderId, manifest.senderName, "Authenticated helper delivery",
                manifest.files.map { TransferFile(it.id, it.name, it.size, it.mime) }, manifest.totalBytes)
            check(withTimeoutOrNull(120_000) { handler.requestApproval(request) } == true) { "Destination declined the transfer" }
            val session = Session(manifest, signed.fullFingerprint, root, keys.receiveKey,
                SignedJson.b64(ByteArray(32).also { SecureRandom().nextBytes(it) }))
            session.state.read()?.let { saved -> saved.keys().forEach { session.receipts.put(it, saved.get(it)) } }
            durable.write(body.getJSONObject("manifest"))
            sessions.put(manifest.id, session)?.active = false
            if (cancelled.get()) { sessions.remove(manifest.id, session); error("Offer cancelled or receiver stopped") }
            manifest.files.forEach { file ->
                if (session.receipts.has(file.id)) {
                    val saved = session.receipts.getJSONObject(file.id)
                    handler.onFileCompleted(manifest.id, file.id, saved.getString("uri"), saved.getString("name"))
                } else {
                    file.chunks.indices.forEach { present(session, file, it) }
                    finishIfReady(session, file)
                }
            }
            return receipt(session, JSONObject().put("token", session.token).put("accepted", true)
                .put("security", with(ControlMessages) { requireNotNull(handler.localMaterialFor(manifest.id)).toDto().toJsonObject() }))
        } finally { offering.remove(manifest.id, cancelled) }
    }

    private fun status(session: Session): JSONObject = receipt(session, JSONObject().put("files", JSONObject().apply {
        session.manifest.files.forEach { file ->
            put(file.id, JSONObject().put("complete", session.receipts.has(file.id)).put("chunks", JSONArray().apply {
                if (!session.receipts.has(file.id)) file.chunks.indices.filter { present(session, file, it) }.forEach { put(it) }
            }))
        }
    }))

    private fun acceptChunk(session: Session, body: JSONObject): JSONObject {
        val file = session.manifest.files.firstOrNull { it.id == body.getString("fileId") } ?: error("Unknown file")
        val index = body.getInt("index"); require(index in file.chunks.indices)
        if (!session.receipts.has(file.id) && !present(session, file, index)) {
            val nonce = SignedJson.unb64(body.getString("nonce")); require(nonce.size == 12)
            val encrypted = SignedJson.unb64(body.getString("data"))
            val size = minOf(MeshManifest.CHUNK_BYTES.toLong(), file.size - index.toLong() * MeshManifest.CHUNK_BYTES).toInt()
            require(encrypted.size == size + 16)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(session.key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad(session.manifest.hash, file.id, index))
            val plaintext = cipher.doFinal(encrypted)
            require(AtomicJson.digest(plaintext) == file.chunks[index]) { "Chunk does not match the authenticated manifest" }
            check(session.active) { "Session stopped" }
            val target = chunk(session, file, index)
            val temp = File.createTempFile("chunk-", ".tmp", session.root)
            try {
                FileOutputStream(temp).use { it.write(plaintext); it.fd.sync() }
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally { temp.delete() }
            session.verified.getOrPut(file.id) { mutableSetOf() }.add(index)
            handler.onFileStarted(session.manifest.id, file.id)
            val received = file.chunks.indices.filter { chunk(session, file, it).isFile }.sumOf {
                minOf(MeshManifest.CHUNK_BYTES.toLong(), file.size - it.toLong() * MeshManifest.CHUNK_BYTES)
            }
            handler.onFileProgress(session.manifest.id, file.id, received, file.size)
            finishIfReady(session, file)
        }
        return receipt(session, JSONObject().put("fileId", file.id).put("index", index).put("digest", file.chunks[index])
            .put("stored", true).put("complete", session.receipts.has(file.id)))
    }

    private fun finishIfReady(session: Session, file: MeshFile) {
        check(session.active) { "Session stopped" }
        if (session.receipts.has(file.id)) return
        if ((session.verified[file.id]?.size ?: 0) != file.chunks.size) return
        val sink = files.createSink(file.name, file.mime)
        try {
            val hash = java.security.MessageDigest.getInstance("SHA-256")
            val output = java.security.DigestOutputStream(sink.outputStream, hash)
            file.chunks.indices.forEach { index ->
                check(session.active) { "Session stopped" }
                chunk(session, file, index).inputStream().use { source -> source.copyTo(output) }
            }
            val digest = hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            require(digest == file.digest) { "Reassembled file failed its authenticated whole-file hash" }
            check(session.active) { "Session stopped" }
            val uri = sink.finish() ?: error("Could not publish reassembled file")
            session.receipts.put(file.id, JSONObject().put("uri", uri).put("name", sink.displayName).put("digest", digest).put("bytes", file.size))
            check(session.active) { "Session stopped after publication" }
            session.state.write(session.receipts)
            file.chunks.indices.forEach { chunk(session, file, it).delete() }
            handler.onFileCompleted(session.manifest.id, file.id, uri, sink.displayName)
        } catch (error: Exception) { sink.abort(); handler.onFileFailed(session.manifest.id, file.id, error.message ?: "Assembly failed"); throw error }
    }

    fun cancel(id: String) {
        offering[id]?.set(true)
        sessions.remove(id)?.active = false
        root(id).deleteRecursively()
    }
    fun stop() { offering.values.forEach { it.set(true) }; sessions.values.forEach { it.active = false }; sessions.clear() } // durable manifests/chunks remain; no key or approval is recovered
    companion object {
        fun aad(manifest: String, file: String, index: Int) = "meshdrop-chunk-v1\n$manifest\n$file\n$index".toByteArray(Charsets.UTF_8)
    }
}
