package com.meshdrop.cli

import com.meshdrop.core.model.*
import com.meshdrop.core.pairing.*
import com.meshdrop.core.persistence.*
import com.meshdrop.transfer.client.*
import com.meshdrop.transfer.crypto.*
import com.meshdrop.transfer.link.*
import com.meshdrop.transfer.mesh.*
import com.meshdrop.transfer.server.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess

private const val HELP = """MeshDrop — Android-compatible encrypted file transfer (JDK 17+)
  meshdrop identity [--home DIR] [--name NAME] [--port 45821]
  meshdrop trust FULL_SHA256_OR_PAIRING_CODE [--home DIR]
  meshdrop receive --out DIR [--port 45821] [--accept-verified]
  meshdrop send --host ADDRESS --port PORT [--pin FULL_SHA256] FILE...
  meshdrop retry BATCH_ID [--host ADDRESS --port PORT]
  meshdrop queue
  meshdrop forget BATCH_ID

Helpers: receive --helper --peer ID@ADDRESS:PORT [--peer ...]
  Each route still requires a fresh interactive yes; --accept-verified never approves relaying.
Swarm: send --destination ID --route HELPER_ID [--route ANOTHER_HELPER_ID]
  --peer HELPER_ID@ADDRESS:PORT --pin DESTINATION_FULL_SHA256 FILE...
Multi-hop: --route FIRST_ID,SECOND_ID,THIRD_ID (maximum three distinct helpers)
  Only the first hop needs to be in the sender's --peer directory. Each helper must know its next hop.
Native serial/RFCOMM endpoint: --link /dev/rfcomm0 (an OS-configured duplex character device).
  No TCP tunnel is used on this lane. Android must already be listening for RFCOMM.

State defaults to ~/.meshdrop. Keys, manifests and queues are private local files.
Interrupted sends stay in queue; retry is explicit and negotiates fresh keys/approval.
Compare codes interactively or use a FULL fingerprint obtained over a trusted channel.
"""

private class Arguments(args: Array<String>) {
    val values = mutableMapOf<String, MutableList<String>>()
    val positional = mutableListOf<String>()
    init {
        var i = 0
        while (i < args.size) {
            val item = args[i++]
            if (item.startsWith("--")) {
                val key = item.removePrefix("--")
                val value = if (key in setOf("helper", "accept-verified", "help")) "true" else args.getOrNull(i++) ?: error("Missing value for $item")
                values.getOrPut(key) { mutableListOf() }.add(value)
            } else positional += item
        }
    }
    fun one(key: String, default: String = "") = values[key]?.lastOrNull() ?: default
    fun all(key: String) = values[key].orEmpty()
    fun has(key: String) = values.containsKey(key)
}

private val promptLock = Any()
private fun approve(message: String): Boolean = synchronized(promptLock) {
    System.err.println("$message\nType yes to approve:")
    readlnOrNull()?.trim()?.equals("yes", true) == true
}

fun main(args: Array<String>) {
    try { runBlocking { run(args) } }
    catch (error: Exception) { System.err.println("MeshDrop: ${error.message}"); exitProcess(1) }
}

