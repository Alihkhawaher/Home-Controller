package com.technlov.home.connectors.tuya

import com.technlov.home.core.Capability
import com.technlov.home.core.Keys
import com.technlov.home.core.SetValue
import com.technlov.home.core.Value
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TuyaModelTest {
    private val cz = TuyaModel.points("cz", null, emptyMap())
    private val owon = TuyaModel.points("zndb", "9rjjf6rdkca9woju", emptyMap())

    private fun num(m: Map<String, Value>, k: String) = (m[k] as Value.Num).v
    private fun txt(m: Map<String, Value>, k: String) = (m[k] as Value.Text).v
    private fun bool(m: Map<String, Value>, k: String) = (m[k] as Value.Bool).v

    // ---- metering plug (cz) ----

    @Test
    fun czPlugDecodesPowerMonitoringWithScales() {
        val d = TuyaModel.decode(
            mapOf("1" to true, "9" to 0, "17" to 1234, "18" to 270, "19" to 1033, "20" to 2301, "26" to 0, "38" to "memory", "40" to false),
            cz,
        )
        assertEquals(true, bool(d, Keys.POWER))
        assertEquals(103.3, num(d, TuyaKeys.POWER_W), 1e-9)   // dp19 /10 = W
        assertEquals(230.1, num(d, TuyaKeys.VOLTAGE), 1e-9)   // dp20 /10 = V
        assertEquals(270.0, num(d, TuyaKeys.CURRENT), 1e-9)   // dp18 mA (no scale)
        assertEquals(1.234, num(d, TuyaKeys.ENERGY), 1e-9)    // dp17 /1000 = kWh
        assertEquals("none", txt(d, Keys.FAULT))
        assertEquals("memory", txt(d, TuyaKeys.RELAY_STATUS))
        assertEquals(false, bool(d, TuyaKeys.CHILD_LOCK))
    }

    @Test
    fun czPlugCapabilitiesPutPowerMonitoringUpFront() {
        val caps = TuyaModel.capabilities(setOf(1, 9, 17, 18, 19, 20, 26, 38, 39, 40), cz)
        val keys = caps.map { it.key }
        assertEquals(Keys.POWER, keys.first())
        assertTrue(keys.indexOf(TuyaKeys.POWER_W) < keys.indexOf(TuyaKeys.CHILD_LOCK))
        val byKey = caps.associateBy { it.key }
        assertTrue(byKey[Keys.POWER] is Capability.Power)
        val power = byKey[TuyaKeys.POWER_W] as Capability.Reading
        assertEquals("W", power.unit)
        assertFalse(power.diagnostic)
        val relay = byKey[TuyaKeys.RELAY_STATUS] as Capability.Choice
        assertEquals(listOf("off", "on", "memory"), relay.options.map { it.value })
        val lock = byKey[TuyaKeys.CHILD_LOCK] as Capability.Toggle
        assertTrue(lock.writable)
        val countdown = byKey[TuyaKeys.COUNTDOWN] as Capability.Number
        assertEquals("s", countdown.unit)
        assertTrue(countdown.writable)
        assertTrue((byKey[Keys.FAULT] as Capability.Reading).diagnostic)
    }

    @Test
    fun czPlugEncodesControlsAndRefusesTheRest() {
        assertEquals(mapOf("1" to false), TuyaModel.encode(listOf(SetValue(Keys.POWER, Value.Bool(false))), cz))
        assertEquals(mapOf("9" to 60), TuyaModel.encode(listOf(SetValue(TuyaKeys.COUNTDOWN, Value.Num(60.0))), cz))
        assertEquals(mapOf("40" to true), TuyaModel.encode(listOf(SetValue(TuyaKeys.CHILD_LOCK, Value.Bool(true))), cz))
        assertEquals(mapOf("38" to "off"), TuyaModel.encode(listOf(SetValue(TuyaKeys.RELAY_STATUS, Value.Text("off"))), cz))
        // read-only, unknown and invalid-choice all refused before sending
        assertThrows(IllegalArgumentException::class.java) { TuyaModel.encode(listOf(SetValue(TuyaKeys.POWER_W, Value.Num(1.0))), cz) }
        assertThrows(IllegalArgumentException::class.java) { TuyaModel.encode(listOf(SetValue("nonsense", Value.Bool(true))), cz) }
        assertThrows(IllegalArgumentException::class.java) { TuyaModel.encode(listOf(SetValue(TuyaKeys.RELAY_STATUS, Value.Text("boom"))), cz) }
        assertThrows(IllegalArgumentException::class.java) { TuyaModel.encode(listOf(SetValue(TuyaKeys.COUNTDOWN, Value.Num(99999.0))), cz) }
    }

    @Test
    fun completeViewShowsEveryDpEvenUnmapped() {
        val d = TuyaModel.decode(mapOf("1" to true, "99" to 123, "100" to "custom"), cz)
        assertEquals(true, bool(d, Keys.POWER))
        assertEquals(123.0, num(d, "dp_99"), 0.0)
        assertEquals("custom", txt(d, "dp_100"))
        val caps = TuyaModel.capabilities(setOf(1, 99), cz).map { it.key }
        assertTrue("dp_99" in caps)
    }

    // ---- energy meter (zndb / OWON PC321-TY, per-product decoder) ----

    @Test
    fun owonMeterDecodesThreePhasesAndTotals() {
        val d = TuyaModel.decode(
            mapOf(
                "101" to 1270, "102" to 3600, "103" to 457, "104" to 98, "106" to 1234,
                "131" to 5678, "132" to 28800, "133" to 3600, "135" to 60, "136" to 385, "139" to 12,
                "137" to 7, "138" to 9, // unknown -> shown raw
            ),
            owon,
        )
        assertEquals(127.0, num(d, "phase_a_voltage"), 1e-9) // /10
        assertEquals(3.6, num(d, "phase_a_current"), 1e-9)   // /1000
        assertEquals(457.0, num(d, "phase_a_power"), 1e-9)   // raw W
        assertEquals(0.98, num(d, "phase_a_power_factor"), 1e-9) // /100
        assertEquals(12.34, num(d, "phase_a_energy"), 1e-9)  // /100
        assertEquals(56.78, num(d, "energy_total"), 1e-9)
        assertEquals(28.8, num(d, "current_total"), 1e-9)
        assertEquals(3600.0, num(d, "power_total"), 1e-9)    // ~3.6 kW
        assertEquals(60.0, num(d, "frequency"), 1e-9)        // raw Hz
        assertEquals(38.5, num(d, "temperature"), 1e-9)      // /10
        assertEquals(0.12, num(d, "energy_produced_total"), 1e-9)
        assertEquals(7.0, num(d, "dp_137"), 0.0)
        assertEquals(9.0, num(d, "dp_138"), 0.0)
    }

    @Test
    fun owonMeterCapabilitiesAreReadOnly() {
        val caps = TuyaModel.capabilities(setOf(101, 103, 133, 135, 136), owon)
        assertTrue(caps.all { it is Capability.Reading })
        val byKey = caps.associateBy { it.key }
        assertEquals("W", (byKey["power_total"] as Capability.Reading).unit)
        assertFalse((byKey["power_total"] as Capability.Reading).diagnostic)
        assertTrue((byKey["temperature"] as Capability.Reading).diagnostic)
        // Not writable: a meter refuses any set.
        assertThrows(IllegalArgumentException::class.java) { TuyaModel.encode(listOf(SetValue("power_total", Value.Num(1.0))), owon) }
    }

    @Test
    fun owonProductIdsAllMapToTheSameDecoder() {
        for (pid in listOf("9rjjf6rdkca9woju", "r64livttuufctble", "gqmmtjclqb7reg5p")) {
            val p = TuyaModel.points("zndb", pid, emptyMap())
            assertEquals("phase_a_voltage", p[101]!!.code)
        }
    }

    // ---- local-strategy override and imported spec ----

    @Test
    fun dpMapOverrideAddsAndWins() {
        val points = TuyaModel.points("cz", null, TuyaModel.parseDpMapOverride("""{"5":"cur_power"}"""))
        val d = TuyaModel.decode(mapOf("5" to 500), points)
        assertEquals(50.0, num(d, TuyaKeys.POWER_W), 1e-9)
    }

    @Test
    fun theClampsReadingsAreRefreshedUnderEitherNaming() {
        val owon = listOf(101, 102, 103, 104, 106, 111, 112, 113, 121, 122, 123, 131, 132, 133, 135, 136)
        val wanted = listOf(101, 102, 103, 111, 112, 113, 121, 122, 123, 132, 133)
        // As the import QR names them (tuya-local's codes)...
        val imported = TuyaModel.parseDpSpec(
            listOf("a" to 100, "b" to 110, "c" to 120).flatMap { (p, base) ->
                listOf("""[${base + 1},"voltage_$p","int",1,"V",false]""", """[${base + 2},"current_$p","int",3,"A",false]""",
                    """[${base + 3},"power_$p","int",0,"W",false]""", """[${base + 4},"power_factor_$p","int",2,"",false]""",
                    """[${base + 6},"energy_$p","int",2,"kWh",false]""")
            }.plus(listOf("""[131,"energy_total","int",2,"kWh",false]""", """[132,"current_total","int",3,"A",false]""",
                """[133,"power_total","int",0,"W",false]""", """[135,"frequency","int",0,"Hz",false]""",
                """[136,"temperature","int",1,"°C",false]"""))
                .joinToString(",", "[", "]"),
        )
        assertEquals(wanted, TuyaModel.refreshIds(owon.toSet(), TuyaModel.points(null, null, emptyMap(), imported)))
        // ...and as the built-in map does.
        val builtIn = TuyaModel.points(null, "9rjjf6rdkca9woju", emptyMap(), emptyMap())
        assertEquals(wanted, TuyaModel.refreshIds(owon.toSet(), builtIn))
    }

    @Test
    fun importedSpecDrivesDecodeAndEncode() {
        val spec = TuyaModel.parseDpSpec(
            """[[1,"switch_1","bool",0,"",true],[19,"cur_power","int",1,"W",false],[101,"phase_a_voltage","int",1,"V",false]]""",
        )
        val points = TuyaModel.points(null, null, emptyMap(), spec)
        val d = TuyaModel.decode(mapOf("1" to true, "19" to 1033, "101" to 1270), points)
        assertEquals(true, bool(d, Keys.POWER))
        assertEquals(103.3, num(d, TuyaKeys.POWER_W), 1e-9)
        assertEquals(127.0, num(d, "phase_a_voltage"), 1e-9) // scale 1 == /10
        assertEquals(mapOf("1" to true), TuyaModel.encode(listOf(SetValue(Keys.POWER, Value.Bool(true))), points))
        assertThrows(IllegalArgumentException::class.java) { TuyaModel.encode(listOf(SetValue(TuyaKeys.POWER_W, Value.Num(1.0))), points) }
    }
}
