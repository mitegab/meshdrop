package com.meshdrop.transfer.mesh

import com.meshdrop.core.persistence.AtomicJson
import com.meshdrop.transfer.crypto.DeviceIdentity
import com.meshdrop.transfer.crypto.IdentityVerifier
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/** One canonical encoding on Android and desktop; signatures bind the complete message, not just an ephemeral key. */
object SignedJson {
    fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        is Boolean -> value.toString()
        is Int, is Long -> value.toString()
        else -> error("Only integer JSON numbers are supported by the signed protocol")
    }
    fun bytes(value: JSONObject): ByteArray = ("meshdrop-signed-v1\n" + canonical(value)).toByteArray(Charsets.UTF_8)
    fun sign(value: JSONObject, identity: DeviceIdentity): JSONObject = JSONObject()
        .put("payload", value).put("publicKey", b64(identity.publicKeyBytes)).put("signature", b64(identity.sign(bytes(value))))
    fun verify(envelope: JSONObject, expectedKey: ByteArray? = null): Verified {
        val key = unb64(envelope.getString("publicKey"))
        require(key.size <= 256 && (expectedKey == null || key.contentEquals(expectedKey))) { "Unexpected signing identity" }
        val payload = envelope.getJSONObject("payload")
        require(IdentityVerifier.verify(key, bytes(payload), unb64(envelope.getString("signature")))) { "Invalid message signature" }
        return Verified(payload, key)
    }
    data class Verified(val payload: JSONObject, val publicKey: ByteArray) {
        val fingerprint get() = IdentityVerifier.fingerprintOf(publicKey)
        val fullFingerprint get() = AtomicJson.digest(publicKey)
    }
    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun unb64(value: String): ByteArray = Base64.getDecoder().decode(value)
}

data class MeshFile(val id: String, val name: String, val size: Long, val mime: String?, val digest: String, val chunks: List<String>)
data class MeshManifest(val id: String, val senderId: String, val senderName: String, val destination: String, val expires: Long, val files: List<MeshFile>) {
    val totalBytes get() = files.sumOf { it.size }
    val hash get() = com.meshdrop.core.persistence.AtomicJson.digest(SignedJson.bytes(toJson()))
    fun toJson(): JSONObject = JSONObject().put("version", 1).put("id", id).put("senderId", senderId)
        .put("senderName", senderName).put("destination", destination).put("expires", expires).put("chunkSize", CHUNK_BYTES)
        .put("files", JSONArray().apply { files.forEach { f -> put(JSONObject().put("id", f.id).put("name", f.name).put("size", f.size)
            .put("mime", f.mime ?: "").put("digest", f.digest).put("chunks", JSONArray(f.chunks))) } })
    companion object {
        const val CHUNK_BYTES = 1024 * 1024
        const val MAX_CHUNKS = 8192
        fun parse(json: JSONObject, now: Long = System.currentTimeMillis()): MeshManifest {
            require(json.getInt("version") == 1 && json.getInt("chunkSize") == CHUNK_BYTES)
            fun id(value: String): String = value.also { require(it.matches(Regex("[A-Za-z0-9_.-]{1,128}"))) }
            fun hash(value: String): String = value.also { require(it.matches(Regex("[a-f0-9]{64}"))) }
            val expires = json.getLong("expires")
            require(expires in now..(now + 24 * 60 * 60_000L)) { "Manifest expired" }
            val array = json.getJSONArray("files")
            require(array.length() in 1..1024)
            val files = (0 until array.length()).map { n -> array.getJSONObject(n).let { f ->
                val size = f.getLong("size"); require(size in 0..(CHUNK_BYTES.toLong() * MAX_CHUNKS))
                val chunks = f.getJSONArray("chunks")
                require(chunks.length().toLong() == (size + CHUNK_BYTES - 1) / CHUNK_BYTES)
                val name = f.getString("name"); require(name.isNotBlank() && name.length <= 255)
                MeshFile(id(f.getString("id")), name, size, f.optString("mime").ifBlank { null }, hash(f.getString("digest")),
                    (0 until chunks.length()).map { hash(chunks.getString(it)) })
            } }
            require(files.map { it.id }.distinct().size == files.size && files.sumOf { it.chunks.size } <= MAX_CHUNKS)
            return MeshManifest(id(json.getString("id")), id(json.getString("senderId")), json.getString("senderName").take(64),
                hash(json.getString("destination")), expires, files)
        }
    }
}
