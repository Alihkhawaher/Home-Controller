package com.technlov.home.core

import java.time.DayOfWeek
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
)

sealed interface Trigger {
    /** At a time of day, on the given days (empty = every day). */
    data class TimeOfDay(val hour: Int, val minute: Int, val days: Set<DayOfWeek> = emptySet()) : Trigger

    /** When a device value crosses into a state, e.g. room temperature >= 30. */
    data class StateMatches(val device: DeviceKey, val key: String, val op: Op, val value: Value) : Trigger
}

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
 * [lastRun] lets it fire a time trigger once per matching minute, and a state trigger only on the edge
 * (false -> true), not on every poll while the state stays true.
 */
interface AutomationEngine {
    fun due(
        now: LocalDateTime,
        automations: List<Automation>,
        states: Map<DeviceKey, DeviceState>,
        previousStates: Map<DeviceKey, DeviceState>,
        lastRun: Map<String, LocalDateTime>,
    ): List<Automation>
}
