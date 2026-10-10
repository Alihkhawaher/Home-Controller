package com.technlov.home.connectors.tuya

import com.technlov.home.core.Capability
import com.technlov.home.core.Keys
import com.technlov.home.core.SetValue
import com.technlov.home.core.Value

/** Capability keys this connector adds to the shared [Keys]. These are Tuya data-point code names: stable, never renamed. */
object TuyaKeys {
    const val POWER_W = "cur_power"
    const val CURRENT = "cur_current"
    const val VOLTAGE = "cur_voltage"
    const val ENERGY = "add_ele"
    const val COUNTDOWN = "countdown_1"
    const val CHILD_LOCK = "child_lock"
    const val OVERCHARGE = "overcharge_switch"
    const val RELAY_STATUS = "relay_status"
    const val LIGHT_MODE = "light_mode"
    const val SWITCH_INCHING = "switch_inching"
    const val CYCLE_TIME = "cycle_time"
    const val RANDOM_TIME = "random_time"
    const val FAULT = Keys.FAULT
}

/**
 * The Tuya data-point (DP) model: how the numbered dps a device reports on the LAN map to Home Controller
 * capabilities and values, and back.
 *
 * A Tuya device reports a bag of numbered points, e.g. `{"1":true,"18":0,"19":1033,"20":2301}`. The numbers carry no
 * meaning on the wire; what each one means comes from the device's *local strategy* (`dp id -> code`), which the app
 * holds per device. This model resolves a dp id to a [CodeSpec] (kind, unit, divisor, options, writable) from, in
 * order of precedence: the device's own local-strategy override, its category default ([DEFAULT_DP_MAP], `cz`
 * metering plug), and a per-product decoder ([PRODUCT_SPECS], for products the Tuya cloud gives no spec for, like the
 * OWON PC321-TY meter). Anything still unmapped is shown generically as `dp_<id>`.
 *
 * The result is a *complete device view*: every reported dp is decoded, named by its code where known and `dp_<id>`
 * otherwise, with its value and whether it can be written.
 */
internal object TuyaModel {
    enum class Kind { POWER, TOGGLE, CHOICE, NUMBER, READING, RAW }

    /**
     * One data-point's meaning. [capabilityKey] is what the app and automations use (usually the code itself; the
     * main switch uses [Keys.POWER]). A raw integer is shown divided by [divisor] when it is > 0, else by 10^[scale].
     * [writable] marks a control; a read-only reading refuses a set. [RAW] values pass through as text.
     */
    data class CodeSpec(
        val code: String,
        val kind: Kind,
        val label: String,
        val capabilityKey: String = code,
        val unit: String = "",
        val scale: Int = 0,
        val divisor: Double = 0.0,
        val min: Double = 0.0,
        val max: Double = 0.0,
        val step: Double = 1.0,
        val writable: Boolean = false,
        val diagnostic: Boolean = false,
        val options: List<Capability.Option> = emptyList(),
    )

    private fun opts(vararg pairs: Pair<String, String>) = pairs.map { Capability.Option(it.first, it.second) }

