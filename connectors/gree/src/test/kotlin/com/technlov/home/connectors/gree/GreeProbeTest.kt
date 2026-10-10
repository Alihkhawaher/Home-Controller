package com.technlov.home.connectors.gree

import com.technlov.home.core.Capability
import com.technlov.home.core.ConnectorException
import com.technlov.home.core.DeviceSession
import com.technlov.home.core.Keys
import com.technlov.home.core.LocalNetwork
import com.technlov.home.core.SetValue
import com.technlov.home.core.Value
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** AntiDirectBlow, LigSen, OutEnvTem, DwatSen: probed at open in their own small request, shown only when answered. */
class GreeProbeTest {
    private val net = LocalNetwork("192.0.2.255", "192.0.2.")
    private var now = 1_000_000L

    private val probeKeys = mapOf(
        "AntiDirectBlow" to GreeKeys.ANTI_DIRECT_BLOW,
        "LigSen" to GreeKeys.AUTO_DISPLAY_LIGHT,
        "OutEnvTem" to GreeKeys.OUTDOOR_TEMPERATURE,
        "DwatSen" to GreeKeys.HUMIDITY,
    )

    private suspend fun open(unit: FakeGreeUnit, log: RecordingLog = RecordingLog()): DeviceSession {
        val gree = GreeConnector(FakeLan(unit), GreeConfig(sweep = false, diagnosticsEveryMs = 300_000, clock = { now }), log)
        return gree.open(gree.discover(net, 500).single())
    }

    /** Every status request the unit received (silent ones too), as its column list. */
    private fun statusRequests(unit: FakeGreeUnit): List<List<String>> =
        unit.inner.filter { it.optString("t") == "status" }.map { p ->
            val a = p.getJSONArray("cols")
            List(a.length()) { a.getString(it) }
        }

    private fun probeRequests(unit: FakeGreeUnit) = statusRequests(unit).count { it == GreeModel.PROBE_COLUMNS }

    @Test
    fun aUnitAnsweringAllFourShowsThem() = runTest {
        val unit = FakeGreeUnit().withProbeColumns()
        val s = open(unit)
        val caps = s.capabilities.associateBy { it.key }
        assertEquals("Anti direct blow", (caps[GreeKeys.ANTI_DIRECT_BLOW] as Capability.Toggle).label)
        assertEquals("Auto display light", (caps[GreeKeys.AUTO_DISPLAY_LIGHT] as Capability.Toggle).label)
        val outdoor = caps[GreeKeys.OUTDOOR_TEMPERATURE] as Capability.Reading
        assertEquals("Outdoor temperature", outdoor.label)
        assertEquals("°C", outdoor.unit)
        assertFalse(outdoor.diagnostic)
        val humidity = caps[GreeKeys.HUMIDITY] as Capability.Reading
        assertEquals("Humidity", humidity.label)
        assertEquals("%", humidity.unit)
        assertFalse(humidity.diagnostic)
        assertTrue(GreeKeys.CHILD_LOCK in caps && GreeKeys.DEFROST in caps)   // the extras as before

        val st = s.read()
        assertEquals(false, st.bool(GreeKeys.ANTI_DIRECT_BLOW))
        assertEquals(false, st.bool(GreeKeys.AUTO_DISPLAY_LIGHT))             // LigSen 1 = sensor not used
        assertEquals(35.0, st.number(GreeKeys.OUTDOOR_TEMPERATURE)!!, 0.0)    // 75 - 40: TemSen 68 has the offset too
        assertEquals(55.0, st.number(GreeKeys.HUMIDITY)!!, 0.0)
        assertEquals(1, probeRequests(unit))
    }

    @Test
    fun eachProbedColumnIsShownOnlyWhenAnswered() = runTest {
        for ((column, key) in probeKeys) {
            // Left out because the unit has no such value...
            val missing = open(FakeGreeUnit().withProbeColumns(column)).capabilities.map { it.key }
            // ...or because it does not know the name: the same.
            val unknown = open(FakeGreeUnit().withProbeColumns().apply { unknownColumns += column }).capabilities.map { it.key }
            for (keys in listOf(missing, unknown)) {
                assertFalse("$column not answered: $key hidden", key in keys)
                for ((other, otherKey) in probeKeys) {
                    if (other != column) assertTrue("$other answered: $otherKey shown", otherKey in keys)
                }
            }
        }
    }

