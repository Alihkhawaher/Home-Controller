package com.technlov.home.connectors.tuya

import com.technlov.home.core.Capability
import com.technlov.home.core.ConnectorException
import com.technlov.home.core.DeviceRef
import com.technlov.home.core.DeviceSession
import com.technlov.home.core.DeviceState
import com.technlov.home.core.EventLog
import com.technlov.home.core.SetValue
import com.technlov.home.core.info
import com.technlov.home.core.trace
import com.technlov.home.core.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * An open, live connection to one Tuya device. Requests are serialised (one in flight per device).
 *
 * While the session lives it keeps ONE TCP connection open -- many Tuya devices accept a single local connection --
 * and hears what the device pushes: a change made on the device itself or in another app, an alarm going off.
 * Every push reaches [updates] as a whole state at once. A HEART_BEAT goes out whenever the connection has been idle
 * for [TuyaConfig.heartbeatIntervalMs] (devices drop an idle socket); a connection that drops or goes silent is
 * reopened with a growing delay and the state read again. Polls ([read]) keep working throughout and stay the
 * fallback. Devices with readings they only re-measure when asked (plug and meter power, current, voltage) get an
 * UPDATEDPS refresh on every other read, alternating with the normal status query as tuya-local does.
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
    links: CoroutineScope,
) : DeviceSession {
    override var device: DeviceRef = device
        private set

    override var capabilities: List<Capability> = emptyList()
        private set

    /** The reader and the keep-alive loop run here; [close] cancels it. */
    private val job = SupervisorJob(links.coroutineContext[Job])
    private val scope = CoroutineScope(links.coroutineContext + job + CoroutineName("tuya-session"))

    /** One request at a time, and (re)connecting only under it: never two sockets to one device. */
    private val lock = Mutex()

    /** Guards [raw] and [lastStamp]: the reader merges frames into them while requests read them. */
    private val store = Any()
    private var raw: Map<String, Any?> = emptyMap()
    private var lastStamp = Long.MIN_VALUE

    @Volatile private var started = false
    @Volatile private var closed = false
    private var online = true

    /** Never suspends the reader: the oldest pushes go first if nobody keeps up (each one is a whole state). */
    private val pushes = MutableSharedFlow<DeviceState>(extraBufferCapacity = PUSH_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** Reported dps the device only re-measures on UPDATEDPS; empty when it has none (or ignores the command). */
    private var refreshIds: List<Int> = emptyList()
    private var refreshTurn = false
    private var refreshMisses = 0

    private val who get() = "${device.name} (${device.address})"

    init {
        client.onDps = ::onDps
        client.onDropped = { wake.trySend(Unit) }
    }

    override fun updates(): Flow<DeviceState> = pushes.asSharedFlow()

    /** Connects, reads the first state to learn which data points the device has, then keeps the connection alive. */
    internal suspend fun start() {
        lock.withLock {
            connectLocked()
            client.status()
            val reported = reportedIds(synchronized(store) { raw })
            capabilities = TuyaModel.capabilities(reported, points)
            refreshIds = TuyaModel.refreshIds(reported, points)
            started = true
        }
        if (refreshIds.isNotEmpty()) {
            log.trace(TuyaClient.SOURCE) { "$who: refreshes dps ${refreshIds.joinToString(",")} with UPDATEDPS on alternate reads" }
        }
        scope.launch(CoroutineName("tuya-keepalive")) { keepAlive() }
    }

    override suspend fun read(): DeviceState = lock.withLock { readLocked(refresh = true) }

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
        if (dps.isEmpty()) return@withLock readLocked(refresh = false)
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

    /** Closes the connection and stops the keep-alive; [updates] goes quiet. */
    override fun close() {
        if (closed) return
        closed = true
        job.cancel()
        client.close()
        log.trace(TuyaClient.SOURCE) { "$who: session closed" }
    }

    private suspend fun setRawTimer(code: String, encoded: String): DeviceState = lock.withLock {
        val id = points.entries.firstOrNull { it.value.capabilityKey == code }?.key
            ?: throw ConnectorException("${device.name}: this device has no '$code'")
        sendLocked(linkedMapOf(id.toString() to encoded))
    }

    private suspend fun sendLocked(dps: Map<String, Any?>): DeviceState {
        log.info(TuyaClient.SOURCE, "$who: command sent: ${dps.entries.joinToString { "${it.key}=${it.value}" }}")
        val echoed = try {
            if (!client.isConnected) connectLocked()
            client.set(dps)
        } catch (e: TuyaProtocolException) {
            log.warn(TuyaClient.SOURCE, "$who: command refused: ${e.message}")
            throw ConnectorException("${device.name}: ${e.message}", e)
        } catch (e: ConnectorException) {
            log.warn(TuyaClient.SOURCE, "$who: command not confirmed: ${e.message}")
            throw e
        }
        // Optimistically fold in what we sent (and anything the device echoed), then read the real state back.
        synchronized(store) { raw = raw + dps + echoed }
        return readLocked(refresh = false)
    }

    private suspend fun readLocked(refresh: Boolean): DeviceState {
        return try {
            fetchWithReconnect(refresh)
            if (!online) log.info(TuyaClient.SOURCE, "$who: answering again")
            online = true
            snapshot(online = true, error = null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (online) log.warn(TuyaClient.SOURCE, "$who: went silent: ${e.message}")
            online = false
            snapshot(online = false, error = e.message)
        }
    }

    /** A dropped or silent connection is reopened once (3.4/3.5 renegotiate the session key) before a read is given up on. */
    private suspend fun fetchWithReconnect(refresh: Boolean) {
        try {
            if (!client.isConnected) connectLocked()
            fetch(refresh)
        } catch (e: ConnectorException) {
            log.warn(TuyaClient.SOURCE, "$who: ${e.message}; reconnecting")
            connectLocked()
            client.status()
        }
    }

    /** The status query; for a device with readings to refresh, every other read sends UPDATEDPS instead. */
    private suspend fun fetch(refresh: Boolean) {
        val ids = refreshIds
        if (refresh && ids.isNotEmpty()) {
            refreshTurn = !refreshTurn
            if (refreshTurn) {
                // Answered: the fresh readings follow as a STATUS push (to updates() and the next read).
                if (client.updateDps(ids)) {
                    refreshMisses = 0
                    return
                }
                if (++refreshMisses >= MAX_REFRESH_MISSES) {
                    refreshIds = emptyList()
                    log.info(TuyaClient.SOURCE, "$who: does not answer UPDATEDPS; reading it by status query only")
                } else {
                    log.trace(TuyaClient.SOURCE) { "$who: no answer to UPDATEDPS; status query instead" }
                }
            }
        }
        client.status()
    }

    private suspend fun connectLocked() {
        if (closed) throw ConnectorException("${device.name}: the session is closed")
        client.connect(scope)
        if (closed) {
            client.close()
            throw ConnectorException("${device.name}: the session is closed")
        }
    }

    /**
     * Runs while the session lives. Connected: every heartbeat interval, a HEART_BEAT if nothing else went out (the
     * device closes an idle socket), and a reconnect if nothing at all came back for [TuyaConfig.silentIntervals]
     * intervals. Dropped: reconnect after 1 s, 2 s, 4 s ... up to [TuyaConfig.reconnectMaxDelayMs], reading the
     * state again on success (pushes sent meanwhile were lost). The delay only starts again from 1 s once a
     * connection has held for [STABLE_INTERVALS] intervals, so a device that hangs up after every answer is not
     * reconnected to every second. Polls may reconnect sooner; the lock keeps it to one socket.
     */
    private suspend fun keepAlive() {
        var attempts = 0
        var heldIntervals = 0
        var silent = 0
        var heardMark = client.framesHeard
        var sentMark = client.framesSent
        while (true) {
            if (!client.isConnected) {
                delay(reconnectDelayMs(attempts++))
                if (reconnect()) {
                    heldIntervals = 0
                    silent = 0
                    heardMark = client.framesHeard
                    sentMark = client.framesSent
                }
                continue
            }
            // A drop wakes us at once; otherwise this is one heartbeat interval.
            if (withTimeoutOrNull(config.heartbeatIntervalMs) { wake.receive() } != null) continue
            if (!client.isConnected) continue
            if (++heldIntervals >= STABLE_INTERVALS) attempts = 0
            val heard = client.framesHeard
            silent = if (heard == heardMark) silent + 1 else 0
            heardMark = heard
            if (silent >= config.silentIntervals) {
                log.info(TuyaClient.SOURCE, "$who: nothing heard for ${silent * config.heartbeatIntervalMs / 1_000} s; reconnecting")
                client.drop("silent")
                silent = 0
                continue
            }
            if (client.framesSent == sentMark) client.heartbeat()
            sentMark = client.framesSent
        }
    }

    /** One reconnect attempt, under the request lock; true when connected (by this attempt or a poll meanwhile). */
    private suspend fun reconnect(): Boolean = lock.withLock {
        if (client.isConnected) return@withLock true
        try {
            connectLocked()
            client.status() // catch up: pushes sent while the connection was down are lost
            online = true
            log.info(TuyaClient.SOURCE, "$who: connection restored")
            pushes.tryEmit(snapshot(online = true, error = null))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            client.close()
            log.trace(TuyaClient.SOURCE) { "$who: reconnect failed: ${e.message}" }
            false
        }
    }

    private fun reconnectDelayMs(attempts: Int): Long =
        (RECONNECT_FIRST_MS shl attempts.coerceAtMost(16)).coerceAtMost(config.reconnectMaxDelayMs)

    /** From the reader, in frame order: merge, and emit the whole state when the device sent it on its own. */
    private fun onDps(dps: Map<String, Any?>, push: Boolean) {
        val state = synchronized(store) {
            raw = raw + dps
            if (push && started && !closed) stateLocked(online = true, error = null) else null
        } ?: return
        log.trace(TuyaClient.SOURCE) { "$who: pushed ${dps.entries.joinToString { "${it.key}=${it.value}" }}" }
        pushes.tryEmit(state)
    }

    private fun snapshot(online: Boolean, error: String?): DeviceState = synchronized(store) { stateLocked(online, error) }

    /**
     * Call holding [store]. The stamp only goes up within the session -- even if the phone's clock steps back -- and is
     * taken together with the values, so a state's stamp orders it among this session's pushes and reads.
     */
    private fun stateLocked(online: Boolean, error: String?): DeviceState {
        val now = config.clock()
        lastStamp = if (now > lastStamp) now else lastStamp + 1
        return DeviceState(TuyaModel.decode(raw, points), online = online, updatedAtMillis = lastStamp, error = error)
    }

    private fun reportedIds(raw: Map<String, Any?>): Set<Int> = raw.keys.mapNotNullTo(LinkedHashSet()) { it.toIntOrNull() }

    private fun rawText(code: String): String? {
        val id = points.entries.firstOrNull { it.value.capabilityKey == code }?.key ?: return null
        return synchronized(store) { raw[id.toString()] }?.toString()
    }

    override fun toString(): String = "TuyaSession(${device.id})"

    private companion object {
        const val PUSH_BUFFER = 64
        const val MAX_REFRESH_MISSES = 3
        const val RECONNECT_FIRST_MS = 1_000L

        /** Heartbeat intervals a connection must hold before reconnect delays start again from the first (60 s). */
        const val STABLE_INTERVALS = 6
    }
}