    /** The generic, category-independent codes (a metering plug and friends). Keyed by code name. */
    private val CODE_LIST: List<CodeSpec> = listOf(
        // Switch and other writable controls
        CodeSpec("switch_1", Kind.POWER, "Power", capabilityKey = Keys.POWER, writable = true),
        CodeSpec("switch_2", Kind.TOGGLE, "Switch 2", writable = true),
        CodeSpec("switch_3", Kind.TOGGLE, "Switch 3", writable = true),
        CodeSpec("switch_4", Kind.TOGGLE, "Switch 4", writable = true),
        // Power monitoring (surfaced prominently, not diagnostic)
        CodeSpec(TuyaKeys.POWER_W, Kind.READING, "Power", unit = "W", scale = 1),
        CodeSpec(TuyaKeys.VOLTAGE, Kind.READING, "Voltage", unit = "V", scale = 1),
        CodeSpec(TuyaKeys.CURRENT, Kind.READING, "Current", unit = "mA", scale = 0),
        CodeSpec(TuyaKeys.ENERGY, Kind.READING, "Energy", unit = "kWh", scale = 3),
        // Native local timers and other controls
        CodeSpec(TuyaKeys.COUNTDOWN, Kind.NUMBER, "Countdown", unit = "s", min = 0.0, max = 86_400.0, step = 1.0, writable = true),
        CodeSpec(TuyaKeys.CHILD_LOCK, Kind.TOGGLE, "Child lock", writable = true),
        CodeSpec(TuyaKeys.OVERCHARGE, Kind.TOGGLE, "Over-charge cutoff", writable = true),
        CodeSpec(
            TuyaKeys.RELAY_STATUS, Kind.CHOICE, "Power-on state", writable = true,
            options = opts("off" to "Off", "on" to "On", "memory" to "Remember last"),
        ),
        CodeSpec(
            TuyaKeys.LIGHT_MODE, Kind.CHOICE, "Indicator light", writable = true,
            options = opts("none" to "Off", "relay" to "Follows the switch", "pos" to "On when switched off", "on" to "Always on"),
        ),
        CodeSpec(TuyaKeys.SWITCH_INCHING, Kind.RAW, "Inching (raw)", writable = true, diagnostic = true),
        CodeSpec(TuyaKeys.CYCLE_TIME, Kind.RAW, "Cycle timer (raw)", writable = true, diagnostic = true),
        CodeSpec(TuyaKeys.RANDOM_TIME, Kind.RAW, "Random timer (raw)", writable = true, diagnostic = true),
        // Diagnostics / calibration (read-only)
        CodeSpec(TuyaKeys.FAULT, Kind.READING, "Fault", capabilityKey = Keys.FAULT, diagnostic = true),
        CodeSpec("test_bit", Kind.READING, "Self-test", diagnostic = true),
        CodeSpec("voltage_coe", Kind.READING, "Voltage calibration", diagnostic = true),
        CodeSpec("electric_coe", Kind.READING, "Current calibration", diagnostic = true),
        CodeSpec("power_coe", Kind.READING, "Power calibration", diagnostic = true),
        CodeSpec("electricity_coe", Kind.READING, "Energy calibration", diagnostic = true),
    )

    val CODES: Map<String, CodeSpec> = CODE_LIST.associateBy { it.code }

    /** Display order so power monitoring sits right under the switch; codes not listed fall to the end (by dp id). */
    private val DISPLAY_ORDER: List<String> = CODE_LIST.map { it.code }
    private fun order(code: String?): Int = (code?.let { DISPLAY_ORDER.indexOf(it) } ?: -1).let { if (it < 0) DISPLAY_ORDER.size else it }

    /**
     * Built-in `dp id -> code` maps per category. Only `cz` (Wi-Fi metering socket) is known; `zndb` (energy
     * meters/clamps) gets no usable spec from the cloud, so it relies on [PRODUCT_SPECS] or the device's own strategy.
     */
    val DEFAULT_DP_MAP: Map<String, Map<Int, String>> = mapOf(
        "cz" to mapOf(
            1 to "switch_1", 9 to TuyaKeys.COUNTDOWN, 17 to TuyaKeys.ENERGY, 18 to TuyaKeys.CURRENT,
            19 to TuyaKeys.POWER_W, 20 to TuyaKeys.VOLTAGE, 21 to "test_bit", 22 to "voltage_coe",
            23 to "electric_coe", 24 to "power_coe", 25 to "electricity_coe", 26 to TuyaKeys.FAULT,
            38 to TuyaKeys.RELAY_STATUS, 39 to TuyaKeys.LIGHT_MODE, 40 to TuyaKeys.CHILD_LOCK,
            41 to TuyaKeys.CYCLE_TIME, 42 to TuyaKeys.RANDOM_TIME, 43 to TuyaKeys.SWITCH_INCHING,
            44 to TuyaKeys.OVERCHARGE,
        ),
    )

    /** One phase of the OWON PC321-TY three-phase meter: six read-only readings at `base+1 .. base+7`. */
    private fun owonPhase(base: Int, phase: String): Map<Int, CodeSpec> {
        val p = phase.lowercase()
        val name = "Phase ${phase.uppercase()}"
        fun r(id: Int, code: String, label: String, unit: String, divisor: Double) =
            id to CodeSpec("phase_${p}_$code", Kind.READING, "$name $label", unit = unit, divisor = divisor)
        return mapOf(
            r(base + 1, "voltage", "voltage", "V", 10.0),
            r(base + 2, "current", "current", "A", 1000.0),
            r(base + 3, "power", "power", "W", 0.0),
            r(base + 4, "power_factor", "power factor", "", 100.0),
            r(base + 6, "energy", "energy", "kWh", 100.0),
            r(base + 7, "energy_produced", "energy produced", "kWh", 100.0),
        )
    }

