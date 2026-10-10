package com.technlov.homecontroller.ui.automations

import com.technlov.home.core.Action
import com.technlov.home.core.Automation
import com.technlov.home.core.Capability
import com.technlov.home.core.Condition
import com.technlov.home.core.Keys
import com.technlov.home.core.Op
import com.technlov.home.core.SetValue
import com.technlov.home.core.Trigger
import com.technlov.home.core.Value
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

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
