package com.technlov.home.connectors.gree

import com.technlov.home.core.Capability
import com.technlov.home.core.Keys
import com.technlov.home.core.SetValue
import com.technlov.home.core.Value
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GreeModelTest {
    private fun decode(vararg raw: Pair<String, Any?>) = GreeModel.decode(raw.toMap())

    @Test
    fun roomTemperatureOffset() {
        assertEquals(28.0, GreeModel.roomTemperature(68.0)!!, 0.0)
        assertEquals(0.0, GreeModel.roomTemperature(40.0)!!, 0.0)
        assertEquals(25.0, GreeModel.roomTemperature(25.0)!!, 0.0)   // already degrees C
        assertEquals(39.0, GreeModel.roomTemperature(39.0)!!, 0.0)
        assertNull(GreeModel.roomTemperature(0.0))                   // no reading
        assertEquals(Value.Num(28.0), decode("TemSen" to 68)[Keys.CURRENT_TEMPERATURE])
        assertFalse(Keys.CURRENT_TEMPERATURE in decode("TemSen" to 0))
    }

    @Test
    fun modesAndFanSpeeds() {
        val modes = listOf("auto", "cool", "dry", "fan", "heat")
        modes.forEachIndexed { code, name -> assertEquals(Value.Text(name), decode("Mod" to code)[Keys.MODE]) }
        assertEquals(Value.Text("7"), decode("Mod" to 7)[Keys.MODE])
        val fans = listOf("auto", "low", "medium_low", "medium", "medium_high", "high")
        fans.forEachIndexed { code, name -> assertEquals(Value.Text(name), decode("WdSpd" to code)[Keys.FAN_SPEED]) }
    }

    @Test
    fun swingValuesAndLabels() {
        assertEquals(Value.Text("default"), decode("SwUpDn" to 0)[Keys.SWING_VERTICAL])
        assertEquals(Value.Text("full_swing"), decode("SwUpDn" to 1)[Keys.SWING_VERTICAL])
        assertEquals(Value.Text("fixed_upper"), decode("SwUpDn" to 2)[Keys.SWING_VERTICAL])
        assertEquals(Value.Text("fixed_lower"), decode("SwUpDn" to 6)[Keys.SWING_VERTICAL])
        assertEquals(Value.Text("swing_upper"), decode("SwUpDn" to 11)[Keys.SWING_VERTICAL])
        assertEquals(Value.Text("centre"), decode("SwingLfRig" to 4)[Keys.SWING_HORIZONTAL])
        assertEquals(Value.Text("right"), decode("SwingLfRig" to 6)[Keys.SWING_HORIZONTAL])

        val caps = GreeModel.capabilities().associateBy { it.key }
        val vertical = caps[Keys.SWING_VERTICAL] as Capability.Choice
        assertEquals(12, vertical.options.size)
        assertEquals("Fixed upper-middle", vertical.options[3].label)
        assertEquals("Swing lower", vertical.options[7].label)
        val horizontal = caps[Keys.SWING_HORIZONTAL] as Capability.Choice
        assertEquals(listOf("Default", "Full swing", "Left", "Left-centre", "Centre", "Right-centre", "Right"), horizontal.options.map { it.label })
    }

    @Test
    fun togglesAndDiagnostics() {
        val d = decode(
            "Pow" to 1, "Lig" to 0, "Quiet" to 2, "SwhSlp" to 1, "Health" to 1, "Blo" to 0, "Tur" to 1,
            "AllErr" to 0, "Dfltr" to 1, "hid" to "TEST05RT13V1.00", "time" to "2026-10-10 12:00:00",
        )
        assertEquals(Value.Bool(true), d[Keys.POWER])
        assertEquals(Value.Bool(false), d[Keys.DISPLAY_LIGHT])
        assertEquals(Value.Bool(true), d[GreeKeys.QUIET])
        assertEquals(Value.Bool(true), d[GreeKeys.SLEEP])
        assertEquals(Value.Bool(true), d[GreeKeys.HEALTH])
        assertEquals(Value.Bool(false), d[GreeKeys.X_FAN])
        assertEquals(Value.Bool(true), d[GreeKeys.TURBO])
        assertEquals(Value.Text("none"), d[Keys.FAULT])
        assertEquals(Value.Bool(true), d[GreeKeys.CLEAN_FILTER])
        assertEquals(Value.Text("TEST05RT13V1.00"), d[Keys.FIRMWARE])
        assertEquals(Value.Text("2026-10-10 12:00:00"), d[GreeKeys.CLOCK])
        assertEquals(Value.Text("code 5"), decode("AllErr" to 5)[Keys.FAULT])
    }

    @Test
    fun moduleBuildFromHid() {
        // Shapes seen on real modules (digits made up).
        assertEquals("WB05RT13V1.09", GreeModel.moduleBuild("123456789012+U-WB05RT13V1.09.bin"))
        assertEquals("CS532Z(LT)V3.56", GreeModel.moduleBuild("CS532Z(LT)V3.56"))
        assertEquals(null, GreeModel.moduleBuild("  "))
        assertEquals(Value.Text("WB05RT13V1.12"), decode("hid" to "123456789012+U-WB05RT13V1.12.bin")[Keys.FIRMWARE])
    }

    @Test
    fun capabilitiesAndTheirKinds() {
        val caps = GreeModel.capabilities()
        assertEquals(
            listOf(
                Keys.POWER, Keys.MODE, Keys.TARGET_TEMPERATURE, Keys.CURRENT_TEMPERATURE, Keys.FAN_SPEED,
                Keys.SWING_VERTICAL, Keys.SWING_HORIZONTAL, Keys.DISPLAY_LIGHT, GreeKeys.TURBO, GreeKeys.QUIET,
                GreeKeys.SLEEP, GreeKeys.HEALTH, GreeKeys.X_FAN, GreeKeys.FRESH_AIR, GreeKeys.STEADY_HEAT,
                GreeKeys.ENERGY_SAVING, Keys.FAULT, Keys.FIRMWARE, GreeKeys.CLEAN_FILTER, GreeKeys.CLOCK,
            ),
            caps.map { it.key },
        )
        val byKey = caps.associateBy { it.key }
        assertTrue(byKey[Keys.POWER] is Capability.Power)
        val target = byKey[Keys.TARGET_TEMPERATURE] as Capability.Number
        assertEquals(16.0, target.min, 0.0)
        assertEquals(30.0, target.max, 0.0)
        assertEquals(1.0, target.step, 0.0)
        assertEquals("°C", target.unit)
        val room = byKey[Keys.CURRENT_TEMPERATURE] as Capability.Reading
        assertFalse(room.diagnostic)
        assertEquals("Ioniser", byKey[GreeKeys.HEALTH]!!.label)
        assertEquals("X-Fan", byKey[GreeKeys.X_FAN]!!.label)
        assertEquals("Fresh air", byKey[GreeKeys.FRESH_AIR]!!.label)
        assertEquals("8 °C frost protection", byKey[GreeKeys.STEADY_HEAT]!!.label)
        assertTrue(byKey[GreeKeys.ENERGY_SAVING] is Capability.Toggle)
        for (k in listOf(Keys.FAULT, Keys.FIRMWARE, GreeKeys.CLEAN_FILTER, GreeKeys.CLOCK)) {
            assertTrue(k, (byKey[k] as Capability.Reading).diagnostic)
        }
        assertEquals(listOf("auto", "cool", "dry", "fan", "heat"), (byKey[Keys.MODE] as Capability.Choice).options.map { it.value })
    }

    @Test
    fun capabilitiesFollowTheAnsweredColumns() {
        val answered = GreeModel.STATUS_COLUMNS.toSet() - "SwingLfRig" - "Quiet" + GreeModel.DIAGNOSTIC_COLUMNS
        val keys = GreeModel.capabilities(answered).map { it.key }
        assertFalse(Keys.SWING_HORIZONTAL in keys)
        assertFalse(GreeKeys.QUIET in keys)
        assertTrue(Keys.SWING_VERTICAL in keys)
        assertEquals(20, GreeModel.capabilities(emptySet()).size)
        // a unit without fresh air / frost protection / energy saving does not show those switches
        val basic = GreeModel.capabilities(answered - "Air" - "StHt" - "SvSt").map { it.key }
        assertFalse(GreeKeys.FRESH_AIR in basic)
        assertFalse(GreeKeys.STEADY_HEAT in basic)
        assertFalse(GreeKeys.ENERGY_SAVING in basic)
        assertTrue(GreeKeys.X_FAN in basic)
    }

    @Test
    fun encodeTemperatureSetsUnitAndRecord() {
        val cols = GreeModel.encode(listOf(SetValue(Keys.POWER, Value.Bool(true)), SetValue(Keys.TARGET_TEMPERATURE, Value.Num(24.0))))
        assertEquals(listOf("Pow", "SetTem", "TemUn", "TemRec"), cols.keys.toList())
        assertEquals(listOf(1, 24, 0, 0), cols.values.toList())
        assertEquals(24, GreeModel.encode(listOf(SetValue(Keys.TARGET_TEMPERATURE, Value.Num(23.6))))["SetTem"])
        assertEquals(16, GreeModel.encode(listOf(SetValue(Keys.TARGET_TEMPERATURE, Value.Text("16"))))["SetTem"])
        assertThrows(IllegalArgumentException::class.java) {
            GreeModel.encode(listOf(SetValue(Keys.TARGET_TEMPERATURE, Value.Num(31.0))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GreeModel.encode(listOf(SetValue(Keys.TARGET_TEMPERATURE, Value.Num(15.0))))
        }
    }

    @Test
    fun encodeChoicesByValueLabelOrCode() {
        assertEquals(4, GreeModel.encode(listOf(SetValue(Keys.MODE, Value.Text("heat"))))["Mod"])
        assertEquals(1, GreeModel.encode(listOf(SetValue(Keys.MODE, Value.Text("Cool"))))["Mod"])
        assertEquals(3, GreeModel.encode(listOf(SetValue(Keys.MODE, Value.Num(3.0))))["Mod"])
        assertEquals(4, GreeModel.encode(listOf(SetValue(Keys.FAN_SPEED, Value.Text("medium_high"))))["WdSpd"])
        assertEquals(4, GreeModel.encode(listOf(SetValue(Keys.FAN_SPEED, Value.Text("Medium-high"))))["WdSpd"])
        assertEquals(9, GreeModel.encode(listOf(SetValue(Keys.SWING_VERTICAL, Value.Text("swing_middle"))))["SwUpDn"])
        assertEquals(1, GreeModel.encode(listOf(SetValue(Keys.SWING_HORIZONTAL, Value.Text("full_swing"))))["SwingLfRig"])
        assertThrows(IllegalArgumentException::class.java) { GreeModel.encode(listOf(SetValue(Keys.MODE, Value.Text("turbo")))) }
        assertThrows(IllegalArgumentException::class.java) { GreeModel.encode(listOf(SetValue(Keys.MODE, Value.Num(5.0)))) }
        assertThrows(IllegalArgumentException::class.java) { GreeModel.encode(listOf(SetValue(Keys.MODE, Value.Num(1.5)))) }
        assertThrows(IllegalArgumentException::class.java) { GreeModel.encode(listOf(SetValue(Keys.SWING_HORIZONTAL, Value.Num(7.0)))) }
    }

    @Test
    fun encodeToggles() {
        assertEquals(mapOf("Quiet" to 2), GreeModel.encode(listOf(SetValue(GreeKeys.QUIET, Value.Bool(true)))))
        assertEquals(mapOf("Quiet" to 0), GreeModel.encode(listOf(SetValue(GreeKeys.QUIET, Value.Bool(false)))))
        assertEquals(mapOf("SwhSlp" to 1, "SlpMod" to 1), GreeModel.encode(listOf(SetValue(GreeKeys.SLEEP, Value.Bool(true)))))
        assertEquals(mapOf("Lig" to 0), GreeModel.encode(listOf(SetValue(Keys.DISPLAY_LIGHT, Value.Text("off")))))
        assertEquals(mapOf("Health" to 1), GreeModel.encode(listOf(SetValue(GreeKeys.HEALTH, Value.Num(1.0)))))
        assertEquals(mapOf("Blo" to 1, "Tur" to 0), GreeModel.encode(listOf(SetValue(GreeKeys.X_FAN, Value.Bool(true)), SetValue(GreeKeys.TURBO, Value.Bool(false)))))
        assertThrows(IllegalArgumentException::class.java) { GreeModel.encode(listOf(SetValue(Keys.POWER, Value.Text("maybe")))) }
    }

    @Test
    fun laterChangeToTheSameColumnWins() {
        val cols = GreeModel.encode(listOf(SetValue(Keys.POWER, Value.Bool(true)), SetValue(Keys.POWER, Value.Bool(false))))
        assertEquals(mapOf("Pow" to 0), cols)
    }

    @Test
    fun readOnlyAndUnknownKeysAreRefused() {
        for (k in listOf(Keys.CURRENT_TEMPERATURE, Keys.FAULT, Keys.FIRMWARE, GreeKeys.CLEAN_FILTER, GreeKeys.CLOCK, "volume")) {
            assertThrows(k, IllegalArgumentException::class.java) { GreeModel.encode(listOf(SetValue(k, Value.Num(1.0)))) }
        }
    }
}