    @Test
    fun aUnitWithoutThemShowsNoneAndIsNotAskedAgain() = runTest {
        val unit = FakeGreeUnit() // answers the probe, but with none of the four
        val s = open(unit)
        val keys = s.capabilities.map { it.key }
        for (k in probeKeys.values) assertFalse(k, k in keys)
        assertEquals(1, probeRequests(unit))
        now += 301_000
        assertTrue(s.read().online)
        assertEquals(1, probeRequests(unit)) // not polled with the diagnostics
        val values = s.read().values
        assertTrue(probeKeys.values.none { it in values })
    }

    @Test
    fun theyArePolledWithTheDiagnostics() = runTest {
        val unit = FakeGreeUnit().withProbeColumns()
        val s = open(unit)
        unit.state["DwatSen"] = 60
        unit.state["OutEnvTem"] = 88
        repeat(3) { s.read() }
        assertEquals(1, probeRequests(unit))                       // not on every poll
        assertEquals(55.0, s.read().number(GreeKeys.HUMIDITY)!!, 0.0)
        now += 301_000
        val st = s.read()
        assertEquals(2, probeRequests(unit))                       // together with the diagnostics
        assertEquals(60.0, st.number(GreeKeys.HUMIDITY)!!, 0.0)
        assertEquals(48.0, st.number(GreeKeys.OUTDOOR_TEMPERATURE)!!, 0.0)
    }

    @Test
    fun theProbeNeverJoinsAnotherRequest() = runTest {
        val unit = FakeGreeUnit().withProbeColumns()
        val s = open(unit)
        repeat(2) { s.read() }
        now += 301_000
        s.read()
        s.apply(listOf(SetValue(GreeKeys.ANTI_DIRECT_BLOW, Value.Bool(true)), SetValue(Keys.POWER, Value.Bool(true))))
        val allowed = listOf(
            GreeModel.STATUS_COLUMNS, GreeModel.DIAGNOSTIC_COLUMNS, GreeModel.EXTRA_COLUMNS, GreeModel.PROBE_COLUMNS,
        )
        val requests = statusRequests(unit)
        assertTrue(requests.toString(), requests.all { it in allowed })
        assertEquals(listOf("AntiDirectBlow", "LigSen", "OutEnvTem", "DwatSen"), GreeModel.PROBE_COLUMNS)
        assertTrue(GreeModel.STATUS_COLUMNS.none { it in GreeModel.PROBE_COLUMNS })
        assertTrue(GreeModel.EXTRA_COLUMNS.none { it in GreeModel.PROBE_COLUMNS })
        assertTrue(GreeModel.DIAGNOSTIC_COLUMNS.none { it in GreeModel.PROBE_COLUMNS })
    }

    @Test
    fun aModuleSilentToTheProbeKeepsItsExtras() = runTest {
        // V1.2.1-like: leaves ChildLock out, and goes silent to a request naming a column it does not know.
        val unit = FakeGreeUnit(ver = "V1.2.1").apply {
            unknownColumns += "ChildLock"
            silentOnColumns += "OutEnvTem"
        }
        val log = RecordingLog()
        val s = open(unit, log)
        val keys = s.capabilities.map { it.key }
        for (k in probeKeys.values) assertFalse(k, k in keys)
        assertTrue(GreeKeys.DEFROST in keys && GreeKeys.TIMER_ON in keys && GreeKeys.TIMER_OFF in keys)
        assertEquals("no", s.read().text(GreeKeys.DEFROST))
        assertEquals(1, probeRequests(unit))      // one send at open (GreeConfig.probeTries)...
        now += 301_000
        assertTrue(s.read().online)
        assertEquals(1, probeRequests(unit))      // ...and never again
        assertTrue(log.text.contains("newer optional columns not answered"))
    }

    @Test
    fun aModuleSilentToTheExtrasIsNotProbed() = runTest {
        val unit = FakeGreeUnit().withProbeColumns().apply { silentOnColumns += "ChildLock" }
        val s = open(unit)
        assertEquals(0, probeRequests(unit))
        val keys = s.capabilities.map { it.key }
        for (k in probeKeys.values) assertFalse(k, k in keys)
        assertTrue(Keys.POWER in keys && Keys.FAULT in keys)
    }