    /**
     * Per-product decoders for devices the Tuya cloud returns no spec for. Used only when the device has no
     * local-strategy override and no category default. The OWON PC321-TY 3-phase clamp meter (category `zndb`,
     * protocol 3.5): dp map from make-all / tuya-local's `pc321ty_energy_meter.yaml` (MIT), confirmed against a live
     * device. All readings are read-only and use plain divisors (not Tuya's 10^scale).
     */
    val PRODUCT_SPECS: Map<String, Map<Int, CodeSpec>> = run {
        val owon = buildMap<Int, CodeSpec> {
            putAll(owonPhase(100, "a"))
            putAll(owonPhase(110, "b"))
            putAll(owonPhase(120, "c"))
            put(131, CodeSpec("energy_total", Kind.READING, "Energy total", unit = "kWh", divisor = 100.0))
            put(132, CodeSpec("current_total", Kind.READING, "Current total", unit = "A", divisor = 1000.0))
            put(133, CodeSpec("power_total", Kind.READING, "Power total", unit = "W"))
            put(135, CodeSpec("frequency", Kind.READING, "Frequency", unit = "Hz"))
            put(136, CodeSpec("temperature", Kind.READING, "Meter temperature", unit = "°C", divisor = 10.0, diagnostic = true))
            put(139, CodeSpec("energy_produced_total", Kind.READING, "Energy produced total", unit = "kWh", divisor = 100.0))
        }
        // The three product ids that are this same meter.
        listOf("9rjjf6rdkca9woju", "r64livttuufctble", "gqmmtjclqb7reg5p").associateWith { owon }
    }

    private fun genericSpec(code: String): CodeSpec = CODES[code] ?: CodeSpec(code, Kind.READING, humanize(code))

    /**
     * The effective `dp id -> spec` map for a device, lowest precedence first: the per-product decoder, the category
     * default, the device's own local-strategy [override] (`dp id -> code`), and finally an imported full [spec]
     * (`dp id -> CodeSpec`, from a QR import), each merging by dp id.
     */
    fun points(
        category: String?,
        productId: String?,
        override: Map<Int, String>,
        spec: Map<Int, CodeSpec> = emptyMap(),
    ): Map<Int, CodeSpec> {
        val out = LinkedHashMap<Int, CodeSpec>()
        productId?.let { PRODUCT_SPECS[it] }?.let { out.putAll(it) }
        category?.lowercase()?.let { DEFAULT_DP_MAP[it] }?.forEach { (id, code) -> out[id] = genericSpec(code) }
        override.forEach { (id, code) -> out[id] = genericSpec(code) }
        out.putAll(spec)
        return out
    }

    /**
     * Parses an imported dp spec: a JSON array of `[dp_id, code, type, scale, unit, writable]` rows, where `type` is
     * `bool|int|enum|str|raw` and `scale` is the 10^n divisor exponent. A known code keeps its label, options and
     * diagnostic flag; the import is authoritative for type, scale, unit and writable.
     */
    fun parseDpSpec(json: String?): Map<Int, CodeSpec> {
        if (json.isNullOrBlank()) return emptyMap()
        val arr = try {
            org.json.JSONArray(json)
        } catch (e: Exception) {
            return emptyMap()
        }
        val out = LinkedHashMap<Int, CodeSpec>()
        for (i in 0 until arr.length()) {
            val row = arr.optJSONArray(i) ?: continue
            if (row.length() < 2) continue
            val id = row.opt(0)?.toString()?.toIntOrNull() ?: continue
            val code = row.optString(1).trim().takeIf { it.isNotEmpty() } ?: continue
            val type = row.optString(2, "raw")
            val scale = row.opt(3)?.toString()?.toIntOrNull() ?: 0
            val unit = row.optString(4, "")
            val writable = row.optBoolean(5, false)
            out[id] = specFromImport(code, type, scale, unit, writable)
        }
        return out
    }

