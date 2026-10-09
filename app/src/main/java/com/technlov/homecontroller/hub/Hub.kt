package com.technlov.homecontroller.hub

import com.technlov.home.core.Action
import com.technlov.home.core.Automation
import com.technlov.home.core.AutomationEngine
import com.technlov.home.core.Capability
import com.technlov.home.core.ConnectorRegistry
import com.technlov.home.core.DeviceKey
import com.technlov.home.core.DeviceRef
import com.technlov.home.core.DeviceSession
import com.technlov.home.core.DeviceState
import com.technlov.home.core.SetValue
import com.technlov.home.core.key
import com.technlov.homecontroller.data.HomeData
import com.technlov.homecontroller.data.HomeRepository
import com.technlov.homecontroller.data.SavedDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

/** What the hub tells the outside world (Android notifications in the app, a fake in tests). */
interface HubEvents {
    /** An automation's Notify action. */
    fun notify(title: String, text: String)

    /** An automation could not change a device. */
    fun automationFailed(automation: Automation, deviceName: String, issue: DeviceIssue)
}

/**
 * The hub: owns one [DeviceSession] per saved device, polls their states, sends changes with an optimistic overlay,
 * and runs automations every minute. Pure Kotlin (no Android), so it is unit-tested on the JVM. [HubService] keeps
 * it alive by running [run] in a foreground service; the UI calls [refresh] and [apply] directly.
 *
 * A device error never escapes: the device is marked offline and retried with [Backoff].
 */
