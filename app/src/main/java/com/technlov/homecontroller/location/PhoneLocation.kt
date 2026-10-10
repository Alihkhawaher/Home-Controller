package com.technlov.homecontroller.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.technlov.homecontroller.data.HomeLocation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * Where the phone is, read once when the user taps "Use this phone's location" (for the home's sunrise and sunset).
 * Never tracks: one reading, then nothing. Approximate location is enough (the result is rounded to about a
 * kilometre anyway), so only ACCESS_COARSE_LOCATION is asked for. Plain Android LocationManager: no Google services.
 */
object PhoneLocation {
    sealed interface Result {
        data class Found(val location: HomeLocation) : Result
        /** Location is turned off on the phone. */
        data object Off : Result
        data object NoPermission : Result
        /** No reading in time and none remembered. */
        data object NotFound : Result
    }

    /** The permission to ask for: approximate location only. */
    const val PERMISSION = Manifest.permission.ACCESS_COARSE_LOCATION

    fun permitted(context: Context): Boolean =
        listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION).any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    @SuppressLint("MissingPermission") // checked by permitted(); a SecurityException is still caught per provider
    suspend fun find(context: Context, timeoutMs: Long = 20_000): Result {
        if (!permitted(context)) return Result.NoPermission
        val manager = context.getSystemService(LocationManager::class.java) ?: return Result.NotFound
        if (!LocationManagerCompat.isLocationEnabled(manager)) return Result.Off
        val enabled = runCatching { manager.getProviders(true) }.getOrDefault(emptyList())
        val providers = PREFERRED.filter { it in enabled }
        val fresh = withTimeoutOrNull(timeoutMs) {
            providers.firstNotNullOfOrNull { current(manager, it) }
        }
        // No fresh reading: the newest one Android remembers is as good for a home that does not move.
        val fix = fresh ?: enabled.mapNotNull { p -> runCatching { manager.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
        return fix?.let { HomeLocation.of(it.latitude, it.longitude) }?.let { Result.Found(it) } ?: Result.NotFound
    }

    @SuppressLint("MissingPermission")
    private suspend fun current(manager: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { continuation ->
            val cancel = CancellationSignal()
            continuation.invokeOnCancellation { cancel.cancel() }
            try {
                LocationManagerCompat.getCurrentLocation(manager, provider, cancel, DIRECT) { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
            } catch (e: SecurityException) {
                continuation.resume(null) // e.g. GPS without the precise-location permission
            } catch (e: IllegalArgumentException) {
                continuation.resume(null) // provider went away
            }
        }

    private val DIRECT = Executor { it.run() }

    /** Fused (Android 12+) first, then the network's estimate, then GPS, then whatever another app last asked for. */
    private val PREFERRED = listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
}
