package com.technlov.homecontroller.ui.automations

import com.technlov.home.core.Action
import com.technlov.home.core.Automation
import com.technlov.home.core.Capability
import com.technlov.home.core.Condition
import com.technlov.home.core.Keys
import com.technlov.home.core.Op
import com.technlov.home.core.SetValue
import com.technlov.home.core.SunClock
import com.technlov.home.core.SunEvent
import com.technlov.home.core.Trigger
import com.technlov.home.core.Value
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class ScheduleRulesTest {
    private val plug = "tuya:plug1"
    private val on = Action.SetValues(plug, listOf(SetValue(Keys.POWER, Value.Bool(true))))
    private fun at(vararg actions: Action, conditions: List<Condition> = emptyList()) =
        Automation("a", "n", trigger = Trigger.TimeOfDay(7, 0, setOf(DayOfWeek.MONDAY)), conditions = conditions, actions = actions.toList())

    @Test
    fun aTimeOfDaySetOnTheDeviceIsItsSchedule() {
        assertTrue(at(on).isScheduleFor(plug))
        assertFalse("another device's", at(on).isScheduleFor("gree:ac1"))
    }

    @Test
    fun aSunriseOrSunsetSetOnTheDeviceIsItsScheduleToo() {
        val atSunset = Automation("s", "n", trigger = Trigger.Sun(SunEvent.SUNSET, -15, setOf(DayOfWeek.FRIDAY)), actions = listOf(on))
        assertTrue(atSunset.isScheduleFor(plug))
        assertFalse(atSunset.isScheduleFor("gree:ac1"))
        assertFalse(atSunset.copy(conditions = listOf(Condition.TimeBetween(6, 0, 8, 0))).isScheduleFor(plug))
        assertFalse(atSunset.copy(actions = listOf(on, Action.Notify("t", "x"))).isScheduleFor(plug))
        assertTrue("run once is still a schedule", atSunset.copy(runOnce = true).isScheduleFor(plug))
    }

    @Test
    fun schedulesSortByWhenTheyFallToday() {
        val today = LocalDate.of(2026, 10, 12)
        val sun = SunClock { event, date -> if (event == SunEvent.SUNRISE) date.atTime(5, 50) else date.atTime(17, 42) }
        val triggers = listOf(
            Trigger.TimeOfDay(18, 0),
            Trigger.Sun(SunEvent.SUNSET, -15), // 17:27
            Trigger.TimeOfDay(7, 0),
            Trigger.Sun(SunEvent.SUNRISE, 30), // 06:20
        )
        assertEquals(
            listOf(Trigger.Sun(SunEvent.SUNRISE, 30), Trigger.TimeOfDay(7, 0), Trigger.Sun(SunEvent.SUNSET, -15), Trigger.TimeOfDay(18, 0)),
            triggers.sortedBy { it.minuteOfDay(today, sun) },
        )
        // Without a location, sunrise counts as 06:00 and sunset as 18:00.
        assertEquals(17 * 60 + 45, Trigger.Sun(SunEvent.SUNSET, -15).minuteOfDay(today, SunClock.UNKNOWN))
        assertEquals(6 * 60, Trigger.Sun(SunEvent.SUNRISE).minuteOfDay(today, SunClock.UNKNOWN))
    }

    @Test
    fun anythingMoreComplexStaysAnAutomationOnly() {
        assertFalse(at(on, Action.Notify("t", "x")).isScheduleFor(plug))
        assertFalse(at(on, Action.SetValues("gree:ac1", on.changes)).isScheduleFor(plug))
        assertFalse(at(on, conditions = listOf(Condition.TimeBetween(6, 0, 8, 0))).isScheduleFor(plug))
        val stateTrigger = Automation("b", "n", trigger = Trigger.StateMatches(plug, Keys.POWER, Op.EQ, Value.Bool(true)), actions = listOf(on))
        assertFalse(stateTrigger.isScheduleFor(plug))
    }

    @Test
    fun aNewScheduleStartsAsPowerOnAtSevenEveryDay() {
        val option = DeviceOption(plug, "Plug", listOf(Capability.Power(), Capability.Reading("cur_power", "Power", "W")))
        val d = AutomationDraft.newSchedule("id1", option)
        assertTrue(d.isNew)
        assertEquals(TriggerKind.TIME, d.kind)
        assertEquals(7, d.hour); assertEquals(0, d.minute); assertTrue(d.days.isEmpty())
        assertEquals(listOf(Action.SetValues(plug, listOf(SetValue(Keys.POWER, Value.Bool(true))))), d.actions)
    }
}
