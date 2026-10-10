package com.technlov.home.connectors.gree

import com.technlov.home.core.Capability
import com.technlov.home.core.Keys
import com.technlov.home.core.SetValue
import com.technlov.home.core.Value

/** Capability keys this connector adds to the shared [Keys]. */
object GreeKeys {
    const val TURBO = "turbo"
    const val QUIET = "quiet"
    const val SLEEP = "sleep"
    const val HEALTH = "health"
    const val X_FAN = "x_fan"
    const val CLEAN_FILTER = "clean_filter"
    const val CLOCK = "clock"
    const val CHILD_LOCK = "child_lock"
    const val DEFROST = "defrost"
    const val TIMER_ON = "timer_on"
    const val TIMER_OFF = "timer_off"
}

/**
 * The Gree status columns and how they map to Home Controller capabilities and values.
 * Codes and meanings from greeclimate (device.py) and probes of real units.
 */
internal object GreeModel {
    /** Asked on every read. Only known names: an old module answered nothing to a request with unknown ones. */
    val STATUS_COLUMNS = listOf(
        "Pow", "Mod", "SetTem", "TemUn", "TemRec", "TemSen", "WdSpd", "Air", "Blo", "Health", "SwhSlp", "SlpMod",
        "Lig", "SwingLfRig", "SwUpDn", "Quiet", "Tur", "StHt", "HeatCoolType", "SvSt",
    )

    /** Asked in a separate small request, less often. */
    val DIAGNOSTIC_COLUMNS = listOf("AllErr", "Dfltr", "hid", "time")

    /**
     * Asked in their own small request (at open, then with the diagnostics), NEVER in [STATUS_COLUMNS]: modules differ
     * on them (V1.2.1 leaves out ChildLock), and an old module went silent to a long request with names it did not know.
     * Only the ones a unit answers at open become capabilities.
     */
    val EXTRA_COLUMNS = listOf("ChildLock", "TmrOn", "TmrOff", "Defrost")

    const val TEMP_MIN = 16
    const val TEMP_MAX = 30

    /** Some modules report the room sensor as degrees C + 40 (68 -> 28). */
    const val TEMP_SENSOR_OFFSET = 40

    private fun opts(vararg pairs: Pair<String, String>) = pairs.map { Capability.Option(it.first, it.second) }

    /** Index in each list = the code the unit uses. */
    val MODES = opts("auto" to "Auto", "cool" to "Cool", "dry" to "Dry", "fan" to "Fan", "heat" to "Heat")
    val FAN_SPEEDS = opts(
        "auto" to "Auto", "low" to "Low", "medium_low" to "Medium-low", "medium" to "Medium",
        "medium_high" to "Medium-high", "high" to "High",
    )
    val SWING_VERTICAL = opts(
        "default" to "Default", "full_swing" to "Full swing",
        "fixed_upper" to "Fixed upper", "fixed_upper_middle" to "Fixed upper-middle", "fixed_middle" to "Fixed middle",
        "fixed_lower_middle" to "Fixed lower-middle", "fixed_lower" to "Fixed lower",
        "swing_lower" to "Swing lower", "swing_lower_middle" to "Swing lower-middle", "swing_middle" to "Swing middle",
        "swing_upper_middle" to "Swing upper-middle", "swing_upper" to "Swing upper",
    )
    val SWING_HORIZONTAL = opts(
        "default" to "Default", "full_swing" to "Full swing", "left" to "Left", "left_centre" to "Left-centre",
        "centre" to "Centre", "right_centre" to "Right-centre", "right" to "Right",
    )

    /** One capability and the column(s) behind it. */
    private sealed class Field(val capability: Capability, val column: String, val diagnostic: Boolean = false)

    private class PowerField : Field(Capability.Power(), "Pow")
    private class ChoiceField(key: String, label: String, column: String, val options: List<Capability.Option>) :
        Field(Capability.Choice(key, label, options), column)

    /** [onValue] is what "on" is written as (Quiet uses 2, as greeclimate does); [alsoSets] get the same 0/1. */
    private class ToggleField(
        key: String,
        label: String,
        column: String,
        val onValue: Int = 1,
        val alsoSets: List<String> = emptyList(),
    ) : Field(Capability.Toggle(key, label), column)

    private class TargetTempField : Field(
        Capability.Number(Keys.TARGET_TEMPERATURE, "Target temperature", TEMP_MIN.toDouble(), TEMP_MAX.toDouble(), 1.0, "°C"),
        "SetTem",
    )

