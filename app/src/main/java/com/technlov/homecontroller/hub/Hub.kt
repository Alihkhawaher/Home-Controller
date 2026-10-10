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
import com.technlov.home.core.EventLog
import com.technlov.home.core.SetValue
import com.technlov.home.core.Trigger
import com.technlov.home.core.info
import com.technlov.home.core.trace
import com.technlov.home.core.warn
import com.technlov.home.core.key
import com.technlov.homecontroller.data.HomeData
import com.technlov.homecontroller.data.HomeRepository
import com.technlov.homecontroller.data.SecretBox
import com.technlov.homecontroller.history.HistorySink
import com.technlov.homecontroller.data.SavedDevice
import com.technlov.homecontroller.diag.LogSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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
 * A session that offers [DeviceSession.updates] (a device on a live connection) is also followed for as long as it
 * is open: each pushed state is shown at once, kept in the history, and checked against the state-triggered
 * automations straight away. Polling carries on at the normal interval as the fallback.
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
    /** Diagnostics: link changes, retries, poll summaries, automation runs. Never device keys. */
    private val log: EventLog = EventLog.None,
    /** Power history: every successful reading is offered here (electrical readings are kept; the rest ignored). */
    private val history: HistorySink = HistorySink.None,
) {
    private data class SlotView(
        val status: LinkStatus,
        val state: DeviceState?,
        val capabilities: List<Capability>,
        val issue: DeviceIssue?,
        val lastSeenMillis: Long?,
    )

    /** One device's connection. Mutable fields change only while [mutex] is held. */
    private class Slot(ref: DeviceRef, name: String, capabilities: List<Capability>) {
        val mutex = Mutex()
        @Volatile var ref: DeviceRef = ref
        @Volatile var name: String = name
        var offlineSince: Long? = null
        var session: DeviceSession? = null

        /** Follows the open session's pushes ([DeviceSession.updates]); ends with the session. */
        var pushes: Job? = null

        /** [DeviceState.updatedAtMillis] of the newest state shown from the open session: an older push is stale. */
        var shownStamp = Long.MIN_VALUE
        var failures = 0
        var nextAttemptAt = 0L
        @Volatile var view = SlotView(LinkStatus.CONNECTING, null, capabilities, null, null)

        fun closeSession() {
            pushes?.cancel()
            pushes = null
            val open = session ?: return
            session = null
            runCatching { open.close() }
        }
    }

    private val slots = ConcurrentHashMap<DeviceKey, Slot>()
    private val overlay = OptimisticOverlay()
    private val syncMutex = Mutex()
    private val runMutex = Mutex()

    /** Guards [previousStates], shared by the minute tick and the push-driven state checks. */
    private val automationMutex = Mutex()

    /** Parent of the push collectors (one per open session that offers updates) and the automation runs they start. */
    private val pushWork = SupervisorJob()
    private val publishLock = Any()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var previousStates: Map<DeviceKey, DeviceState> = emptyMap()
    private var lastSummary: String? = null
    private var lastSummaryAt = 0L

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
            log.info(LogSource.HUB, "Hub started")
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
                log.info(LogSource.HUB, "Hub stopped")
                withContext(NonCancellable) {
                    slots.values.forEach { slot -> slot.mutex.withLock { slot.closeSession() } }
                }
                pushWork.cancelChildren() // automations a push started stop with the hub, like the minute tick's
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
                val what = changes.describe()
                val result = attempt(slot) { it.apply(changes) }
                    .mapCatching { state -> if (state.online) state else throw DeviceReportedOfflineException(state.error) }
                result.onSuccess {
                    log.info(LogSource.HUB, "${slot.name}: set $what")
                    onSuccess(slot, it)
                }.onFailure { error ->
                    log.warn(LogSource.HUB, "${slot.name}: could not set $what: ${DeviceIssue.from(error).describe()}")
                    slot.closeSession()
                }
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
        log.info(LogSource.HUB, "Automation \"${automation.name}\": running ${automation.actions.size} action(s)")
        for (action in automation.actions) {
            when (action) {
                is Action.SetValues -> apply(action.device, action.changes).onFailure { error ->
                    val name = names.device(action.device)?.name ?: action.device
                    log.warn(LogSource.HUB, "Automation \"${automation.name}\": $name did not take the change")
                    runCatching { events.automationFailed(automation, name, DeviceIssue.from(error)) }
                }
                is Action.Notify -> {
                    log.info(LogSource.HUB, "Automation \"${automation.name}\": notification \"${action.title}\"")
                    runCatching { events.notify(action.title, action.text) }
                }
            }
        }
    }

    /** One automation pass for the minute [now]. Called every minute by [run]; public for tests. */
    suspend fun tick(now: LocalDateTime) {
        val data = repository.current()
        val due = dueNow(now, data.automations.filter { it.enabled })
        for (automation in due) execute(automation, data)
    }

    /**
     * The automations among [candidates] due at [now], against the states shown and the states of the last check
     * (a minute tick or a push), which become the new baseline. Their last run is set to [now].
     */
    private suspend fun dueNow(now: LocalDateTime, candidates: List<Automation>): List<Automation> = automationMutex.withLock {
        val states = currentStates()
        val due = engine.due(now, candidates, states, previousStates, _lastRuns.value)
        previousStates = states
        due.onEach { automation -> _lastRuns.update { it + (automation.id to now) } }
    }

    // ---- loops ----

    private suspend fun pollLoop() {
        while (true) {
            val started = nowMillis()
            pollSlots(slots.values.toList(), force = false)
            logSummary(nowMillis() - started)
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
                log.warn(LogSource.HUB, "Automation check failed", e)
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
        log.trace(LogSource.HUB) { "${slot.name}: opening a session at ${ref.address.ifEmpty { "(address to be found)" }}" }
        // Secrets are stored sealed; the connector gets them in clear, in memory only.
        val session = connector.open(ref.copy(extras = SecretBox.unsealExtras(ref.extras)))
        slot.session = session
        slot.shownStamp = Long.MIN_VALUE
        session.updates()?.let { updates -> slot.pushes = followPushes(slot, session, updates) }
        rememberWhereItIs(slot, ref, session.device)
        val capabilities = session.capabilities
        log.info(LogSource.HUB, "${slot.name}: connected at ${ref.address} (${capabilities.size} capabilities)")
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

    /**
     * The connector may have found the device at a new address (DHCP moved it, or an imported device had none yet)
     * or learned something non-secret such as its protocol version: keep that, but never a plaintext secret.
     */
    private suspend fun rememberWhereItIs(slot: Slot, stored: DeviceRef, used: DeviceRef) {
        val extras = stored.extras + SecretBox.withoutPlaintext(used.extras, stored.extras)
        if (used.address == stored.address && extras == stored.extras) return
        val updated = stored.copy(address = used.address, extras = extras)
        slot.ref = updated
        log.info(LogSource.HUB, "${slot.name}: found at ${used.address}")
        try {
            repository.refreshDeviceRef(updated)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The session works at the new address either way; it is found again next time if this save failed.
        }
    }

    private fun onSuccess(slot: Slot, state: DeviceState) {
        if (!state.online) {
            onFailure(slot, DeviceReportedOfflineException(state.error))
            return
        }
        val now = nowMillis()
        if (slot.view.status == LinkStatus.OFFLINE) {
            val away = slot.offlineSince?.let { " after ${(now - it) / 1_000} s" }.orEmpty()
            log.info(LogSource.HUB, "${slot.name} is online again$away")
        }
        slot.failures = 0
        slot.nextAttemptAt = 0
        slot.offlineSince = null
        slot.shownStamp = state.updatedAtMillis
        slot.view = slot.view.copy(status = LinkStatus.ONLINE, state = state, issue = null, lastSeenMillis = now)
        history.record(slot.ref.key(), slot.view.capabilities, state, now)
    }

    // ---- pushes ----

    /**
     * Follows [session]'s pushes for as long as it is [slot]'s session (closing it cancels this). Started undispatched,
     * so it is listening before the session's first read; it runs where device calls run ([ioContext]) and never
     * throws.
     */
    private suspend fun followPushes(slot: Slot, session: DeviceSession, updates: Flow<DeviceState>): Job {
        val context = currentCoroutineContext().minusKey(Job) + pushWork + CoroutineName("pushes: ${slot.name}")
        return CoroutineScope(context).launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                updates.collect { state -> onPushed(slot, session, state) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(LogSource.HUB, "${slot.name}: live updates stopped; polling only", e)
            }
        }
    }

    /**
     * A state the device pushed: shown at once and kept in the history like a poll, then checked against the
     * state-triggered automations straight away -- an alarm going off notifies within a second, not at the next poll.
     * A push from a session that is no longer the slot's, or older than a poll it crossed (by its stamp), is dropped.
     */
    private suspend fun onPushed(slot: Slot, session: DeviceSession, state: DeviceState) {
        if (!state.online) return // pushes carry what the device said; an unreachable device is found by polling
        val shown = slot.mutex.withLock {
            when {
                slot.session !== session -> false
                state.updatedAtMillis < slot.shownStamp -> {
                    log.trace(LogSource.HUB) { "${slot.name}: a push older than the state shown; skipped" }
                    false
                }
                else -> {
                    onSuccess(slot, state)
                    true
                }
            }
        }
        if (!shown) return
        log.trace(LogSource.HUB) { "${slot.name}: pushed update (${state.values.size} values)" }
        publish()
        runStateTriggers()
    }

    /**
     * The state-triggered automations that are due now, run at once (time triggers stay on the minute tick). They
     * share the baseline with the tick, so an edge fires once whichever check sees it first. The runs are not tied to
     * the pushing session: an action that fails on that same device and closes its session still completes.
     */
    private suspend fun runStateTriggers() {
        try {
            val data = repository.current()
            val candidates = data.automations.filter { it.enabled && it.trigger is Trigger.StateMatches }
            if (candidates.isEmpty()) return
            val due = dueNow(clock(), candidates)
            if (due.isEmpty()) return
            CoroutineScope(currentCoroutineContext().minusKey(Job) + pushWork).launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    for (automation in due) execute(automation, data)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn(LogSource.HUB, "Automation run failed", e)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // An engine or storage error must not stop the updates; the minute tick checks again.
            log.warn(LogSource.HUB, "Automation check failed", e)
        }
    }

    private fun onFailure(slot: Slot, error: Throwable) {
        slot.failures++
        slot.closeSession()
        val retryIn = Backoff.delayMs(slot.failures)
        slot.nextAttemptAt = nowMillis() + retryIn
        val issue = DeviceIssue.from(error)
        // One lost reply is common on Wi-Fi; only call a device that was online offline after a second miss.
        val stillOnline = slot.view.status == LinkStatus.ONLINE && slot.failures < OFFLINE_AFTER_FAILURES
        val status = if (stillOnline) LinkStatus.ONLINE else LinkStatus.OFFLINE
        if (status == LinkStatus.OFFLINE && slot.view.status != LinkStatus.OFFLINE) {
            slot.offlineSince = nowMillis()
            log.warn(LogSource.HUB, "${slot.name} is offline: ${issue.describe()}", error.takeIf { issue is DeviceIssue.Failed })
        }
        val message = "${slot.name}: ${issue.describe()} (failure ${slot.failures}, next try in ${retryIn / 1_000} s)"
        if (slot.failures >= OFFLINE_AFTER_FAILURES) log.info(LogSource.HUB, message) else log.trace(LogSource.HUB) { message }
        slot.view = slot.view.copy(status = status, issue = issue)
    }

    /** A poll summary: logged when the picture changes (or every few minutes), otherwise only when verbose. */
    private fun logSummary(elapsedMs: Long) {
        if (slots.isEmpty()) return
        val views = slots.values.map { it.view.status }
        val online = views.count { it == LinkStatus.ONLINE }
        val offline = views.count { it == LinkStatus.OFFLINE }
        val summary = "${slots.size} device(s): $online online, $offline offline"
        val now = nowMillis()
        if (summary != lastSummary || now - lastSummaryAt >= SUMMARY_EVERY_MS) {
            log.info(LogSource.HUB, "Poll: $summary ($elapsedMs ms)")
            lastSummary = summary
            lastSummaryAt = now
        } else {
            log.trace(LogSource.HUB) { "Poll: $summary ($elapsedMs ms)" }
        }
    }

    /** Brings [slots] in line with the saved devices: opens new ones, closes removed ones, reconnects moved ones. */
    private suspend fun sync(devices: List<SavedDevice>) {
        syncMutex.withLock {
            val wanted = devices.associateBy { it.key }
            for (key in slots.keys.toList()) {
                if (key in wanted) continue
                val removed = slots.remove(key) ?: continue
                log.info(LogSource.HUB, "${removed.name}: removed")
                removed.mutex.withLock { removed.closeSession() }
            }
            for ((key, device) in wanted) {
                val slot = slots[key]
                if (slot == null) {
                    slots[key] = Slot(device.ref, device.name, device.capabilities)
                    log.info(LogSource.HUB, "${device.name}: added (${device.ref.connectorId} at ${device.ref.address})")
                } else if (slot.ref != device.ref) {
                    slot.name = device.name
                    log.info(LogSource.HUB, "${device.name}: now at ${device.ref.address}, reconnecting")
                    slot.mutex.withLock {
                        slot.closeSession()
                        slot.ref = device.ref
                        slot.failures = 0
                        slot.nextAttemptAt = 0
                    }
                } else {
                    slot.name = device.name
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
        private const val SUMMARY_EVERY_MS = 10 * 60_000L
    }
}