class Hub(
    private val registry: ConnectorRegistry,
    private val repository: HomeRepository,
    private val engine: AutomationEngine,
    private val events: HubEvents,
    private val beforeNetworkUse: () -> Unit = {},
    private val clock: () -> LocalDateTime = { LocalDateTime.now() },
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val ioTimeoutMs: Long = DEFAULT_IO_TIMEOUT_MS,
    /** Where device calls run: never the main thread, even if a connector forgets to switch itself. */
    private val ioContext: CoroutineContext = Dispatchers.IO,
) {
    private data class SlotView(
        val status: LinkStatus,
        val state: DeviceState?,
        val capabilities: List<Capability>,
        val issue: DeviceIssue?,
        val lastSeenMillis: Long?,
    )

    /** One device's connection. Mutable fields change only while [mutex] is held. */
    private class Slot(ref: DeviceRef, capabilities: List<Capability>) {
        val mutex = Mutex()
        @Volatile var ref: DeviceRef = ref
        var session: DeviceSession? = null
        var failures = 0
        var nextAttemptAt = 0L
        @Volatile var view = SlotView(LinkStatus.CONNECTING, null, capabilities, null, null)

        fun closeSession() {
            val open = session ?: return
            session = null
            runCatching { open.close() }
        }
    }

    private val slots = ConcurrentHashMap<DeviceKey, Slot>()
    private val overlay = OptimisticOverlay()
    private val syncMutex = Mutex()
    private val runMutex = Mutex()
    private val publishLock = Any()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var previousStates: Map<DeviceKey, DeviceState> = emptyMap()

    private val _live = MutableStateFlow<Map<DeviceKey, DeviceLive>>(emptyMap())
    /** Live state of every saved device, keyed by [DeviceKey]. */
    val live: StateFlow<Map<DeviceKey, DeviceLive>> = _live.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _lastRuns = MutableStateFlow<Map<String, LocalDateTime>>(emptyMap())
    /** When each automation last ran (since the hub started). */
    val lastRuns: StateFlow<Map<String, LocalDateTime>> = _lastRuns.asStateFlow()

    /** True while the app is on screen: devices are polled more often. */
    val appVisible = MutableStateFlow(false)

    /** Runs the hub until cancelled: keeps sessions in line with the saved devices, polls, runs automations. */
    suspend fun run() {
        runMutex.withLock {
            _running.value = true
            try {
                coroutineScope {
                    launch {
                        repository.data.map { it.devices }.distinctUntilChanged().collect {
                            sync(it)
                            wake.trySend(Unit)
                        }
                    }
                    launch { appVisible.collect { visible -> if (visible) wake.trySend(Unit) } }
                    launch { pollLoop() }
                    launch { automationLoop() }
                }
            } finally {
                _running.value = false
                withContext(NonCancellable) {
                    slots.values.forEach { slot -> slot.mutex.withLock { slot.closeSession() } }
                }
            }
        }
    }

    /** Reads the given devices (all when null) now, ignoring any retry delay. */
    suspend fun refresh(keys: Collection<DeviceKey>? = null) {
        sync(repository.current().devices)
        val targets = keys?.mapNotNull { slots[it] } ?: slots.values.toList()
        pollSlots(targets, force = true)
    }

    /**
     * Sends [changes] to a device. The UI shows them at once (optimistic overlay); if the device refuses or does not
     * answer, the overlay is dropped so the UI snaps back, and the failure is returned.
     */
    suspend fun apply(key: DeviceKey, changes: List<SetValue>): Result<DeviceState> {
        if (changes.isEmpty()) return Result.failure(IllegalArgumentException("Nothing to change"))
        val slot = slots[key] ?: run {
            sync(repository.current().devices)
            slots[key]
        } ?: return Result.failure(UnknownDeviceException(key))

        val token = overlay.begin(key, changes)
        publish()
        try {
            runCatching(beforeNetworkUse)
            return slot.mutex.withLock {
                val result = attempt(slot) { it.apply(changes) }
                    .mapCatching { state -> if (state.online) state else throw DeviceReportedOfflineException(state.error) }
                result.onSuccess { onSuccess(slot, it) }.onFailure { slot.closeSession() }
                result
            }
        } finally {
            overlay.end(key, token)
            publish()
        }
    }

    /** Runs an automation's actions now (the minute tick, or "Run now" in the editor). */
    suspend fun execute(automation: Automation, data: HomeData? = null) {
        val names = data ?: repository.current()
        for (action in automation.actions) {
            when (action) {
                is Action.SetValues -> apply(action.device, action.changes).onFailure { error ->
                    val name = names.device(action.device)?.name ?: action.device
                    runCatching { events.automationFailed(automation, name, DeviceIssue.from(error)) }
                }
                is Action.Notify -> runCatching { events.notify(action.title, action.text) }
            }
        }
    }

    /** One automation pass for the minute [now]. Called every minute by [run]; public for tests. */
    suspend fun tick(now: LocalDateTime) {
        val data = repository.current()
        val states = currentStates()
        val due = engine.due(now, data.automations.filter { it.enabled }, states, previousStates, _lastRuns.value)
        previousStates = states
        for (automation in due) {
            _lastRuns.update { it + (automation.id to now) }
            execute(automation, data)
        }
    }

    // ---- loops ----

    private suspend fun pollLoop() {
        while (true) {
            pollSlots(slots.values.toList(), force = false)
            val settings = repository.current().settings
            val seconds = if (appVisible.value) settings.foregroundPollSeconds else settings.backgroundPollSeconds
            withTimeoutOrNull(seconds.coerceAtLeast(1) * 1_000L) { wake.receive() }
        }
    }

    private suspend fun automationLoop() {
        while (true) {
            val now = clock()
            val untilNextMinute = 60_000L - (now.second * 1_000L + now.nano / 1_000_000L)
            delay(untilNextMinute + 200)
            try {
                tick(clock().withSecond(0).withNano(0))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // An engine or storage error must not stop the hub; the next minute tries again.
            }
        }
    }

    // ---- devices ----

    private suspend fun pollSlots(targets: List<Slot>, force: Boolean) {
        if (targets.isEmpty()) return
        runCatching(beforeNetworkUse)
        coroutineScope { targets.forEach { slot -> launch { poll(slot, force) } } }
    }

    private suspend fun poll(slot: Slot, force: Boolean) {
        if (!force && nowMillis() < slot.nextAttemptAt) return
        slot.mutex.withLock {
            attempt(slot) { it.read() }.fold({ onSuccess(slot, it) }, { onFailure(slot, it) })
        }
        publish()
    }

    private suspend fun <T> attempt(slot: Slot, block: suspend (DeviceSession) -> T): Result<T> = try {
        withTimeout(ioTimeoutMs) {
            Result.success(withContext(ioContext) { block(slot.session ?: openSession(slot)) })
        }
    } catch (e: TimeoutCancellationException) {
        Result.failure(DeviceTimeoutException())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private suspend fun openSession(slot: Slot): DeviceSession {
        val ref = slot.ref
        val connector = registry[ref.connectorId] ?: throw MissingConnectorException(ref.connectorId)
        val session = connector.open(ref)
        slot.session = session
        val capabilities = session.capabilities
        if (capabilities != slot.view.capabilities) {
            slot.view = slot.view.copy(capabilities = capabilities)
            try {
                repository.rememberCapabilities(ref.key(), capabilities)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Remembering is a convenience for offline use; the session works without it.
            }
        }
        return session
    }

    private fun onSuccess(slot: Slot, state: DeviceState) {
        if (!state.online) {
            onFailure(slot, DeviceReportedOfflineException(state.error))
            return
        }
        slot.failures = 0
        slot.nextAttemptAt = 0
        slot.view = slot.view.copy(status = LinkStatus.ONLINE, state = state, issue = null, lastSeenMillis = nowMillis())
    }

    private fun onFailure(slot: Slot, error: Throwable) {
        slot.failures++
        slot.closeSession()
        slot.nextAttemptAt = nowMillis() + Backoff.delayMs(slot.failures)
        // One lost reply is common on Wi-Fi; only call a device that was online offline after a second miss.
        val stillOnline = slot.view.status == LinkStatus.ONLINE && slot.failures < OFFLINE_AFTER_FAILURES
        slot.view = slot.view.copy(
            status = if (stillOnline) LinkStatus.ONLINE else LinkStatus.OFFLINE,
            issue = DeviceIssue.from(error),
        )
    }

    /** Brings [slots] in line with the saved devices: opens new ones, closes removed ones, reconnects moved ones. */
    private suspend fun sync(devices: List<SavedDevice>) {
        syncMutex.withLock {
            val wanted = devices.associateBy { it.key }
            for (key in slots.keys.toList()) {
                if (key in wanted) continue
                val removed = slots.remove(key) ?: continue
                removed.mutex.withLock { removed.closeSession() }
            }
            for ((key, device) in wanted) {
                val slot = slots[key]
                if (slot == null) {
                    slots[key] = Slot(device.ref, device.capabilities)
                } else if (slot.ref != device.ref) {
                    slot.mutex.withLock {
                        slot.closeSession()
                        slot.ref = device.ref
                        slot.failures = 0
                        slot.nextAttemptAt = 0
                    }
                }
            }
        }
        publish()
    }

    private fun currentStates(): Map<DeviceKey, DeviceState> = buildMap {
        slots.forEach { (key, slot) ->
            val view = slot.view
            view.state?.let { put(key, it.copy(online = view.status == LinkStatus.ONLINE)) }
        }
    }

    private fun publish() {
        synchronized(publishLock) {
            _live.value = slots.entries.associate { (key, slot) ->
                val view = slot.view
                key to DeviceLive(
                    key = key,
                    status = view.status,
                    state = overlay.merge(key, view.state),
                    capabilities = view.capabilities,
                    issue = view.issue,
                    lastSeenMillis = view.lastSeenMillis,
                    busy = overlay.hasPending(key),
                )
            }
        }
    }

    companion object {
        const val DEFAULT_IO_TIMEOUT_MS = 8_000L
        const val OFFLINE_AFTER_FAILURES = 2
    }
}
