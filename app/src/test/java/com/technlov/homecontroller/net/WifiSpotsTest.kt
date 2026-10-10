package com.technlov.homecontroller.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiSpotsTest {

    @Test
    fun ssidsLoseQuotesAndHiddenNamesBecomeNull() {
        assertEquals("ExampleNet", WifiSpots.cleanSsid("\"ExampleNet\""))
        assertEquals("ExampleNet", WifiSpots.cleanSsid(" ExampleNet "))
        assertNull(WifiSpots.cleanSsid("<unknown ssid>"))
        assertNull(WifiSpots.cleanSsid("\"\""))
        assertNull(WifiSpots.cleanSsid(null))
    }

    @Test
    fun setupHotspotsAreFilteredDedupedAndStrongestFirst() {
        val isSetup = { ssid: String -> Regex("^[0-9a-f]{8}$").matches(ssid) }
        val spots = listOf(
            WifiSpot("ExampleNet", -40, 2_437),
            WifiSpot("1a2b3c4d", -80, 2_412),
            WifiSpot("1a2b3c4d", -60, 2_412),
            WifiSpot("99887766", -50, 2_462),
            WifiSpot("", -30, 2_412),
        )
        val found = WifiSpots.setupHotspots(spots, isSetup)
        assertEquals(listOf("99887766" to -50, "1a2b3c4d" to -60), found.map { it.ssid to it.level })
    }

    @Test
    fun fiveGhzOnlyWhenNoScanShowsTheSameNameOn24() {
        val on5 = CurrentWifi("ExampleNet", 5_180)
        assertTrue(WifiSpots.looksFiveGhzOnly(on5, listOf(WifiSpot("ExampleNet", -50, 5_180))))
        assertFalse(WifiSpots.looksFiveGhzOnly(on5, listOf(WifiSpot("ExampleNet", -50, 5_180), WifiSpot("ExampleNet", -60, 2_437))))
        assertFalse(WifiSpots.looksFiveGhzOnly(CurrentWifi("ExampleNet", 2_437), emptyList()))
        assertFalse(WifiSpots.looksFiveGhzOnly(CurrentWifi(null, 5_180), emptyList()))
        assertFalse(WifiSpots.looksFiveGhzOnly(null, emptyList()))
    }
}
