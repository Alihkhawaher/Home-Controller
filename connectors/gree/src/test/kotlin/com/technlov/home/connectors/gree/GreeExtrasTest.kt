package com.technlov.home.connectors.gree

import com.technlov.home.core.Capability
import com.technlov.home.core.ConnectorException
import com.technlov.home.core.DeviceSession
import com.technlov.home.core.Keys
import com.technlov.home.core.LocalNetwork
import com.technlov.home.core.SetValue
import com.technlov.home.core.Value
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** ChildLock, TmrOn, TmrOff, Defrost: their own small request, at open and with the diagnostics. */
class GreeExtrasTest {
    private val net = LocalNetwork("192.0.2.255", "192.0.2.")
    private var now = 1_000_000L

    /** A V3.0.0-like unit answers all four; a V1.2.1-like unit leaves out ChildLock. */
    private fun v3() = FakeGreeUnit()
    private fun v121() = FakeGreeUnit(ver = "V1.2.1").apply { unknownColumns += "ChildLock" }

    private suspend fun open(unit: FakeGreeUnit, log: RecordingLog = RecordingLog()): DeviceSession {
        val gree = GreeConnector(FakeLan(unit), GreeConfig(sweep = false, diagnosticsEveryMs = 300_000, clock = { now }), log)
        return gree.open(gree.discover(net, 500).single())
    }

    private fun statusRequests(unit: FakeGreeUnit): List<List<String>> =
        unit.inner.filter { it.optString("t") == "status" }.map { p ->
            val a = p.getJSONArray("cols")
            List(a.length()) { a.getString(it) }
        }

    private fun extrasRequests(unit: FakeGreeUnit) = statusRequests(unit).count { it == GreeModel.EXTRA_COLUMNS }

    @Test
    fun v3UnitDeclaresAllFour() = runTest {
        val unit = v3()
        val s = open(unit)
        val caps = s.capabilities.associateBy { it.key }
        val lock = caps[GreeKeys.CHILD_LOCK] as Capability.Toggle
        assertEquals("Child lock", lock.label)
        assertTrue(lock.writable)
        for ((key, label) in listOf(
            GreeKeys.DEFROST to "Defrosting", GreeKeys.TIMER_ON to "On timer set", GreeKeys.TIMER_OFF to "Off timer set",
        )) {
            val r = caps[key] as Capability.Reading
            assertEquals(label, r.label)
            assertTrue(key, r.diagnostic)
        }
        val st = s.read()
        assertEquals(false, st.bool(GreeKeys.CHILD_LOCK))
        assertEquals("no", st.text(GreeKeys.DEFROST))
        assertEquals("no", st.text(GreeKeys.TIMER_ON))
        assertEquals("no", st.text(GreeKeys.TIMER_OFF))
    }

    @Test
    fun v121UnitHasNoChildLockButTheReadings() = runTest {
        val unit = v121()
        val s = open(unit)
        val keys = s.capabilities.map { it.key }
        assertFalse(GreeKeys.CHILD_LOCK in keys)
        assertTrue(GreeKeys.DEFROST in keys && GreeKeys.TIMER_ON in keys && GreeKeys.TIMER_OFF in keys)
        val st = s.read()
        assertFalse(GreeKeys.CHILD_LOCK in st.values)
        assertEquals("no", st.text(GreeKeys.DEFROST))
    }

    @Test
    fun childLockIsRefusedBeforeSendingOnAUnitWithout() = runTest {
        val unit = v121()
        val log = RecordingLog()
        val s = open(unit, log)
        try {
            s.apply(listOf(SetValue(GreeKeys.CHILD_LOCK, Value.Bool(true))))
            fail("expected a refusal")
        } catch (e: ConnectorException) {
            assertTrue(e.message!!, e.message!!.contains("has no 'child_lock'"))
        }
        assertTrue(unit.commands.isEmpty())
        assertTrue(log.text.contains("change refused before sending: this unit has no 'child_lock'"))
    }

    @Test
    fun extrasNeverJoinTheMainStatusRequest() = runTest {
        for (unit in listOf(v3(), v121())) {
            val s = open(unit)
            repeat(3) { s.read() }
            now += 301_000
            s.read()
            s.apply(listOf(SetValue(Keys.POWER, Value.Bool(true))))
            val requests = statusRequests(unit)
            val allowed = listOf(
                GreeModel.STATUS_COLUMNS, GreeModel.DIAGNOSTIC_COLUMNS, GreeModel.EXTRA_COLUMNS, GreeModel.PROBE_COLUMNS,
            )
            assertTrue(requests.toString(), requests.all { it in allowed })
            assertTrue(requests.filter { "Pow" in it }.none { cols -> cols.any { it in GreeModel.OPTIONAL_COLUMNS } })
            assertEquals(listOf("ChildLock", "TmrOn", "TmrOff", "Defrost"), GreeModel.EXTRA_COLUMNS)
            assertTrue(GreeModel.STATUS_COLUMNS.none { it in GreeModel.EXTRA_COLUMNS })
        }
    }