    @Test
    fun autoDisplayLightEncodesBothWays() {
        val on = GreeModel.encode(listOf(SetValue(GreeKeys.AUTO_DISPLAY_LIGHT, Value.Bool(true))))
        assertEquals(listOf("Lig" to 1, "LigSen" to 0), on.toList())
        val off = GreeModel.encode(listOf(SetValue(GreeKeys.AUTO_DISPLAY_LIGHT, Value.Text("off"))))
        assertEquals(listOf("LigSen" to 1), off.toList())
        // Inverted: LigSen 0 = the light sensor runs the display.
        assertEquals(Value.Bool(true), GreeModel.decode(mapOf("LigSen" to 0))[GreeKeys.AUTO_DISPLAY_LIGHT])
        assertEquals(Value.Bool(false), GreeModel.decode(mapOf("LigSen" to 1))[GreeKeys.AUTO_DISPLAY_LIGHT])
        // A later change to the same column still wins.
        val both = GreeModel.encode(
            listOf(SetValue(GreeKeys.AUTO_DISPLAY_LIGHT, Value.Bool(true)), SetValue(Keys.DISPLAY_LIGHT, Value.Bool(false))),
        )
        assertEquals(listOf("Lig" to 0, "LigSen" to 0), both.toList())
    }

    @Test
    fun autoDisplayLightThroughTheSession() = runTest {
        val unit = FakeGreeUnit().withProbeColumns().apply { state["Lig"] = 0 }
        val s = open(unit)
        val asked = probeRequests(unit)
        val st = s.apply(listOf(SetValue(GreeKeys.AUTO_DISPLAY_LIGHT, Value.Bool(true))))
        val cmd = unit.commands.single()
        assertEquals("""["Lig","LigSen"]""", cmd.getJSONArray("opt").toString())
        assertEquals("[1,0]", cmd.getJSONArray("p").toString())
        assertEquals(true, st.bool(GreeKeys.AUTO_DISPLAY_LIGHT))
        assertEquals(true, st.bool(Keys.DISPLAY_LIGHT))
        assertEquals(asked + 1, probeRequests(unit))             // read back at once, not in 5 minutes

        s.apply(listOf(SetValue(GreeKeys.AUTO_DISPLAY_LIGHT, Value.Bool(false))))
        val off = unit.commands.last()
        assertEquals("""["LigSen"]""", off.getJSONArray("opt").toString())
        assertEquals("[1]", off.getJSONArray("p").toString())
        val after = s.read()
        assertEquals(false, after.bool(GreeKeys.AUTO_DISPLAY_LIGHT))
        assertEquals(true, after.bool(Keys.DISPLAY_LIGHT))      // turning the sensor off leaves the light as it is
    }

    @Test
    fun antiDirectBlowSwitches() = runTest {
        val unit = FakeGreeUnit().withProbeColumns()
        val s = open(unit)
        val st = s.apply(listOf(SetValue(GreeKeys.ANTI_DIRECT_BLOW, Value.Bool(true))))
        val cmd = unit.commands.single()
        assertEquals("""["AntiDirectBlow"]""", cmd.getJSONArray("opt").toString())
        assertEquals("[1]", cmd.getJSONArray("p").toString())
        assertEquals(1, unit.state["AntiDirectBlow"])
        assertEquals(true, st.bool(GreeKeys.ANTI_DIRECT_BLOW))
        assertEquals(true, s.read().bool(GreeKeys.ANTI_DIRECT_BLOW))
    }

    @Test
    fun aUnitWithoutThemRefusesThemBeforeSending() = runTest {
        val unit = FakeGreeUnit()
        val s = open(unit)
        for (k in listOf(GreeKeys.AUTO_DISPLAY_LIGHT, GreeKeys.ANTI_DIRECT_BLOW)) {
            try {
                s.apply(listOf(SetValue(k, Value.Bool(true))))
                fail("expected a refusal for $k")
            } catch (e: ConnectorException) {
                assertTrue(e.message!!, e.message!!.contains("has no '$k'"))
            }
        }
        assertTrue(unit.commands.isEmpty())
    }

