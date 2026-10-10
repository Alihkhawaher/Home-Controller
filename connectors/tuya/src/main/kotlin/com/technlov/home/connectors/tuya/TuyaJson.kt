package com.technlov.home.connectors.tuya

import org.json.JSONException
import org.json.JSONObject

/**
 * JSON for the Tuya frames. Outgoing payloads are written here in a fixed key order, compact and ASCII-escaped, so
 * they are the same bytes tinytuya produces (`json.dumps(data).replace(" ", "")`, which is also `ensure_ascii`).
 * That keeps the frames independent of how a JSONObject happens to order its keys (insertion order on Android, hash
 * order on the JVM). Incoming frames are parsed with org.json (part of Android).
 */
internal object TuyaJson {
    /** Writes maps (in their iteration order), lists, strings, numbers, booleans and null. */
    fun write(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

    private fun append(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Number -> {
                val d = v.toDouble()
                if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) sb.append(d.toLong()) else sb.append(d)
            }
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k.toString())
                    sb.append(':')
                    append(sb, x)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (x in v) {
                    if (!first) sb.append(',')
                    first = false
                    append(sb, x)
                }
                sb.append(']')
            }
            else -> quote(sb, v.toString())
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' || c > '~' -> sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    fun parse(text: String): JSONObject = try {
        JSONObject(text)
    } catch (e: JSONException) {
        throw TuyaProtocolException("reply is not a JSON object")
    }

    /** A JSON value as a plain Kotlin value: Int/Long/Double for numbers, String, Boolean, null. */
    fun plain(v: Any?): Any? = when (v) {
        null, JSONObject.NULL -> null
        is Int, is Long, is String, is Boolean -> v
        is Number -> {
            val d = v.toDouble()
            if (d == Math.floor(d) && Math.abs(d) <= Int.MAX_VALUE) d.toInt() else d
        }
        else -> v.toString()
    }

    fun optString(o: JSONObject, key: String): String? =
        if (o.has(key) && !o.isNull(key)) o.opt(key)?.toString() else null

    /**
     * The `dps` object of a decoded reply, as `dp id (string) -> value`. Tuya replies are `{"dps":{...}}`, or
     * `{"data":{"dps":{...}}}` on 3.4/3.5; both are handled. Returns an empty map when there is no `dps`.
     */
    fun dps(o: JSONObject): LinkedHashMap<String, Any?> {
        val dps = o.optJSONObject("dps") ?: o.optJSONObject("data")?.optJSONObject("dps")
        val out = LinkedHashMap<String, Any?>()
        if (dps != null) for (k in dps.keys()) out[k] = plain(dps.opt(k))
        return out
    }
}
