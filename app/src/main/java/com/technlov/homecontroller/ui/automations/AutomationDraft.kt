package com.technlov.homecontroller.ui.automations

import com.technlov.home.core.SetValue
import com.technlov.home.core.Action
import com.technlov.home.core.Automation
import com.technlov.home.core.Capability
import com.technlov.home.core.Condition
import com.technlov.home.core.DeviceKey
import com.technlov.home.core.Op
import com.technlov.home.core.Trigger
import com.technlov.home.core.Value
import com.technlov.homecontroller.data.HomeData
import com.technlov.homecontroller.hub.DeviceLive
import java.time.DayOfWeek

enum class TriggerKind { TIME, DEVICE }

enum class DraftProblem { NO_TRIGGER_DEVICE, NO_TRIGGER_VALUE, NO_ACTIONS, EMPTY_DEVICE_ACTION, EMPTY_NOTIFICATION }

/** A device as the automation editor sees it: what it can do and its latest values. */
data class DeviceOption(
    val key: DeviceKey,
    val name: String,
    val capabilities: List<Capability>,
    val values: Map<String, Value> = emptyMap(),
) {
    fun capability(key: String?): Capability? = capabilities.firstOrNull { it.key == key }

    /** Capabilities an action can set. */
    val settable: List<Capability>
        get() = capabilities.filter {
            when (it) {
                is Capability.Power -> true
                is Capability.Choice -> it.writable
                is Capability.Number -> it.writable
                is Capability.Toggle -> it.writable
                is Capability.Reading -> false
            }
        }
}

/** Saved devices with live capabilities when connected, the remembered ones otherwise (Power at least). */
fun deviceOptions(data: HomeData, live: Map<DeviceKey, DeviceLive>): List<DeviceOption> =
    data.sortedDevices.map { device ->
        val link = live[device.key]
        val capabilities = link?.capabilities?.takeIf { it.isNotEmpty() }
            ?: device.capabilities.takeIf { it.isNotEmpty() }
            ?: listOf(Capability.Power())
        DeviceOption(device.key, device.name, capabilities, link?.state?.values.orEmpty())
    }

/** What a new action on [device] starts with: power on if it has power, else its first settable value. */
fun defaultChanges(device: DeviceOption): List<SetValue> {
    val first = device.settable.firstOrNull { it is Capability.Power } ?: device.settable.firstOrNull() ?: return emptyList()
    return listOf(SetValue(first.key, defaultValue(first, null)))
}

/**
 * A "schedule" of [device]: an automation that only says "at this time, on these days, set this device". These are
 * listed on the device's page; anything with conditions, other devices or notifications stays in Automations only.
 */
fun Automation.isScheduleFor(device: DeviceKey): Boolean =
    trigger is Trigger.TimeOfDay && conditions.isEmpty() && actions.isNotEmpty() &&
        actions.all { it is Action.SetValues && it.device == device }

/** The editor's working copy of an automation. Pure; converted to and from [Automation]. */
data class AutomationDraft(
    val id: String,
    val isNew: Boolean,
    val name: String = "",
    val enabled: Boolean = true,
    val kind: TriggerKind = TriggerKind.TIME,
    val hour: Int = 7,
    val minute: Int = 0,
    val days: Set<DayOfWeek> = emptySet(),
    val device: DeviceKey? = null,
    val key: String? = null,
    val op: Op = Op.GE,
    val value: Value? = null,
    /** Kept as they are: the editor does not change conditions yet. */
    val conditions: List<Condition> = emptyList(),
    val actions: List<Action> = emptyList(),
) {
    fun problem(): DraftProblem? = when {
        kind == TriggerKind.DEVICE && (device == null || key == null) -> DraftProblem.NO_TRIGGER_DEVICE
        kind == TriggerKind.DEVICE && value == null -> DraftProblem.NO_TRIGGER_VALUE
        actions.isEmpty() -> DraftProblem.NO_ACTIONS
        actions.any { it is Action.SetValues && (it.device.isBlank() || it.changes.isEmpty()) } -> DraftProblem.EMPTY_DEVICE_ACTION
        actions.any { it is Action.Notify && it.title.isBlank() && it.text.isBlank() } -> DraftProblem.EMPTY_NOTIFICATION
        else -> null
    }

    /** The automation to save, or null while [problem] is not null. [fallbackName] names an unnamed one. */
    fun toAutomation(fallbackName: String): Automation? {
        if (problem() != null) return null
        val finalName = name.trim().ifEmpty { fallbackName }
        val trigger = when (kind) {
            TriggerKind.TIME -> Trigger.TimeOfDay(hour.coerceIn(0, 23), minute.coerceIn(0, 59), days)
            TriggerKind.DEVICE -> Trigger.StateMatches(device!!, key!!, op, value!!)
        }
        val cleanActions = actions.map {
            if (it is Action.Notify) it.copy(title = it.title.trim().ifEmpty { finalName }, text = it.text.trim()) else it
        }
        return Automation(id, finalName, enabled, trigger, conditions, cleanActions)
    }

    companion object {
        /** A new schedule for [device]: 07:00 every day, its default change (power on, usually). */
        fun newSchedule(id: String, device: DeviceOption): AutomationDraft = AutomationDraft(
            id = id,
            isNew = true,
            kind = TriggerKind.TIME,
            actions = listOf(Action.SetValues(device.key, defaultChanges(device))),
        )

        fun from(automation: Automation): AutomationDraft {
            val base = AutomationDraft(
                id = automation.id,
                isNew = false,
                name = automation.name,
                enabled = automation.enabled,
                conditions = automation.conditions,
                actions = automation.actions,
            )
            return when (val t = automation.trigger) {
                is Trigger.TimeOfDay -> base.copy(kind = TriggerKind.TIME, hour = t.hour, minute = t.minute, days = t.days)
                is Trigger.StateMatches -> base.copy(kind = TriggerKind.DEVICE, device = t.device, key = t.key, op = t.op, value = t.value)
            }
        }
    }
}

/** Comparisons that make sense for a capability: all of them for numbers, is / is not for the rest. */
fun opsFor(capability: Capability?): List<Op> = when (capability) {
    is Capability.Number, is Capability.Reading -> listOf(Op.GE, Op.GT, Op.LE, Op.LT, Op.EQ, Op.NE)
    else -> listOf(Op.EQ, Op.NE)
}

/** A sensible starting value for a capability: its current value when known. */
fun defaultValue(capability: Capability, current: Value?): Value = when (capability) {
    is Capability.Power, is Capability.Toggle -> (current as? Value.Bool) ?: Value.Bool(true)
    is Capability.Choice -> (current as? Value.Text) ?: Value.Text(capability.options.firstOrNull()?.value.orEmpty())
    is Capability.Number -> (current as? Value.Num)?.let { Value.Num(Math.round(it.v).toDouble()) }
        ?: Value.Num(((capability.min + capability.max) / 2).let { Math.round(it).toDouble() })
    is Capability.Reading -> when (current) {
        is Value.Num -> Value.Num(Math.round(current.v).toDouble())
        null -> Value.Num(0.0)
        else -> current
    }
}
