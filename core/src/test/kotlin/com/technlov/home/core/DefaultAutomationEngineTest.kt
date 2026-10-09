package com.technlov.home.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime

class DefaultAutomationEngineTest {
    private val engine = DefaultAutomationEngine()

    private val ac = "gree:f0fe6b000001"
    private val lamp = "switch:aa0000000002"

    // 2026-10-12 is a Monday.
    private fun at(h: Int, m: Int, s: Int = 0, day: Int = 12) = LocalDateTime.of(2026, 10, day, h, m, s)

    private val notify = listOf(Action.Notify("t", "x"))

    private fun auto(
        trigger: Trigger,
        conditions: List<Condition> = emptyList(),
        id: String = "a1",
        enabled: Boolean = true,
    ) = Automation(id, "test $id", enabled, trigger, conditions, notify)

    private fun state(vararg values: Pair<String, Value>, online: Boolean = true) =
        DeviceState(values.toMap(), online = online)

    private fun due(
        a: List<Automation>,
        now: LocalDateTime,
        states: Map<DeviceKey, DeviceState> = emptyMap(),
        previous: Map<DeviceKey, DeviceState> = emptyMap(),
        lastRun: Map<String, LocalDateTime> = emptyMap(),
    ) = engine.due(now, a, states, previous, lastRun).map { it.id }

    // ---- time of day ----

    @Test
    fun timeTriggerFiresDuringItsMinute() {
        val a = listOf(auto(Trigger.TimeOfDay(7, 30)))
        assertEquals(listOf("a1"), due(a, at(7, 30, 0)))
        assertEquals(listOf("a1"), due(a, at(7, 30, 59)))
        assertEquals(emptyList<String>(), due(a, at(7, 29, 59)))
        assertEquals(emptyList<String>(), due(a, at(7, 31, 0)))
        assertEquals(emptyList<String>(), due(a, at(19, 30)))
    }

    @Test
    fun timeTriggerFiresOncePerMinute() {
        val a = listOf(auto(Trigger.TimeOfDay(7, 30)))
        assertEquals(emptyList<String>(), due(a, at(7, 30, 40), lastRun = mapOf("a1" to at(7, 30, 2))))
        // Ran yesterday at the same time, or a minute earlier: due again.
        assertEquals(listOf("a1"), due(a, at(7, 30, 40), lastRun = mapOf("a1" to at(7, 30, 2, day = 11))))
        assertEquals(listOf("a1"), due(a, at(7, 30, 1), lastRun = mapOf("a1" to at(7, 29, 59))))
        // Another automation's run does not count.
        assertEquals(listOf("a1"), due(a, at(7, 30, 40), lastRun = mapOf("other" to at(7, 30, 2))))
    }

