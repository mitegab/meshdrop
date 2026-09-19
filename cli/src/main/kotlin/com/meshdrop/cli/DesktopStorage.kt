package com.meshdrop.cli

import com.meshdrop.core.persistence.AtomicJson
import com.meshdrop.core.resume.PartialFile
import com.meshdrop.data.*
import com.meshdrop.transfer.crypto.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.*
import java.security.spec.*
import java.util.UUID

class FileIdentity(home: File) : DeviceIdentity {
    private val file = File(home, "identity.json")
    private val store = AtomicJson(file)
    private val pair: KeyPair
    val deviceId: String
    init {
        val saved = store.read()
        check(saved != null || !file.exists()) { "Identity file is corrupt; restore a backup instead of silently changing keys" }
        if (saved == null) {
            pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            deviceId = UUID.randomUUID().toString()
            store.write(JSONObject().put("deviceId", deviceId).put("public", com.meshdrop.transfer.mesh.SignedJson.b64(pair.public.encoded))
                .put("private", com.meshdrop.transfer.mesh.SignedJson.b64(pair.private.encoded)))
        } else {
            val factory = KeyFactory.getInstance("EC")
            pair = KeyPair(factory.generatePublic(X509EncodedKeySpec(com.meshdrop.transfer.mesh.SignedJson.unb64(saved.getString("public")))),
                factory.generatePrivate(PKCS8EncodedKeySpec(com.meshdrop.transfer.mesh.SignedJson.unb64(saved.getString("private")))))
            deviceId = saved.getString("deviceId")
        }
    }
    override val publicKeyBytes: ByteArray get() = pair.public.encoded
    override val fingerprint get() = IdentityVerifier.fingerprintOf(publicKeyBytes)
    override fun sign(data: ByteArray) = Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(data); sign() }
}

class FileTrust(home: File) : TrustedPeers {
    private val store = AtomicJson(File(home, "trusted.json"))
    private val values = store.read() ?: JSONObject()
    @Synchronized override fun isVerified(fingerprint: String) = values.has(fingerprint)
    @Synchronized override fun remember(fingerprint: String, label: String) { values.put(fingerprint, label); store.write(values) }
    @Synchronized override fun forget(fingerprint: String) { values.remove(fingerprint); store.write(values) }
}

class DesktopFileStore(private val output: File, home: File) : IncomingFileStore {
    override val partialDirectory = File(home, "incoming").apply { mkdirs() }
    override fun createPartial() = PartialFile(partialDirectory)
    override fun createSink(requestedName: String, mimeType: String?): ReceivedFileSink {
        output.mkdirs()
        // A peer's name never becomes a path, including Windows drive prefixes and devices.
        val clean = requestedName.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.').take(160).ifBlank { "file" }
        val safe = if (clean.substringBefore('.').uppercase() in setOf("CON", "PRN", "AUX", "NUL", "COM1", "COM2", "LPT1", "LPT2")) "_$clean" else clean
        val temp = File.createTempFile(".meshdrop-", ".tmp", output)
        val stream = FileOutputStream(temp)
        return object : ReceivedFileSink {
            private var landed: File? = null
            override val outputStream = stream
            override val displayName get() = landed?.name ?: safe
            override fun finish(): String {
                landed?.let { return it.toURI().toString() }
                stream.fd.sync(); stream.close()
                var index = 0
                while (true) {
                    val target = File(output, if (index == 0) safe else "${index}_$safe")
                    try {
                        // No overwrite: CREATE_NEW is atomic even when several transfers finish together.
                        Files.createFile(target.toPath())
                        try { Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                        catch (error: Exception) { target.delete(); throw error }
                        landed = target
                        return target.toURI().toString()
                    } catch (_: java.nio.file.FileAlreadyExistsException) { index++ }
                }
            }
            override fun abort() { runCatching { stream.close() }; temp.delete() }
        }
    }
}
