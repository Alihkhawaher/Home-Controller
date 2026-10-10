package com.technlov.homecontroller.data

import com.technlov.home.core.SolarClock
import com.technlov.home.core.SunClock
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToLong

/** Sunrise and sunset at the home, in [zone] (the phone's time zone); none without a location. */
fun HomeLocation?.sunClock(zone: ZoneId = ZoneId.systemDefault()): SunClock =
    this?.let { SolarClock(it.latitude, it.longitude, zone) } ?: SunClock.UNKNOWN

/**
 * Where the home is, for sunrise and sunset. Kept to two decimals (about a kilometre): far more than the sun's times
 * need (a kilometre moves them by a few seconds), and no more precise than that. Stored only on the phone, and
 * never written to the diagnostics log.
 */
data class HomeLocation(val latitude: Double, val longitude: Double) {
    init {
        require(isValid(latitude, longitude)) { "Not a place on Earth" }
    }

    /** "51.48, -0.01": the form [parse] reads back. */
    fun text(): String = String.format(Locale.ROOT, "%.2f, %.2f", latitude, longitude)

    companion object {
        fun isValid(latitude: Double, longitude: Double): Boolean =
            latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0

        /** The place rounded to two decimals, or null when it is not on Earth. */
        fun of(latitude: Double, longitude: Double): HomeLocation? =
            if (isValid(latitude, longitude)) HomeLocation(round2(latitude), round2(longitude)) else null

        /**
         * Reads a latitude and a longitude as people type or paste them: "51.4779, -0.0015" (as maps apps copy
         * them), "51.48 -0.01", decimal commas ("51,48; -0,01"), Arabic-Indic digits, and hemisphere letters
         * ("51.48° N, 0.01° W"; "33.87 S 151.21 E"; longitude first when its letter says so). Null for anything
         * else, including degrees-minutes-seconds.
         */
        fun parse(text: String): HomeLocation? {
            val matches = NUMBER.findAll(normalizeDigits(text)).toList()
            if (matches.size != 2) return null
            val values = matches.map { m ->
                val number = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
                val hemisphere = m.groupValues[2].uppercase()
                val signed = if (hemisphere == "S" || hemisphere == "W") -number else number
                signed to hemisphere
            }
            val (first, second) = values
            val longitudeFirst = first.second in setOf("E", "W") || second.second in setOf("N", "S")
            val sameAxis = first.second.isNotEmpty() && second.second.isNotEmpty() &&
                (first.second in setOf("N", "S")) == (second.second in setOf("N", "S"))
            if (sameAxis) return null
            return if (longitudeFirst) of(second.first, first.first) else of(first.first, second.first)
        }

        private val NUMBER = Regex("""([+-]?\d+(?:[.,]\d+)?)\s*°?\s*([NSEWnsew](?![A-Za-z]))?""")

        private fun normalizeDigits(text: String): String = buildString(text.length) {
            for (c in text) {
                append(
                    when (c) {
                        in '٠'..'٩' -> '0' + (c - '٠')   // Arabic-Indic digits
                        in '۰'..'۹' -> '0' + (c - '۰')   // Eastern Arabic-Indic digits
                        '٫' -> '.'                                 // Arabic decimal separator
                        '،' -> ','                                 // Arabic comma
                        '−' -> '-'                                 // minus sign
                        else -> c
                    },
                )
            }
        }

        private fun round2(v: Double): Double {
            val r = (v * 100).roundToLong() / 100.0
            return if (r == 0.0) 0.0 else r // no "-0.00"
        }
    }
}
