package com.meshdrop.discovery.api

import com.meshdrop.core.model.PeerDevice
import com.meshdrop.core.transport.LatencyEstimate
import com.meshdrop.core.transport.ProbePolicy
import com.meshdrop.core.util.MeshLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Measures how far away each discovered peer actually is.
 *
 * Discovery says a device exists; it cannot say whether that device is on the same access
 * point or three hops away behind a phone hotspot. That difference is most of the transfer
 * experience — 15 ms and 300 ms are the same file at very different speeds — so it is worth
 * a connection attempt every few seconds to find out.
 *
 * What is measured is a TCP connect to the peer's transfer port: no request, no handshake,
 * nothing the receiver has to answer beyond accepting and closing. Cheap on both sides, and
 * it measures the path the transfer itself will use rather than an ICMP round trip that
 * routers routinely deprioritise.
 */
class PeerProber(
    private val peers: StateFlow<List<PeerDevice>>,
    private val scope: CoroutineScope,
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    private val _estimates = MutableStateFlow<Map<String, LatencyEstimate>>(emptyMap())

    /** One smoothed round trip per peer, keyed by device id. */
    val estimates: StateFlow<Map<String, LatencyEstimate>> = _estimates.asStateFlow()

    /** The shape [com.meshdrop.core.model.PeerRanking] wants: id → milliseconds. */
    val latenciesMillis: StateFlow<Map<String, Long>> = estimates
        .map { map -> map.mapNotNull { (id, estimate) -> estimate.millis?.let { id to it } }.toMap() }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /**
     * Probes on a timer while this object lives.
     *
     * One pass measures at most [ProbePolicy.duePeers] peers, so a room full of devices
     * costs a handful of connection attempts per pass rather than one per device.
     */
    fun start() {
        scope.launch {
            while (true) {
                runCatching { probeOnce() }.onFailure { MeshLog.v(TAG, "probe pass failed: ${it.message}") }
                delay(ProbePolicy.PROBE_INTERVAL_MILLIS)
            }
        }
    }

    /** Measures the peers that are due, concurrently, and folds the results in. */
    suspend fun probeOnce(devices: List<PeerDevice> = peers.value) {
        val targets = ProbePolicy
            .duePeers(devices.map { it.id }, _estimates.value, now())
            .mapNotNull { id -> devices.firstOrNull { it.id == id } }
            .filter { ProbePolicy.canProbe(it.host != null, it.port) }
        if (targets.isEmpty()) return

        val results = coroutineScope {
            targets.map { peer -> async(Dispatchers.IO) { peer to measure(peer) } }.awaitAll()
        }

        val timestamp = now()
        val live = devices.map { it.id }.toSet()
        // Peers that have left are forgotten rather than measured forever.
        _estimates.value = _estimates.value.filterKeys { it in live }.toMutableMap().apply {
            results.forEach { (peer, millis) ->
                val previous = get(peer.id) ?: LatencyEstimate()
                put(
                    peer.id,
                    if (millis == null) previous.observeFailure(timestamp) else previous.observe(millis, timestamp),
                )
            }
        }
    }

    /** Round trip in milliseconds, or null when the peer did not accept in time. */
    private fun measure(peer: PeerDevice): Long? {
        val host = peer.host ?: return null
        val started = System.nanoTime()
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, peer.port), connectTimeoutMillis)
                ((System.nanoTime() - started) / NANOS_PER_MILLI).coerceAtLeast(0L)
            }
        }.onFailure {
            MeshLog.v(TAG, "${peer.name}: probe failed (${it.message})")
        }.getOrNull()
    }

    /** Drops everything — used when discovery restarts. */
    fun reset() {
        _estimates.value = emptyMap()
    }

    companion object {
        private const val TAG = "PeerProber"
        private const val NANOS_PER_MILLI = 1_000_000L

        /** Long enough for a genuine LAN hop, short enough that a dead peer costs little. */
        const val DEFAULT_CONNECT_TIMEOUT_MS = 1_500
    }
}
