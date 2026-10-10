package com.technlov.homecontroller.history

import com.technlov.home.core.Capability
import com.technlov.home.core.DeviceState
import com.technlov.home.core.Value
import com.technlov.homecontroller.history.HistoryMath.HOUR
import com.technlov.homecontroller.history.HistoryMath.MINUTE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryMathTest {
    private val plug = listOf(
        Capability.Power("power", "Power"),
        Capability.Reading("cur_power", "Power", unit = "W"),
        Capability.Reading("cur_voltage", "Voltage", unit = "V"),
        Capability.Reading("cur_current", "Current", unit = "mA"),
        Capability.Reading("fault", "Fault", unit = ""),
    )
    private val meter = listOf(
        Capability.Reading("power_a", "Power A", unit = "W"),
        Capability.Reading("power_total", "Power", unit = "W"),
        Capability.Reading("energy_produced_total", "Produced", unit = "kWh"),
        Capability.Reading("energy_total", "Energy", unit = "kWh"),
        Capability.Reading("frequency", "Frequency", unit = "Hz"),
    )

    @Test
    fun planPicksElectricalReadingsAndTheMainPowerAndCounter() {
        val p = HistoryMath.plan(plug)
        assertEquals(setOf("cur_power", "cur_voltage", "cur_current"), p.metrics.keys)
        assertEquals("cur_power", p.powerKey)
        assertNull("a plug has no cumulative counter", p.energyCounterKey)
        val m = HistoryMath.plan(meter)
        assertEquals("power_total", m.powerKey)                       // the total, not phase A
        assertEquals("energy_total", m.energyCounterKey)              // not the produced counter
        assertEquals(false, "frequency" in m.metrics)
    }

    @Test
    fun valuesKeepsNumbersOfThePlanOnly() {
        val p = HistoryMath.plan(plug)
        val state = DeviceState(mapOf("cur_power" to Value.Num(264.4), "power" to Value.Bool(true), "fault" to Value.Text("none")))
        assertEquals(mapOf("cur_power" to 264.4), HistoryMath.values(p, state))
    }

    @Test
    fun integratesASteadyKilowattForAnHour() {
        val s = (0..60).map { Sample(it * MINUTE, 1_000.0) }
        assertEquals(1.0, HistoryMath.integrateKwh(s), 1e-9)          // 1 kW for 1 h = 1 kWh
    }

    @Test
    fun skipsGapsInsteadOfGuessing() {
        val s = listOf(Sample(0, 1_000.0), Sample(MINUTE, 1_000.0), Sample(2 * HOUR, 1_000.0), Sample(2 * HOUR + MINUTE, 1_000.0))
        // two 1-minute steps counted (2/60 kWh); the 2-hour gap is not
        assertEquals(2.0 / 60.0, HistoryMath.integrateKwh(s), 1e-9)
    }

    @Test
    fun counterEnergyIsExactAcrossGapsAndSurvivesAReset() {
        val s = listOf(Sample(0, 100.0), Sample(5 * HOUR, 103.5), Sample(6 * HOUR, 0.2), Sample(7 * HOUR, 1.2))
        assertEquals(3.5 + 1.0, HistoryMath.counterKwh(s), 1e-9)      // the drop to 0.2 is a reset, not -103 kWh
    }

    @Test
    fun hoursAndDailyEnergyFromPowerIntegration() {
        val s = (0..120).map { Sample(it * MINUTE, 500.0) }          // 0.5 kW for 2 h
        val h = HistoryMath.hours(s, integrate = true)
        assertEquals(3, h.size)                                       // 00:00-, 01:00-, and the 02:00 sample
        assertEquals(0.5, h[0].kwh!!, 1e-9)
        val day = HistoryMath.dailyKwh(h, counter = false) { 0L }
        assertEquals(1.0, day.getValue(0L), 1e-9)
    }

    @Test
    fun dailyEnergyFromACounterCountsGapEnergyInTheNextHour() {
        val hours = listOf(
            HourStat(0, 10.0, 10.0, 10.0, 60),
            HourStat(HOUR, 11.0, 10.5, 11.0, 60),
            HourStat(5 * HOUR, 14.0, 13.0, 14.0, 60),               // hours 2-4 missing
        )
        val day = HistoryMath.dailyKwh(hours, counter = true) { 0L }
        assertEquals(4.0, day.getValue(0L), 1e-9)                     // 14 - 10, exact despite the gap
    }
}
