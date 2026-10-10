package com.technlov.homecontroller.ui

import android.content.Context
import android.telephony.TelephonyManager
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.os.ConfigurationCompat
import com.technlov.homecontroller.data.Weekend

/**
 * The weekend for the "Workdays" / "Weekend" shortcuts and day summaries: the user's choice in Settings, or the
 * phone's region's ([Weekend.forRegion] of [phoneRegion]). Provided once at the top of the app.
 */
val LocalWeekend = staticCompositionLocalOf { Weekend.SAT_SUN }

/**
 * The phone's region as an ISO country code, or null: the SIM's country (where the phone's owner lives, even when
 * roaming), else the mobile network's, else the language-and-region setting's (a phone without a SIM). Needs no
 * permission and stays on the phone.
 */
fun phoneRegion(context: Context): String? {
    val telephony = runCatching { context.getSystemService(TelephonyManager::class.java) }.getOrNull()
    val sim = runCatching { telephony?.simCountryIso }.getOrNull()
    val network = runCatching { telephony?.networkCountryIso }.getOrNull()
    val setting = ConfigurationCompat.getLocales(context.resources.configuration)[0]?.country
    return listOf(sim, network, setting).firstOrNull { !it.isNullOrBlank() }
}
