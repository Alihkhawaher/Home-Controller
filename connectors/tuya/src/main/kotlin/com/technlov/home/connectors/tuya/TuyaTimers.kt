package com.technlov.home.connectors.tuya

import java.util.Base64

/**
 * The device's own on-board timers, the ones that keep running with the phone off and the internet down:
 *  - **inching** (`switch_inching`): auto-off after N seconds whenever the channel is switched on;
 *  - **random** (`random_time`): switch at a random minute inside a daily window (an away/anti-burglar look);
 *  - **cycle** (`cycle_time`): repeat on for X minutes, off for Y, inside a daily window.
 *
 * Each is one data point carrying a base64 string of fixed-width records, all multi-byte numbers big-endian. The
 * first byte of every record is a control byte: bit 0 is "task enabled", and bits 7..1 are the 0-based channel index
 * (switch_1 is channel 0), so `ctrl = (channel << 1) | enabled`. Record order is the app's order and is preserved.
 *
 * Record widths:
 *  - inching: `[ctrl, seconds(2)]`;
 *  - random:  `[ctrl, week, startMinute(2), endMinute(2)]`, up to 16 tasks;
 *  - cycle:   `[ctrl, week, startMinute(2), endMinute(2), onMinutes(2), offMinutes(2)]`, up to 10 tasks.
 *
 * The `week` byte is `0x00` for a one-off, else a bitmask: bit 0 Sunday … bit 6 Saturday (Mon|Wed|Fri = `0x2A`).
 *
 * Formats confirmed byte-for-byte against real Tuya-app writes captured in make-all / tuya-local
 * (`wifi_m604_quad_switch.yaml`).
 */
object TuyaTimers {
    const val ONCE = 0x00
    const val SUNDAY = 0x01
    const val MONDAY = 0x02
    const val TUESDAY = 0x04
    const val WEDNESDAY = 0x08
    const val THURSDAY = 0x10
    const val FRIDAY = 0x20
    const val SATURDAY = 0x40

    const val MAX_RANDOM_TASKS = 16
    const val MAX_CYCLE_TASKS = 10

    /** Auto-off N seconds after the channel turns on. */
    data class Inching(val channel: Int, val enabled: Boolean, val seconds: Int)

    /** Switch at a random minute inside [startMinute]..[endMinute] (minutes from midnight), on the days in [week]. */
    data class RandomTimer(val channel: Int, val enabled: Boolean, val week: Int, val startMinute: Int, val endMinute: Int)

    /** On for [onMinutes], off for [offMinutes], repeated inside [startMinute]..[endMinute] on the days in [week]. */
    data class CycleTimer(
        val channel: Int,
        val enabled: Boolean,
        val week: Int,
        val startMinute: Int,
        val endMinute: Int,
        val onMinutes: Int,
        val offMinutes: Int,
    )

    fun controlByte(channel: Int, enabled: Boolean): Int {
        require(channel in 0..127) { "channel is 0..127" }
        return (channel shl 1) or (if (enabled) 1 else 0)
    }

    fun channelOf(control: Int): Int = (control and 0xFF) ushr 1
    fun enabledOf(control: Int): Boolean = (control and 1) != 0

    // ---- inching ----

    fun encodeInching(tasks: List<Inching>): String {
        val out = ByteArray(tasks.size * 3)
        tasks.forEachIndexed { i, t ->
            val o = i * 3
            out[o] = controlByte(t.channel, t.enabled).toByte()
            putU16(out, o + 1, t.seconds)
        }
        return b64(out)
    }

    fun decodeInching(value: String): List<Inching> {
        val b = unb64(value, 3)
        return (b.indices step 3).map { o ->
            Inching(channelOf(b[o].i()), enabledOf(b[o].i()), u16(b, o + 1))
        }
    }

    // ---- random ----

    fun encodeRandom(tasks: List<RandomTimer>): String {
        require(tasks.size <= MAX_RANDOM_TASKS) { "at most $MAX_RANDOM_TASKS random timers" }
        val out = ByteArray(tasks.size * 6)
        tasks.forEachIndexed { i, t ->
            val o = i * 6
            out[o] = controlByte(t.channel, t.enabled).toByte()
            out[o + 1] = (t.week and 0xFF).toByte()
            putU16(out, o + 2, t.startMinute)
            putU16(out, o + 4, t.endMinute)
        }
        return b64(out)
    }

    fun decodeRandom(value: String): List<RandomTimer> {
        val b = unb64(value, 6)
        return (b.indices step 6).map { o ->
            RandomTimer(channelOf(b[o].i()), enabledOf(b[o].i()), b[o + 1].i(), u16(b, o + 2), u16(b, o + 4))
        }
    }

    // ---- cycle ----

    fun encodeCycle(tasks: List<CycleTimer>): String {
        require(tasks.size <= MAX_CYCLE_TASKS) { "at most $MAX_CYCLE_TASKS cycle timers" }
        val out = ByteArray(tasks.size * 10)
        tasks.forEachIndexed { i, t ->
            val o = i * 10
            out[o] = controlByte(t.channel, t.enabled).toByte()
            out[o + 1] = (t.week and 0xFF).toByte()
            putU16(out, o + 2, t.startMinute)
            putU16(out, o + 4, t.endMinute)
            putU16(out, o + 6, t.onMinutes)
            putU16(out, o + 8, t.offMinutes)
        }
        return b64(out)
    }

    fun decodeCycle(value: String): List<CycleTimer> {
        val b = unb64(value, 10)
        return (b.indices step 10).map { o ->
            CycleTimer(
                channelOf(b[o].i()), enabledOf(b[o].i()), b[o + 1].i(),
                u16(b, o + 2), u16(b, o + 4), u16(b, o + 6), u16(b, o + 8),
            )
        }
    }

    // ---- helpers ----

    private fun putU16(b: ByteArray, off: Int, v: Int) {
        require(v in 0..0xFFFF) { "value $v does not fit in 16 bits" }
        b[off] = ((v ushr 8) and 0xFF).toByte()
        b[off + 1] = (v and 0xFF).toByte()
    }

    private fun u16(b: ByteArray, off: Int): Int = (b[off].i() shl 8) or b[off + 1].i()
    private fun Byte.i(): Int = this.toInt() and 0xFF

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun unb64(value: String, recordLen: Int): ByteArray {
        val b = try {
            Base64.getDecoder().decode(value.trim())
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("timer value is not valid base64")
        }
        require(b.size % recordLen == 0) { "timer value is not whole $recordLen-byte records" }
        return b
    }
}
