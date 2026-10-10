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
    const val FRESH_AIR = "fresh_air"
    const val STEADY_HEAT = "steady_heat"
    const val ENERGY_SAVING = "energy_saving"
    const val ANTI_DIRECT_BLOW = "anti_direct_blow"
    const val AUTO_DISPLAY_LIGHT = "auto_display_light"
    const val OUTDOOR_TEMPERATURE = "outdoor_temperature"
    const val HUMIDITY = "humidity"

    /** Not a unit column: the app's own per-device setting for the commands it sends (see GreeModel.buzzerColumns). */
    const val BEEP = "beep"
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

    /**
     * Newer optional columns, probed at open in a second small request of their own -- never added to [EXTRA_COLUMNS],
     * so a module that goes silent to names it does not know keeps the extras it has. Asked only when the unit replied
     * to the extras request; after that they work like [EXTRA_COLUMNS]: declared only when answered at open.
     * AntiDirectBlow 0/1; LigSen 0 = the light sensor runs the display light (inverted); OutEnvTem the outdoor
     * temperature (+40 like TemSen, see [outdoorTemperature]); DwatSen the room humidity in %.
     */
    val PROBE_COLUMNS = listOf("AntiDirectBlow", "LigSen", "OutEnvTem", "DwatSen")

    /** Both optional groups: declared only when a unit answers them, never asked in [STATUS_COLUMNS]. */
    val OPTIONAL_COLUMNS: Set<String> = (EXTRA_COLUMNS + PROBE_COLUMNS).toSet()

    /**
     * The beeper flags. Sent in commands only, never read (units report them inconsistently), and they act on the
     * command that carries them -- the unit does not store them. Buzzer_ON_OFF = 1 silences older module firmware,
     * BuzzerCtrl = 0 newer firmware (1.44 and up); some modules honour neither.
     */
    const val BUZZER_ON_OFF = "Buzzer_ON_OFF"
    const val BUZZER_CTRL = "BuzzerCtrl"
    val BUZZER_COLUMNS = setOf(BUZZER_ON_OFF, BUZZER_CTRL)

    /**
     * What every command carries for "Beep on commands". Off: both flags, so the unit stays silent whichever one its
     * firmware reads. On: neither -- a command without them beeps as it always has, and a module that does not know
     * them gets exactly the command it got before this setting existed.
     */
    fun buzzerColumns(beep: Boolean): Map<String, Int> =
        if (beep) emptyMap() else linkedMapOf(BUZZER_ON_OFF to 1, BUZZER_CTRL to 0)

    /** "Beep on commands": the app's setting rather than a column, so every unit declares it (last). */
    val BEEP = Capability.Toggle(GreeKeys.BEEP, "Beep on commands")

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

    /**
     * [onValue] / [offValue] are what "on" / "off" are written as (Quiet writes 2 for on, as greeclimate does; LigSen is
     * inverted); anything but [offValue] reads as on. [alsoSets] get the same 0/1. [withOn] are written as well when
     * turning on (the auto display light also switches the display light on).
     */
    private class ToggleField(
        key: String,
        label: String,
        column: String,
        val onValue: Int = 1,
        val offValue: Int = 0,
        val alsoSets: List<String> = emptyList(),
        val withOn: Map<String, Int> = emptyMap(),
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
        // PROBE_COLUMNS: shown only on units that answer them.
        ReadingField(GreeKeys.OUTDOOR_TEMPERATURE, "Outdoor temperature", "OutEnvTem", "°C"),
        ReadingField(GreeKeys.HUMIDITY, "Humidity", "DwatSen", "%"),
        ChoiceField(Keys.FAN_SPEED, "Fan speed", "WdSpd", FAN_SPEEDS),
        ChoiceField(Keys.SWING_VERTICAL, "Vertical swing", "SwUpDn", SWING_VERTICAL),
        ChoiceField(Keys.SWING_HORIZONTAL, "Horizontal swing", "SwingLfRig", SWING_HORIZONTAL),
        ToggleField(Keys.DISPLAY_LIGHT, "Display light", "Lig"),
        // Inverted: LigSen 0 = the light sensor runs the display light. Turning it on also switches the light on.
        ToggleField(
            GreeKeys.AUTO_DISPLAY_LIGHT, "Auto display light", "LigSen", onValue = 0, offValue = 1, withOn = mapOf("Lig" to 1),
        ),
        ToggleField(GreeKeys.TURBO, "Turbo", "Tur"),
        ToggleField(GreeKeys.QUIET, "Quiet", "Quiet", onValue = 2),
        ToggleField(GreeKeys.SLEEP, "Sleep", "SwhSlp", alsoSets = listOf("SlpMod")),
        ToggleField(GreeKeys.HEALTH, "Ioniser", "Health"),
        ToggleField(GreeKeys.X_FAN, "X-Fan", "Blo"),
        ToggleField(GreeKeys.ANTI_DIRECT_BLOW, "Anti direct blow", "AntiDirectBlow"),
        // Shown only on units that answer these columns (greeclimate's Air / StHt / SvSt; HA calls StHt "away",
        // SvSt "eco"). StHt holds the room at 8 C in heat mode; SvSt works in cool mode. The connector does not block
        // any of these by mode (see docs/connectors/gree.md, "Features and modes").
        ToggleField(GreeKeys.FRESH_AIR, "Fresh air", "Air"),
        ToggleField(GreeKeys.STEADY_HEAT, "8 °C frost protection", "StHt"),
        ToggleField(GreeKeys.ENERGY_SAVING, "Energy saving", "SvSt"),
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
     * a field whose column it did not return is left out (null or empty = unknown: declare them all). [optional] = the
     * [OPTIONAL_COLUMNS] it answered: those are declared only when answered. [BEEP] is always declared, last.
     */
    fun capabilities(answered: Set<String>? = null, optional: Set<String> = emptySet()): List<Capability> =
        FIELDS.filter {
            if (it.column in OPTIONAL_COLUMNS) it.column in optional else answered.isNullOrEmpty() || it.column in answered
        }.map { it.capability } + BEEP

    /** Raw status (+ diagnostic, + optional) columns -> capability values. Columns the unit did not send are left out. */
    fun decode(raw: Map<String, Any?>): Map<String, Value> {
        val out = LinkedHashMap<String, Value>()
        for (f in FIELDS) {
            val v = raw[f.column] ?: continue
            val value: Value? = when (f) {
                is PowerField -> int(v)?.let { Value.Bool(it != 0) }
                is ToggleField -> int(v)?.let { Value.Bool(it != f.offValue) }
                is ChoiceField -> int(v)?.let { code -> Value.Text(f.options.getOrNull(code)?.value ?: code.toString()) }
                is TargetTempField -> num(v)?.let { Value.Num(it) }
                is ReadingField -> decodeReading(f.capability.key, v, raw)
            }
            if (value != null) out[f.capability.key] = value
        }
        return out
    }

    private fun decodeReading(key: String, v: Any, raw: Map<String, Any?>): Value? = when (key) {
        Keys.CURRENT_TEMPERATURE -> num(v)?.let { roomTemperature(it) }?.let { Value.Num(it) }
        GreeKeys.OUTDOOR_TEMPERATURE -> num(v)?.let { outdoorTemperature(it, num(raw["TemSen"])) }?.let { Value.Num(it) }
        // 0 (or anything outside 1-100) = no sensor or no reading, as on units without one.
        GreeKeys.HUMIDITY -> num(v)?.takeIf { it > 0.0 && it <= 100.0 }?.let { Value.Num(it) }
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
     * Outdoor temperature in degrees C from OutEnvTem, which carries the +40 offset on the modules that add it to
     * TemSen. Outdoor values run below 0 and above 40 C, where the room rule on the value alone guesses wrong (45 is
     * 5 C with the offset, 45 C without), so the unit's own room reading [roomRaw] decides: TemSen of 40 and up means
     * this module adds 40, a lower TemSen means it does not. Without a room reading, [roomTemperature]'s rule is used
     * on the value itself. 0 means "no reading".
     */
    fun outdoorTemperature(raw: Double, roomRaw: Double? = null): Double? = when {
        raw == 0.0 -> null
        roomRaw == null || roomRaw == 0.0 -> roomTemperature(raw)
        roomRaw >= TEMP_SENSOR_OFFSET -> raw - TEMP_SENSOR_OFFSET
        else -> raw
    }

    /**
     * The `cmd` columns for [changes], in order (a later change to the same column wins). Throws
     * [IllegalArgumentException] for an unknown or read-only key or a value out of range -- before anything is sent.
     * [GreeKeys.BEEP] is checked here but gives no column: it is the session's setting (see [beepSetting]).
     */
    fun encode(changes: List<SetValue>): LinkedHashMap<String, Int> {
        val out = LinkedHashMap<String, Int>()
        for (c in changes) {
            if (c.key == GreeKeys.BEEP) {
                bool(c)
                continue
            }
            val f = BY_KEY[c.key] ?: throw IllegalArgumentException("unknown setting '${c.key}'")
            when (f) {
                is PowerField -> out["Pow"] = if (bool(c)) 1 else 0
                is ToggleField -> {
                    val on = bool(c)
                    if (on) out.putAll(f.withOn)
                    out[f.column] = if (on) f.onValue else f.offValue
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

    /** The "Beep on commands" value in [changes] (the last one wins), or null when they do not touch it. */
    fun beepSetting(changes: List<SetValue>): Boolean? = changes.lastOrNull { it.key == GreeKeys.BEEP }?.let { bool(it) }

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