    private class ReadingField(key: String, label: String, column: String, unit: String = "", diagnostic: Boolean = false) :
        Field(Capability.Reading(key, label, unit, diagnostic), column, diagnostic)

    private val FIELDS: List<Field> = listOf(
        PowerField(),
        ChoiceField(Keys.MODE, "Mode", "Mod", MODES),
        TargetTempField(),
        ReadingField(Keys.CURRENT_TEMPERATURE, "Room temperature", "TemSen", "°C"),
        ChoiceField(Keys.FAN_SPEED, "Fan speed", "WdSpd", FAN_SPEEDS),
        ChoiceField(Keys.SWING_VERTICAL, "Vertical swing", "SwUpDn", SWING_VERTICAL),
        ChoiceField(Keys.SWING_HORIZONTAL, "Horizontal swing", "SwingLfRig", SWING_HORIZONTAL),
        ToggleField(Keys.DISPLAY_LIGHT, "Display light", "Lig"),
        ToggleField(GreeKeys.TURBO, "Turbo", "Tur"),
        ToggleField(GreeKeys.QUIET, "Quiet", "Quiet", onValue = 2),
        ToggleField(GreeKeys.SLEEP, "Sleep", "SwhSlp", alsoSets = listOf("SlpMod")),
        ToggleField(GreeKeys.HEALTH, "Ioniser", "Health"),
        ToggleField(GreeKeys.X_FAN, "X-Fan", "Blo"),
        ToggleField(GreeKeys.CHILD_LOCK, "Child lock", "ChildLock"),
        ReadingField(Keys.FAULT, "Fault", "AllErr", diagnostic = true),
        ReadingField(Keys.FIRMWARE, "Module firmware", "hid", diagnostic = true),
        ReadingField(GreeKeys.CLEAN_FILTER, "Clean filter", "Dfltr", diagnostic = true),
        ReadingField(GreeKeys.CLOCK, "Unit clock", "time", diagnostic = true),
        ReadingField(GreeKeys.DEFROST, "Defrosting", "Defrost", diagnostic = true),
        ReadingField(GreeKeys.TIMER_ON, "On timer set", "TmrOn", diagnostic = true),
        ReadingField(GreeKeys.TIMER_OFF, "Off timer set", "TmrOff", diagnostic = true),
    )

    private val BY_KEY = FIELDS.associateBy { it.capability.key }

    /**
     * The capabilities to declare. [answered] = the status and diagnostic columns the unit returned at the first read;
     * a field whose column it did not return is left out (null or empty = unknown: declare them all). [extras] = the
     * [EXTRA_COLUMNS] it answered: those are declared only when answered.
     */
    fun capabilities(answered: Set<String>? = null, extras: Set<String> = emptySet()): List<Capability> =
        FIELDS.filter {
            if (it.column in EXTRA_COLUMNS) it.column in extras else answered.isNullOrEmpty() || it.column in answered
        }.map { it.capability }

    /** Raw status (+ diagnostic) columns -> capability values. Columns the unit did not send are left out. */
    fun decode(raw: Map<String, Any?>): Map<String, Value> {
        val out = LinkedHashMap<String, Value>()
        for (f in FIELDS) {
            val v = raw[f.column] ?: continue
            val value: Value? = when (f) {
                is PowerField, is ToggleField -> int(v)?.let { Value.Bool(it != 0) }
                is ChoiceField -> int(v)?.let { code -> Value.Text(f.options.getOrNull(code)?.value ?: code.toString()) }
                is TargetTempField -> num(v)?.let { Value.Num(it) }
                is ReadingField -> decodeReading(f.capability.key, v)
            }
            if (value != null) out[f.capability.key] = value
        }
        return out
    }

    private fun decodeReading(key: String, v: Any): Value? = when (key) {
        Keys.CURRENT_TEMPERATURE -> num(v)?.let { roomTemperature(it) }?.let { Value.Num(it) }
        Keys.FAULT -> int(v)?.let { Value.Text(if (it == 0) "none" else "code $it") }
        GreeKeys.CLEAN_FILTER -> int(v)?.let { Value.Bool(it != 0) }
        GreeKeys.DEFROST, GreeKeys.TIMER_ON, GreeKeys.TIMER_OFF -> int(v)?.let { Value.Text(if (it != 0) "yes" else "no") }
        Keys.FIRMWARE -> moduleBuild(v.toString())?.let { Value.Text(it) }
        else -> v.toString().trim().takeIf { it.isNotEmpty() }?.let { Value.Text(it) }
    }

