package com.technlov.homecontroller.pairing

import com.technlov.home.core.Connector
import com.technlov.home.core.DeviceRef
import com.technlov.home.core.EventLog
import com.technlov.home.core.LocalNetwork
import com.technlov.home.core.Provisioning
import com.technlov.home.core.ProvisioningChannel
import com.technlov.home.core.info
import com.technlov.home.core.trace
import com.technlov.home.core.warn
import com.technlov.homecontroller.diag.LogSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable

/** The phone's link to a device's setup hotspot. Closing it lets the phone go back to the home Wi-Fi. */
interface HotspotLink : Closeable {
    val channel: ProvisioningChannel
}

/** What the pairing needs from Android: joining a hotspot, and the home network afterwards. Faked in tests. */
interface PairingPlatform {
    /** True when the user must join the hotspot by hand (Android 8 and 9). */
    val joinNeedsUser: Boolean

    /** Joins [ssid] (WPA2 [password], or open when null); throws if it cannot within [timeoutMs]. */
    suspend fun joinHotspot(ssid: String, password: String?, timeoutMs: Long): HotspotLink

    /** The home network to search once the hotspot is released; null while the phone is not back on it. */
    suspend fun homeNetwork(setupSsid: String): LocalNetwork?

    /** Runs a discovery the way the platform needs it (Android: with a multicast lock). */
    suspend fun <T> discoveryWindow(block: suspend () -> T): T = block()
}

/** The home Wi-Fi password for one hand-over. [take] gives it out once and forgets it. Never logged. */
class WifiSecret(value: String) {
    private var value: String? = value

    fun take(): String {
        val v = value ?: ""
        value = null
        return v
    }

    val isUsed: Boolean get() = value == null

    override fun toString() = "WifiSecret(hidden)"
}

enum class PairingFailure {
    /** The phone could not join the device's setup hotspot. */
    JOIN_FAILED,

    /** The Wi-Fi details could not be sent. */
    SEND_FAILED,

    /** The device refused the details, or did not confirm them (some never do: waiting may still work). */
    NOT_CONFIRMED,

    /** The device did not show up on the home network in time. */
    NOT_FOUND,
}

sealed interface PairingProgress {
    data object Idle : PairingProgress
    data class Joining(val hotspot: String, val manual: Boolean) : PairingProgress
    data object Sending : PairingProgress
    data class Waiting(val startedAtMillis: Long, val timeoutMs: Long, val searches: Int) : PairingProgress
    data class Found(val device: DeviceRef, val message: String) : PairingProgress
    data class Failed(val reason: PairingFailure, val detail: String?) : PairingProgress
    data object Cancelled : PairingProgress

    /** The password has been handed over (or the attempt is over): the form must forget it. */
    val credentialsGone: Boolean
        get() = when (this) {
            Idle, is Joining -> false
            is Failed -> reason != PairingFailure.JOIN_FAILED
            else -> true
        }

    val isRunning: Boolean get() = this is Joining || this == Sending || this is Waiting
}

data class PairingTiming(
    val joinTimeoutMs: Long = 60_000,
    /** Android 8/9: the user joins the hotspot in Wi-Fi settings. */
    val manualJoinTimeoutMs: Long = 180_000,
    val waitTimeoutMs: Long = 90_000,
    val searchEveryMs: Long = 5_000,
    val discoverTimeoutMs: Long = 3_000,
)

/**
 * Pairing as a state machine: join the device's hotspot -> send the home Wi-Fi details -> release the hotspot ->
 * search the home network until the device appears. Pure Kotlin; Android sits behind [PairingPlatform].
 * Cancelling the calling coroutine releases the hotspot.
 */
