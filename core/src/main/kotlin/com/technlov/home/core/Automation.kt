package com.technlov.home.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Automations -- what makes the app a hub rather than a remote. The app's hub service (a foreground service) polls
 * the devices, keeps their latest [DeviceState]s, and every minute asks [AutomationEngine.due] which automations
 * should run now. Everything is local: no server, no cloud.
 *
 * A device is addressed across connectors by its [DeviceKey] = "connectorId:deviceId".
 */
typealias DeviceKey = String

fun DeviceRef.key(): DeviceKey = "$connectorId:$id"

data class Automation(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val trigger: Trigger,
    val conditions: List<Condition> = emptyList(),
    val actions: List<Action>,
    /**
     * Run once: the hub turns the automation off ([enabled] = false) after a run in which every device took its
     * change. A run that failed leaves it on, so it tries again next time. Turning it back on arms it again.
     */
    val runOnce: Boolean = false,
)

sealed interface Trigger {
    /** At a time of day, on the given days (empty = every day). */
    data class TimeOfDay(val hour: Int, val minute: Int, val days: Set<DayOfWeek> = emptySet()) : Trigger

    /**
     * At sunrise or sunset at the home, moved by [offsetMinutes] (negative = before: sunset - 15 min), on the given
     * days (empty = every day). The sun's times come from a [SunClock]: without the home's location it never fires.
     */
    data class Sun(val event: SunEvent, val offsetMinutes: Int = 0, val days: Set<DayOfWeek> = emptySet()) : Trigger {
        companion object {
            /** The furthest a sun trigger may be moved from the sunrise or sunset, either way. */
            const val MAX_OFFSET_MINUTES = 180
        }
    }

    /** When a device value crosses into a state, e.g. room temperature >= 30. */
    data class StateMatches(val device: DeviceKey, val key: String, val op: Op, val value: Value) : Trigger
}

enum class SunEvent { SUNRISE, SUNSET }

/**
 * When the sun rises and sets at the home, in the phone's local time. Worked out on the phone ([SolarClock]); no
 * service is asked.
 */
fun interface SunClock {
    /** The minute of [event] on [date], or null: the home's location is not known, or the sun does not rise or set. */
    fun time(event: SunEvent, date: LocalDate): LocalDateTime?

    companion object {
        /** No location yet: sun triggers do not fire. */
        val UNKNOWN = SunClock { _, _ -> null }
    }
}

/**
 * The time a time or sun trigger is set for on [date] (for a sun trigger, the day of the sunrise or sunset), or
 * null: a day it is not set for, a sun time [sun] does not know, or a trigger without a time.
 */
fun Trigger.timeOn(date: LocalDate, sun: SunClock = SunClock.UNKNOWN): LocalDateTime? = when (this) {
    is Trigger.TimeOfDay -> if (days.isEmpty() || date.dayOfWeek in days) date.atTime(hour, minute) else null
    is Trigger.Sun -> if (days.isEmpty() || date.dayOfWeek in days) sun.time(event, date)?.plusMinutes(offsetMinutes.toLong()) else null
    is Trigger.StateMatches -> null
}

/** The latest time at or before [now] that a time or sun trigger was set for (about a day back at most), or null. */
fun Trigger.latestTime(now: LocalDateTime, sun: SunClock = SunClock.UNKNOWN): LocalDateTime? =
    (-1L..1L).mapNotNull { timeOn(now.toLocalDate().plusDays(it), sun) }.filter { !it.isAfter(now) }.maxOrNull()

/** The next time after [after] that a time or sun trigger is set for, looking a week ahead, or null. */
fun Trigger.nextTime(after: LocalDateTime, sun: SunClock = SunClock.UNKNOWN): LocalDateTime? =
    (-1L..8L).mapNotNull { timeOn(after.toLocalDate().plusDays(it), sun) }.filter { it.isAfter(after) }.minOrNull()

sealed interface Condition {
    data class StateCompare(val device: DeviceKey, val key: String, val op: Op, val value: Value) : Condition
    data class TimeBetween(val fromHour: Int, val fromMinute: Int, val toHour: Int, val toMinute: Int) : Condition
}

enum class Op { EQ, NE, GT, GE, LT, LE }

sealed interface Action {
    data class SetValues(val device: DeviceKey, val changes: List<SetValue>) : Action
    data class Notify(val title: String, val text: String) : Action
}

/**
 * Decides which automations run. Pure Kotlin (no Android), so it is unit-tested on the JVM.
 *
 * A state trigger fires only on the edge ([previousStates] -> [states]: false -> true), not on every poll while the
 * state stays true. A time trigger fires once for each time it is set for: [lastRun] (when each automation last
 * ran; the app keeps it across restarts) says whether it already ran, and [previousCheck] (when the hub last asked;
 * null right after a start) tells a time the hub missed -- Android dozed, or the app was restarted -- from one it
 * saw. A missed time still runs if the hub is back within a short window. [sun] gives the sunrise and sunset times
 * for sun triggers.
 */
interface AutomationEngine {
    fun due(
        now: LocalDateTime,
        automations: List<Automation>,
        states: Map<DeviceKey, DeviceState>,
        previousStates: Map<DeviceKey, DeviceState>,
        lastRun: Map<String, LocalDateTime>,
        previousCheck: LocalDateTime? = null,
        sun: SunClock = SunClock.UNKNOWN,
    ): List<Automation>
}
