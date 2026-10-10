package com.technlov.homecontroller.data

import com.technlov.home.core.Action
import com.technlov.home.core.Automation
import com.technlov.home.core.Capability
import com.technlov.home.core.Condition
import com.technlov.home.core.DeviceCategory
import com.technlov.home.core.DeviceRef
import com.technlov.home.core.Op
import com.technlov.home.core.SetValue
import com.technlov.home.core.SunEvent
import com.technlov.home.core.Trigger
import com.technlov.home.core.Value
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.format.DateTimeParseException

/**
 * The saved data as JSON. The stored shape is a set of DTOs, not the core classes, so the plugin contract can change
 * without breaking what people have saved: unknown keys are ignored and unknown enum names fall back to safe values.
 */
object HomeJson {
    const val FORMAT_VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    fun encode(data: HomeData): String = json.encodeToString(HomeDataDto.serializer(), data.toDto())

    fun decode(text: String): HomeData = json.decodeFromString(HomeDataDto.serializer(), text).toModel()
}

// ---- stored shape ----

@Serializable
internal data class HomeDataDto(
    val version: Int = HomeJson.FORMAT_VERSION,
    val devices: List<DeviceDto> = emptyList(),
    val rooms: List<RoomDto> = emptyList(),
    val automations: List<AutomationDto> = emptyList(),
    val settings: SettingsDto = SettingsDto(),
    /** Automation id -> ISO local date-time of its last run ("2026-10-12T07:30"). */
    val lastRuns: Map<String, String> = emptyMap(),
)

@Serializable
internal data class DeviceDto(
    val connectorId: String,
    val id: String,
    val address: String,
    val reportedName: String = "",
    val category: String = "OTHER",
    val model: String? = null,
    val firmware: String? = null,
    val extras: Map<String, String> = emptyMap(),
    val name: String,
    val roomId: String? = null,
    val hidden: Boolean = false,
    val order: Int = 0,
    val capabilities: List<CapabilityDto> = emptyList(),
    val powerButton: String = "SHOW",
)

@Serializable
internal data class RoomDto(val id: String, val name: String, val order: Int = 0)

@Serializable
internal data class SettingsDto(
    val runInBackground: Boolean = false,
    val foregroundPollSeconds: Int = AppSettings.DEFAULT_FOREGROUND_POLL,
    val backgroundPollSeconds: Int = AppSettings.DEFAULT_BACKGROUND_POLL,
    val theme: String = ThemeMode.SYSTEM.name,
    val dynamicColor: Boolean = true,
    val verboseLogging: Boolean = false,
    val homeLocation: LocationDto? = null,
)

@Serializable
internal data class LocationDto(val latitude: Double, val longitude: Double)

@Serializable
internal data class OptionDto(val value: String, val label: String)

@Serializable
internal sealed class CapabilityDto {
    abstract val key: String
    abstract val label: String

    @Serializable @SerialName("power")
    data class Power(override val key: String, override val label: String) : CapabilityDto()

    @Serializable @SerialName("choice")
    data class Choice(
        override val key: String,
        override val label: String,
        val options: List<OptionDto> = emptyList(),
        val writable: Boolean = true,
    ) : CapabilityDto()

    @Serializable @SerialName("number")
    data class Number(
        override val key: String,
        override val label: String,
        val min: Double,
        val max: Double,
        val step: Double = 1.0,
        val unit: String = "",
        val writable: Boolean = true,
    ) : CapabilityDto()

    @Serializable @SerialName("toggle")
    data class Toggle(override val key: String, override val label: String, val writable: Boolean = true) : CapabilityDto()

    @Serializable @SerialName("reading")
    data class Reading(
        override val key: String,
        override val label: String,
        val unit: String = "",
        val diagnostic: Boolean = false,
    ) : CapabilityDto()
}

@Serializable
internal sealed class ValueDto {
    @Serializable @SerialName("bool") data class Bool(val v: Boolean) : ValueDto()
    @Serializable @SerialName("num") data class Num(val v: Double) : ValueDto()
    @Serializable @SerialName("text") data class Text(val v: String) : ValueDto()
}

@Serializable
internal data class SetValueDto(val key: String, val value: ValueDto)

@Serializable
internal data class AutomationDto(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val trigger: TriggerDto,
    val conditions: List<ConditionDto> = emptyList(),
    val actions: List<ActionDto> = emptyList(),
    val runOnce: Boolean = false,
)