class PairingRunner(
    private val platform: PairingPlatform,
    private val log: EventLog = EventLog.None,
    private val now: () -> Long = System::currentTimeMillis,
    private val timing: PairingTiming = PairingTiming(),
) {
    private val _progress = MutableStateFlow<PairingProgress>(PairingProgress.Idle)
    val progress: StateFlow<PairingProgress> = _progress.asStateFlow()

    fun reset() {
        _progress.value = PairingProgress.Idle
    }

    fun markCancelled() {
        if (_progress.value.isRunning) log.info(LogSource.PAIRING, "Cancelled")
        _progress.value = PairingProgress.Cancelled
    }

    /** The whole hand-over. Returns the final progress (also published on [progress]). */
    suspend fun run(
        connector: Connector,
        provisioning: Provisioning,
        setupSsid: String,
        homeSsid: String,
        password: WifiSecret,
    ): PairingProgress {
        log.info(LogSource.PAIRING, "Setting up a ${connector.info.name} from hotspot \"$setupSsid\"")

        // 1. Join the device's hotspot.
        val manual = platform.joinNeedsUser
        _progress.value = PairingProgress.Joining(setupSsid, manual)
        log.info(LogSource.PAIRING, if (manual) "Waiting for the phone to join the hotspot (by hand)" else "Joining the hotspot")
        val link = try {
            platform.joinHotspot(
                setupSsid,
                provisioning.setupHotspotPassword,
                if (manual) timing.manualJoinTimeoutMs else timing.joinTimeoutMs,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(LogSource.PAIRING, "Could not join the hotspot \"$setupSsid\": ${e.message ?: e.javaClass.simpleName}")
            return finish(PairingProgress.Failed(PairingFailure.JOIN_FAILED, e.message))
        }
        log.info(LogSource.PAIRING, "Joined the hotspot")

        // 2. Hand over the home Wi-Fi details, then let go of the hotspot whatever happens.
        _progress.value = PairingProgress.Sending
        log.info(LogSource.PAIRING, "Sending the Wi-Fi details")
        val result = try {
            link.use { provisioning.sendWifiCredentials(it.channel, homeSsid, password.take()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Only the error's type: a connector's message must not be able to leak anything it was given.
            log.warn(LogSource.PAIRING, "Sending failed (${e.javaClass.simpleName})")
            return finish(PairingProgress.Failed(PairingFailure.SEND_FAILED, null))
        } finally {
            password.take() // forget it even if the connector never asked
            log.info(LogSource.PAIRING, "Released the hotspot")
        }
        if (!result.accepted) {
            log.warn(LogSource.PAIRING, "The device did not confirm the Wi-Fi details")
            return finish(PairingProgress.Failed(PairingFailure.NOT_CONFIRMED, result.message))
        }
        log.info(LogSource.PAIRING, "The device accepted the Wi-Fi details")

        // 3. Wait for it on the home network.
        return waitForDevice(connector, provisioning, setupSsid, result.message)
    }

    /** Searches the home network until the device from [setupSsid] appears (also "Wait for it anyway"). */
    suspend fun waitForDevice(
        connector: Connector,
        provisioning: Provisioning,
        setupSsid: String,
        message: String,
    ): PairingProgress {
        val started = now()
        var searches = 0
        log.info(LogSource.PAIRING, "Waiting for the device to join the home Wi-Fi")
        while (now() - started < timing.waitTimeoutMs) {
            searches++
            _progress.value = PairingProgress.Waiting(started, timing.waitTimeoutMs, searches)
            val network = try {
                platform.homeNetwork(setupSsid)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (network == null) {
                log.trace(LogSource.PAIRING) { "Search $searches: not back on the home Wi-Fi yet" }
            } else {
                val found = try {
                    platform.discoveryWindow { connector.discover(network, timing.discoverTimeoutMs) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn(LogSource.PAIRING, "Search $searches failed: ${e.message ?: e.javaClass.simpleName}")
                    emptyList()
                }
                val match = found.firstOrNull { provisioning.matchesSetupHotspot(it, setupSsid) }
                if (match != null) {
                    log.info(LogSource.PAIRING, "Found it: ${match.name} at ${match.address} (search $searches)")
                    return finish(PairingProgress.Found(match, message))
                }
                log.trace(LogSource.PAIRING) { "Search $searches: ${found.size} device(s), not the new one yet" }
            }
            delay(timing.searchEveryMs)
        }
        log.warn(LogSource.PAIRING, "The device did not appear on the home Wi-Fi after $searches searches")
        return finish(PairingProgress.Failed(PairingFailure.NOT_FOUND, null))
    }

    private fun finish(progress: PairingProgress): PairingProgress {
        _progress.value = progress
        return progress
    }
}