private suspend fun run(raw: Array<String>) = coroutineScope {
    if (raw.isEmpty() || raw[0] in setOf("help", "--help", "-h")) { println(HELP); return@coroutineScope }
    val command = raw[0]
    val args = Arguments(raw.drop(1).toTypedArray())
    val home = File(args.one("home", File(System.getProperty("user.home"), ".meshdrop").path)).apply {
        mkdirs(); setReadable(false, false); setWritable(false, false); setExecutable(false, false)
        setReadable(true, true); setWritable(true, true); setExecutable(true, true)
    }
    // One process owns an identity's queue/journal. Use --home for independent nodes.
    java.io.RandomAccessFile(File(home, "process.lock"), "rw").use { lockFile ->
        val lock = lockFile.channel.tryLock() ?: error("This state directory is already in use")
        try {
            val identity = FileIdentity(home)
            val trust = FileTrust(home)
            val name = args.one("name", "Desktop MeshDrop")
            val journal = SendJournal(home)
            val negotiator = SessionNegotiator(identity, trust)
            val source = PayloadSource { file -> runCatching { File(URI(requireNotNull(file.localUri))).let { it.inputStream() to it.length() } }.getOrNull() }
            val known = args.all("peer").map { value ->
                val id = value.substringBefore('@'); val endpoint = value.substringAfter('@')
                PeerDevice(id, id, DiscoveryKind.LAN_NSD, InetAddress.getByName(endpoint.substringBeforeLast(':').removeSurrounding("[", "]")), endpoint.substringAfterLast(':').toInt())
            }
            fun pin(value: String) {
                val info = PairingPayload.decode(value)
                val fingerprint = (info?.fingerprint ?: value).lowercase().replace(":", "")
                require(fingerprint.matches(Regex("[a-f0-9]{64}"))) { "Use the full SHA-256 fingerprint or a current pairing code" }
                trust.remember(fingerprint, info?.deviceName ?: "Pinned destination")
            }
            if (args.has("pin")) pin(args.one("pin"))
            when (command) {
                "identity" -> {
                    val full = AtomicJson.digest(identity.publicKeyBytes)
                    println(JSONObject().put("deviceId", identity.deviceId).put("fingerprint", full).put("short", identity.fingerprint)
                        .put("pairingCode", PairingPayload.encode(PairingInfo(identity.deviceId, name, args.one("port", "45821").toInt(), full,
                            com.meshdrop.transfer.protocol.TransferProtocol.VERSION))).toString(2))
                }
                "trust" -> { pin(args.positional.single()); println("Trusted identity saved") }
                "queue" -> journal.all().forEach { println("${it.id}\t${it.peer.name}\t${it.files.size} files\tRetry requires fresh approval") }
                "forget" -> { val id = args.positional.single(); journal.remove(id); File(home, "mesh-outgoing/${AtomicJson.digest(id.toByteArray())}").deleteRecursively() }
                "receive", "send", "retry" -> {
                    val handler = DesktopHandler(negotiator, trust, args.has("accept-verified"))
                    val store = DesktopFileStore(File(args.one("out", File(System.getProperty("user.home"), "Downloads/MeshDrop").path)), home)
                    val server = TransferServer(store, handler, this, { identity.deviceId }, { name })
                    var link: FramedLink? = null
                    var connections = ConnectionFactory.TCP
                    try {
                        if (args.has("link")) {
                            val path = args.one("link")
                            val input = java.io.FileInputStream(path); val output = java.io.FileOutputStream(path)
                            link = FramedLink(input, output, java.io.Closeable { input.close(); output.close() }, true,
                                { channel -> launch(Dispatchers.IO) { server.serve(channel) } })
                            connections = ConnectionFactory { _, register -> requireNotNull(link).open().also(register) }
                        }
                        val relay = RelayService(identity.deviceId, File(home, "relay-cache"), { known }, MeshRpc(connections), { args.has("helper") })
                        server.meshService = MeshService(MeshReceiver(File(home, "mesh-incoming"), identity.deviceId, { name }, identity, store, handler), relay)
                        if (command == "receive") {
                            val port = server.start(args.one("port", "45821").toInt()); check(port > 0)
                            println(JSONObject().put("deviceId", identity.deviceId).put("port", port).put("fingerprint", AtomicJson.digest(identity.publicKeyBytes)))
                            val consent = launch(Dispatchers.IO) { relay.incoming.collect { offers -> offers.forEach {
                                relay.decide(it.id, approve("RELAY: ${it.senderFingerprint} → ${it.nextPeer}; ${it.bytes} bytes until ${it.expires}. Allow this route?"))
                            } } }
                            try { awaitCancellation() } finally { consent.cancel() }
                        } else {
                            val saved = if (command == "retry") journal.all().firstOrNull { it.id == args.positional.single() } ?: error("Unknown queued batch") else null
                            val routes = args.all("route").map { it.split(',') }.ifEmpty { saved?.routes.orEmpty() }
                            var peer = saved?.peer ?: PeerDevice(args.one("destination", "pending"), args.one("destination", "Destination"),
                                if (link != null) DiscoveryKind.BLUETOOTH else DiscoveryKind.LAN_NSD,
                                args.one("host").ifBlank { null }?.let { InetAddress.getByName(it) }, args.one("port", "45821").toInt(), linkId = if (link != null) "native" else null)
                            if (args.has("host")) peer = peer.copy(host = InetAddress.getByName(args.one("host")), port = args.one("port", peer.port.toString()).toInt())
                            if (routes.isEmpty()) {
                                val remote = SignedJson.verify(MeshRpc(connections).call(peer, MeshRpc.PREFIX + "identity"))
                                peer = peer.copy(id = remote.payload.getString("deviceId"), name = remote.payload.getString("name"))
                            }
                            val files = saved?.files ?: args.positional.map { path ->
                                val file = File(path).canonicalFile; require(file.isFile) { "Not a readable file: $path" }
                                TransferFile(UUID.randomUUID().toString(), file.name, file.length(), localUri = file.toURI().toString())
                            }
                            require(files.isNotEmpty()) { "Select at least one file" }
                            val id = saved?.id ?: UUID.randomUUID().toString()
                            journal.put(QueuedSnapshot(id, peer, files, routes))
                            println("Batch $id")
                            val crypto = BatchCrypto(negotiator, EndpointRole.SENDER)
                            val verify: suspend () -> Unit = {
                                val keys = requireNotNull(crypto.sessionKeys()) { "Peer refused encryption" }
                                require(keys.signatureValid) { "Invalid peer signature" }
                                if (!keys.peerRemembered) {
                                    check(approve("Compare code ${keys.safetyCode} on ${peer.name}. Do both codes match?")) { "Verification declined" }
                                    trust.remember(requireNotNull(keys.peerIdentityFingerprint), peer.name)
                                }
                            }
                            val outcome = withContext(Dispatchers.IO) {
                                if (routes.isEmpty()) TransferClient(source, connections).send(peer, id, identity.deviceId, name, files,
                                    crypto = crypto, resume = saved != null, onHandshakeAccepted = verify)
                                else MeshSender(File(home, "mesh-outgoing"), source, identity, trust, connections, { known })
                                    .send(peer, id, identity.deviceId, name, files, routes, crypto, onSecurity = verify)
                            }
                            check(outcome.isSuccess) { outcome.message ?: outcome.integrityMessage ?: "Delivery not confirmed; use retry $id" }
                            journal.remove(id)
                            println("Delivered ${outcome.outcomes.sumOf { it.bytesSent }} bytes; destination confirmed integrity")
                        }
                    } finally { server.stop(); link?.close() }
                }
                else -> error("Unknown command. Run meshdrop help")
            }
        } finally { lock.release() }
    }
}

