package com.technlov.home.core

import java.time.Duration
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * The automation rules, as a pure function of the clock and the device states.
 *
 * An automation is due when it is enabled, its trigger fires, and every condition holds:
 *  - [Trigger.TimeOfDay] fires once for each time it is set for (on one of its days, if any are set): during that
 *    minute, or -- when the hub missed it -- late, within [catchUp] after it. "Missed" means the time passed after
 *    the previous check ([AutomationEngine.due]'s previousCheck), and [lastRun] shows it has not run since. A time the
 *    hub checked on time is never retried later, even if a condition did not hold then. Right after a start (no
 *    previous check) a late run also needs a saved last run, so a time that may have run before an app update is not
 *    run again. Never twice: not again once [lastRun] is at or after that time.
 *  - [Trigger.Sun] follows the same rules, at the [SunClock]'s sunrise or sunset (plus its offset) on its days. With
 *    no sun times (no location; the sun does not set that day) it does not fire.
 *  - [Trigger.StateMatches] fires on the edge: the comparison is true in the current state and was not true in the
 *    previous one (a missing previous state or value counts as not true).
 *  - [Condition.StateCompare] checks the current state; [Condition.TimeBetween] is from-inclusive, to-exclusive, may
 *    cross midnight (22:00-06:00), and from == to means the whole day.
 *
 * A current state that is offline does not count (its values are stale): a trigger or condition on it is not met.
 * Comparisons follow [Value.matches]. A previous check or last run later than now (the clock was set back) is
 * ignored, so time triggers keep working.
 */
class DefaultAutomationEngine(private val catchUp: Duration = CATCH_UP) : AutomationEngine {
    override fun due(
        now: LocalDateTime,
        automations: List<Automation>,
        states: Map<DeviceKey, DeviceState>,
        previousStates: Map<DeviceKey, DeviceState>,
        lastRun: Map<String, LocalDateTime>,
        previousCheck: LocalDateTime?,
        sun: SunClock,
    ): List<Automation> {
        val since = previousCheck?.takeUnless { it.isAfter(now) }
        return automations.filter { a ->
            a.enabled &&
                fires(a, now, states, previousStates, lastRun[a.id]?.takeUnless { it.isAfter(now) }, since, sun) &&
                a.conditions.all { holds(it, now, states) }
        }
    }

    private fun fires(
        a: Automation,
        now: LocalDateTime,
        states: Map<DeviceKey, DeviceState>,
        previousStates: Map<DeviceKey, DeviceState>,
        ranAt: LocalDateTime?,
        since: LocalDateTime?,
        sun: SunClock,
    ): Boolean = when (val t = a.trigger) {
        is Trigger.TimeOfDay, is Trigger.Sun -> timeFires(t.latestTime(now, sun), now, ranAt, since)
        is Trigger.StateMatches -> {
            val current = states[t.device]?.takeIf { it.online }?.values?.get(t.key)
            val previous = previousStates[t.device]?.values?.get(t.key)
            current != null && current.matches(t.op, t.value) &&
                !(previous != null && previous.matches(t.op, t.value))
        }
    }

    /** [at]: the latest time the trigger was set for, at or before [now]. */
    private fun timeFires(at: LocalDateTime?, now: LocalDateTime, ranAt: LocalDateTime?, since: LocalDateTime?): Boolean {
        if (at == null || at.isBefore(now.minus(catchUp))) return false
        if (since != null && !at.isAfter(since)) return false   // the hub saw that time come: not a miss
        if (ranAt != null && !ranAt.isBefore(at)) return false  // already ran for it
        val late = at.isBefore(now.truncatedTo(ChronoUnit.MINUTES))
        return !late || since != null || ranAt != null
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

    companion object {
        /** How late a missed time may still run: the hub was asleep or restarting, not the user's choice. */
        val CATCH_UP: Duration = Duration.ofMinutes(10)
    }
}
