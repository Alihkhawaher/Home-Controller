@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.technlov.home.connectors.gree

import com.technlov.home.core.LocalNetwork
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Discovery on virtual time: the fake LAN stamps every scan with the test clock. */
class GreeDiscoveryTest {
    private val lanNet = LocalNetwork("192.0.2.255", "192.0.2.")

    private fun TestScope.lan(vararg units: FakeGreeUnit) = FakeLan(*units, clock = { testScheduler.currentTime })
    private fun TestScope.config(sweep: Boolean = true) = GreeConfig(sweep = sweep, clock = { testScheduler.currentTime })

    // ---- the plan ----

    @Test
    fun planPutsKnownHostsFirstWithoutRepeats() {
        val gree = GreeConnector(FakeLan())
        val p = gree.plan(LocalNetwork("192.0.2.255", "192.0.2", listOf("192.0.2.10", " 198.51.100.7 ", "192.0.2.10", "")))
        assertEquals(listOf("192.0.2.10", "198.51.100.7"), p.known)
        assertEquals("192.0.2.255", p.broadcast)
        assertEquals(253, p.sweep.size)                // .1-.254 without the known .10
        assertFalse("192.0.2.10" in p.sweep)
        assertEquals("192.0.2.1", p.sweep.first())
        assertEquals("192.0.2.254", p.sweep.last())
    }

    @Test
    fun planForOneTypedAddressOrNoSweep() {
        // The app's "add by address" passes the host as broadcast and as known host.
        val one = GreeConnector(FakeLan()).plan(LocalNetwork("192.0.2.10", "", listOf("192.0.2.10")))
        assertEquals(listOf("192.0.2.10"), one.known)
        assertNull(one.broadcast)
        assertTrue(one.sweep.isEmpty())
        val noSweep = GreeConnector(FakeLan(), GreeConfig(sweep = false)).plan(lanNet)
        assertTrue(noSweep.sweep.isEmpty())
        assertEquals("192.0.2.255", noSweep.broadcast)
        assertTrue(GreeConnector(FakeLan()).plan(LocalNetwork("192.0.2.255", "not-a-prefix")).sweep.isEmpty())
        assertTrue(GreeConnector(FakeLan()).plan(LocalNetwork("", "")).isEmpty)
    }

    // ---- timing ----

    @Test
    fun fullPlanTiming() = runTest {
        val lan = lan()                                   // nobody answers: every step runs in full
        val gree = GreeConnector(lan, config())
        val t0 = testScheduler.currentTime
        val found = gree.discover(LocalNetwork("192.0.2.255", "192.0.2.", listOf("192.0.2.200", "198.51.100.7")), 4_000)
        assertTrue(found.isEmpty())
        assertEquals("discovery waits the whole window", 4_000L, testScheduler.currentTime - t0)

        // Known hosts first, then the broadcast, then the sweep from .1.
        assertEquals(listOf("192.0.2.200", "198.51.100.7", "192.0.2.255", "192.0.2.1"), lan.sent.take(4).map { it.host })

        // Broadcast rounds at 0, 1 s and 2 s (the third lands in the middle of pass 2 and still goes out on time).
        assertEquals(listOf(0L, 1_000L, 2_000L), lan.sent.filter { it.host == "192.0.2.255" }.map { it.at - t0 })

        // Paced: bursts of at most 16, sweep bursts 25 ms apart.
        assertTrue(lan.bursts.toString(), lan.bursts.all { it <= 16 })
        val pass1 = lan.sent.filter { it.host.startsWith("192.0.2.") && it.host != "192.0.2.255" && it.host != "192.0.2.200" }
            .groupBy { it.host }.mapValues { (_, v) -> v.map { it.at - t0 } }
        val pass1Times = pass1.values.map { it.first() }.distinct().sorted()
        assertEquals(16, pass1Times.size)                 // 256 pass-1 sends in 16 bursts
        assertTrue(pass1Times.zipWithNext().all { (a, b) -> b - a == 25L })
        val pass1End = pass1Times.last()
        assertEquals(375L, pass1End)

        // Pass 2: 1.5 s after pass 1, to every address again (none answered), paced the same way.
        assertTrue(pass1.values.all { it.size == 2 })
        val pass2Times = pass1.values.map { it[1] }.distinct().sorted()
        assertEquals(pass1End + 1_500, pass2Times.first())
        assertTrue(pass2Times.zipWithNext().all { (a, b) -> b - a == 25L })

        // Known hosts that stay silent are in every pass and every broadcast round: pass 1, 1 s, pass 2, 2 s.
        assertEquals(listOf(0L, 1_000L, 1_875L, 2_000L), lan.sent.filter { it.host == "198.51.100.7" }.map { it.at - t0 })
        assertEquals(1, lan.socketsOpened)
        assertEquals(1, lan.socketsClosed)
    }

