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

    /** When a device value crosses into a state, e.g. room temperature >= 30. */
    data class StateMatches(val device: DeviceKey, val key: String, val op: Op, val value: Value) : Trigger
}

/** The time a time trigger is set for on [date], or null: a day it is not set for, or a trigger without a time. */
fun Trigger.timeOn(date: LocalDate): LocalDateTime? = when (this) {
    is Trigger.TimeOfDay -> if (days.isEmpty() || date.dayOfWeek in days) date.atTime(hour, minute) else null
    is Trigger.StateMatches -> null
}

/** The latest time at or before [now] that a time trigger was set for (today's, or yesterday's), or null. */
fun Trigger.latestTime(now: LocalDateTime): LocalDateTime? =
    (-1L..0L).mapNotNull { timeOn(now.toLocalDate().plusDays(it)) }.filter { !it.isAfter(now) }.maxOrNull()

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
 * saw. A missed time still runs if the hub is back within a short window.
 */
interface AutomationEngine {
    fun due(
        now: LocalDateTime,
        automations: List<Automation>,
        states: Map<DeviceKey, DeviceState>,
        previousStates: Map<DeviceKey, DeviceState>,
        lastRun: Map<String, LocalDateTime>,
        previousCheck: LocalDateTime? = null,
    ): List<Automation>
}
