package com.technlov.homecontroller.net

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Wi-Fi scanning for pairing: finds devices' setup hotspots and reads the current network's name. Android needs
 * location permission for this (plus "Nearby devices" on Android 13+), and Location turned on.
 */
class WifiScanner(context: Context) {
    private val appContext = context.applicationContext
    private val wifi = appContext.getSystemService(WifiManager::class.java)

    /** The permissions to ask for before scanning. */
    val permissions: Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }.toTypedArray()

    fun hasPermission(): Boolean {
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val nearby = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(Manifest.permission.NEARBY_WIFI_DEVICES)
        return fine && nearby
    }

    fun locationEnabled(): Boolean {
        val manager = appContext.getSystemService(LocationManager::class.java) ?: return true
        return LocationManagerCompat.isLocationEnabled(manager)
    }

    /** Asks for a fresh scan. False when Android throttles scans (a few per two minutes): use the last results. */
    fun startScan(): Boolean = try {
        @Suppress("DEPRECATION") // still the only way to ask for a scan; results arrive via the broadcast.
        wifi?.startScan() == true
    } catch (e: SecurityException) {
        false
    }

    fun results(): List<WifiSpot> = try {
        wifi?.scanResults.orEmpty().mapNotNull { result ->
            @Suppress("DEPRECATION") // SSID is deprecated on 33+, but wifiSsid is not available below it.
            val ssid = WifiSpots.cleanSsid(result.SSID) ?: return@mapNotNull null
            WifiSpot(ssid, result.level, result.frequency)
        }
    } catch (e: SecurityException) {
        emptyList()
    }

    /** The Wi-Fi the phone is on (name readable only with location permission and Location on). */
    fun current(): CurrentWifi? = try {
        @Suppress("DEPRECATION") // connectionInfo still reports the name and band of the current network.
        val info = wifi?.connectionInfo
        info?.let { CurrentWifi(WifiSpots.cleanSsid(it.ssid), it.frequency.takeIf { f -> f > 0 }) }
    } catch (e: SecurityException) {
        null
    }

    /** Emits each time Android delivers new scan results. */
    fun scanUpdates(): Flow<Unit> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(Unit)
            }
        }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        awaitClose { runCatching { appContext.unregisterReceiver(receiver) } }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
}