@Serializable
internal sealed class TriggerDto {
    @Serializable @SerialName("time")
    data class TimeOfDay(val hour: Int, val minute: Int, val days: List<String> = emptyList()) : TriggerDto()

    @Serializable @SerialName("sun")
    data class Sun(val event: String, val offsetMinutes: Int = 0, val days: List<String> = emptyList()) : TriggerDto()

    @Serializable @SerialName("state")
    data class StateMatches(val device: String, val key: String, val op: String, val value: ValueDto) : TriggerDto()
}

@Serializable
internal sealed class ConditionDto {
    @Serializable @SerialName("state")
    data class StateCompare(val device: String, val key: String, val op: String, val value: ValueDto) : ConditionDto()

    @Serializable @SerialName("time_between")
    data class TimeBetween(val fromHour: Int, val fromMinute: Int, val toHour: Int, val toMinute: Int) : ConditionDto()
}

@Serializable
internal sealed class ActionDto {
    @Serializable @SerialName("set")
    data class SetValues(val device: String, val changes: List<SetValueDto>) : ActionDto()

    @Serializable @SerialName("notify")
    data class Notify(val title: String, val text: String) : ActionDto()
}

// ---- mapping ----

private inline fun <reified E : Enum<E>> enumOr(name: String, fallback: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: fallback

private fun List<String>.toDays(): Set<DayOfWeek> = mapNotNull { d -> DayOfWeek.entries.firstOrNull { it.name == d } }.toSet()

internal fun HomeData.toDto() = HomeDataDto(
    devices = devices.map { it.toDto() },
    rooms = rooms.map { RoomDto(it.id, it.name, it.order) },
    automations = automations.map { it.toDto() },
    settings = SettingsDto(
        runInBackground = settings.runInBackground,
        foregroundPollSeconds = settings.foregroundPollSeconds,
        backgroundPollSeconds = settings.backgroundPollSeconds,
        theme = settings.theme.name,
        dynamicColor = settings.dynamicColor,
        verboseLogging = settings.verboseLogging,
        homeLocation = settings.homeLocation?.let { LocationDto(it.latitude, it.longitude) },
    ),
    lastRuns = lastRuns.mapValues { it.value.toString() },
)

internal fun HomeDataDto.toModel() = HomeData(
    devices = devices.map { it.toModel() },
    rooms = rooms.map { Room(it.id, it.name, it.order) },
    automations = automations.map { it.toModel() },
    settings = AppSettings(
        runInBackground = settings.runInBackground,
        foregroundPollSeconds = settings.foregroundPollSeconds.coerceIn(1, 3_600),
        backgroundPollSeconds = settings.backgroundPollSeconds.coerceIn(5, 3_600),
        theme = enumOr(settings.theme, ThemeMode.SYSTEM),
        dynamicColor = settings.dynamicColor,
        verboseLogging = settings.verboseLogging,
        // A place off the map is dropped: sun schedules then ask for the location again.
        homeLocation = settings.homeLocation?.let { HomeLocation.of(it.latitude, it.longitude) },
    ),
    // An unreadable time is dropped: at worst that automation is not caught up once.
    lastRuns = lastRuns.mapNotNull { (id, at) ->
        try {
            id to LocalDateTime.parse(at)
        } catch (e: DateTimeParseException) {
            null
        }
    }.toMap(),
)

private fun SavedDevice.toDto() = DeviceDto(
    connectorId = ref.connectorId,
    id = ref.id,
    address = ref.address,
    reportedName = ref.name,
    category = ref.category.name,
    model = ref.model,
    firmware = ref.firmware,
    extras = ref.extras,
    name = name,
    roomId = roomId,
    hidden = hidden,
    order = order,
    capabilities = capabilities.map { it.toDto() },
    powerButton = powerButton.name,
)

private fun DeviceDto.toModel() = SavedDevice(
    ref = DeviceRef(
        connectorId = connectorId,
        id = id,
        address = address,
        name = reportedName.ifEmpty { name },
        category = enumOr(category, DeviceCategory.OTHER),
        model = model,
        firmware = firmware,
        extras = extras,
    ),
    name = name,
    roomId = roomId,
    hidden = hidden,
    order = order,
    capabilities = capabilities.map { it.toModel() },
    powerButton = enumOr(powerButton, PowerButton.SHOW),
)

private fun Capability.toDto(): CapabilityDto = when (this) {
    is Capability.Power -> CapabilityDto.Power(key, label)
    is Capability.Choice -> CapabilityDto.Choice(key, label, options.map { OptionDto(it.value, it.label) }, writable)
    is Capability.Number -> CapabilityDto.Number(key, label, min, max, step, unit, writable)
    is Capability.Toggle -> CapabilityDto.Toggle(key, label, writable)
    is Capability.Reading -> CapabilityDto.Reading(key, label, unit, diagnostic)
}

private fun CapabilityDto.toModel(): Capability = when (this) {
    is CapabilityDto.Power -> Capability.Power(key, label)
    is CapabilityDto.Choice -> Capability.Choice(key, label, options.map { Capability.Option(it.value, it.label) }, writable)
    is CapabilityDto.Number -> Capability.Number(key, label, min, max, step, unit, writable)
    is CapabilityDto.Toggle -> Capability.Toggle(key, label, writable)
    is CapabilityDto.Reading -> Capability.Reading(key, label, unit, diagnostic)
}

private fun Value.toDto(): ValueDto = when (this) {
    is Value.Bool -> ValueDto.Bool(v)
    is Value.Num -> ValueDto.Num(v)
    is Value.Text -> ValueDto.Text(v)
}

private fun ValueDto.toModel(): Value = when (this) {
    is ValueDto.Bool -> Value.Bool(v)
    is ValueDto.Num -> Value.Num(v)
    is ValueDto.Text -> Value.Text(v)
}

private fun Automation.toDto() = AutomationDto(
    id = id,
    name = name,
    enabled = enabled,
    trigger = when (val t = trigger) {
        is Trigger.TimeOfDay -> TriggerDto.TimeOfDay(t.hour, t.minute, t.days.sorted().map { it.name })
        is Trigger.Sun -> TriggerDto.Sun(t.event.name, t.offsetMinutes, t.days.sorted().map { it.name })
        is Trigger.StateMatches -> TriggerDto.StateMatches(t.device, t.key, t.op.name, t.value.toDto())
    },
    conditions = conditions.map {
        when (it) {
            is Condition.StateCompare -> ConditionDto.StateCompare(it.device, it.key, it.op.name, it.value.toDto())
            is Condition.TimeBetween -> ConditionDto.TimeBetween(it.fromHour, it.fromMinute, it.toHour, it.toMinute)
        }
    },
    actions = actions.map {
        when (it) {
            is Action.SetValues -> ActionDto.SetValues(it.device, it.changes.map { c -> SetValueDto(c.key, c.value.toDto()) })
            is Action.Notify -> ActionDto.Notify(it.title, it.text)
        }
    },
    runOnce = runOnce,
)

private fun AutomationDto.toModel() = Automation(
    id = id,
    name = name,
    enabled = enabled,
    trigger = when (val t = trigger) {
        is TriggerDto.TimeOfDay -> Trigger.TimeOfDay(
            hour = t.hour.coerceIn(0, 23),
            minute = t.minute.coerceIn(0, 59),
            days = t.days.toDays(),
        )
        is TriggerDto.Sun -> Trigger.Sun(
            event = enumOr(t.event, SunEvent.SUNSET),
            offsetMinutes = t.offsetMinutes.coerceIn(-Trigger.Sun.MAX_OFFSET_MINUTES, Trigger.Sun.MAX_OFFSET_MINUTES),
            days = t.days.toDays(),
        )
        is TriggerDto.StateMatches -> Trigger.StateMatches(t.device, t.key, enumOr(t.op, Op.EQ), t.value.toModel())
    },
    conditions = conditions.map {
        when (it) {
            is ConditionDto.StateCompare -> Condition.StateCompare(it.device, it.key, enumOr(it.op, Op.EQ), it.value.toModel())
            is ConditionDto.TimeBetween -> Condition.TimeBetween(it.fromHour, it.fromMinute, it.toHour, it.toMinute)
        }
    },
    actions = actions.map {
        when (it) {
            is ActionDto.SetValues -> Action.SetValues(it.device, it.changes.map { c -> SetValue(c.key, c.value.toModel()) })
            is ActionDto.Notify -> Action.Notify(it.title, it.text)
        }
    },
    runOnce = runOnce,
)
