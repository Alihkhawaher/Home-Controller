package com.technlov.home.core

import org.shredzone.commons.suncalc.SunTimes
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Sunrise and sunset at a place, worked out on the phone with commons-suncalc (Apache-2.0): the standard sunrise and
 * sunset (the sun's upper edge on the horizon, with refraction), rounded to the minute, in [zone]'s local time.
 * Nothing goes online. On a day the sun does not rise or set there (far north or south), [time] is null.
 */
class SolarClock(
    private val latitude: Double,
    private val longitude: Double,
    private val zone: ZoneId,
) : SunClock {
    init {
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0) { "Not a place on Earth: $latitude, $longitude" }
    }

    private val days = HashMap<LocalDate, SunTimes>()

    override fun time(event: SunEvent, date: LocalDate): LocalDateTime? {
        val times = synchronized(days) { days.getOrPut(date) { compute(date) } }
        val at = when (event) {
            SunEvent.SUNRISE -> times.rise
            SunEvent.SUNSET -> times.set
        } ?: return null
        val local = at.withZoneSameInstant(zone).toLocalDateTime().plusSeconds(30).truncatedTo(ChronoUnit.MINUTES)
        // The search covers the day's 24 hours from local midnight; on a day of 23 hours (clocks going forward) it
        // can reach into the next day, which is not this day's sunrise or sunset.
        return local.takeIf { it.toLocalDate() == date }
    }

    private fun compute(date: LocalDate): SunTimes =
        SunTimes.compute().timezone(zone).on(date).at(latitude, longitude).oneDay().execute()
}