    private fun specFromImport(code: String, type: String, scale: Int, unit: String, writable: Boolean): CodeSpec {
        val base = CODES[code]
        val kind = base?.kind ?: when (type.trim().lowercase()) {
            "bool" -> if (code == "switch_1") Kind.POWER else Kind.TOGGLE
            "int" -> if (writable) Kind.NUMBER else Kind.READING
            "enum" -> Kind.CHOICE
            "str", "raw" -> Kind.RAW
            else -> Kind.READING
        }
        val max = if (base?.max != null && base.max > 0.0) base.max else if (code == TuyaKeys.COUNTDOWN) 86_400.0 else 1e9
        return CodeSpec(
            code = code,
            kind = kind,
            label = base?.label ?: humanize(code),
            capabilityKey = base?.capabilityKey ?: if (code == "switch_1") Keys.POWER else code,
            unit = unit.ifEmpty { base?.unit ?: "" },
            scale = scale,
            min = base?.min ?: 0.0,
            max = max,
            step = base?.step ?: 1.0,
            writable = writable,
            diagnostic = base?.diagnostic ?: false,
            options = if (kind == Kind.CHOICE) base?.options ?: emptyList() else emptyList(),
        )
    }

    /** Parses a `dp id -> code` override carried as JSON on the device (e.g. `{"1":"switch_1","18":"cur_current"}`). */
    fun parseDpMapOverride(json: String?): Map<Int, String> {
        if (json.isNullOrBlank()) return emptyMap()
        val o = try {
            TuyaJson.parse(json)
        } catch (e: TuyaProtocolException) {
            return emptyMap()
        }
        val out = LinkedHashMap<Int, String>()
        for (k in o.keys()) {
            val id = k.toIntOrNull() ?: continue
            val code = TuyaJson.optString(o, k)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            out[id] = code
        }
        return out
    }

    // ---- decode: raw dps -> capability values (the complete device view) ----

    /** Every reported dp as a capability value, keyed by its code's capability key (or `dp_<id>` when unmapped). */
    fun decode(rawDps: Map<String, Any?>, points: Map<Int, CodeSpec>): LinkedHashMap<String, Value> {
        val out = LinkedHashMap<String, Value>()
        for ((idStr, raw) in rawDps) {
            if (raw == null) continue
            val id = idStr.toIntOrNull()
            val spec = id?.let { points[it] }
            val key = spec?.capabilityKey ?: "dp_$idStr"
            decodeValue(spec, key, raw)?.let { out[key] = it }
        }
        return out
    }

    private fun decodeValue(spec: CodeSpec?, key: String, raw: Any): Value? {
        if (key == Keys.FAULT) return int(raw)?.let { Value.Text(if (it == 0) "none" else "code $it") } ?: Value.Text(raw.toString())
        return when (spec?.kind) {
            Kind.POWER, Kind.TOGGLE -> boolOf(raw)?.let { Value.Bool(it) }
            Kind.CHOICE, Kind.RAW -> Value.Text(raw.toString())
            Kind.NUMBER, Kind.READING -> num(raw)?.let { Value.Num(scaleDown(it, spec)) } ?: Value.Text(raw.toString())
            null -> when (raw) {                       // unmapped dp: keep its natural type
                is Boolean -> Value.Bool(raw)
                is Int, is Long, is Double, is Float -> Value.Num((raw as Number).toDouble())
                else -> Value.Text(raw.toString())
            }
        }
    }

    // ---- capabilities ----

    /**
     * The capabilities to declare, from the dps the device actually reported ([reportedDpIds]) and the effective
     * [points] map. Known codes become their proper capability; a mapped-but-specless code or an unmapped dp becomes
     * a read-only reading, so the complete device view always has something to show.
     */
    fun capabilities(reportedDpIds: Set<Int>, points: Map<Int, CodeSpec>): List<Capability> {
        data class Entry(val ord: Int, val id: Int, val cap: Capability)
        val entries = reportedDpIds.map { id ->
            val spec = points[id]
            val cap = if (spec != null) capabilityOf(spec) else Capability.Reading("dp_$id", "DP $id")
            Entry(order(spec?.code), id, cap)
        }
        return entries.sortedWith(compareBy({ it.ord }, { it.id })).distinctBy { it.cap.key }.map { it.cap }
    }

    private fun capabilityOf(spec: CodeSpec): Capability = when (spec.kind) {
        Kind.POWER -> Capability.Power(spec.capabilityKey, spec.label)
        Kind.TOGGLE -> Capability.Toggle(spec.capabilityKey, spec.label, spec.writable)
        Kind.CHOICE -> Capability.Choice(spec.capabilityKey, spec.label, spec.options, spec.writable)
        Kind.NUMBER -> Capability.Number(spec.capabilityKey, spec.label, spec.min, spec.max, spec.step, spec.unit, spec.writable)
        Kind.READING, Kind.RAW -> Capability.Reading(spec.capabilityKey, spec.label, spec.unit, spec.diagnostic)
    }

