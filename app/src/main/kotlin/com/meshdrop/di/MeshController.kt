package com.meshdrop.di

import com.meshdrop.core.model.IncomingRequest
import com.meshdrop.core.model.PeerDevice
import com.meshdrop.core.model.PeerRanking
import com.meshdrop.core.model.TransferFile
import com.meshdrop.core.prefs.UserPreferences
import com.meshdrop.core.util.MeshLog
import com.meshdrop.discovery.api.DiscoveryCoordinator
import com.meshdrop.discovery.api.LocalIdentity
import com.meshdrop.discovery.api.PeerProber
import com.meshdrop.transfer.TransferEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The single object the UI talks to.
 *
 * It hides the fact that "being visible on the network" is really two things — running a
 * transfer server and advertising it through every discovery strategy — and keeps the
 * advertised port in sync with the server that is actually listening.
 */
class MeshController(
    private val preferences: UserPreferences,
    private val discovery: DiscoveryCoordinator,
    private val engine: TransferEngine,
    scope: CoroutineScope,
    private val startLinks: () -> Unit = {},
    private val stopLinks: () -> Unit = {},
) {

    private val mutex = Mutex()

    /**
     * How far away each discovered peer is, measured by connecting to its transfer port.
     *
     * Discovery can only say a device exists. Two devices on the same access point and one
     * three hops away behind a hotspot all look identical in [peers], and the difference is
     * the whole of the transfer experience.
     */
    private val prober = PeerProber(discovery.peers, scope).also { it.start() }

    val peers: StateFlow<List<PeerDevice>> = discovery.peers

    /**
     * Peers ordered by how good a target they are right now.
     *
     * Replaces the alphabetical list from discovery: nearest and freshest first, devices we
     * cannot reach last, whatever their name.
     */
    val rankedPeers: StateFlow<List<PeerDevice>> =
        combine(discovery.peers, prober.latenciesMillis) { devices, latencies ->
            PeerRanking.rank(devices, latenciesMillis = latencies)
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Round trip per device, for the "12 ms" line under a peer's name. */
    val peerLatencies: StateFlow<Map<String, Long>> = prober.latenciesMillis
    val discoveryState = discovery.state
    val strategyStatuses = discovery.strategyStatuses
    val jobs = engine.jobs
    val incoming: StateFlow<List<IncomingRequest>> = engine.incoming
    val security: StateFlow<Map<String, com.meshdrop.core.model.SecurityState>> = engine.security
    val deviceName: StateFlow<String> = preferences.deviceName

    /**
     * The device the user most recently tapped. Kept in memory (not in a flow) because it is
     * pure navigation context: the Send screen reads it once on entry.
     */
    @Volatile
    var selectedPeer: PeerDevice? = null
        private set

    fun selectPeer(peer: PeerDevice) {
        selectedPeer = peer
    }

    /**
     * Other ways to reach the same device, best first.
     *
     * Used when a transfer stalls: the route we picked may be the one that is broken, and
     * discovery usually knows a second address for the same device.
     */
    fun alternativeRoutes(deviceId: String): List<PeerDevice> = discovery.routes.value
        .filter { it.id == deviceId }
        .sortedByDescending { it.lastSeenMillis }

    /** Every finished transfer, newest first, surviving restarts. */
    fun loggedTransfers() = engine.loggedTransfers()

    fun forgetTransfer(jobId: String) = engine.forgetTransfer(jobId)

    fun clearLog() = engine.clearLog()

    /** Applies the retention policy; returns how many entries were dropped. */
    fun pruneLog(): Int = engine.pruneLog()

    /** "Next in line" / "3rd in line" for a send that is waiting for a slot; null when it is moving. */
    fun queueLabelFor(jobId: String): String? = engine.queueLabelFor(jobId)

    /** "2 jobs waiting · next in about 12s" for the send screen header. */
    val queuePlan get() = engine.queuePlan

    /** TCP port we accept transfers on, or 0 while the receiver is off. */
    val localPort: Int get() = engine.localPort

    fun identity(port: Int = engine.localPort): LocalIdentity = LocalIdentity(
        deviceId = preferences.deviceId,
        deviceName = preferences.deviceName.value,
        port = port,
    )

    /** Port we are currently advertising, so repeated start() calls are cheap. */
    private var advertisedPort = 0

    /**
     * Starts the receiver and makes this device discoverable.
     *
     * Called from the activity's `onStart` and from both screens, so it has to be cheap when
     * nothing changed: we only re-advertise when the listening port actually moved.
     */
    suspend fun start() = mutex.withLock {
        val port = engine.startReceiving()
        startLinks()
        if (port <= 0) {
            MeshLog.w(TAG, "Could not start the receiver; skipping discovery")
            return
        }
        if (advertisedPort == port) {
            discovery.startScan()
            return
        }
        advertisedPort = port
        discovery.start(identity(port))
        MeshLog.i(TAG, "MeshDrop is live as ${identity(port).deviceName} on port $port")
    }

    suspend fun stop() = mutex.withLock {
        advertisedPort = 0
        discovery.stop()
        stopLinks()
        engine.stopReceiving()
    }

    /** Re-advertises with a fresh port (used after a restart or a name change). */
    suspend fun reAdvertise() = mutex.withLock {
        discovery.stop()
        advertisedPort = engine.localPort
        discovery.start(identity())
    }

    /** Forces a new scan pass — wired to the refresh button on the Home screen. */
    suspend fun refresh() = mutex.withLock {
        discovery.refresh(identity())
    }

    fun setDeviceName(name: String) {
        preferences.setDeviceName(name)
    }

    // region transfers --------------------------------------------------------------------

    suspend fun send(peer: PeerDevice, files: List<TransferFile>): String =
        engine.send(discovery.connect(peer), files)

    fun approve(batchId: String) = engine.approve(batchId)

    /** The user compared the safety codes on both devices and they matched. */
    fun confirmSafetyCode(batchId: String, peerLabel: String) =
        engine.confirmSafetyCode(batchId, peerLabel)

    fun securityFor(batchId: String) = engine.securityFor(batchId)

    /** Sends the files that did not make it again. False when there is nothing to retry. */
    fun retry(jobId: String): Boolean = engine.retry(jobId)

    fun reject(batchId: String) = engine.reject(batchId)

    fun cancel(jobId: String) = engine.cancel(jobId)

    fun clearFinished() = engine.clearFinished()

    // endregion

    companion object {
        const val TAG = "MeshController"
    }
}
