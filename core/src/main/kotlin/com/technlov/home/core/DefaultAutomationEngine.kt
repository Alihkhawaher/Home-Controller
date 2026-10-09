package com.technlov.home.core

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * The automation rules, as a pure function of the clock and the device states.
 *
 * An automation is due when it is enabled, its trigger fires, and every condition holds:
 *  - [Trigger.TimeOfDay] fires during its minute (on one of its days, if any are set), once: not when [lastRun]
 *    already falls in that same minute.
 *  - [Trigger.StateMatches] fires on the edge: the comparison is true in the current state and was not true in the
 *    previous one (a missing previous state or value counts as not true).
 *  - [Condition.StateCompare] checks the current state; [Condition.TimeBetween] is from-inclusive, to-exclusive, may
 *    cross midnight (22:00-06:00), and from == to means the whole day.
 *
 * A current state that is offline does not count (its values are stale): a trigger or condition on it is not met.
 * Comparisons follow [Value.matches].
 */
class DefaultAutomationEngine : AutomationEngine {
    override fun due(
        now: LocalDateTime,
        automations: List<Automation>,
        states: Map<DeviceKey, DeviceState>,
        previousStates: Map<DeviceKey, DeviceState>,
        lastRun: Map<String, LocalDateTime>,
    ): List<Automation> = automations.filter { a ->
        a.enabled &&
            fires(a, now, states, previousStates, lastRun) &&
            a.conditions.all { holds(it, now, states) }
    }

    private fun fires(
        a: Automation,
        now: LocalDateTime,
        states: Map<DeviceKey, DeviceState>,
        previousStates: Map<DeviceKey, DeviceState>,
        lastRun: Map<String, LocalDateTime>,
    ): Boolean = when (val t = a.trigger) {
        is Trigger.TimeOfDay -> {
            val minute = now.truncatedTo(ChronoUnit.MINUTES)
            now.hour == t.hour && now.minute == t.minute &&
                (t.days.isEmpty() || now.dayOfWeek in t.days) &&
                lastRun[a.id]?.truncatedTo(ChronoUnit.MINUTES) != minute
        }
        is Trigger.StateMatches -> {
            val current = states[t.device]?.takeIf { it.online }?.values?.get(t.key)
            val previous = previousStates[t.device]?.values?.get(t.key)
            current != null && current.matches(t.op, t.value) &&
                !(previous != null && previous.matches(t.op, t.value))
        }
    }

    private fun holds(c: Condition, now: LocalDateTime, states: Map<DeviceKey, DeviceState>): Boolean = when (c) {
        is Condition.StateCompare -> {
            val current = states[c.device]?.takeIf { it.online }?.values?.get(c.key)
            current != null && current.matches(c.op, c.value)
        }
        is Condition.TimeBetween -> {
            val t = now.hour * 60 + now.minute
            val from = c.fromHour * 60 + c.fromMinute
            val to = c.toHour * 60 + c.toMinute
            when {
                from == to -> true
                from < to -> t in from until to
                else -> t >= from || t < to
            }
        }
    }
}