    // ---- encode: SetValue changes -> raw dps ({ dp id string -> raw value }) ----

    /**
     * The `dps` to send for [changes], as `dp id (string) -> raw value`. Validated before anything is sent: an
     * unknown key, a read-only one or a value out of range throws [IllegalArgumentException]. A later change to the
     * same dp wins.
     */
    fun encode(changes: List<SetValue>, points: Map<Int, CodeSpec>): LinkedHashMap<String, Any?> {
        val byKey = HashMap<String, Pair<Int, CodeSpec>>()
        for ((id, spec) in points) byKey[spec.capabilityKey] = id to spec
        val out = LinkedHashMap<String, Any?>()
        for (c in changes) {
            val (id, spec) = byKey[c.key] ?: throw IllegalArgumentException("unknown setting '${c.key}'")
            if (!spec.writable || spec.kind == Kind.READING) throw IllegalArgumentException("'${c.key}' is read-only")
            out[id.toString()] = when (spec.kind) {
                Kind.POWER, Kind.TOGGLE -> bool(c)
                Kind.NUMBER -> {
                    val v = number(c)
                    if (v < spec.min || v > spec.max) throw IllegalArgumentException("'${c.key}' $v is outside ${fmt(spec.min)}-${fmt(spec.max)}${spec.unit}")
                    scaleUp(v, spec)
                }
                Kind.CHOICE -> choice(c, spec.options)
                Kind.RAW -> text(c)
                Kind.READING -> throw IllegalArgumentException("'${c.key}' is read-only")
            }
        }
        return out
    }

    // ---- value helpers (mirroring the Gree connector's) ----

    private fun scaleDown(raw: Double, spec: CodeSpec): Double = when {
        spec.divisor > 0.0 -> raw / spec.divisor
        spec.scale > 0 -> raw / Math.pow(10.0, spec.scale.toDouble())
        else -> raw
    }

    private fun scaleUp(v: Double, spec: CodeSpec): Int = when {
        spec.divisor > 0.0 -> Math.round(v * spec.divisor).toInt()
        spec.scale > 0 -> Math.round(v * Math.pow(10.0, spec.scale.toDouble())).toInt()
        else -> Math.round(v).toInt()
    }

    private fun fmt(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else v.toString()

    private fun humanize(code: String): String =
        code.split('_').filter { it.isNotEmpty() }.joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

    private fun boolOf(raw: Any?): Boolean? = when (raw) {
        is Boolean -> raw
        is Number -> raw.toDouble() != 0.0
        is String -> when (raw.trim().lowercase()) { "true", "on", "1", "yes" -> true; "false", "off", "0", "no" -> false; else -> null }
        else -> null
    }

    private fun bool(c: SetValue): Boolean = when (val v = c.value) {
        is Value.Bool -> v.v
        is Value.Num -> v.v != 0.0
        is Value.Text -> boolOf(v.v) ?: throw IllegalArgumentException("'${c.key}' needs on/off, not '${v.v}'")
    }

    private fun number(c: SetValue): Double = when (val v = c.value) {
        is Value.Num -> v.v
        is Value.Text -> v.v.trim().toDoubleOrNull() ?: throw IllegalArgumentException("'${c.key}' needs a number, not '${v.v}'")
        is Value.Bool -> throw IllegalArgumentException("'${c.key}' needs a number")
    }

    private fun text(c: SetValue): String = when (val v = c.value) {
        is Value.Text -> v.v
        is Value.Num -> if (v.v == Math.floor(v.v)) v.v.toLong().toString() else v.v.toString()
        is Value.Bool -> v.v.toString()
    }

    private fun choice(c: SetValue, options: List<Capability.Option>): String {
        val v = c.value
        val chosen = when (v) {
            is Value.Text -> {
                val s = v.v.trim()
                options.firstOrNull { it.value.equals(s, true) }?.value ?: options.firstOrNull { it.label.equals(s, true) }?.value
            }
            is Value.Num -> if (v.v == Math.floor(v.v)) options.getOrNull(v.v.toInt())?.value else null
            is Value.Bool -> null
        }
        return chosen ?: throw IllegalArgumentException("'${c.key}' must be one of ${options.joinToString { it.value }}, not ${describe(v)}")
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
        is Boolean -> if (v) 1.0 else 0.0
        else -> null
    }
}
