package com.technlov.homecontroller.hub

import com.technlov.home.core.Capability
import com.technlov.home.core.DeviceKey
import com.technlov.home.core.DeviceState
import com.technlov.home.core.SetValue
import com.technlov.home.core.Value

/** How the hub's link to one device is doing. */
enum class LinkStatus { CONNECTING, ONLINE, OFFLINE }

/** Why a device could not be reached or changed. The UI turns it into words. */
sealed interface DeviceIssue {
    data object Timeout : DeviceIssue
    data class NoConnector(val connectorId: String) : DeviceIssue
    data object UnknownDevice : DeviceIssue
    data class Failed(val message: String?) : DeviceIssue

    companion object {
        fun from(error: Throwable): DeviceIssue = when (error) {
            is DeviceTimeoutException -> Timeout
            is MissingConnectorException -> NoConnector(error.connectorId)
            is UnknownDeviceException -> UnknownDevice
            else -> Failed(error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName)
        }
    }
}

/** Plain English for the diagnostics log (the UI uses translated strings instead). */
fun DeviceIssue.describe(): String = when (this) {
    DeviceIssue.Timeout -> "no answer in time"
    DeviceIssue.UnknownDevice -> "not a saved device"
    is DeviceIssue.NoConnector -> "no plugin \"$connectorId\""
    is DeviceIssue.Failed -> message ?: "failed"
}

/** "power=on, target_temperature=22" for the diagnostics log. */
fun List<SetValue>.describe(): String = joinToString(", ") { change ->
    val shown = when (val v = change.value) {
        is Value.Bool -> if (v.v) "on" else "off"
        is Value.Num -> if (v.v % 1.0 == 0.0) v.v.toLong().toString() else v.v.toString()
        is Value.Text -> v.v
    }
    "${change.key}=$shown"
}

class DeviceTimeoutException : Exception("The device did not answer in time")
class MissingConnectorException(val connectorId: String) : Exception("No plugin for \"$connectorId\"")
class UnknownDeviceException(key: DeviceKey) : Exception("Unknown device $key")
class DeviceReportedOfflineException(message: String?) : Exception(message ?: "The device reported it is offline")

/** What the UI sees about one device. */
data class DeviceLive(
    val key: DeviceKey,
    val status: LinkStatus,
    /** The last state read from the device, with changes still being sent laid over it (optimistic UI). */
    val state: DeviceState?,
    val capabilities: List<Capability>,
    val issue: DeviceIssue? = null,
    val lastSeenMillis: Long? = null,
    /** A change is on its way to the device. */
    val busy: Boolean = false,
)

/**
 * Lays changes that are still being sent over the last known state, so the UI moves the moment the user taps and
 * snaps back if the device refuses. Pure Kotlin; thread-safe.
 */
class OptimisticOverlay {
    private val lock = Any()
    private var nextToken = 1L
    private val pending = LinkedHashMap<DeviceKey, MutableList<Pair<Long, List<SetValue>>>>()

    /** Records [changes] as in flight for [key]; returns the token to pass to [end]. */
    fun begin(key: DeviceKey, changes: List<SetValue>): Long = synchronized(lock) {
        val token = nextToken++
        pending.getOrPut(key) { mutableListOf() }.add(token to changes)
        token
    }

    /** The change with [token] finished (applied or failed): stop showing it. */
    fun end(key: DeviceKey, token: Long): Unit = synchronized(lock) {
        val list = pending[key] ?: return
        list.removeAll { it.first == token }
        if (list.isEmpty()) pending.remove(key)
    }

    fun hasPending(key: DeviceKey): Boolean = synchronized(lock) { pending[key]?.isNotEmpty() == true }

    fun merge(key: DeviceKey, base: DeviceState?): DeviceState? {
        val changes = synchronized(lock) { pending[key]?.map { it.second }.orEmpty() }
        return mergeOptimistic(base, changes)
    }
}

/** [base] with every value in [pending] (oldest first, so the newest wins) laid over it. */
fun mergeOptimistic(base: DeviceState?, pending: List<List<SetValue>>): DeviceState? {
    if (pending.isEmpty()) return base
    val values = base?.values.orEmpty().toMutableMap()
    pending.forEach { changes -> changes.forEach { values[it.key] = it.value } }
    return (base ?: DeviceState(values = emptyMap(), online = false)).copy(values = values)
}

/** Retry delays after consecutive failures: 5 s, 10 s, 20 s ... capped at 5 minutes. */
object Backoff {
    const val BASE_MS = 5_000L
    const val MAX_MS = 300_000L

    fun delayMs(failures: Int, baseMs: Long = BASE_MS, maxMs: Long = MAX_MS): Long {
        if (failures <= 0) return 0
        val shift = (failures - 1).coerceAtMost(20)
        return (baseMs shl shift).coerceAtMost(maxMs)
    }
}
