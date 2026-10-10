package com.technlov.homecontroller.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Input
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.TimerOff
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.DeviceThermostat
import androidx.compose.material.icons.rounded.DevicesOther
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.ToggleOn
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.WindPower
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.technlov.home.core.Capability
import com.technlov.home.core.DeviceCategory
import com.technlov.home.core.Keys
import com.technlov.home.core.Value
import com.technlov.homecontroller.R
import com.technlov.homecontroller.ui.theme.isDarkScheme
import java.text.NumberFormat

/** Flat colours per climate mode: cool = blue, heat = orange, dry = teal, fan = grey, auto = purple. */
object ModeColors {
    fun of(mode: String?, dark: Boolean): Color? = when (mode?.lowercase()) {
        "cool" -> if (dark) Color(0xFF7AB4FF) else Color(0xFF2F74E0)
        "heat" -> if (dark) Color(0xFFFFA463) else Color(0xFFE2620B)
        "dry" -> if (dark) Color(0xFF52D6C4) else Color(0xFF0E9484)
        "fan" -> if (dark) Color(0xFFA9B5BF) else Color(0xFF6E7C88)
        "auto" -> if (dark) Color(0xFFC2A2FA) else Color(0xFF8250D6)
        else -> null
    }
}

/** Colour for a climate mode; the theme's primary for anything else. */
@Composable
@ReadOnlyComposable
fun modeColor(mode: String?): Color = ModeColors.of(mode, isDarkScheme()) ?: MaterialTheme.colorScheme.primary

/** Green for "online", tuned for light and dark. */
@Composable
@ReadOnlyComposable
fun onlineColor(): Color = if (isDarkScheme()) Color(0xFF6FD99A) else Color(0xFF1E8E4E)

fun modeIcon(mode: String?): ImageVector = when (mode?.lowercase()) {
    "cool" -> Icons.Rounded.AcUnit
    "heat" -> Icons.Rounded.WbSunny
    "dry" -> Icons.Rounded.WaterDrop
    "fan" -> Icons.Rounded.Air
    "auto" -> Icons.Rounded.Autorenew
    else -> Icons.Rounded.Thermostat
}

fun categoryIcon(category: DeviceCategory): ImageVector = when (category) {
    DeviceCategory.CLIMATE -> Icons.Rounded.AcUnit
    DeviceCategory.TV -> Icons.Rounded.Tv
    DeviceCategory.SWITCH -> Icons.Rounded.ToggleOn
    DeviceCategory.LIGHT -> Icons.Rounded.Lightbulb
    DeviceCategory.SENSOR -> Icons.Rounded.Sensors
    DeviceCategory.MEDIA -> Icons.Rounded.Speaker
    DeviceCategory.OTHER -> Icons.Rounded.DevicesOther
}

/** An icon for a capability, by its well-known key (Gree's extra feature keys included). */
fun capabilityIcon(key: String): ImageVector = when (key) {
    Keys.POWER -> Icons.Rounded.PowerSettingsNew
    Keys.MODE -> Icons.Rounded.Tune
    Keys.TARGET_TEMPERATURE -> Icons.Rounded.Thermostat
    Keys.CURRENT_TEMPERATURE -> Icons.Rounded.DeviceThermostat
    Keys.FAN_SPEED -> Icons.Rounded.Air
    Keys.SWING_VERTICAL -> Icons.Rounded.SwapVert
    Keys.SWING_HORIZONTAL -> Icons.Rounded.SwapHoriz
    Keys.DISPLAY_LIGHT -> Icons.Rounded.Lightbulb
    Keys.FAULT -> Icons.Rounded.ErrorOutline
    Keys.FIRMWARE -> Icons.Rounded.Memory
    Keys.VOLUME -> Icons.AutoMirrored.Rounded.VolumeUp
    Keys.BRIGHTNESS -> Icons.Rounded.LightMode
    Keys.INPUT -> Icons.AutoMirrored.Rounded.Input
    "turbo" -> Icons.Rounded.Bolt
    "quiet" -> Icons.AutoMirrored.Rounded.VolumeOff
    "sleep" -> Icons.Rounded.Bedtime
    "health" -> Icons.Rounded.Spa
    "x_fan" -> Icons.Rounded.WindPower
    "clean_filter" -> Icons.Rounded.FilterAlt
    "clock" -> Icons.Rounded.Schedule
    "child_lock" -> Icons.Rounded.Lock
    "defrost" -> Icons.Rounded.AcUnit
    "timer_on" -> Icons.Rounded.Timer
    "timer_off" -> Icons.Rounded.TimerOff
    "night_mode" -> Icons.Rounded.NightsStay
    "color_temperature" -> Icons.Rounded.Palette
    else -> Icons.Rounded.Tune
}

private val numberFormat: NumberFormat
    get() = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }

/** 22.0 -> "22", 22.5 -> "22.5" (in the user's locale). */
fun formatNumber(value: Double): String = numberFormat.format(value)

/** A value as words: the option's label for choices, On/Off for switches, the number with its unit. */
@Composable
fun valueText(capability: Capability?, value: Value?): String {
    if (value == null) return stringResource(R.string.value_unknown)
    return when (value) {
        is Value.Bool -> stringResource(if (value.v) R.string.value_on else R.string.value_off)
        is Value.Num -> {
            val unit = when (capability) {
                is Capability.Number -> capability.unit
                is Capability.Reading -> capability.unit
                else -> ""
            }
            formatNumber(value.v) + unitSuffix(unit)
        }
        is Value.Text -> (capability as? Capability.Choice)?.options?.firstOrNull { it.value == value.v }?.label ?: value.v
    }
}

/** "°C" and "%" sit right after the number; other units get a space. */
fun unitSuffix(unit: String): String = when {
    unit.isEmpty() -> ""
    unit.startsWith("°") || unit == "%" -> unit
    else -> " $unit"
}

fun List<Capability>.find(key: String): Capability? = firstOrNull { it.key == key }
