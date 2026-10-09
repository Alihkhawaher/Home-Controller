package com.technlov.home.connectors.gree

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * JSON for the Gree frames. Outgoing frames are written here in a fixed key order (the same bytes as the Python
 * connector and greeclimate produce: compact, ASCII-escaped), so they do not depend on how a JSONObject orders keys
 * (insertion order on Android, hash order on the JVM). Incoming frames are parsed with org.json (part of Android).
 */
internal object GreeJson {
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
        throw GreeProtocolException("not a JSON object")
    }

    /** A JSON array as plain Kotlin values: Int/Long/Double for numbers, String, Boolean, null. */
    fun list(a: JSONArray?): List<Any?> {
        if (a == null) return emptyList()
        return List(a.length()) { i -> plain(a.opt(i)) }
    }

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
}
