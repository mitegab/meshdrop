package com.meshdrop.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/** Ranking is pure arithmetic over PeerDevice, so it is tested without a device. */
class PeerRankingTest {

    private val now = 1_000_000L

    private fun peer(
        id: String = "a",
        name: String = "Pixel",
        kind: DiscoveryKind = DiscoveryKind.LAN_NSD,
        host: String? = "192.168.1.20",
        ageMillis: Long = 1_000L,
    ) = PeerDevice(
        id = id,
        name = name,
        kind = kind,
        host = host?.let(InetAddress::getByName),
        port = if (host == null) 0 else 45821,
        lastSeenMillis = now - ageMillis,
    )

    @Test
    fun `a peer we can actually reach beats one we cannot`() {
        val reachable = PeerRanking.score(peer(), now)
        val resolving = PeerRanking.score(peer(host = null), now)

        assertTrue(reachable.score > resolving.score)
        assertEquals(PeerScore.Grade.UNREACHABLE, resolving.grade)
        assertFalse(reachable.reasons.contains("no address yet"))
        assertTrue(resolving.reasons.contains("no address yet"))
    }

    @Test
    fun `a quiet peer scores worse than one seen a moment ago`() {
        val fresh = PeerRanking.score(peer(ageMillis = 500L), now)
        val stale = PeerRanking.score(peer(ageMillis = 15_000L), now)
        val gone = PeerRanking.score(peer(ageMillis = 60_000L), now)

        assertTrue(fresh.score > stale.score)
        assertTrue(stale.score > gone.score)
        assertEquals("just seen", fresh.reasons[1])
        assertEquals("seen 15s ago", stale.reasons[1])
        assertEquals("gone quiet", gone.reasons[1])
        assertEquals(60_000L, gone.freshnessMillis)
    }

    @Test
    fun `a better lane scores better`() {
        val direct = PeerRanking.score(peer(kind = DiscoveryKind.WIFI_DIRECT), now)
        val lan = PeerRanking.score(peer(kind = DiscoveryKind.LAN_NSD), now)
        val bluetooth = PeerRanking.score(peer(kind = DiscoveryKind.BLUETOOTH), now)

        assertTrue(direct.score > lan.score)
        assertTrue(lan.score > bluetooth.score)
        assertEquals("Wi-Fi Direct", direct.reasons[2])
    }

    @Test
    fun `verification and a measured round trip both help`() {
        val plain = PeerRanking.score(peer(), now)
        val verified = PeerRanking.score(peer(), now, verified = true)
        val nearby = PeerRanking.score(peer(), now, latencyMillis = 8L)

        assertTrue(verified.score > plain.score)
        assertTrue(nearby.score > plain.score)
        assertTrue(verified.reasons.contains("verified"))
        assertTrue(nearby.reasons.contains("8ms away"))
    }

    @Test
    fun `scores are capped and never negative`() {
        val best = PeerRanking.score(
            peer(kind = DiscoveryKind.WIFI_DIRECT, ageMillis = 0L),
            now,
            verified = true,
            latencyMillis = 1L,
        )

        assertTrue(best.score <= PeerRanking.MAX_SCORE)
        assertEquals(PeerScore.Grade.EXCELLENT, best.grade)

        val worst = PeerRanking.score(peer(host = null, ageMillis = 900_000L), now)
        assertTrue(worst.score >= 0)
    }

    @Test
    fun `the best target comes first and unreachable peers go last`() {
        val staleLan = peer(id = "stale", name = "A stale", ageMillis = 18_000L)
        val freshDirect = peer(id = "direct", name = "B direct", kind = DiscoveryKind.WIFI_DIRECT, ageMillis = 200L)
        val resolving = peer(id = "resolving", name = "C resolving", host = null)
        val freshLan = peer(id = "lan", name = "D lan", ageMillis = 300L)

        val ranked = PeerRanking.rank(listOf(staleLan, resolving, freshLan, freshDirect), now)

        assertEquals(listOf("direct", "lan", "stale", "resolving"), ranked.map { it.id })
    }

    @Test
    fun `equal peers are ordered by name so the list does not reshuffle`() {
        val first = peer(id = "1", name = "Alice")
        val second = peer(id = "2", name = "Bob")

        assertEquals(listOf("Alice", "Bob"), PeerRanking.rank(listOf(second, first), now).map { it.name })
        assertEquals(listOf("Alice", "Bob"), PeerRanking.rank(listOf(first, second), now).map { it.name })
    }

    @Test
    fun `verified peers are recognised by id when ranking`() {
        val a = peer(id = "a", name = "A")
        val b = peer(id = "b", name = "B")

        val ranked = PeerRanking.rank(listOf(a, b), now, verifiedIds = setOf("b"))

        assertEquals("b", ranked.first().id)
    }

    @Test
    fun `the scored view carries the reason next to the peer`() {
        val scored = listOf(peer(name = "Pixel")).scored(now, latenciesMillis = mapOf("a" to 12L))

        assertEquals(1, scored.size)
        assertEquals("Pixel", scored.single().peer.name)
        assertTrue(scored.single().score.headline.contains("12ms away"))
        assertTrue(scored.single().score.score > 0)
    }

    @Test
    fun `a grade is never shown for a peer without an address`() {
        val score = PeerRanking.score(peer(host = null), now)
        assertEquals(PeerScore.Grade.UNREACHABLE.label, "Not reachable")
        assertTrue(score.headline.isNotBlank())
    }
}