    /** `hid` as the build name: "<digits>+U-WB05RT13V1.09.bin" -> "WB05RT13V1.09"; other forms are kept as sent. */
    fun moduleBuild(hid: String): String? =
        hid.trim().removeSuffix(".bin").substringAfterLast('-').trim().takeIf { it.isNotEmpty() }

    /**
     * Room temperature in degrees C from TemSen: values of 40 and up carry the +40 offset; below 40 they are taken
     * as degrees C already. 0 means "no reading" (as greeclimate treats it) and gives null.
     */
    fun roomTemperature(raw: Double): Double? = when {
        raw == 0.0 -> null
        raw >= TEMP_SENSOR_OFFSET -> raw - TEMP_SENSOR_OFFSET
        else -> raw
    }

    /**
     * The `cmd` columns for [changes], in order (a later change to the same column wins). Throws
     * [IllegalArgumentException] for an unknown or read-only key or a value out of range -- before anything is sent.
     */
    fun encode(changes: List<SetValue>): LinkedHashMap<String, Int> {
        val out = LinkedHashMap<String, Int>()
        for (c in changes) {
            val f = BY_KEY[c.key] ?: throw IllegalArgumentException("unknown setting '${c.key}'")
            when (f) {
                is PowerField -> out["Pow"] = if (bool(c)) 1 else 0
                is ToggleField -> {
                    val on = bool(c)
                    out[f.column] = if (on) f.onValue else 0
                    for (col in f.alsoSets) out[col] = if (on) 1 else 0
                }
                is ChoiceField -> out[f.column] = choice(c, f.options)
                is TargetTempField -> {
                    val t = number(c)
                    val whole = Math.round(t).toInt()
                    if (whole < TEMP_MIN || whole > TEMP_MAX) {
                        throw IllegalArgumentException("target temperature $t is outside $TEMP_MIN-$TEMP_MAX °C")
                    }
                    out["SetTem"] = whole
                    out["TemUn"] = 0   // degrees C
                    out["TemRec"] = 0  // no half-degree (Fahrenheit) bit
                }
                is ReadingField -> throw IllegalArgumentException("'${c.key}' is read-only")
            }
        }
        return out
    }

    private fun bool(c: SetValue): Boolean = when (val v = c.value) {
        is Value.Bool -> v.v
        is Value.Num -> v.v != 0.0
        is Value.Text -> when (v.v.trim().lowercase()) {
            "on", "true", "1", "yes" -> true
            "off", "false", "0", "no" -> false
            else -> throw IllegalArgumentException("'${c.key}' needs on/off, not '${v.v}'")
        }
    }

    private fun number(c: SetValue): Double = when (val v = c.value) {
        is Value.Num -> v.v
        is Value.Text -> v.v.trim().toDoubleOrNull() ?: throw IllegalArgumentException("'${c.key}' needs a number, not '${v.v}'")
        is Value.Bool -> throw IllegalArgumentException("'${c.key}' needs a number")
    }

    private fun choice(c: SetValue, options: List<Capability.Option>): Int {
        val v = c.value
        val code = when (v) {
            is Value.Text -> {
                val s = v.v.trim()
                options.indexOfFirst { it.value.equals(s, true) }.takeIf { it >= 0 }
                    ?: options.indexOfFirst { it.label.equals(s, true) }.takeIf { it >= 0 }
                    ?: s.toIntOrNull()
            }
            is Value.Num -> if (v.v == Math.floor(v.v)) v.v.toInt() else null
            is Value.Bool -> null
        }
        if (code == null || code !in options.indices) {
            throw IllegalArgumentException(
                "'${c.key}' must be one of ${options.joinToString { it.value }}, not ${describe(v)}",
            )
        }
        return code
    }

    private fun describe(v: Value) = when (v) {
        is Value.Text -> "'${v.v}'"
        is Value.Num -> v.v.toString()
        is Value.Bool -> v.v.toString()
    }

    private fun int(v: Any?): Int? = when (v) {
        is Int -> v
        is Number -> v.toDouble().takeIf { it == Math.floor(it) }?.toInt()
        is String -> v.trim().toIntOrNull()
        is Boolean -> if (v) 1 else 0
        else -> null
    }

    private fun num(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull()
        else -> null
    }
}
