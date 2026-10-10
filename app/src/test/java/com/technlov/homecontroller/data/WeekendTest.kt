package com.technlov.homecontroller.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY

class WeekendTest {
    private val sundayToThursday = setOf(SUNDAY, MONDAY, TUESDAY, WEDNESDAY, THURSDAY)

    @Test
    fun saudiArabiaAndTheOtherFridaySaturdayCountriesDefaultToFridaySaturday() {
        for (region in listOf("SA", "sa", " SA ", "KW", "QA", "BH", "OM", "EG", "JO", "IQ", "DZ", "YE")) {
            assertEquals(region, Weekend.FRI_SAT, Weekend.forRegion(region))
        }
    }

    @Test
    fun everywhereElseDefaultsToSaturdaySunday() {
        // The UAE moved to Saturday-Sunday in 2022.
        for (region in listOf("AE", "US", "GB", "DE", "IN", "TR", "MA")) {
            assertEquals(region, Weekend.SAT_SUN, Weekend.forRegion(region))
        }
        assertEquals(Weekend.SAT_SUN, Weekend.forRegion(null))
        assertEquals(Weekend.SAT_SUN, Weekend.forRegion(""))
    }

    @Test
    fun workdaysAreTheRestOfTheWeek() {
        assertEquals(sundayToThursday, Weekend.FRI_SAT.workdays)
        assertEquals(setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY), Weekend.SAT_SUN.workdays)
        assertEquals(DayOfWeek.entries.toSet() - SUNDAY, Weekend.SUN.workdays)
        assertEquals(DayOfWeek.entries.toSet() - FRIDAY, Weekend.FRI.workdays)
    }

    @Test
    fun theDayPicksFollowTheWeekend() {
        assertEquals(DayPreset.WORKDAYS, DayPreset.of(sundayToThursday, Weekend.FRI_SAT))
        assertEquals(DayPreset.WEEKEND, DayPreset.of(setOf(FRIDAY, SATURDAY), Weekend.FRI_SAT))
        assertNull("Sunday to Thursday are not the workdays of a Saturday-Sunday weekend", DayPreset.of(sundayToThursday, Weekend.SAT_SUN))
        assertEquals(DayPreset.EVERY_DAY, DayPreset.of(emptySet(), Weekend.FRI_SAT))
        assertEquals(DayPreset.EVERY_DAY, DayPreset.of(DayOfWeek.entries.toSet(), Weekend.SAT_SUN))
        assertNull(DayPreset.of(setOf(MONDAY), Weekend.SAT_SUN))
        // A pick sets the days the engine reads; "every day" is stored as no days.
        assertEquals(sundayToThursday, DayPreset.WORKDAYS.days(Weekend.FRI_SAT))
        assertEquals(setOf(SATURDAY, SUNDAY), DayPreset.WEEKEND.days(Weekend.SAT_SUN))
        assertEquals(emptySet<DayOfWeek>(), DayPreset.EVERY_DAY.days(Weekend.FRI))
        for (weekend in Weekend.entries) for (pick in DayPreset.entries) {
            assertEquals("$weekend $pick", pick, DayPreset.of(pick.days(weekend), weekend))
        }
    }
}
