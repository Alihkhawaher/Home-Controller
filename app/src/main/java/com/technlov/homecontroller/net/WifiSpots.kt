package com.technlov.homecontroller.net

/** One Wi-Fi network seen in a scan. */
data class WifiSpot(val ssid: String, val level: Int, val frequencyMhz: Int)

/** The Wi-Fi the phone is on. */
data class CurrentWifi(val ssid: String?, val frequencyMhz: Int?)

/** Pure helpers for Wi-Fi scan results (unit-tested on the JVM). */
object WifiSpots {
    private const val UNKNOWN_SSID = "<unknown ssid>"

    /** Android quotes network names ("\"Home\"") and hides them as "<unknown ssid>" without permission. */
    fun cleanSsid(raw: String?): String? {
        val s = raw?.trim() ?: return null
        val unquoted = if (s.length >= 2 && s.startsWith('"') && s.endsWith('"')) s.substring(1, s.length - 1) else s
        return unquoted.takeUnless { it.isEmpty() || it.equals(UNKNOWN_SSID, ignoreCase = true) || it == "0x" }
    }

    fun is24GHz(frequencyMhz: Int): Boolean = frequencyMhz in 2_400..2_500
    fun is5GHz(frequencyMhz: Int): Boolean = frequencyMhz in 4_900..5_900

    /** The setup hotspots among [spots]: one per name (the strongest), strongest first. */
    fun setupHotspots(spots: List<WifiSpot>, isSetupHotspot: (String) -> Boolean): List<WifiSpot> =
        spots.filter { it.ssid.isNotBlank() && isSetupHotspot(it.ssid) }
            .groupBy { it.ssid }
            .map { (_, same) -> same.maxBy { it.level } }
            .sortedByDescending { it.level }

    /**
     * True when the phone is on a 5 GHz network that no scan shows on 2.4 GHz too, so a device that needs 2.4 GHz
     * probably cannot join it. Dual-band networks with one name are fine.
     */
    fun looksFiveGhzOnly(current: CurrentWifi?, spots: List<WifiSpot>): Boolean {
        val ssid = current?.ssid ?: return false
        val frequency = current.frequencyMhz ?: return false
        if (!is5GHz(frequency)) return false
        return spots.none { it.ssid == ssid && is24GHz(it.frequencyMhz) }
    }
}