    @Test
    fun outdoorTemperatureTakesTheOffsetFromTheRoomSensor() {
        // TemSen with the +40 offset (68 = 28 C): OutEnvTem has it too, also below 40 (frost outside).
        assertEquals(-10.0, GreeModel.outdoorTemperature(30.0, 68.0)!!, 0.0)
        assertEquals(0.0, GreeModel.outdoorTemperature(40.0, 68.0)!!, 0.0)
        assertEquals(45.0, GreeModel.outdoorTemperature(85.0, 68.0)!!, 0.0)
        // TemSen without it (24 = 24 C): OutEnvTem is degrees C as sent, also above 40 (a hot summer day).
        assertEquals(46.0, GreeModel.outdoorTemperature(46.0, 24.0)!!, 0.0)
        assertEquals(5.0, GreeModel.outdoorTemperature(5.0, 24.0)!!, 0.0)
        // No room reading: TemSen's rule on the value itself.
        assertEquals(35.0, GreeModel.outdoorTemperature(75.0, null)!!, 0.0)
        assertEquals(30.0, GreeModel.outdoorTemperature(30.0, 0.0)!!, 0.0)
        // 0 = no reading.
        assertNull(GreeModel.outdoorTemperature(0.0, 68.0))
        // Through decode, with the room sensor in the same values.
        assertEquals(Value.Num(-5.0), GreeModel.decode(mapOf("TemSen" to 65, "OutEnvTem" to 35))[GreeKeys.OUTDOOR_TEMPERATURE])
        assertEquals(Value.Num(46.0), GreeModel.decode(mapOf("TemSen" to 30, "OutEnvTem" to 46))[GreeKeys.OUTDOOR_TEMPERATURE])
        assertEquals(Value.Num(6.0), GreeModel.decode(mapOf("OutEnvTem" to 46))[GreeKeys.OUTDOOR_TEMPERATURE])
        assertFalse(GreeKeys.OUTDOOR_TEMPERATURE in GreeModel.decode(mapOf("TemSen" to 68, "OutEnvTem" to 0)))
    }

    @Test
    fun outdoorTemperatureOnAModuleWithoutTheOffset() = runTest {
        val unit = FakeGreeUnit().withProbeColumns().apply {
            state["TemSen"] = 26
            state["OutEnvTem"] = 47
        }
        val st = open(unit).read()
        assertEquals(26.0, st.number(Keys.CURRENT_TEMPERATURE)!!, 0.0)
        assertEquals(47.0, st.number(GreeKeys.OUTDOOR_TEMPERATURE)!!, 0.0)
    }

    @Test
    fun humidityAndReadOnlyReadings() {
        assertEquals(Value.Num(55.0), GreeModel.decode(mapOf("DwatSen" to 55))[GreeKeys.HUMIDITY])
        assertFalse(GreeKeys.HUMIDITY in GreeModel.decode(mapOf("DwatSen" to 0)))     // no sensor
        assertFalse(GreeKeys.HUMIDITY in GreeModel.decode(mapOf("DwatSen" to 255)))   // not a percentage
        for (k in listOf(GreeKeys.OUTDOOR_TEMPERATURE, GreeKeys.HUMIDITY)) {
            assertThrows(k, IllegalArgumentException::class.java) { GreeModel.encode(listOf(SetValue(k, Value.Num(20.0)))) }
        }
        assertEquals(mapOf("AntiDirectBlow" to 0), GreeModel.encode(listOf(SetValue(GreeKeys.ANTI_DIRECT_BLOW, Value.Bool(false)))))
    }

    @Test
    fun declaredOnlyWhenAnswered() {
        val all = GreeModel.capabilities().map { it.key }
        for (k in probeKeys.values) assertFalse(k, k in all)
        val some = GreeModel.capabilities(null, setOf("LigSen", "DwatSen")).map { it.key }
        assertTrue(GreeKeys.AUTO_DISPLAY_LIGHT in some && GreeKeys.HUMIDITY in some)
        assertFalse(GreeKeys.ANTI_DIRECT_BLOW in some || GreeKeys.OUTDOOR_TEMPERATURE in some)
    }
}