private class DesktopHandler(private val negotiator: SessionNegotiator, private val trust: TrustedPeers, private val auto: Boolean) : IncomingTransferHandler {
    private val batches = ConcurrentHashMap<String, BatchCrypto>()
    override fun negotiateKeys(batchId: String, peerMaterial: SecurityOffer?): SessionKeys? =
        BatchCrypto(negotiator, EndpointRole.RECEIVER).also { batches[batchId] = it }.establish(peerMaterial)
    override fun localMaterialFor(batchId: String) = batches[batchId]?.localMaterial()
    override fun sessionKeyFor(batchId: String) = batches[batchId]?.sessionKeys()?.receiveKey
    override val verificationRequired = true
    override suspend fun requestApproval(request: IncomingRequest): Boolean {
        val keys = batches[request.batchId]?.sessionKeys() ?: return false
        if (!keys.signatureValid) return false
        if (auto && keys.peerRemembered) return true
        val accepted = withContext(Dispatchers.IO) { approve("${request.senderName}: ${request.files.size} files, ${request.totalBytes} bytes.\nCompare code ${keys.safetyCode}. Do codes match, and may these files be received?") }
        if (accepted) trust.remember(requireNotNull(keys.peerIdentityFingerprint), request.senderName)
        return accepted
    }
    override suspend fun awaitVerification(batchId: String): Boolean = batches[batchId]?.sessionKeys()?.let {
        it.signatureValid && it.peerIdentityFingerprint?.let(trust::isVerified) == true
    } ?: false
    override fun onFileStarted(batchId: String, fileId: String) {}
    override fun onFileProgress(batchId: String, fileId: String, bytesReceived: Long, sizeBytes: Long) {}
    override fun onFileCompleted(batchId: String, fileId: String, uri: String?, landedName: String?) { println("Received: $landedName ($uri)") }
    override fun onFileFailed(batchId: String, fileId: String, reason: String) { System.err.println("Failed: $fileId: $reason") }
}
