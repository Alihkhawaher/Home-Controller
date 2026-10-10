package com.technlov.home.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class SolarClockTest {
    // The Royal Observatory, Greenwich: a public reference point with well-known sunrise and sunset tables.
    private val greenwich = SolarClock(51.4779, -0.0015, ZoneId.of("Europe/London"))
    private val midsummer = LocalDate.of(2026, 6, 21)
    private val midwinter = LocalDate.of(2026, 12, 21)

    @Test
    fun sunriseAndSunsetAtGreenwichToTheMinute() {
        assertEquals(midsummer.atTime(4, 43), greenwich.time(SunEvent.SUNRISE, midsummer))
        assertEquals(midsummer.atTime(21, 21), greenwich.time(SunEvent.SUNSET, midsummer))
        assertEquals(midwinter.atTime(8, 3), greenwich.time(SunEvent.SUNRISE, midwinter))
        assertEquals(midwinter.atTime(15, 53), greenwich.time(SunEvent.SUNSET, midwinter))
    }

    @Test
    fun timesAreInTheGivenTimeZone() {
        // The same place and day in UTC: an hour earlier on the clock than British Summer Time.
        val utc = SolarClock(51.4779, -0.0015, ZoneOffset.UTC)
        assertEquals(midsummer.atTime(3, 43), utc.time(SunEvent.SUNRISE, midsummer))
        assertEquals(midsummer.atTime(20, 21), utc.time(SunEvent.SUNSET, midsummer))
        // In winter London is on UTC: the same times.
        assertEquals(greenwich.time(SunEvent.SUNSET, midwinter), utc.time(SunEvent.SUNSET, midwinter))
    }

    @Test
    fun noSunsetInPolarSummerAndNoSunriseInPolarWinter() {
        val tromso = SolarClock(69.6492, 18.9553, ZoneId.of("Europe/Oslo"))
        assertNull(tromso.time(SunEvent.SUNSET, midsummer))
        assertNull(tromso.time(SunEvent.SUNRISE, midsummer))
        assertNull(tromso.time(SunEvent.SUNRISE, midwinter))
    }

    @Test
    fun theEngineRunsASunsetScheduleWithTheRealSun() {
        // "Sunset - 15 min, on Sundays": 2026-06-21 is a Sunday, sunset 21:21, so 21:06.
        val engine = DefaultAutomationEngine()
        val lights = Automation(
            "lights", "Garden lights",
            trigger = Trigger.Sun(SunEvent.SUNSET, -15, setOf(DayOfWeek.SUNDAY)),
            actions = listOf(Action.Notify("t", "x")),
        )
        fun due(now: LocalDateTime) =
            engine.due(now, listOf(lights), emptyMap(), emptyMap(), emptyMap(), sun = greenwich).map { it.id }
        assertEquals(listOf("lights"), due(midsummer.atTime(21, 6)))
        assertEquals(emptyList<String>(), due(midsummer.atTime(21, 5)))
        assertEquals(midsummer.atTime(21, 6), lights.trigger.nextTime(midsummer.atTime(12, 0), greenwich))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aPlaceOffTheMapIsRefused() {
        SolarClock(91.0, 0.0, ZoneOffset.UTC)
    }
}
