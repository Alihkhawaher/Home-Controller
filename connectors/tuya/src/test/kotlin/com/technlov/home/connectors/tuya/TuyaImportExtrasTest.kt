package com.technlov.home.connectors.tuya

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The optional 7th element of an import row carries what a device actually accepts: options for a choice, a range
 * for a number. Without it, known codes (an alarm host's arm modes) still get their built-in options.
 */
class TuyaImportExtrasTest {
    private fun spec(json: String) = TuyaModel.parseDpSpec(json)

    @Test
    fun alarmModesCanBeChosenEvenWithoutImportedOptions() {
        val s = spec("""[[1,"master_mode","enum",0,"",true]]""").getValue(1)
        assertEquals(TuyaModel.Kind.CHOICE, s.kind)
        assertEquals(listOf("disarmed", "arm", "home", "sos"), s.options.map { it.value })
        assertEquals("Armed (away)", s.options[1].label)
        assertTrue(s.writable)
    }

    @Test
    fun importedOptionsAreTheDevicesOwnValues() {
        // the cloud says power_off/power_on/last; this plug answers off/on/memory on the LAN
        val s = spec("""[[38,"relay_status","enum",0,"",true,{"options":[["memory","Remember last state"],["off","Off"],["on","On"]]}]]""")
            .getValue(38)
        assertEquals(listOf("memory", "off", "on"), s.options.map { it.value })
        assertEquals("Remember last", s.options[0].label)                  // a known value keeps the built-in label
    }

    @Test
    fun anUnknownEnumTakesTheImportedLabels() {
        val s = spec("""[[7,"mode_x","enum",0,"",true,{"options":[["eco","Eco"],["boost","Boost"]]}]]""").getValue(7)
        assertEquals(listOf("Eco", "Boost"), s.options.map { it.label })
    }

    @Test
    fun importedRangesWin() {
        val s = spec("""[[2,"delay_set","int",0,"s",true,{"min":0.0,"max":1000.0,"step":5.0}]]""").getValue(2)
        assertEquals(1000.0, s.max, 0.0)
        assertEquals(5.0, s.step, 0.0)
        val bare = spec("""[[2,"delay_set","int",0,"s",true]]""").getValue(2)
        assertEquals(300.0, bare.max, 0.0)                                // built-in default without an import range
    }

    @Test
    fun theEnergyPulseIsADiagnosticNotATotal() {
        val s = spec("""[[17,"add_ele","int",3,"",false]]""").getValue(17)
        assertTrue(s.diagnostic)
        assertTrue(s.label.startsWith("Energy pulse"))
    }

    @Test
    fun oldSixElementRowsStillParse() {
        val s = spec("""[[9,"countdown_1","int",0,"s",true]]""").getValue(9)
        assertEquals(86_400.0, s.max, 0.0)
    }
}
