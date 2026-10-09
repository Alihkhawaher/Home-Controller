package com.technlov.home.connectors.gree

import com.technlov.home.core.ConnectorException
import com.technlov.home.core.DeviceCategory
import com.technlov.home.core.Keys
import com.technlov.home.core.LocalNetwork
import com.technlov.home.core.SetValue
import com.technlov.home.core.Value
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GreeConnectorTest {
    private val net = LocalNetwork(broadcast = "192.0.2.255", prefix = "192.0.2.")

    private fun connector(lan: FakeLan, config: GreeConfig = GreeConfig()) = GreeConnector(lan, config)

    private suspend inline fun <reified T : Throwable> expect(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw e
        }
        fail("expected ${T::class.simpleName}")
        throw IllegalStateException()
    }

    // ---- full flow ----

    @Test
    fun scanBindStatusCommandStatusV1() = runTest { fullFlow(cipher = 1) }

    @Test
    fun scanBindStatusCommandStatusV2() = runTest { fullFlow(cipher = 2) }

    private suspend fun fullFlow(cipher: Int) {
        val unit = FakeGreeUnit(cipher = cipher)
        val lan = FakeLan(unit)
        val gree = connector(lan, GreeConfig(sweep = false))

        val found = gree.discover(net, 1_000)
        assertEquals(1, found.size)
        val ref = found[0]
        assertEquals("gree", ref.connectorId)
        assertEquals("f0fe6b000001", ref.id)
        assertEquals("192.0.2.10", ref.address)
        assertEquals("Gree AC 0001", ref.name)
        assertEquals(DeviceCategory.CLIMATE, ref.category)
        assertEquals("Gree", ref.model)
        assertEquals("V3.0.0", ref.firmware)
        assertEquals(cipher.toString(), ref.extras[GreeConnector.EXTRA_CIPHER])

        val session = gree.open(ref)
        assertEquals(17, session.capabilities.size)
        assertEquals(cipher.toString(), session.device.extras[GreeConnector.EXTRA_CIPHER])

        val s1 = session.read()
        assertTrue(s1.online)
        assertNull(s1.error)
        assertEquals(false, s1.bool(Keys.POWER))
        assertEquals("cool", s1.text(Keys.MODE))
        assertEquals(21.0, s1.number(Keys.TARGET_TEMPERATURE)!!, 0.0)
        assertEquals(28.0, s1.number(Keys.CURRENT_TEMPERATURE)!!, 0.0)   // TemSen 68
        assertEquals("high", s1.text(Keys.FAN_SPEED))
        assertEquals("fixed_upper", s1.text(Keys.SWING_VERTICAL))
        assertEquals("centre", s1.text(Keys.SWING_HORIZONTAL))
        assertEquals(true, s1.bool(Keys.DISPLAY_LIGHT))
        assertEquals(true, s1.bool(GreeKeys.HEALTH))
        assertEquals(true, s1.bool(GreeKeys.X_FAN))
        assertEquals(false, s1.bool(GreeKeys.QUIET))
        assertEquals("none", s1.text(Keys.FAULT))
        assertEquals("TEST05RT13V1.00", s1.text(Keys.FIRMWARE))
        assertEquals(false, s1.bool(GreeKeys.CLEAN_FILTER))

        val s2 = session.apply(listOf(SetValue(Keys.POWER, Value.Bool(true)), SetValue(Keys.TARGET_TEMPERATURE, Value.Num(24.0))))
        val cmd = unit.commands.single()
        assertEquals("""["Pow","SetTem","TemUn","TemRec"]""", cmd.getJSONArray("opt").toString())
        assertEquals("[1,24,0,0]", cmd.getJSONArray("p").toString())
        // The outer frame of the command.
        val cmdFrame = unit.rawRequests.first { raw ->
            val o = JSONObject(raw)
            o.optString("t") == "pack" && runCatching {
                JSONObject(GreeCipher.decrypt(o.getString("pack"), unit.unitKey.toByteArray(), cipher, o.optString("tag").ifEmpty { null }))
                    .optString("t") == "cmd"
            }.getOrDefault(false)
        }
        val f = JSONObject(cmdFrame)
        assertEquals("app", f.getString("cid"))
        assertEquals(0, f.getInt("i"))
        assertEquals(0, f.getInt("uid"))
        assertEquals("f0fe6b000001", f.getString("tcid"))
        assertEquals(cipher == 2, f.has("tag"))

        assertTrue(s2.online)
        assertEquals(true, s2.bool(Keys.POWER))
        assertEquals(24.0, s2.number(Keys.TARGET_TEMPERATURE)!!, 0.0)
        assertEquals(1, unit.state["Pow"])
        assertEquals(24, unit.state["SetTem"])
    }

    @Test
    fun bindFrameIsByteForByteWhatPythonSends() = runTest {
        val unit = FakeGreeUnit()
        val client = GreeClient("192.0.2.10", FakeLan(unit))
        client.scan()
        client.bind()
        assertEquals(
            """{"cid":"app","i":1,"t":"pack","uid":0,"tcid":"f0fe6b000001","pack":"LbyXlIr/T0/qWhgSNSKzEpzHQmRp/h87PxQH+DSyG2Oj9mrs/0p7GUTdWxmNaBUt"}""",
            unit.rawRequests[1],
        )
        assertEquals("""{"t":"scan"}""", unit.rawRequests[0])
    }

    @Test
    fun namedUnitKeepsItsName() = runTest {
        val lan = FakeLan(FakeGreeUnit(name = "Bedroom"))
        assertEquals("Bedroom", connector(lan).discover(net, 500).single().name)
    }

    // ---- UDP loss ----

    @Test
    fun readRetriesOnSilence() = runTest {
        val unit = FakeGreeUnit()
        val lan = FakeLan(unit)
        val gree = connector(lan, GreeConfig(sweep = false))
        val session = gree.open(gree.discover(net, 500).single())
        session.read()
        val before = lan.exchanges
        unit.dropNext = 3
        val s = session.read()
        assertTrue(s.online)
        assertEquals(28.0, s.number(Keys.CURRENT_TEMPERATURE)!!, 0.0)
        assertEquals(4, lan.exchanges - before)   // 3 lost + 1 answered (diagnostics not due yet)
    }

    @Test
    fun readGivesOfflineStateAfterFourSilentTries() = runTest {
        val unit = FakeGreeUnit()
        val lan = FakeLan(unit)
        val gree = connector(lan, GreeConfig(sweep = false))
        val session = gree.open(gree.discover(net, 500).single())
        session.read()
        val before = lan.exchanges
        unit.dropNext = 4
        val s = session.read()
        assertFalse(s.online)
        assertTrue(s.error!!, s.error!!.contains("no answer"))
        assertEquals(4, lan.exchanges - before)
        assertEquals("cool", s.text(Keys.MODE))   // last known values stay
        assertTrue(session.read().online)          // and it recovers
    }

    @Test
    fun commandIsResentOnlyOnSilence() = runTest {
        val unit = FakeGreeUnit()
        val lan = FakeLan(unit)
        val gree = connector(lan, GreeConfig(sweep = false))
        val session = gree.open(gree.discover(net, 500).single())

        session.apply(listOf(SetValue(Keys.DISPLAY_LIGHT, Value.Bool(false))))
        assertEquals(1, unit.commands.size)   // answered at once: sent once

        val requestsBefore = unit.rawRequests.size
        unit.dropNext = 1
        val s = session.apply(listOf(SetValue(Keys.DISPLAY_LIGHT, Value.Bool(true))))
        assertEquals(true, s.bool(Keys.DISPLAY_LIGHT))
        assertEquals(2, unit.commands.size)   // the lost one never reached the unit's handler
        // lost cmd + re-sent cmd + read-back status
        assertEquals(3, unit.rawRequests.size - requestsBefore)
        assertEquals(unit.rawRequests[requestsBefore], unit.rawRequests[requestsBefore + 1])
    }

    @Test
    fun commandFailsAfterTwoSilentTries() = runTest {
        val unit = FakeGreeUnit()
        val lan = FakeLan(unit)
        val gree = connector(lan, GreeConfig(sweep = false))
        val session = gree.open(gree.discover(net, 500).single())
        val before = lan.exchanges
        unit.dropNext = 2
        val e = expect<ConnectorException> { session.apply(listOf(SetValue(Keys.POWER, Value.Bool(true)))) }
        assertTrue(e.message!!, e.message!!.contains("no answer"))
        assertEquals(2, lan.exchanges - before)
        assertEquals(0, unit.state["Pow"])
    }

    @Test
    fun invalidChangeIsRefusedBeforeSending() = runTest {
        val unit = FakeGreeUnit()
        val lan = FakeLan(unit)
        val gree = connector(lan, GreeConfig(sweep = false))
        val session = gree.open(gree.discover(net, 500).single())
        val before = lan.exchanges
        expect<ConnectorException> { session.apply(listOf(SetValue(Keys.POWER, Value.Bool(true)), SetValue(Keys.TARGET_TEMPERATURE, Value.Num(35.0)))) }
        expect<ConnectorException> { session.apply(listOf(SetValue(Keys.CURRENT_TEMPERATURE, Value.Num(20.0)))) }
        expect<ConnectorException> { session.apply(listOf(SetValue(Keys.MODE, Value.Text("boost")))) }
        assertEquals(0, lan.exchanges - before)
        assertTrue(unit.commands.isEmpty())
    }

    @Test
    fun emptyApplyJustReads() = runTest {
        val unit = FakeGreeUnit()
        val gree = connector(FakeLan(unit), GreeConfig(sweep = false))
        val session = gree.open(gree.discover(net, 500).single())
        assertTrue(session.apply(emptyList()).online)
        assertTrue(unit.commands.isEmpty())
    }

    // ---- bind ----

    @Test
    fun bindFallsBackToTheOtherCipher() = runTest {
        val unit = FakeGreeUnit(cipher = 2)
        val client = GreeClient("192.0.2.10", FakeLan(unit), cipherHint = 1)
        client.mac = unit.mac          // no scan: the cipher is unknown
        client.bind()
        assertEquals(2, client.cipher)
        assertTrue(client.isBound)
        assertEquals(21, client.status(listOf("SetTem"))["SetTem"])
    }

    @Test
    fun rebindsWhenTheUnitKeyChanged() = runTest {
        val unit = FakeGreeUnit()
        val gree = connector(FakeLan(unit), GreeConfig(sweep = false))
        val session = gree.open(gree.discover(net, 500).single())
        unit.rekey("NewKey0123456789")
        val s = session.read()
        assertTrue(s.error ?: "", s.online)
        assertEquals("cool", s.text(Keys.MODE))
    }

    @Test
    fun keyNeverShowsInToStringOrErrors() = runTest {
        val unit = FakeGreeUnit()
        val lan = FakeLan(unit)
        val client = GreeClient("192.0.2.10", lan)
        client.scan()
        client.bind()
        assertFalse(client.toString().contains(unit.unitKey))
        unit.dropNext = 10
        val e = expect<ConnectorException> { client.status(listOf("Pow")) }
        assertFalse(e.message!!.contains(unit.unitKey))
    }

    // ---- open ----

    @Test
    fun openRefusesADifferentUnitAtTheAddress() = runTest {
        val lan = FakeLan(FakeGreeUnit(mac = "f0fe6b000001"))
        val ref = connector(lan).discover(net, 500).single()
        lan.units["192.0.2.10"] = FakeGreeUnit(mac = "f0fe6b0000ff")
        val e = expect<ConnectorException> { connector(lan).open(ref) }
        assertTrue(e.message!!, e.message!!.contains("different Gree unit"))
    }

    @Test
    fun openFailsCleanlyWhenNothingAnswers() = runTest {
        val lan = FakeLan(FakeGreeUnit())
        val ref = connector(lan).discover(net, 500).single()
        lan.units.clear()
        expect<ConnectorException> { connector(lan).open(ref) }
    }

    @Test
    fun capabilitiesLeaveOutColumnsTheUnitDoesNotKnow() = runTest {
        val unit = FakeGreeUnit().apply { unknownColumns += setOf("SwingLfRig", "Quiet") }
        val gree = connector(FakeLan(unit), GreeConfig(sweep = false))
        val keys = gree.open(gree.discover(net, 500).single()).capabilities.map { it.key }
        assertFalse(Keys.SWING_HORIZONTAL in keys)
        assertFalse(GreeKeys.QUIET in keys)
        assertTrue(Keys.SWING_VERTICAL in keys)
        assertTrue(Keys.FAULT in keys)
    }

    @Test
    fun diagnosticsAreReadLessOften() = runTest {
        var now = 1_000_000L
        val unit = FakeGreeUnit()
        val gree = connector(FakeLan(unit), GreeConfig(sweep = false, diagnosticsEveryMs = 60_000, clock = { now }))
        val session = gree.open(gree.discover(net, 500).single())
        fun diagRequests() = unit.inner.count { it.optString("t") == "status" && it.getJSONArray("cols").toString().contains("AllErr") }
        assertEquals(1, diagRequests())            // at open
        session.read()
        assertEquals(1, diagRequests())
        unit.state["AllErr"] = 3
        now += 61_000
        val s = session.read()
        assertEquals(2, diagRequests())
        assertEquals("code 3", s.text(Keys.FAULT))
        // Status columns and diagnostic columns are never asked together.
        for (p in unit.inner.filter { it.optString("t") == "status" }) {
            val cols = p.getJSONArray("cols").toString()
            assertFalse(cols.contains("AllErr") && cols.contains("Pow"))
        }
    }

    // ---- discovery ----

    @Test
    fun discoveryTargetsBroadcastKnownHostsAndSweep() {
        val gree = GreeConnector(FakeLan())
        val targets = gree.discoveryTargets(LocalNetwork("192.0.2.255", "192.0.2", listOf("192.0.2.10", "198.51.100.7")))
        assertEquals("192.0.2.255", targets[0])
        assertEquals("192.0.2.10", targets[1])
        assertEquals("198.51.100.7", targets[2])
        assertEquals(targets.size, targets.toSet().size)
        assertEquals(3 + 253, targets.size)           // .10 is already listed
        assertTrue("192.0.2.1" in targets && "192.0.2.254" in targets)
        assertFalse("192.0.2.0" in targets)

        val noSweep = GreeConnector(FakeLan(), GreeConfig(sweep = false))
        assertEquals(listOf("192.0.2.255", "192.0.2.10"), noSweep.discoveryTargets(LocalNetwork("192.0.2.255", "192.0.2.", listOf("192.0.2.10"))))
        assertEquals(listOf("192.0.2.255"), GreeConnector(FakeLan()).discoveryTargets(LocalNetwork("192.0.2.255", "not-a-prefix")))
    }

    @Test
    fun discoveryDeduplicatesByMac() = runTest {
        val a = FakeGreeUnit(host = "192.0.2.10", mac = "f0fe6b000001")
        val b = FakeGreeUnit(host = "192.0.2.11", mac = "F0:FE:6B:00:00:02", name = "Office", cipher = 2)
        val lan = FakeLan(a, b)
        lan.noise += GreeDatagram("192.0.2.50", "not json".toByteArray())
        lan.noise += GreeDatagram("192.0.2.51", """{"t":"pack","pack":"AAAA"}""".toByteArray())
        val gree = GreeConnector(lan, GreeConfig(discoveryRounds = 2))
        // Broadcast + known host + sweep, two rounds: each unit answers up to 6 times.
        val found = gree.discover(LocalNetwork("192.0.2.255", "192.0.2.", listOf("192.0.2.10")), 1_000)
        assertEquals(listOf("f0fe6b000001", "f0fe6b000002"), found.map { it.id })
        assertEquals("Office", found[1].name)
        assertEquals("2", found[1].extras[GreeConnector.EXTRA_CIPHER])
        assertEquals("F0:FE:6B:00:00:02", found[1].extras[GreeConnector.EXTRA_MAC])
        assertEquals("192.0.2.11", found[1].address)
    }

    @Test
    fun discoveryWithNothingToAsk() = runTest {
        val gree = GreeConnector(FakeLan(FakeGreeUnit()), GreeConfig(sweep = false))
        assertTrue(gree.discover(LocalNetwork("", ""), 500).isEmpty())
    }
}
