package com.technlov.home.connectors.tuya

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The on-board timer codecs. The inching vectors are real Tuya-app writes captured in make-all / tuya-local
 * (`wifi_m604_quad_switch.yaml`): four channels, each auto-off after a set number of seconds.
 */
class TuyaTimersTest {
    @Test
    fun inchingMatchesTheCapturedVector() {
        val tasks = listOf(
            TuyaTimers.Inching(channel = 3, enabled = true, seconds = 40),
            TuyaTimers.Inching(channel = 1, enabled = true, seconds = 20),
            TuyaTimers.Inching(channel = 2, enabled = true, seconds = 30),
            TuyaTimers.Inching(channel = 0, enabled = true, seconds = 10),
        )
        assertEquals("BwAoAwAUBQAeAQAK", TuyaTimers.encodeInching(tasks))
        assertEquals(tasks, TuyaTimers.decodeInching("BwAoAwAUBQAeAQAK"))
    }

    @Test
    fun inchingDisabledMatchesTheCapturedVector() {
        val tasks = listOf(
            TuyaTimers.Inching(3, false, 40),
            TuyaTimers.Inching(1, false, 20),
            TuyaTimers.Inching(2, false, 30),
            TuyaTimers.Inching(0, false, 10),
        )
        assertEquals("BgAoAgAUBAAeAAAK", TuyaTimers.encodeInching(tasks))
        assertEquals(tasks, TuyaTimers.decodeInching("BgAoAgAUBAAeAAAK"))
    }

    @Test
    fun controlByteIsChannelAndEnabled() {
        assertEquals(0x07, TuyaTimers.controlByte(3, true))
        assertEquals(0x06, TuyaTimers.controlByte(3, false))
        assertEquals(0x01, TuyaTimers.controlByte(0, true))
        assertEquals(3, TuyaTimers.channelOf(0x07))
        assertTrue(TuyaTimers.enabledOf(0x07))
        assertEquals(3, TuyaTimers.channelOf(0x06))
        assertTrue(!TuyaTimers.enabledOf(0x06))
    }

    @Test
    fun weekBitsForMondayWednesdayFriday() {
        assertEquals(0x2A, TuyaTimers.MONDAY or TuyaTimers.WEDNESDAY or TuyaTimers.FRIDAY)
        assertEquals(0x00, TuyaTimers.ONCE)
        assertEquals(0x01, TuyaTimers.SUNDAY)
        assertEquals(0x40, TuyaTimers.SATURDAY)
    }

    @Test
    fun randomTimersRoundTrip() {
        val tasks = listOf(
            TuyaTimers.RandomTimer(channel = 0, enabled = true, week = TuyaTimers.MONDAY or TuyaTimers.FRIDAY, startMinute = 18 * 60, endMinute = 23 * 60),
            TuyaTimers.RandomTimer(channel = 0, enabled = false, week = TuyaTimers.ONCE, startMinute = 0, endMinute = 1439),
        )
        val encoded = TuyaTimers.encodeRandom(tasks)
        assertEquals(tasks, TuyaTimers.decodeRandom(encoded))
    }

    @Test
    fun cycleTimersRoundTrip() {
        val tasks = listOf(
            TuyaTimers.CycleTimer(channel = 0, enabled = true, week = 0x7F, startMinute = 8 * 60, endMinute = 18 * 60, onMinutes = 15, offMinutes = 45),
        )
        val encoded = TuyaTimers.encodeCycle(tasks)
        assertEquals(tasks, TuyaTimers.decodeCycle(encoded))
    }

    @Test
    fun limitsAndMalformedInputAreRejected() {
        val many = (0..16).map { TuyaTimers.RandomTimer(0, true, 0, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { TuyaTimers.encodeRandom(many) }
        val tooManyCycles = (0..10).map { TuyaTimers.CycleTimer(0, true, 0, 0, 0, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { TuyaTimers.encodeCycle(tooManyCycles) }
        assertThrows(IllegalArgumentException::class.java) { TuyaTimers.decodeInching("not base64 ###") }
        // 4 bytes is not a whole 3-byte inching record.
        assertThrows(IllegalArgumentException::class.java) { TuyaTimers.decodeInching("AAAAAA==") }
    }

    @Test
    fun emptyIsEmpty() {
        assertEquals("", TuyaTimers.encodeInching(emptyList()))
        assertEquals(emptyList<TuyaTimers.Inching>(), TuyaTimers.decodeInching(""))
    }
}
