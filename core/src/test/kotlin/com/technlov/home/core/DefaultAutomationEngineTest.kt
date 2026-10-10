package com.technlov.home.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        since: LocalDateTime? = null,
        sun: SunClock = SunClock.UNKNOWN,
    ) = engine.due(now, a, states, previous, lastRun, since, sun).map { it.id }

    /** A home where the sun rises at 05:50 and sets at 17:42 every day. */
    private val sun = SunClock { event, date ->
        if (event == SunEvent.SUNRISE) date.atTime(5, 50) else date.atTime(17, 42)
    }

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

    // ---- catch-up: a time the hub missed (Android dozed, or the app restarted) ----

    @Test
    fun aTimeMissedByThreeMinutesRunsOnce() {
        val a = listOf(auto(Trigger.TimeOfDay(7, 30)))
        // The hub checked at 07:29, the phone dozed through 07:30, and the next check is at 07:33.
        assertEquals(listOf("a1"), due(a, at(7, 33), since = at(7, 29)))
        // It ran at 07:33: the next minutes do not run it again.
        val ran = mapOf("a1" to at(7, 33))
        assertEquals(emptyList<String>(), due(a, at(7, 34), lastRun = ran, since = at(7, 33)))
        assertEquals(emptyList<String>(), due(a, at(7, 35), lastRun = ran, since = at(7, 29)))
    }

    @Test
    fun aTimeMissedByThirtyMinutesIsSkipped() {
        val a = listOf(auto(Trigger.TimeOfDay(7, 30)))
        assertEquals(emptyList<String>(), due(a, at(8, 0), since = at(7, 29)))
        // The window is ten minutes after the time.
        assertEquals(listOf("a1"), due(a, at(7, 40), since = at(7, 29)))
        assertEquals(emptyList<String>(), due(a, at(7, 41), since = at(7, 29)))
        assertEquals(10L, DefaultAutomationEngine.CATCH_UP.toMinutes())
    }

    @Test
    fun aRestartAfterTheRunDoesNotRepeatIt() {
        val a = listOf(auto(Trigger.TimeOfDay(7, 30)))
        // Ran at 07:30; the app restarted (nothing checked since the start) at 07:32: the saved run says it ran.
        assertEquals(emptyList<String>(), due(a, at(7, 32), lastRun = mapOf("a1" to at(7, 30))))
        assertEquals(emptyList<String>(), due(a, at(7, 33), lastRun = mapOf("a1" to at(7, 30)), since = at(7, 32)))
        // Restarted at 07:33 having last run yesterday: today's 07:30 was missed, so it runs now.
        assertEquals(listOf("a1"), due(a, at(7, 33), lastRun = mapOf("a1" to at(7, 30, day = 11))))
    }

    @Test
    fun rightAfterAStartWithoutASavedRunOnlyOnTimeCounts() {
        // No saved run (an app updated from a version that did not keep them): 07:30 may have run already.
        val a = listOf(auto(Trigger.TimeOfDay(7, 30)))
        assertEquals(emptyList<String>(), due(a, at(7, 33)))
        assertEquals(listOf("a1"), due(a, at(7, 30)))
    }

    @Test
    fun aTimeCheckedOnTimeIsNotRetriedLater() {
        // At 07:30 the AC was on, so the automation did not run. That was not a miss: 07:31 does not retry it.
        val a = listOf(auto(Trigger.TimeOfDay(7, 30), listOf(Condition.StateCompare(ac, Keys.POWER, Op.EQ, Value.Bool(false)))))
        val off = mapOf(ac to state(Keys.POWER to Value.Bool(false)))
        assertEquals(emptyList<String>(), due(a, at(7, 31), off, since = at(7, 30)))
        assertEquals(listOf("a1"), due(a, at(7, 31), off, since = at(7, 29)))
    }

    @Test
    fun aMissedTimeIsCaughtUpAcrossMidnight() {
        // 2026-10-16 is a Friday. 23:55 on Friday, the hub back at 00:03 on Saturday.
        val friday = listOf(auto(Trigger.TimeOfDay(23, 55, setOf(DayOfWeek.FRIDAY))))
        assertEquals(listOf("a1"), due(friday, at(0, 3, day = 17), since = at(23, 50, day = 16)))
        val saturday = listOf(auto(Trigger.TimeOfDay(23, 55, setOf(DayOfWeek.SATURDAY))))
        assertEquals(emptyList<String>(), due(saturday, at(0, 3, day = 17), since = at(23, 50, day = 16)))
    }

    @Test
    fun aClockSetBackDoesNotStopTimeTriggers() {
        // The phone's clock was a day ahead and was corrected: the last check and the last run lie "in the future".
        val a = listOf(auto(Trigger.TimeOfDay(7, 30)))
        assertEquals(listOf("a1"), due(a, at(7, 30), lastRun = mapOf("a1" to at(7, 30, day = 13)), since = at(7, 45, day = 13)))
    }

    // ---- sunrise / sunset ----

    @Test
    fun aSunTriggerFiresAtItsSunTimePlusTheOffset() {
        val beforeSunset = listOf(auto(Trigger.Sun(SunEvent.SUNSET, -15)))
        assertEquals(listOf("a1"), due(beforeSunset, at(17, 27), sun = sun))
        assertEquals(emptyList<String>(), due(beforeSunset, at(17, 26), sun = sun))
        assertEquals("the sunset itself is not its time", emptyList<String>(), due(beforeSunset, at(17, 42), since = at(17, 41), sun = sun))
        val afterSunrise = listOf(auto(Trigger.Sun(SunEvent.SUNRISE, 30)))
        assertEquals(listOf("a1"), due(afterSunrise, at(6, 20), sun = sun))
        val atSunrise = listOf(auto(Trigger.Sun(SunEvent.SUNRISE)))
        assertEquals(listOf("a1"), due(atSunrise, at(5, 50, 59), sun = sun))
    }

    @Test
    fun aSunTriggerKeepsToItsDaysAndRunsOncePerDay() {
        // 2026-10-16 is a Friday, the 17th a Saturday, the 18th a Sunday.
        val weekend = listOf(auto(Trigger.Sun(SunEvent.SUNSET, 0, setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY))))
        assertEquals(listOf("a1"), due(weekend, at(17, 42, day = 16), sun = sun))
        assertEquals(listOf("a1"), due(weekend, at(17, 42, day = 17), sun = sun))
        assertEquals(emptyList<String>(), due(weekend, at(17, 42, day = 18), sun = sun))
        val ran = mapOf("a1" to at(17, 42, day = 16))
        assertEquals(emptyList<String>(), due(weekend, at(17, 42, 30, day = 16), lastRun = ran, sun = sun))
    }

    @Test
    fun withoutSunTimesASunTriggerNeverFires() {
        // No location set: SunClock.UNKNOWN.
        val a = listOf(auto(Trigger.Sun(SunEvent.SUNSET)))
        for (h in 0..23) assertEquals("hour $h", emptyList<String>(), due(a, at(h, 42)))
        // Polar night: the place is known, but the sun does not rise.
        val noSunrise = SunClock { event, date -> if (event == SunEvent.SUNSET) date.atTime(14, 0) else null }
        val sunrise = listOf(auto(Trigger.Sun(SunEvent.SUNRISE)))
        for (h in 0..23) assertEquals("hour $h", emptyList<String>(), due(sunrise, at(h, 0), sun = noSunrise))
    }

    @Test
    fun aMissedSunTimeIsCaughtUpLikeAClockTime() {
        val a = listOf(auto(Trigger.Sun(SunEvent.SUNSET, -15)))
        assertEquals(listOf("a1"), due(a, at(17, 30), since = at(17, 20), sun = sun))
        assertEquals("23 minutes late", emptyList<String>(), due(a, at(17, 50), since = at(17, 20), sun = sun))
    }

    @Test
    fun anOffsetPastMidnightBelongsToTheDayOfItsSunset() {
        // Far north in summer the sun sets at 23:00; two hours later is 01:00 the next morning.
        val lateSunset = SunClock { event, date -> if (event == SunEvent.SUNSET) date.atTime(23, 0) else null }
        val friday = listOf(auto(Trigger.Sun(SunEvent.SUNSET, 120, setOf(DayOfWeek.FRIDAY))))
        assertEquals(listOf("a1"), due(friday, at(1, 0, day = 17), sun = lateSunset)) // Friday's sunset, Saturday 01:00
        assertEquals(emptyList<String>(), due(friday, at(1, 0, day = 16), sun = lateSunset)) // Thursday's
    }

    @Test
    fun nextAndLatestTimesFollowTheSun() {
        val friday = Trigger.Sun(SunEvent.SUNSET, -15, setOf(DayOfWeek.FRIDAY))
        assertEquals(at(17, 27, day = 16), friday.nextTime(at(12, 0, day = 12), sun))
        assertEquals(at(17, 27, day = 16), friday.latestTime(at(18, 0, day = 16), sun))
        assertNull(friday.nextTime(at(12, 0), SunClock.UNKNOWN))
        assertEquals(at(7, 30, day = 13), Trigger.TimeOfDay(7, 30).nextTime(at(7, 30)))
        assertNull(Trigger.StateMatches(ac, Keys.POWER, Op.EQ, Value.Bool(true)).nextTime(at(7, 30), sun))
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
