package com.technlov.home.connectors.tuya

import com.technlov.home.core.Capability
import com.technlov.home.core.ConnectorException
import com.technlov.home.core.DeviceRef
import com.technlov.home.core.DeviceSession
import com.technlov.home.core.DeviceState
import com.technlov.home.core.EventLog
import com.technlov.home.core.SetValue
import com.technlov.home.core.Value
import com.technlov.home.core.info
import com.technlov.home.core.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * An open connection to one Tuya device. Calls are serialised (one request in flight per device).
 *
 * [read] never throws for a device that stops answering: it returns the last known values with `online = false` and
 * the reason in `error`. [apply] throws [ConnectorException] when a change is refused before sending (unknown key,
 * read-only, value out of range) or the device does not confirm it.
 *
 * Capabilities are the device's *complete view*: a control or reading for every data point it reported, named by its
 * code where known and `dp_<id>` otherwise. The on-board timers are set with [setInching], [setCycleTimers] and
 * [setRandomTimers]; they persist on the device with the phone off. For schedules the app runs itself (no cloud),
 * add a TimeOfDay automation whose action sets this device's `power`.
 */
class TuyaSession internal constructor(
    device: DeviceRef,
    private val client: TuyaClient,
    private val points: Map<Int, TuyaModel.CodeSpec>,
    private val config: TuyaConfig,
    private val log: EventLog = EventLog.None,
) : DeviceSession {
    override var device: DeviceRef = device
        private set

    override var capabilities: List<Capability> = emptyList()
        private set

    private val lock = Mutex()
    private var raw: Map<String, Any?> = emptyMap()
    private var online = true

    private val who get() = "${device.name} (${device.address})"

    /** First read: learns which data points the device reports and declares the capabilities for them. */
    internal suspend fun prepare() = lock.withLock {
        raw = client.status()
        capabilities = TuyaModel.capabilities(reportedIds(raw), points)
    }

    override suspend fun read(): DeviceState = lock.withLock { readLocked() }

    override suspend fun apply(changes: List<SetValue>): DeviceState = lock.withLock {
        val dps = try {
            TuyaModel.encode(changes, points)
        } catch (e: IllegalArgumentException) {
            log.warn(TuyaClient.SOURCE, "$who: change refused before sending: ${e.message}")
            throw ConnectorException("${device.name}: ${e.message}", e)
        }
        val declared = capabilities.mapTo(HashSet()) { it.key }
        changes.firstOrNull { it.key !in declared }?.let {
            log.warn(TuyaClient.SOURCE, "$who: change refused before sending: this device has no '${it.key}'")
            throw ConnectorException("${device.name}: this device has no '${it.key}'")
        }
        if (dps.isEmpty()) return@withLock readLocked()
        sendLocked(dps)
    }

    /** Set the device's on-board inching (auto-off) timers; they keep working with the phone off. */
    suspend fun setInching(tasks: List<TuyaTimers.Inching>): DeviceState =
        setRawTimer(TuyaKeys.SWITCH_INCHING, TuyaTimers.encodeInching(tasks))

    /** Set the device's on-board cycle (repeat on/off) timers. */
    suspend fun setCycleTimers(tasks: List<TuyaTimers.CycleTimer>): DeviceState =
        setRawTimer(TuyaKeys.CYCLE_TIME, TuyaTimers.encodeCycle(tasks))

    /** Set the device's on-board random timers. */
    suspend fun setRandomTimers(tasks: List<TuyaTimers.RandomTimer>): DeviceState =
        setRawTimer(TuyaKeys.RANDOM_TIME, TuyaTimers.encodeRandom(tasks))

    /** The device's current on-board inching timers, decoded from its last reported state (empty if none/unsupported). */
    suspend fun currentInching(): List<TuyaTimers.Inching> = lock.withLock {
        rawText(TuyaKeys.SWITCH_INCHING)?.let { runCatching { TuyaTimers.decodeInching(it) }.getOrDefault(emptyList()) } ?: emptyList()
    }

    suspend fun currentCycleTimers(): List<TuyaTimers.CycleTimer> = lock.withLock {
        rawText(TuyaKeys.CYCLE_TIME)?.let { runCatching { TuyaTimers.decodeCycle(it) }.getOrDefault(emptyList()) } ?: emptyList()
    }

    suspend fun currentRandomTimers(): List<TuyaTimers.RandomTimer> = lock.withLock {
        rawText(TuyaKeys.RANDOM_TIME)?.let { runCatching { TuyaTimers.decodeRandom(it) }.getOrDefault(emptyList()) } ?: emptyList()
    }

    private suspend fun setRawTimer(code: String, encoded: String): DeviceState = lock.withLock {
        val id = points.entries.firstOrNull { it.value.capabilityKey == code }?.key
            ?: throw ConnectorException("${device.name}: this device has no '$code'")
        sendLocked(linkedMapOf(id.toString() to encoded))
    }

    private suspend fun sendLocked(dps: Map<String, Any?>): DeviceState {
        log.info(TuyaClient.SOURCE, "$who: command sent: ${dps.entries.joinToString { "${it.key}=${it.value}" }}")
        val echoed = try {
            client.set(dps)
        } catch (e: TuyaProtocolException) {
            log.warn(TuyaClient.SOURCE, "$who: command refused: ${e.message}")
            throw ConnectorException("${device.name}: ${e.message}", e)
        } catch (e: ConnectorException) {
            log.warn(TuyaClient.SOURCE, "$who: command not confirmed: ${e.message}")
            throw e
        }
        // Optimistically fold in what we sent (and anything the device echoed), then read the real state back.
        raw = raw + dps + echoed
        return readLocked()
    }

    private suspend fun readLocked(): DeviceState {
        return try {
            raw = statusWithReconnect()
            if (!online) log.info(TuyaClient.SOURCE, "$who: answering again")
            online = true
            DeviceState(TuyaModel.decode(raw, points), online = true, updatedAtMillis = config.clock())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (online) log.warn(TuyaClient.SOURCE, "$who: went silent: ${e.message}")
            online = false
            DeviceState(TuyaModel.decode(raw, points), online = false, updatedAtMillis = config.clock(), error = e.message)
        }
    }

    /** A dropped connection is reopened once (3.4/3.5 renegotiate the session key) before a read is given up on. */
    private suspend fun statusWithReconnect(): Map<String, Any?> = try {
        client.status()
    } catch (e: ConnectorException) {
        log.warn(TuyaClient.SOURCE, "$who: ${e.message}; reconnecting")
        client.connect()
        client.status()
    }

    private fun reportedIds(raw: Map<String, Any?>): Set<Int> = raw.keys.mapNotNullTo(LinkedHashSet()) { it.toIntOrNull() }

    private fun rawText(code: String): String? {
        val id = points.entries.firstOrNull { it.value.capabilityKey == code }?.key ?: return null
        return raw[id.toString()]?.toString()
    }

    override fun toString(): String = "TuyaSession(${device.id})"
}
