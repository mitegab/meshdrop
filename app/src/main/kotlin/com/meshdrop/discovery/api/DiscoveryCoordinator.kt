package com.meshdrop.discovery.api

import com.meshdrop.core.model.DiscoveryKind
import com.meshdrop.core.model.PeerDevice
import com.meshdrop.core.util.MeshLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class DiscoveryState(
    val scanning: Boolean = false,
    val advertising: Boolean = false,
    val errors: List<String> = emptyList(),
) {
    val firstError: String? get() = errors.firstOrNull()
}

/**
 * Merges every [DiscoveryStrategy] into one view of the neighbourhood.
 *
 * Responsibilities:
 *  * merge peers found by several transports, keeping the best information per device id
 *  * expire devices we have not heard from for [PEER_TTL]
 *  * expose a single [DiscoveryState] so the UI never talks to a strategy directly
 */
class DiscoveryCoordinator(
    private val strategies: List<DiscoveryStrategy>,
    private val scope: CoroutineScope,
    private val now: () -> Long = { System.currentTimeMillis() },
    /** How long a peer stays in the list without a fresh advertisement. */
    private val peerTtl: Duration = DEFAULT_PEER_TTL,
    /** How often stale peers are swept (also bounds how quickly merges are re-evaluated). */
    private val sweepInterval: Duration = DEFAULT_SWEEP_INTERVAL,
) {

    /** Bumped on a timer so stale peers disappear from [peers] even if nothing else changes. */
    private val sweep = MutableStateFlow(0)

    val peers: StateFlow<List<PeerDevice>> =
        combine(strategies.map { it.peers }) { lists: Array<List<PeerDevice>> ->
            lists.fold(emptyList<PeerDevice>()) { acc, list -> acc + list }
        }
            .combine(sweep) { devices, _ ->
                val cutoff = now() - peerTtl.inWholeMilliseconds
                devices
                    .filter { it.lastSeenMillis >= cutoff }
                    .mergeByDeviceId()
                    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val state: StateFlow<DiscoveryState> =
        combine(strategies.map { it.status }) { statuses: Array<StrategyStatus> ->
            DiscoveryState(
                scanning = statuses.any { it.scanning },
                advertising = statuses.any { it.advertising },
                errors = statuses.mapNotNull { it.error },
            )
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), DiscoveryState())

    /** Per-strategy detail, so the UI can explain why a transport found nothing. */
    val strategyStatuses: StateFlow<Map<DiscoveryKind, StrategyStatus>> =
        combine(strategies.map { it.status }) { statuses: Array<StrategyStatus> ->
            strategies.mapIndexed { index, strategy -> strategy.kind to statuses[index] }.toMap()
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    init {
        scope.launch {
            while (true) {
                delay(sweepInterval)
                sweep.update { it + 1 }
            }
        }
    }

    suspend fun start(identity: LocalIdentity) {
        MeshLog.d(TAG, "Starting discovery with ${strategies.size} strategy(ies)")
        strategies.forEach { strategy ->
            runCatching {
                if (strategy.isAvailable) strategy.startAdvertising(identity)
            }.onFailure { MeshLog.w(TAG, "${strategy.kind} advertise failed", it) }
        }
        startScan()
    }

    suspend fun startScan() {
        strategies.forEach { strategy ->
            runCatching {
                if (strategy.isAvailable) strategy.startScan()
            }.onFailure { MeshLog.w(TAG, "${strategy.kind} scan failed", it) }
        }
    }

    suspend fun stop() {
        strategies.forEach { strategy ->
            runCatching { strategy.stopScan() }
            runCatching { strategy.stopAdvertising() }
        }
    }

    /** Force a fresh scan (used by the pull-to-refresh style button on the Home screen). */
    suspend fun refresh(identity: LocalIdentity) {
        strategies.forEach { strategy ->
            runCatching { strategy.stopScan() }
        }
        start(identity)
    }

    private fun List<PeerDevice>.mergeByDeviceId(): List<PeerDevice> =
        groupBy { it.id }.map { (_, candidates) -> candidates.best() }

    /**
     * Prefer an entry that can actually be connected to (it has a host) and, between two
     * equally useful entries, the most recently seen one.
     */
    private fun List<PeerDevice>.best(): PeerDevice = reduce { acc, next ->
        val accScore = acc.score()
        val nextScore = next.score()
        if (nextScore > accScore) next else acc
    }

    private fun PeerDevice.score(): Int =
        (if (host != null) 100 else 0) + (if (port > 0) 10 else 0) +
            (lastSeenMillis / 1_000L).toInt().coerceAtMost(5)

    companion object {
        const val TAG = "Discovery"

        /** A peer that has been quiet for this long is considered gone. */
        val DEFAULT_PEER_TTL = 20.seconds
        val DEFAULT_SWEEP_INTERVAL = 3.seconds
    }
}
