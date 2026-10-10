package com.technlov.homecontroller.history

import com.technlov.homecontroller.history.HistoryMath.DAY
import com.technlov.homecontroller.history.HistoryMath.HOUR
import com.technlov.homecontroller.history.HistoryMath.MINUTE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** The card's numbers, from fake storage. UTC so day boundaries are easy to reason about. */
class HistoryQueryTest {
    private val zone = ZoneOffset.UTC
    private val day0 = 20_000 * DAY                                  // a midnight, UTC, mid-month (1 Oct 2024)
    private val plugPlan = HistoryPlan(mapOf("cur_power" to "W"), powerKey = "cur_power", energyCounterKey = null)

    /** A plug drawing [watts] from midnight to [until], sampled each minute, already rolled up for past hours. */
    private fun plugStorage(watts: Double, until: Long): Pair<(String, Long, Long) -> List<Sample>, (String, Long, Long) -> List<HourStat>> {
        val samples = generateSequence(day0) { it + MINUTE }.takeWhile { it <= until }.map { Sample(it, watts) }.toList()
        val minutes = { _: String, f: Long, t: Long -> samples.filter { it.atMillis in f until t } }
        val hours = { _: String, f: Long, t: Long ->
            HistoryMath.hours(samples, integrate = true).filter { it.hourMillis in f until t }
        }
        return minutes to hours
    }

    @Test
    fun aDayOfASteadyPlug() {
        val now = day0 + 10 * HOUR + 30 * MINUTE                    // 10:30
        val (m, h) = plugStorage(1_000.0, now)
        val ui = HistoryQuery.build(HistoryRange.DAY_1, now, zone, plugPlan, m, h)!!
        assertEquals(1_000.0, ui.nowW!!, 1e-9)
        assertEquals(1_000.0, ui.peakW!!, 1e-9)
        assertEquals(10.5, ui.todayKwh!!, 1e-6)                       // 1 kW for 10.5 h
        assertEquals(ui.todayKwh!!, ui.monthKwh!!, 1e-9)              // only today is in the data
        assertTrue(ui.line.size in 120..130)                         // 5-minute averages over 10.5 h
        assertEquals(false, ui.fromCounter)
    }

    @Test
    fun aMeterUsesItsCounterAcrossAGap() {
        val plan = HistoryPlan(mapOf("power_total" to "W", "energy_total" to "kWh"), "power_total", "energy_total")
        val now = day0 + 12 * HOUR
        // counter at 100 kWh at 01:00, phone away 02:00-09:00, back at 110 kWh by 09:00, 112 at 11:00
        val counterHours = listOf(
            HourStat(day0 + 1 * HOUR, 100.0, 100.0, 100.0, 60),
            HourStat(day0 + 9 * HOUR, 110.0, 110.0, 110.0, 60),
            HourStat(day0 + 11 * HOUR, 112.0, 111.0, 112.0, 60),
        )
        val power = listOf(Sample(now - MINUTE, 3_600.0))
        val ui = HistoryQuery.build(
            HistoryRange.DAYS_7, now, zone, plan,
            minutes = { metric, f, t -> if (metric == "power_total") power.filter { it.atMillis in f until t } else emptyList() },
            hours = { metric, f, t -> if (metric == "energy_total") counterHours.filter { it.hourMillis in f until t } else emptyList() },
        )!!
        assertTrue(ui.fromCounter)
        assertEquals(12.0, ui.todayKwh!!, 1e-9)                       // 100 -> 112, gap included exactly
        assertEquals(3_600.0, ui.nowW!!, 1e-9)
    }

    @Test
    fun noPowerReadingMeansNoCard() {
        val plan = HistoryPlan(mapOf("cur_voltage" to "V"), powerKey = null, energyCounterKey = null)
        assertNull(HistoryQuery.build(HistoryRange.DAY_1, day0, zone, plan, { _, _, _ -> emptyList() }, { _, _, _ -> emptyList() }))
    }

    @Test
    fun aStaleLastReadingIsNotShownAsNow() {
        val now = day0 + 5 * HOUR
        val (m, h) = plugStorage(500.0, day0 + 4 * HOUR)            // stopped an hour ago
        val ui = HistoryQuery.build(HistoryRange.DAY_1, now, zone, plugPlan, m, h)!!
        assertNull(ui.nowW)
        assertNotNull(ui.todayKwh)
    }
}