    @Test
    fun extrasAreAskedAtOpenThenWithTheDiagnostics() = runTest {
        val unit = v3()
        val s = open(unit)
        assertEquals(1, extrasRequests(unit))                    // at open
        unit.state["Defrost"] = 1
        unit.state["TmrOff"] = 90
        repeat(5) { s.read() }
        assertEquals(1, extrasRequests(unit))                    // not on every poll
        assertEquals("no", s.read().text(GreeKeys.DEFROST))      // still the value from open
        now += 301_000
        val st = s.read()
        assertEquals(2, extrasRequests(unit))                    // together with the diagnostics
        assertEquals("yes", st.text(GreeKeys.DEFROST))
        assertEquals("yes", st.text(GreeKeys.TIMER_OFF))
        assertEquals("no", st.text(GreeKeys.TIMER_ON))
        val diagRequests = statusRequests(unit).count { it == GreeModel.DIAGNOSTIC_COLUMNS }
        assertEquals(2, diagRequests)
    }

    @Test
    fun applyChildLockSendsItsOwnColumnAndReadsItBack() = runTest {
        val unit = v3()
        val s = open(unit)
        val before = extrasRequests(unit)
        val st = s.apply(listOf(SetValue(GreeKeys.CHILD_LOCK, Value.Bool(true))))
        val cmd = unit.commands.single()
        assertEquals("""["ChildLock"]""", cmd.getJSONArray("opt").toString())
        assertEquals("[1]", cmd.getJSONArray("p").toString())
        assertEquals(1, unit.state["ChildLock"])
        assertEquals(true, st.bool(GreeKeys.CHILD_LOCK))         // shown at once, not after 5 minutes
        assertEquals(before + 1, extrasRequests(unit))           // read back in the extras request
        assertEquals(true, s.read().bool(GreeKeys.CHILD_LOCK))   // and it stays

        s.apply(listOf(SetValue(GreeKeys.CHILD_LOCK, Value.Bool(false)), SetValue(Keys.DISPLAY_LIGHT, Value.Bool(false))))
        val second = unit.commands.last()
        assertEquals("""["ChildLock","Lig"]""", second.getJSONArray("opt").toString())
        assertEquals("[0,0]", second.getJSONArray("p").toString())
        assertEquals(false, s.read().bool(GreeKeys.CHILD_LOCK))
    }

    @Test
    fun aUnitSilentToTheExtrasStillOpensAndIsNotAskedAgain() = runTest {
        val unit = v3().apply { silentOnColumns += "ChildLock" }
        val log = RecordingLog()
        val s = open(unit, log)
        val keys = s.capabilities.map { it.key }
        for (k in listOf(GreeKeys.CHILD_LOCK, GreeKeys.DEFROST, GreeKeys.TIMER_ON, GreeKeys.TIMER_OFF)) assertFalse(k, k in keys)
        assertTrue(Keys.POWER in keys && Keys.FAULT in keys)
        val asked = unit.rawRequests.size
        now += 301_000
        assertTrue(s.read().online)
        // One status + one diagnostics request; the extras are not tried again.
        assertEquals(2, unit.rawRequests.size - asked)
        assertTrue(log.text.contains("extra columns not answered"))
    }

    @Test
    fun valueMapping() {
        val d = GreeModel.decode(mapOf("ChildLock" to 1, "TmrOn" to 120, "TmrOff" to 0, "Defrost" to 1))
        assertEquals(Value.Bool(true), d[GreeKeys.CHILD_LOCK])
        assertEquals(Value.Text("yes"), d[GreeKeys.TIMER_ON])
        assertEquals(Value.Text("no"), d[GreeKeys.TIMER_OFF])
        assertEquals(Value.Text("yes"), d[GreeKeys.DEFROST])
        assertEquals(mapOf("ChildLock" to 1), GreeModel.encode(listOf(SetValue(GreeKeys.CHILD_LOCK, Value.Bool(true)))))
        for (k in listOf(GreeKeys.DEFROST, GreeKeys.TIMER_ON, GreeKeys.TIMER_OFF)) {
            try {
                GreeModel.encode(listOf(SetValue(k, Value.Text("no"))))
                fail("$k must be read-only")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("read-only"))
            }
        }
        // Extras are declared only when answered, whatever the main columns say.
        assertFalse(GreeKeys.CHILD_LOCK in GreeModel.capabilities().map { it.key })
        assertEquals(
            listOf(GreeKeys.CHILD_LOCK, GreeKeys.DEFROST),
            GreeModel.capabilities(null, setOf("ChildLock", "Defrost")).map { it.key }.filter { it in listOf(GreeKeys.CHILD_LOCK, GreeKeys.DEFROST, GreeKeys.TIMER_ON, GreeKeys.TIMER_OFF) },
        )
    }
}
