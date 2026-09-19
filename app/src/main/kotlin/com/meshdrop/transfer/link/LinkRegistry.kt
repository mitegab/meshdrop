package com.meshdrop.transfer.link

import com.meshdrop.core.model.*
import com.meshdrop.discovery.api.*
import com.meshdrop.transfer.protocol.*
import com.meshdrop.transfer.protocol.Http.readResponse
import com.meshdrop.transfer.protocol.Http.writeRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.io.*
import java.util.concurrent.ConcurrentHashMap

/** Live physical links. A ping on the link discovers the remote identity without any IP bootstrap. */
class LinkRegistry(private val scope: CoroutineScope, private val receive: suspend (DuplexConnection) -> Unit) : ConnectionFactory {
    private val links = ConcurrentHashMap<String, FramedLink>()
    private val devices = MutableStateFlow<List<PeerDevice>>(emptyList())
    val peers = devices.asStateFlow()
    private val messages = MutableStateFlow<Map<DiscoveryKind, StrategyStatus>>(emptyMap())
    val status = messages.asStateFlow()

    fun status(kind: DiscoveryKind, note: String, active: Boolean = false) {
        messages.update { it + (kind to StrategyStatus(advertising = active, scanning = active, note = note)) }
    }

    @Volatile private var accepting = true
    @Synchronized fun start() { accepting = true }

    @Synchronized fun attach(id: String, kind: DiscoveryKind, input: InputStream, output: OutputStream, closeable: Closeable, initiator: Boolean) {
        if (!accepting) { closeable.close(); return }
        detach(id)
        lateinit var link: FramedLink
        link = FramedLink(input, output, closeable, initiator, { connection ->
            scope.launch(Dispatchers.IO) { receive(connection) }
        }, {
            synchronized(this) {
                if (links.remove(id, link)) {
                    devices.update { it.filterNot { p -> p.linkId == id } }
                    status(kind, "Link disconnected")
                }
            }
        })
        links[id] = link
        scope.launch(Dispatchers.IO) {
            runCatching {
                while (isActive && links[id] === link) {
                    val probe = try { link.open() } catch (_: FramedLink.BusyException) { delay(500); continue }
                    val peer = probe.use {
                        it.getOutputStream().writeRequest("GET", TransferProtocol.PATH_PING)
                        val reply = BufferedInputStream(it.getInputStream()).readResponse()
                        require(reply.isSuccessful)
                        val json = JSONObject(reply.bodyText())
                        PeerDevice(json.getString("deviceId"), json.getString("name"), kind, linkId = id)
                    }
                    devices.update { it.filterNot { p -> p.linkId == id } + peer }
                    status(kind, "Connected without IP", true)
                    delay(10_000)
                }
            }.onFailure { if (links.remove(id, link)) { link.close(); status(kind, it.message ?: "Link disconnected") } }
        }
    }

    @Synchronized fun detach(id: String) { links.remove(id)?.close(); devices.update { it.filterNot { p -> p.linkId == id } } }
    @Synchronized fun close() { accepting = false; links.keys.toList().forEach(::detach) }
    override fun open(peer: PeerDevice, register: (DuplexConnection) -> Unit): DuplexConnection {
        val id = peer.linkId ?: return ConnectionFactory.TCP.open(peer, register)
        val channel = (links[id] ?: error("Reconnect ${peer.name} before retrying")).open()
        try { register(channel); return channel } catch (error: Throwable) { channel.close(); throw error }
    }

    fun strategy(kind: DiscoveryKind): DiscoveryStrategy = object : DiscoveryStrategy {
        override val kind = kind
        override val status = messages.map { it[kind] ?: StrategyStatus(note = "Connect a paired Bluetooth device or USB cable in Settings") }
            .stateIn(scope, SharingStarted.Eagerly, StrategyStatus())
        override val peers = devices.map { all -> all.filter { it.kind == kind } }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    }
}