    @Test
    fun timeTriggerRespectsDays() {
        val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY)
        val a = listOf(auto(Trigger.TimeOfDay(6, 0, weekdays)))
        assertEquals(listOf("a1"), due(a, at(6, 0, day = 12)))          // Monday
        assertEquals(listOf("a1"), due(a, at(6, 0, day = 14)))          // Wednesday
        assertEquals(emptyList<String>(), due(a, at(6, 0, day = 16)))   // Friday
        assertEquals(emptyList<String>(), due(a, at(6, 0, day = 18)))   // Sunday
    }

    @Test
    fun emptyDaysMeansEveryDay() {
        val a = listOf(auto(Trigger.TimeOfDay(6, 0)))
        for (day in 12..18) assertEquals("day $day", listOf("a1"), due(a, at(6, 0, day = day)))
    }

    @Test
    fun disabledAutomationIsNeverDue() {
        val a = listOf(auto(Trigger.TimeOfDay(7, 30), enabled = false))
        assertEquals(emptyList<String>(), due(a, at(7, 30)))
        val s = listOf(auto(Trigger.StateMatches(ac, Keys.POWER, Op.EQ, Value.Bool(true)), enabled = false))
        assertEquals(
            emptyList<String>(),
            due(s, at(1, 0), mapOf(ac to state(Keys.POWER to Value.Bool(true))), mapOf(ac to state(Keys.POWER to Value.Bool(false)))),
        )
    }

    // ---- state trigger (edge) ----

    private val hot = Trigger.StateMatches(ac, Keys.CURRENT_TEMPERATURE, Op.GE, Value.Num(30.0))

    @Test
    fun stateTriggerFiresOnTheRisingEdge() {
        val a = listOf(auto(hot))
        val prev = mapOf(ac to state(Keys.CURRENT_TEMPERATURE to Value.Num(29.5)))
        val now = mapOf(ac to state(Keys.CURRENT_TEMPERATURE to Value.Num(30.0)))
        assertEquals(listOf("a1"), due(a, at(14, 0), now, prev))
    }

    @Test
    fun stateTriggerDoesNotRepeatWhileStillTrue() {
        val a = listOf(auto(hot))
        val prev = mapOf(ac to state(Keys.CURRENT_TEMPERATURE to Value.Num(31.0)))
        val now = mapOf(ac to state(Keys.CURRENT_TEMPERATURE to Value.Num(32.0)))
        assertEquals(emptyList<String>(), due(a, at(14, 1), now, prev))
    }

    @Test
    fun stateTriggerDoesNotFireOnFallingEdgeOrWhileFalse() {
        val a = listOf(auto(hot))
        val warm = mapOf(ac to state(Keys.CURRENT_TEMPERATURE to Value.Num(28.0)))
        val hotState = mapOf(ac to state(Keys.CURRENT_TEMPERATURE to Value.Num(31.0)))
        assertEquals(emptyList<String>(), due(a, at(14, 2), warm, hotState))
        assertEquals(emptyList<String>(), due(a, at(14, 3), warm, warm))
    }

    @Test
    fun missingPreviousStateCountsAsNotTrue() {
        val a = listOf(auto(hot))
        val now = mapOf(ac to state(Keys.CURRENT_TEMPERATURE to Value.Num(31.0)))
        assertEquals(listOf("a1"), due(a, at(14, 0), now, emptyMap()))
        // Previous state present but without the key.
        assertEquals(listOf("a1"), due(a, at(14, 0), now, mapOf(ac to state(Keys.POWER to Value.Bool(true)))))
    }

    @Test
    fun missingOrOfflineCurrentStateDoesNotFire() {
        val a = listOf(auto(hot))
        val prev = mapOf(ac to state(Keys.CURRENT_TEMPERATURE to Value.Num(25.0)))
        assertEquals(emptyList<String>(), due(a, at(14, 0), emptyMap(), prev))
        assertEquals(emptyList<String>(), due(a, at(14, 0), mapOf(ac to state(Keys.POWER to Value.Bool(true))), prev))
        val offline = mapOf(ac to state(Keys.CURRENT_TEMPERATURE to Value.Num(31.0), online = false))
        assertEquals(emptyList<String>(), due(a, at(14, 0), offline, prev))
    }

    @Test
    fun stateTriggerOnOtherDeviceIsIgnored() {
        val a = listOf(auto(hot))
        val now = mapOf(lamp to state(Keys.CURRENT_TEMPERATURE to Value.Num(31.0)))
        assertEquals(emptyList<String>(), due(a, at(14, 0), now, emptyMap()))
    }

    @Test
    fun stateTriggerOnTextAndBool() {
        val toCool = listOf(auto(Trigger.StateMatches(ac, Keys.MODE, Op.EQ, Value.Text("cool"))))
        assertEquals(
            listOf("a1"),
            due(toCool, at(9, 0), mapOf(ac to state(Keys.MODE to Value.Text("cool"))), mapOf(ac to state(Keys.MODE to Value.Text("fan")))),
        )
        val turnedOff = listOf(auto(Trigger.StateMatches(ac, Keys.POWER, Op.NE, Value.Bool(true))))
        assertEquals(
            listOf("a1"),
            due(turnedOff, at(9, 0), mapOf(ac to state(Keys.POWER to Value.Bool(false))), mapOf(ac to state(Keys.POWER to Value.Bool(true)))),
        )
        assertEquals(
            emptyList<String>(),
            due(turnedOff, at(9, 0), mapOf(ac to state(Keys.POWER to Value.Bool(false))), mapOf(ac to state(Keys.POWER to Value.Bool(false)))),
        )
    }

    @Test
    fun stateTriggerIgnoresLastRun() {
        val a = listOf(auto(hot))
        val prev = mapOf(ac to state(Keys.CURRENT_TEMPERATURE to Value.Num(29.0)))
        val now = mapOf(ac to state(Keys.CURRENT_TEMPERATURE to Value.Num(30.0)))
        assertEquals(listOf("a1"), due(a, at(14, 0), now, prev, mapOf("a1" to at(14, 0))))
    }

    // ---- conditions ----

    @Test
    fun allStateConditionsMustHold() {
        val a = listOf(
            auto(
                Trigger.TimeOfDay(18, 0),
                listOf(
                    Condition.StateCompare(ac, Keys.POWER, Op.EQ, Value.Bool(false)),
                    Condition.StateCompare(ac, Keys.CURRENT_TEMPERATURE, Op.GT, Value.Num(27.0)),
                ),
            ),
        )
        val offWarm = mapOf(ac to state(Keys.POWER to Value.Bool(false), Keys.CURRENT_TEMPERATURE to Value.Num(29.0)))
        val onWarm = mapOf(ac to state(Keys.POWER to Value.Bool(true), Keys.CURRENT_TEMPERATURE to Value.Num(29.0)))
        val offCool = mapOf(ac to state(Keys.POWER to Value.Bool(false), Keys.CURRENT_TEMPERATURE to Value.Num(24.0)))
        assertEquals(listOf("a1"), due(a, at(18, 0), offWarm))
        assertEquals(emptyList<String>(), due(a, at(18, 0), onWarm))
        assertEquals(emptyList<String>(), due(a, at(18, 0), offCool))
    }

    @Test
    fun stateConditionOnMissingOrOfflineDeviceDoesNotHold() {
        val a = listOf(auto(Trigger.TimeOfDay(18, 0), listOf(Condition.StateCompare(ac, Keys.POWER, Op.EQ, Value.Bool(false)))))
        assertEquals(emptyList<String>(), due(a, at(18, 0), emptyMap()))
        assertEquals(emptyList<String>(), due(a, at(18, 0), mapOf(ac to state(Keys.POWER to Value.Bool(false), online = false))))
        // NE on a missing value is not a match either: nothing is known.
        val ne = listOf(auto(Trigger.TimeOfDay(18, 0), listOf(Condition.StateCompare(ac, Keys.POWER, Op.NE, Value.Bool(true)))))
        assertEquals(emptyList<String>(), due(ne, at(18, 0), emptyMap()))
    }

    @Test
    fun timeBetweenSameDay() {
        val c = Condition.TimeBetween(8, 0, 17, 30)
        val a = listOf(auto(hot, listOf(c)))
        val prev = mapOf(ac to state(Keys.CURRENT_TEMPERATURE to Value.Num(29.0)))
        val now = mapOf(ac to state(Keys.CURRENT_TEMPERATURE to Value.Num(30.0)))
        assertEquals(listOf("a1"), due(a, at(8, 0), now, prev))       // from is inclusive
        assertEquals(listOf("a1"), due(a, at(12, 0), now, prev))
        assertEquals(listOf("a1"), due(a, at(17, 29, 59), now, prev))
        assertEquals(emptyList<String>(), due(a, at(17, 30), now, prev)) // to is exclusive
        assertEquals(emptyList<String>(), due(a, at(7, 59), now, prev))
        assertEquals(emptyList<String>(), due(a, at(23, 0), now, prev))
    }

    @Test
    fun timeBetweenAcrossMidnight() {
        val c = Condition.TimeBetween(22, 0, 6, 0)
        val a = listOf(auto(hot, listOf(c)))
        val prev = mapOf(ac to state(Keys.CURRENT_TEMPERATURE to Value.Num(29.0)))
        val now = mapOf(ac to state(Keys.CURRENT_TEMPERATURE to Value.Num(30.0)))
        assertEquals(listOf("a1"), due(a, at(22, 0), now, prev))
        assertEquals(listOf("a1"), due(a, at(23, 59), now, prev))
        assertEquals(listOf("a1"), due(a, at(0, 0), now, prev))
        assertEquals(listOf("a1"), due(a, at(5, 59), now, prev))
        assertEquals(emptyList<String>(), due(a, at(6, 0), now, prev))
        assertEquals(emptyList<String>(), due(a, at(12, 0), now, prev))
        assertEquals(emptyList<String>(), due(a, at(21, 59), now, prev))
    }

    @Test
    fun timeBetweenWithEqualEndsIsTheWholeDay() {
        val a = listOf(auto(hot, listOf(Condition.TimeBetween(9, 0, 9, 0))))
        val prev = mapOf(ac to state(Keys.CURRENT_TEMPERATURE to Value.Num(29.0)))
        val now = mapOf(ac to state(Keys.CURRENT_TEMPERATURE to Value.Num(30.0)))
        for (h in listOf(0, 8, 9, 13, 23)) assertEquals("hour $h", listOf("a1"), due(a, at(h, 0), now, prev))
    }

    @Test
    fun timeTriggerWithTimeAndStateConditions() {
        val a = listOf(
            auto(
                Trigger.TimeOfDay(23, 0),
                listOf(Condition.TimeBetween(22, 0, 6, 0), Condition.StateCompare(ac, Keys.POWER, Op.EQ, Value.Bool(true))),
            ),
        )
        assertEquals(listOf("a1"), due(a, at(23, 0), mapOf(ac to state(Keys.POWER to Value.Bool(true)))))
        assertEquals(emptyList<String>(), due(a, at(23, 0), mapOf(ac to state(Keys.POWER to Value.Bool(false)))))
    }

    // ---- several automations ----

    @Test
    fun returnsOnlyDueAutomationsInInputOrder() {
        val list = listOf(
            auto(Trigger.TimeOfDay(7, 0), id = "morning"),
            auto(Trigger.TimeOfDay(7, 1), id = "later"),
            auto(hot, id = "hot"),
            auto(Trigger.TimeOfDay(7, 0), id = "off", enabled = false),
            auto(Trigger.TimeOfDay(7, 0), id = "ran"),
        )
        val prev = mapOf(ac to state(Keys.CURRENT_TEMPERATURE to Value.Num(29.0)))
        val now = mapOf(ac to state(Keys.CURRENT_TEMPERATURE to Value.Num(30.0)))
        val result = due(list, at(7, 0, 30), now, prev, mapOf("ran" to at(7, 0, 5)))
        assertEquals(listOf("morning", "hot"), result)
    }

    @Test
    fun emptyInputGivesNothing() {
        assertTrue(due(emptyList(), at(7, 0)).isEmpty())
    }
}
