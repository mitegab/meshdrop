package com.meshdrop.discovery

import com.meshdrop.core.model.DiscoveryKind
import com.meshdrop.core.model.PeerDevice
import com.meshdrop.discovery.api.DiscoveryCoordinator
import com.meshdrop.discovery.api.DiscoveryStrategy
import com.meshdrop.discovery.api.LocalIdentity
import com.meshdrop.discovery.api.StrategyStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import kotlin.time.Duration.Companion.milliseconds

/**
 * Verifies the two jobs the coordinator has: merge the same device seen by several
 * transports, and forget devices that stopped advertising.
 */
class DiscoveryCoordinatorTest {

    private class FakeStrategy(
        override val kind: DiscoveryKind,
        private val emissions: MutableStateFlow<List<PeerDevice>>,
    ) : DiscoveryStrategy {
        override val status: StateFlow<StrategyStatus> = MutableStateFlow(StrategyStatus())
        override val peers: StateFlow<List<PeerDevice>> = emissions
    }

    private fun device(
        kind: DiscoveryKind,
        port: Int,
        lastSeenMillis: Long = 0L,
        id: String = "device-1",
        name: String = "Pixel",
    ) = PeerDevice(
        id = id,
        name = name,
        kind = kind,
        host = InetAddress.getByName("192.168.1.7"),
        port = port,
        lastSeenMillis = lastSeenMillis,
    )

    private fun coordinator(
        nsd: MutableStateFlow<List<PeerDevice>>,
        beacon: MutableStateFlow<List<PeerDevice>>,
        clock: () -> Long,
    ): DiscoveryCoordinator = DiscoveryCoordinator(
        strategies = listOf(
            FakeStrategy(DiscoveryKind.LAN_NSD, nsd),
            FakeStrategy(DiscoveryKind.LAN_BEACON, beacon),
        ),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        now = clock,
        peerTtl = 200.milliseconds,
        sweepInterval = 20.milliseconds,
    )

    @Test
    fun `a device found by two transports is listed once`() = runBlocking {
        val nsd = MutableStateFlow(emptyList<PeerDevice>())
        val beacon = MutableStateFlow(emptyList<PeerDevice>())
        val coordinator = coordinator(nsd, beacon) { 0L }

        val observed = mutableListOf<List<PeerDevice>>()
        val job = launch { coordinator.peers.collect { observed += it } }

        nsd.value = listOf(device(DiscoveryKind.LAN_NSD, port = 45821))
        beacon.value = listOf(device(DiscoveryKind.LAN_BEACON, port = 0))
        delay(150)

        val merged = observed.last()
        assertEquals(1, merged.size)
        // The entry that can actually be connected to wins.
        assertEquals(45821, merged.single().port)

        job.cancel()
    }

    @Test
    fun `peers that stop advertising disappear`() = runBlocking {
        val nsd = MutableStateFlow(emptyList<PeerDevice>())
        val beacon = MutableStateFlow(emptyList<PeerDevice>())
        var clock = 0L
        val coordinator = coordinator(nsd, beacon) { clock }

        val observed = mutableListOf<List<PeerDevice>>()
        val job = launch { coordinator.peers.collect { observed += it } }

        nsd.value = listOf(device(DiscoveryKind.LAN_NSD, port = 45821))
        delay(120)
        assertEquals(1, observed.last().size)

        clock = 5_000L // far beyond the TTL
        delay(150)
        assertTrue(observed.last().isEmpty())

        job.cancel()
    }

    @Test
    fun `state aggregates what the strategies are doing`() = runBlocking {
        val status = MutableStateFlow(StrategyStatus(scanning = true, advertising = true))
        val strategy = object : DiscoveryStrategy {
            override val kind: DiscoveryKind = DiscoveryKind.LAN_NSD
            override val status: StateFlow<StrategyStatus> = status
            override val peers: StateFlow<List<PeerDevice>> = MutableStateFlow(emptyList())
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = DiscoveryCoordinator(
            strategies = listOf(strategy),
            scope = scope,
            now = { 0L },
            peerTtl = 200.milliseconds,
            sweepInterval = 20.milliseconds,
        )

        val job = launch { coordinator.state.collect {} }
        delay(80)
        assertTrue(coordinator.state.value.scanning)
        assertTrue(coordinator.state.value.advertising)

        status.value = StrategyStatus(error = "mDNS is disabled")
        delay(80)
        assertEquals("mDNS is disabled", coordinator.state.value.firstError)

        job.cancel()
        scope.cancel()
    }
}
