package com.meshdrop.transfer.mesh

import com.meshdrop.core.model.*
import com.meshdrop.core.persistence.AtomicJson
import com.meshdrop.core.resume.PartialFile
import com.meshdrop.data.*
import com.meshdrop.transfer.client.PayloadSource
import com.meshdrop.transfer.crypto.*
import com.meshdrop.transfer.link.ConnectionFactory
import com.meshdrop.transfer.server.*
import com.meshdrop.transfer.protocol.ControlMessages
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.*
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class MeshTransferTest {
    @get:Rule val temp = TemporaryFolder()
    private class Node(val id: String, val root: File, val identity: DeviceIdentity = InMemoryIdentity()) : Closeable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val received = ConcurrentHashMap<String, ByteArray>()
        val keys = ConcurrentHashMap<String, BatchCrypto>()
        val approvals = AtomicInteger()
        val consents = AtomicInteger()
        var accept = true
        var helper = false
        var known = emptyList<PeerDevice>()
        val store = object : IncomingFileStore {
            override val partialDirectory = File(root, "parts").apply { mkdirs() }
            override fun createPartial() = PartialFile(partialDirectory)
            override fun createSink(requestedName: String, mimeType: String?) = object : ReceivedFileSink {
                override val outputStream = ByteArrayOutputStream()
                override val displayName = requestedName
                override fun finish(): String { received[requestedName] = outputStream.toByteArray(); return "test://$requestedName" }
                override fun abort() {}
            }
        }
        val handler = object : IncomingTransferHandler {
            override suspend fun requestApproval(request: IncomingRequest): Boolean { approvals.incrementAndGet(); return accept }
            override fun negotiateKeys(batchId: String, peerMaterial: SecurityOffer?): SessionKeys? =
                BatchCrypto(SessionNegotiator(identity, InMemoryTrustedPeers()), EndpointRole.RECEIVER).also { keys[batchId] = it }.establish(peerMaterial)
            override fun localMaterialFor(batchId: String) = keys[batchId]?.localMaterial()
            override fun sessionKeyFor(batchId: String) = keys[batchId]?.sessionKeys()?.receiveKey
            override fun onFileStarted(batchId: String, fileId: String) {}
            override fun onFileProgress(batchId: String, fileId: String, bytesReceived: Long, sizeBytes: Long) {}
            override fun onFileCompleted(batchId: String, fileId: String, uri: String?, landedName: String?) {}
            override fun onFileFailed(batchId: String, fileId: String, reason: String) {}
        }
        val server = TransferServer(store, handler, scope, { id }, { id })
        val relay = RelayService(id, File(root, "relay"), { known }, MeshRpc(), { helper })
        val receiver = MeshReceiver(File(root, "mesh"), id, { id }, identity, store, handler)
        init {
            server.meshService = MeshService(receiver, relay)
            check(server.start() > 0)
            scope.launch { relay.incoming.collect { offers -> offers.forEach { consents.incrementAndGet(); relay.decide(it.id, accept) } } }
        }
        val peer get() = PeerDevice(id, id, DiscoveryKind.LAN_NSD, InetAddress.getLoopbackAddress(), server.port)
        override fun close() { server.stop(); scope.cancel() }
    }
    private fun node(id: String, identity: DeviceIdentity = InMemoryIdentity()) = Node(id, temp.newFolder(id), identity)
    private fun trust(identity: DeviceIdentity) = InMemoryTrustedPeers().apply { remember(AtomicJson.digest(identity.publicKeyBytes), "Destination") }
    private fun manifest(id: String, destination: DeviceIdentity, bytes: ByteArray) = MeshManifest(id, "sender", "Sender",
        AtomicJson.digest(destination.publicKeyBytes), System.currentTimeMillis() + 60_000,
        listOf(MeshFile("file", "file.bin", bytes.size.toLong(), null, AtomicJson.digest(bytes),
            bytes.asList().chunked(MeshManifest.CHUNK_BYTES).map { AtomicJson.digest(it.toByteArray()) })))
    private fun offer(node: Node, manifest: MeshManifest, identity: DeviceIdentity, crypto: BatchCrypto): JSONObject {
        val response = MeshRpc().call(node.peer, MeshRpc.PREFIX + "offer", JSONObject()
            .put("manifest", SignedJson.sign(manifest.toJson(), identity))
            .put("security", with(ControlMessages) { crypto.localMaterial()!!.toDto().toJsonObject() }))
        val payload = SignedJson.verify(response, node.identity.publicKeyBytes).payload
        crypto.establish(ControlMessages.parseSecurityOffer(payload.getJSONObject("security"))!!.toOffer())
        return payload
    }
    private fun chunk(manifest: MeshManifest, answer: JSONObject, keys: SessionKeys, index: Int, bytes: ByteArray): JSONObject {
        val nonce = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.sendKey, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(MeshReceiver.aad(manifest.hash, "file", index))
        return JSONObject().put("batchId", manifest.id).put("token", answer.getString("token")).put("fileId", "file").put("index", index)
            .put("nonce", SignedJson.b64(nonce)).put("data", SignedJson.b64(cipher.doFinal(bytes)))
    }

    @Test fun `canonical signature rejects changed manifest and identity substitution`() {
        val identity = InMemoryIdentity()
        val signed = SignedJson.sign(JSONObject().put("b", 2).put("a", 1), identity)
        assertEquals("{\"a\":1,\"b\":2}", SignedJson.canonical(signed.getJSONObject("payload")))
        SignedJson.verify(signed, identity.publicKeyBytes)
        signed.getJSONObject("payload").put("a", 3)
        try { SignedJson.verify(signed); fail("Accepted tampered manifest") } catch (_: IllegalArgumentException) {}
        val valid = SignedJson.sign(JSONObject().put("a", 1), identity)
        try { SignedJson.verify(valid, InMemoryIdentity().publicKeyBytes); fail("Accepted different signer") } catch (_: IllegalArgumentException) {}
    }

    @Test(timeout = 45000) fun `two helper sources deliver disjoint authenticated chunks to a separate destination`() = runBlocking {
        node("destination").use { destination -> node("a").use { a -> node("b").use { b ->
            a.helper = true; b.helper = true; a.known = listOf(destination.peer); b.known = listOf(destination.peer)
            val identity = InMemoryIdentity(); val trusted = trust(destination.identity)
            val bytes = ByteArray(MeshManifest.CHUNK_BYTES * 2 + 97) { (it % 251).toByte() }
            val source = PayloadSource { file -> if (file.id == "empty") ByteArray(0).inputStream() to 0L else bytes.inputStream() to bytes.size.toLong() }
            val connections = ConnectionFactory { peer, register ->
                check(peer.id in setOf("a", "b")) { "Source attempted to bypass helpers" }
                ConnectionFactory.TCP.open(peer, register)
            }
            val sender = MeshSender(temp.newFolder("out"), source, identity, trusted, connections, { listOf(a.peer, b.peer) })
            var contributions = emptyList<PeerContribution>()
            val outcome = withContext(Dispatchers.IO) { sender.send(destination.peer.copy(host = null, port = 0), "swarm", "sender", "Sender",
                listOf(TransferFile("file", "file.bin", bytes.size.toLong()), TransferFile("empty", "empty.bin", 0)), listOf(listOf("a"), listOf("b")),
                BatchCrypto(SessionNegotiator(identity, trusted), EndpointRole.SENDER),
                onProgress = { _, _, _, peers -> contributions = peers }) }
            assertTrue(outcome.isSuccess)
            assertArrayEquals(bytes, destination.received["file.bin"])
            assertArrayEquals(ByteArray(0), destination.received["empty.bin"])
            assertEquals(2, contributions.count { it.bytesDelivered > 0 })
            assertEquals(1, a.consents.get()); assertEquals(1, b.consents.get())
            assertTrue(a.received.isEmpty()); assertTrue(b.received.isEmpty())
            assertTrue(File(a.root, "relay").listFiles().orEmpty().isEmpty())
        } } }
    }

    @Test(timeout = 45000) fun `two hop relay only needs the first address at source and consent at each hop`() = runBlocking {
        node("destination").use { destination -> node("a").use { a -> node("b").use { b ->
            a.helper = true; b.helper = true; a.known = listOf(b.peer); b.known = listOf(destination.peer)
            val identity = InMemoryIdentity(); val trusted = trust(destination.identity)
            val bytes = "end-to-end, not a TCP proxy".toByteArray()
            val sender = MeshSender(temp.newFolder("out"), PayloadSource { bytes.inputStream() to bytes.size.toLong() }, identity, trusted,
                ConnectionFactory.TCP, { listOf(a.peer) })
            val result = withContext(Dispatchers.IO) { sender.send(destination.peer.copy(host = null, port = 0), "relay", "sender", "Sender",
                listOf(TransferFile("file", "file.bin", bytes.size.toLong())), listOf(listOf("absent"), listOf("a", "b")),
                BatchCrypto(SessionNegotiator(identity, trusted), EndpointRole.SENDER)) }
            assertTrue(result.isSuccess)
            assertArrayEquals(bytes, destination.received["file.bin"])
            assertEquals(1, a.consents.get()); assertEquals(1, b.consents.get())
        } } }
    }

    @Test(timeout = 30000) fun `process restart requires a new offer and recovers verified chunks with fresh keys`() {
        val receiverIdentity = InMemoryIdentity(); val senderIdentity = InMemoryIdentity()
        val root = temp.newFolder("durable")
        val bytes = ByteArray(MeshManifest.CHUNK_BYTES + 19) { (it % 251).toByte() }
        val manifest = manifest("durable", receiverIdentity, bytes)
        val negotiator = SessionNegotiator(senderIdentity, trust(receiverIdentity))
        lateinit var oldPacket: JSONObject
        lateinit var oldKey: ByteArray
        Node("destination", root, receiverIdentity).use { receiver ->
            val crypto = BatchCrypto(negotiator, EndpointRole.SENDER)
            val answer = offer(receiver, manifest, senderIdentity, crypto)
            oldKey = crypto.sessionKeys()!!.sendKey
            oldPacket = chunk(manifest, answer, crypto.sessionKeys()!!, 0, bytes.copyOf(MeshManifest.CHUNK_BYTES))
            MeshRpc().call(receiver.peer, MeshRpc.PREFIX + "chunk", oldPacket)
            assertTrue(receiver.received.isEmpty())
        }
        Node("destination", root, receiverIdentity).use { receiver ->
            try { MeshRpc().call(receiver.peer, MeshRpc.PREFIX + "chunk", oldPacket); fail("Approval survived process death") } catch (_: IllegalStateException) {}
            val crypto = BatchCrypto(negotiator, EndpointRole.SENDER)
            val answer = offer(receiver, manifest, senderIdentity, crypto)
            assertFalse(oldKey.contentEquals(crypto.sessionKeys()!!.sendKey))
            val control = JSONObject().put("batchId", manifest.id).put("token", answer.getString("token"))
            val status = SignedJson.verify(MeshRpc().call(receiver.peer, MeshRpc.PREFIX + "status", control)).payload
            assertEquals(0, status.getJSONObject("files").getJSONObject("file").getJSONArray("chunks").getInt(0))
            MeshRpc().call(receiver.peer, MeshRpc.PREFIX + "chunk", chunk(manifest, answer, crypto.sessionKeys()!!, 1, bytes.copyOfRange(MeshManifest.CHUNK_BYTES, bytes.size)))
            val receipt = SignedJson.verify(MeshRpc().call(receiver.peer, MeshRpc.PREFIX + "complete", control), receiverIdentity.publicKeyBytes)
            assertTrue(receipt.payload.getBoolean("complete"))
            assertEquals(1, receiver.approvals.get())
            assertArrayEquals(bytes, receiver.received["file.bin"])
        }
    }

    @Test(timeout = 15000) fun `tampered chunks and wrong chunk context never publish`() {
        node("destination").use { receiver ->
            val sender = InMemoryIdentity(); val bytes = ByteArray(MeshManifest.CHUNK_BYTES * 2) { (it % 251).toByte() }
            val manifest = manifest("aad", receiver.identity, bytes)
            val crypto = BatchCrypto(SessionNegotiator(sender, trust(receiver.identity)), EndpointRole.SENDER)
            val answer = offer(receiver, manifest, sender, crypto)
            val packet = chunk(manifest, answer, crypto.sessionKeys()!!, 0, bytes.copyOf(MeshManifest.CHUNK_BYTES))
            packet.put("index", 1)
            try { MeshRpc().call(receiver.peer, MeshRpc.PREFIX + "chunk", packet); fail("Accepted reordered chunk") } catch (_: IllegalStateException) {}
            assertTrue(receiver.received.isEmpty())
        }
    }

    @Test(timeout = 15000) fun `disabled helper and routing loops fail before consent`() = runBlocking {
        node("helper").use { helper -> node("destination").use { destination ->
            helper.known = listOf(destination.peer)
            try { withContext(Dispatchers.IO) { RelayRoute.approve(MeshRpc(), InMemoryIdentity(), helper.peer, listOf("helper", "destination"), 16_000_000) }; fail("Helper mode is off") }
            catch (_: IllegalStateException) {}
            helper.helper = true
            try { RelayRoute.approve(MeshRpc(), InMemoryIdentity(), helper.peer, listOf("helper", "helper", "destination"), 16_000_000); fail("Loop accepted") }
            catch (_: IllegalArgumentException) {}
            assertEquals(0, helper.consents.get())
        } }
    }
    @Test(timeout = 15000) fun `relay grants reject stolen tokens arbitrary endpoints replay and revocation`() {
        node("helper").use { helper -> node("destination").use { destination ->
            helper.helper = true; helper.known = listOf(destination.peer)
            val sender = InMemoryIdentity()
            fun signed(payload: JSONObject, signer: DeviceIdentity = sender) = SignedJson.sign(payload
                .put("id", java.util.UUID.randomUUID().toString()).put("created", System.currentTimeMillis()), signer)
            val route = org.json.JSONArray(listOf("helper", "destination"))
            val grant = MeshRpc().call(helper.peer, MeshRpc.RELAY + "offer", signed(JSONObject().put("route", route).put("position", 0)
                .put("bytes", 16_000_000).put("expires", System.currentTimeMillis() + 60_000))).getString("grant")
            fun packet(path: String, signer: DeviceIdentity = sender) = signed(JSONObject().put("grant", grant).put("route", route)
                .put("position", 0).put("request", JSONObject().put("path", path).put("body", JSONObject())), signer)
            try { MeshRpc().call(helper.peer, MeshRpc.RELAY + "forward", packet("/arbitrary/tcp/proxy")); fail("Arbitrary endpoint was forwarded") }
            catch (_: IllegalStateException) {}
            try { MeshRpc().call(helper.peer, MeshRpc.RELAY + "forward", packet(MeshRpc.PREFIX + "identity", InMemoryIdentity())); fail("Stolen grant worked") }
            catch (_: IllegalStateException) {}
            val valid = packet(MeshRpc.PREFIX + "identity")
            val reply = MeshRpc().call(helper.peer, MeshRpc.RELAY + "forward", valid)
            assertEquals("destination", SignedJson.verify(reply, destination.identity.publicKeyBytes).payload.getString("deviceId"))
            try { MeshRpc().call(helper.peer, MeshRpc.RELAY + "forward", valid); fail("Replayed packet worked") }
            catch (_: IllegalStateException) {}
            helper.relay.revokeAll()
            try { MeshRpc().call(helper.peer, MeshRpc.RELAY + "forward", packet(MeshRpc.PREFIX + "identity")); fail("Revoked grant worked") }
            catch (_: IllegalStateException) {}
        } }
    }

    @Test(timeout = 15000) fun `relay enforces a consented byte budget`() {
        node("helper").use { helper -> node("destination").use { destination ->
            helper.helper = true; helper.known = listOf(destination.peer)
            val sender = InMemoryIdentity()
            val route = org.json.JSONArray(listOf("helper", "destination"))
            fun signed(body: JSONObject) = SignedJson.sign(body.put("id", java.util.UUID.randomUUID().toString())
                .put("created", System.currentTimeMillis()), sender)
            val grant = MeshRpc().call(helper.peer, MeshRpc.RELAY + "offer", signed(JSONObject().put("route", route).put("position", 0)
                .put("bytes", 1).put("expires", System.currentTimeMillis() + 60_000))).getString("grant")
            val request = signed(JSONObject().put("grant", grant).put("route", route).put("position", 0)
                .put("request", JSONObject().put("path", MeshRpc.PREFIX + "identity").put("body", JSONObject())))
            try { MeshRpc().call(helper.peer, MeshRpc.RELAY + "forward", request); fail("Exceeded granted bytes") }
            catch (_: IllegalStateException) {}
        } }
    }

    @Test(timeout = 45000) fun `failed chunk assignments and completion use another approved helper`() = runBlocking {
        node("destination").use { destination -> node("a").use { a -> node("b").use { b ->
            a.helper = true; b.helper = true; a.known = listOf(destination.peer); b.known = listOf(destination.peer)
            val senderIdentity = InMemoryIdentity(); val trusted = trust(destination.identity)
            val bytes = ByteArray(MeshManifest.CHUNK_BYTES + 11) { (it % 251).toByte() }
            val callsToA = AtomicInteger()
            val connections = ConnectionFactory { peer, register ->
                // A approved its grant and carried identity/offer/status, then went offline.
                if (peer.id == "a" && callsToA.incrementAndGet() > 4) throw IOException("Helper disconnected")
                ConnectionFactory.TCP.open(peer, register)
            }
            val sender = MeshSender(temp.newFolder("out"), PayloadSource { bytes.inputStream() to bytes.size.toLong() },
                senderIdentity, trusted, connections, { listOf(a.peer, b.peer) })
            val result = withContext(Dispatchers.IO) { sender.send(destination.peer.copy(host = null, port = 0), "failover", "sender", "Sender",
                listOf(TransferFile("file", "file.bin", bytes.size.toLong())), listOf(listOf("a"), listOf("b")),
                BatchCrypto(SessionNegotiator(senderIdentity, trusted), EndpointRole.SENDER)) }
            assertTrue(result.isSuccess)
            assertArrayEquals(bytes, destination.received["file.bin"])
            assertEquals(1, a.consents.get()); assertEquals(1, b.consents.get())
        } } }
    }

}
