package com.technlov.homecontroller.ui.automations

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.technlov.home.core.Action
import com.technlov.home.core.Op
import com.technlov.home.core.SunEvent
import com.technlov.home.core.Trigger
import com.technlov.homecontroller.R
import com.technlov.homecontroller.ui.valueText
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/** The week in the user's order (Sunday or Monday or Saturday first). */
fun localWeek(): List<DayOfWeek> {
    val first = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    return (0L until 7L).map { first.plus(it) }
}

@Composable
fun formatTime(hour: Int, minute: Int): String {
    val pattern = if (DateFormat.is24HourFormat(LocalContext.current)) "HH:mm" else "h:mm a"
    return LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59)).format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
}

/** "Last ran at 07:30" today; "Last ran Fri 9 Oct at 07:30" on an earlier day (runs are kept across restarts). */
@Composable
fun lastRunText(lastRun: LocalDateTime): String {
    val time = formatTime(lastRun.hour, lastRun.minute)
    if (lastRun.toLocalDate() == LocalDate.now()) return stringResource(R.string.automation_last_ran, time)
    val locale = Locale.getDefault()
    val day = lastRun.format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEdMMM"), locale))
    return stringResource(R.string.automation_last_ran_on, day, time)
}

@Composable
fun daysText(days: Set<DayOfWeek>): String = when {
    days.isEmpty() || days.size == 7 -> stringResource(R.string.days_every_day)
    else -> localWeek().filter { it in days }.joinToString(", ") { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
}

/** "15 min", "1 h", "1 h 30 min". */
@Composable
fun durationText(minutes: Int): String {
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0 -> stringResource(R.string.interval_minutes, rest)
        rest == 0 -> stringResource(R.string.duration_hours, hours)
        else -> stringResource(R.string.duration_hours_minutes, hours, rest)
    }
}

/** For the editor and automatic names: "At sunset", "15 min before sunset", "1 h after sunrise". */
@Composable
fun sunOffsetText(event: SunEvent, offsetMinutes: Int): String {
    val sunrise = event == SunEvent.SUNRISE
    return when {
        offsetMinutes == 0 -> stringResource(if (sunrise) R.string.sun_at_sunrise else R.string.sun_at_sunset)
        offsetMinutes < 0 ->
            stringResource(if (sunrise) R.string.sun_before_sunrise else R.string.sun_before_sunset, durationText(-offsetMinutes))
        else -> stringResource(if (sunrise) R.string.sun_after_sunrise else R.string.sun_after_sunset, durationText(offsetMinutes))
    }
}

/** For summaries: "Sunset", "Sunset − 15 min", "Sunrise + 1 h". */
@Composable
fun sunShortText(event: SunEvent, offsetMinutes: Int): String {
    val name = stringResource(if (event == SunEvent.SUNRISE) R.string.sun_sunrise else R.string.sun_sunset)
    return when {
        offsetMinutes == 0 -> name
        offsetMinutes < 0 -> stringResource(R.string.sun_short_before, name, durationText(-offsetMinutes))
        else -> stringResource(R.string.sun_short_after, name, durationText(offsetMinutes))
    }
}

/** "today at 17:27", "tomorrow at 05:50", "Fri at 17:27". */
@Composable
fun whenText(at: LocalDateTime, today: LocalDate = LocalDate.now()): String {
    val time = formatTime(at.hour, at.minute)
    return when (at.toLocalDate()) {
        today -> stringResource(R.string.when_today, time)
        today.plusDays(1) -> stringResource(R.string.when_tomorrow, time)
        else -> stringResource(R.string.when_day, at.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()), time)
    }
}

/** The days a schedule repeats on -- or, for a run-once automation, "Once" (on the next of those days). */
@Composable
fun repeatText(days: Set<DayOfWeek>, runOnce: Boolean): String = when {
    !runOnce -> daysText(days)
    days.isEmpty() || days.size == 7 -> stringResource(R.string.repeat_once)
    else -> stringResource(R.string.summary_pair, stringResource(R.string.repeat_once), daysText(days))
}

@Composable
fun opText(op: Op): String = stringResource(
    when (op) {
        Op.EQ -> R.string.op_eq
        Op.NE -> R.string.op_ne
        Op.GT -> R.string.op_gt
        Op.GE -> R.string.op_ge
        Op.LT -> R.string.op_lt
        Op.LE -> R.string.op_le
    },
)

@Composable
fun triggerSummary(trigger: Trigger, devices: List<DeviceOption>, runOnce: Boolean = false): String = when (trigger) {
    is Trigger.TimeOfDay ->
        stringResource(R.string.trigger_time_summary, formatTime(trigger.hour, trigger.minute), repeatText(trigger.days, runOnce))
    is Trigger.Sun ->
        stringResource(R.string.summary_pair, sunShortText(trigger.event, trigger.offsetMinutes), repeatText(trigger.days, runOnce))
    is Trigger.StateMatches -> {
        val device = devices.firstOrNull { it.key == trigger.device }
        val capability = device?.capability(trigger.key)
        val text = stringResource(
            R.string.trigger_state_summary,
            device?.name ?: stringResource(R.string.automation_unknown_device),
            capability?.label ?: trigger.key,
            opText(trigger.op),
            valueText(capability, trigger.value),
        )
        if (runOnce) stringResource(R.string.summary_pair, text, stringResource(R.string.repeat_once)) else text
    }
}

@Composable
fun actionSummary(action: Action, devices: List<DeviceOption>): String = when (action) {
    is Action.SetValues -> {
        val device = devices.firstOrNull { it.key == action.device }
        val changes = action.changes.map { change ->
            val capability = device?.capability(change.key)
            stringResource(R.string.action_change_summary, capability?.label ?: change.key, valueText(capability, change.value))
        }.joinToString(", ")
        stringResource(R.string.action_set_summary, device?.name ?: stringResource(R.string.automation_unknown_device), changes)
    }
    is Action.Notify -> stringResource(R.string.action_notify_summary, action.title.ifBlank { action.text })
}