    @Test
    fun shorterWindowScalesDownAndStaysInside() = runTest {
        val lan = lan(FakeGreeUnit())
        val gree = GreeConnector(lan, config())
        val t0 = testScheduler.currentTime
        assertEquals(1, gree.discover(lanNet, 3_000).size)  // the core's default timeout
        assertEquals(3_000L, testScheduler.currentTime - t0)
        val broadcasts = lan.sent.filter { it.host == "192.0.2.255" }.map { it.at - t0 }
        assertEquals(listOf(0L, 750L, 1_500L), broadcasts)
        assertTrue(lan.sent.all { it.at - t0 < 3_000 * 4 / 5 })
    }

    // ---- loss ----

    @Test
    fun lostFirstPassIsRecoveredBySecondPass() = runTest {
        // Broadcast frames never arrive and the unit misses the first unicast scan (ARP not resolved yet).
        val unit = FakeGreeUnit(host = "192.0.2.77").apply { dropNext = 1 }
        val lan = lan(unit).apply { broadcastLost = true }
        val found = GreeConnector(lan, config()).discover(lanNet, 4_000)
        assertEquals(listOf("f0fe6b000001"), found.map { it.id })
        assertEquals("192.0.2.77", found[0].address)
        val toUnit = lan.sent.filter { it.host == "192.0.2.77" }
        assertEquals(2, toUnit.size)
        assertTrue(toUnit[1].at >= 375 + 1_500)
    }

    @Test
    fun answeredAddressesAreNotAskedAgain() = runTest {
        val a = FakeGreeUnit(host = "192.0.2.10", mac = "f0fe6b000001")
        val b = FakeGreeUnit(host = "192.0.2.20", mac = "f0fe6b000002")
        val lan = lan(a, b)
        val found = GreeConnector(lan, config()).discover(LocalNetwork("192.0.2.255", "192.0.2.", listOf("192.0.2.20")), 4_000)
        assertEquals(setOf("f0fe6b000001", "f0fe6b000002"), found.map { it.id }.toSet())
        assertEquals(1, lan.sent.count { it.host == "192.0.2.10" })
        assertEquals(1, lan.sent.count { it.host == "192.0.2.20" })   // known host: answered in pass 1, never re-asked
        assertEquals(2, lan.sent.count { it.host == "192.0.2.11" })   // silent: asked in both passes
    }

    @Test
    fun knownHostOutsideTheSweepIsFound() = runTest {
        val far = FakeGreeUnit(host = "198.51.100.7", mac = "f0fe6b0000aa").apply { dropNext = 2 }
        val lan = lan(far).apply { broadcastLost = true }
        val found = GreeConnector(lan, config()).discover(LocalNetwork("192.0.2.255", "192.0.2.", listOf("198.51.100.7")), 4_000)
        assertEquals(listOf("198.51.100.7"), found.map { it.address })
        // Lost at 0 ms and at the 1 s broadcast round, answered in pass 2.
        assertEquals(3, lan.sent.count { it.host == "198.51.100.7" })
    }

    @Test
    fun typedAddressAlone() = runTest {
        val unit = FakeGreeUnit(host = "192.0.2.10").apply { dropNext = 1 }
        val lan = lan(unit)
        val found = GreeConnector(lan, config()).discover(LocalNetwork("192.0.2.10", "", listOf("192.0.2.10")), 4_000)
        assertEquals(1, found.size)
        assertEquals(listOf(0L, 1_000L), lan.sent.map { it.at })      // lost, then the next round
    }

    @Test
    fun deduplicatesByMacAndIgnoresNoise() = runTest {
        val a = FakeGreeUnit(host = "192.0.2.10", mac = "f0fe6b000001")
        val b = FakeGreeUnit(host = "192.0.2.11", mac = "F0:FE:6B:00:00:02", name = "Office", cipher = 2)
        val lan = lan(a, b)
        lan.noise += GreeDatagram("192.0.2.50", "not json".toByteArray())
        lan.noise += GreeDatagram("192.0.2.51", """{"t":"pack","pack":"AAAA"}""".toByteArray())
        val log = RecordingLog()
        val found = GreeConnector(lan, config(), log).discover(LocalNetwork("192.0.2.255", "192.0.2.", listOf("192.0.2.10")), 4_000)
        assertEquals(listOf("f0fe6b000001", "f0fe6b000002"), found.map { it.id })
        assertEquals("Office", found[1].name)
        assertEquals("2", found[1].extras[GreeConnector.EXTRA_CIPHER])
        assertEquals("F0:FE:6B:00:00:02", found[1].extras[GreeConnector.EXTRA_MAC])
        assertEquals("192.0.2.11", found[1].address)
        // Summary line.
        val summary = log.lines.single { it.contains("discovery:") }
        assertTrue(summary, summary.startsWith("INFO gree discovery: 2 unit(s) in 4000 ms; 1 known host(s), broadcast 192.0.2.255 x3"))
    }

    @Test
    fun nothingToAsk() = runTest {
        val lan = lan(FakeGreeUnit())
        assertTrue(GreeConnector(lan, config(sweep = false)).discover(LocalNetwork("", ""), 4_000).isEmpty())
        assertEquals(0, lan.socketsOpened)
    }
}
