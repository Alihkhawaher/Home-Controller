package com.technlov.homecontroller.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeLocationTest {
    private val greenwich = HomeLocation(51.48, -0.01)

    @Test
    fun readsWhatMapsAppsCopyAndRoundsToAboutAKilometre() {
        assertEquals(greenwich, HomeLocation.parse("51.4779, -0.0092"))
        assertEquals(greenwich, HomeLocation.parse("  51.4779,-0.0092 "))
        assertEquals(greenwich, HomeLocation.parse("51.4779 -0.0092"))
        assertEquals(HomeLocation(-33.87, 151.21), HomeLocation.parse("-33.8688, 151.2093"))
    }

    @Test
    fun readsDecimalCommasArabicDigitsAndHemispheres() {
        assertEquals(greenwich, HomeLocation.parse("51,4779; -0,0092"))
        assertEquals(greenwich, HomeLocation.parse("51,48 -0,01"))
        // Arabic-Indic digits with the Arabic decimal separator and comma.
        assertEquals(greenwich, HomeLocation.parse("٥١٫٤٨، -٠٫٠١"))
        assertEquals(greenwich, HomeLocation.parse("51.48° N, 0.01° W"))
        assertEquals(HomeLocation(-33.87, 151.21), HomeLocation.parse("33.87 S 151.21 E"))
        // Longitude first, as its letter says.
        assertEquals(HomeLocation(-33.87, 151.21), HomeLocation.parse("151.21E, 33.87S"))
    }

    @Test
    fun refusesWhatIsNotALatitudeAndALongitude() {
        assertNull(HomeLocation.parse(""))
        assertNull(HomeLocation.parse("51.48"))
        assertNull(HomeLocation.parse("51.48, -0.01, 12"))
        assertNull("degrees, minutes, seconds", HomeLocation.parse("51°28'40\"N 0°0'5\"W"))
        assertNull("off the map", HomeLocation.parse("91, 10"))
        assertNull(HomeLocation.parse("10, 181"))
        assertNull("both latitudes", HomeLocation.parse("51.48 N, 33.87 S"))
        assertNull(HomeLocation.of(Double.NaN, 0.0))
    }

    @Test
    fun showsTwoDecimalsWithoutMinusZero() {
        assertEquals("51.48, -0.01", greenwich.text())
        assertEquals("51.48, 0.00", HomeLocation.of(51.4779, -0.0015)!!.text())
        assertEquals(greenwich, HomeLocation.parse(greenwich.text()))
    }
}
