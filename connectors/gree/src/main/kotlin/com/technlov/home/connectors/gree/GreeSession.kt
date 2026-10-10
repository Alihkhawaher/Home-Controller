package com.technlov.home.connectors.gree

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
 * An open, bound connection to one Gree unit. Calls are serialised (one request in flight per unit).
 *
 * [read] never throws for a unit that stops answering: it returns the last known values with `online = false` and
 * the reason in `error`. [apply] throws [ConnectorException] when a change is refused before sending (unknown key,
 * value out of range) or the unit does not confirm it.
 *
 * "Beep on commands" ([GreeKeys.BEEP]) is the app's own setting, not a unit column. It is read from [device]'s extras
 * ([GreeConnector.EXTRA_BEEP]) at open; a change updates [device], so the app can save it with the device, and is
 * passed to [onBeepChanged].
 */
class GreeSession internal constructor(
    device: DeviceRef,
    private val client: GreeClient,
    private val config: GreeConfig,
    private val log: EventLog = EventLog.None,
    private val onBeepChanged: (Boolean) -> Unit = {},
) : DeviceSession {
    override var device: DeviceRef = device
        private set

    override var capabilities: List<Capability> = GreeModel.capabilities()
        private set

    private val lock = Mutex()
    private var raw: Map<String, Any?> = emptyMap()
    private var diag: Map<String, Any?> = emptyMap()
    private var diagAt: Long? = null
    private var online = true
    private var beep = GreeConnector.beepFrom(device.extras)

    /** The optional columns, each group in its own small request ([GreeModel.EXTRA_COLUMNS], [GreeModel.PROBE_COLUMNS]). */
    private val extras = ColumnGroup(GreeModel.EXTRA_COLUMNS, "extra columns")
    private val probed = ColumnGroup(GreeModel.PROBE_COLUMNS, "newer optional columns")
    private val groups = listOf(extras, probed)

    private val who get() = "${device.name} (${device.address})"

    /** First read: learns which columns the unit answers and declares only those capabilities. */
    internal suspend fun prepare() = lock.withLock {
        raw = statusWithRebind(GreeModel.STATUS_COLUMNS)
        readDiagnostics()
        // The probe only after a reply to the extras: a module that went silent to names it did not know would be
        // silent again, and every silent request makes the open wait.
        if (extras.read(config.diagnosticsTries)) probed.read(config.probeTries)
        for (g in groups) g.polled = g.values.isNotEmpty()
        val answered = raw.keys + diag.keys
        // A unit that answered no diagnostics (or could not be asked) keeps them declared: the next read may get them.
        val withDiag = if (diag.isEmpty()) answered + GreeModel.DIAGNOSTIC_COLUMNS else answered
        // The optional columns, by contrast, only when answered (a module without ChildLock just leaves it out).
        val optional = groups.flatMapTo(HashSet()) { it.values.keys }
        capabilities = GreeModel.capabilities(withDiag.takeIf { raw.isNotEmpty() }, optional)
    }

    override suspend fun read(): DeviceState = lock.withLock { readLocked() }

    override suspend fun apply(changes: List<SetValue>): DeviceState = lock.withLock {
        val (columns, beepWanted) = try {
            GreeModel.encode(changes) to GreeModel.beepSetting(changes)
        } catch (e: IllegalArgumentException) {
            log.warn(GreeClient.SOURCE, "$who: change refused before sending: ${e.message}")
            throw ConnectorException("${device.name}: ${e.message}", e)
        }
        val declared = capabilities.mapTo(HashSet()) { it.key }
        changes.firstOrNull { it.key !in declared }?.let {
            log.warn(GreeClient.SOURCE, "$who: change refused before sending: this unit has no '${it.key}'")
            throw ConnectorException("${device.name}: this unit has no '${it.key}'")
        }
        val beepAfter = beepWanted ?: beep
        if (columns.isEmpty()) {
            setBeep(beepAfter) // nothing goes to the unit for the app's own setting
            return@withLock readLocked()
        }
        // A new beep choice in the same batch already applies to this command, and is kept once the unit takes it.
        val sending = LinkedHashMap(columns).apply { putAll(GreeModel.buzzerColumns(beepAfter)) }
        val sent = sending.entries.joinToString { "${it.key}=${it.value}" }
        log.info(GreeClient.SOURCE, "$who: command sent: $sent")
        val confirmed = try {
            client.command(sending)
        } catch (e: GreeProtocolException) {
            log.warn(GreeClient.SOURCE, "$who: command refused: ${e.message}")
            throw ConnectorException("${device.name}: ${e.message}", e)
        } catch (e: ConnectorException) {
            log.warn(GreeClient.SOURCE, "$who: command not confirmed: ${e.message}")
            throw e
        }
        log.info(GreeClient.SOURCE, "$who: command confirmed: ${confirmed.entries.joinToString { "${it.key}=${it.value}" }}")
        setBeep(beepAfter)
        raw = raw + confirmed.filterKeys { it !in GreeModel.OPTIONAL_COLUMNS && it !in GreeModel.BUZZER_COLUMNS }
        for (g in groups) {
            if (columns.keys.none { it in g.columns }) continue
            g.values = g.values + confirmed.filterKeys { it in g.columns }
            g.due = true // read them back now, not in 5 minutes
        }
        readLocked()
    }

    private suspend fun readLocked(): DeviceState {
        return try {
            raw = statusWithRebind(GreeModel.STATUS_COLUMNS)
            val now = config.clock()
            val last = diagAt
            if (last == null || now - last >= config.diagnosticsEveryMs) {
                readDiagnostics()
                for (g in groups) if (g.polled) g.due = true
            }
            for (g in groups) if (g.due) g.read(config.diagnosticsTries)
            if (!online) log.info(GreeClient.SOURCE, "$who: answering again")
            online = true
            DeviceState(values(), online = true, updatedAtMillis = now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (online) log.warn(GreeClient.SOURCE, "$who: went silent: ${e.message}")
            online = false
            DeviceState(values(), online = false, updatedAtMillis = config.clock(), error = e.message)
        }
    }

    /** The unit's columns as capability values, and the app's own beep setting. */
    private fun values(): Map<String, Value> =
        GreeModel.decode(raw + diag + extras.values + probed.values) + (GreeKeys.BEEP to Value.Bool(beep))

    /** Keeps "Beep on commands" in [device]'s extras, where the app saves it with the device. */
    private fun setBeep(on: Boolean) {
        if (on == beep) return
        beep = on
        device = device.copy(extras = device.extras + (GreeConnector.EXTRA_BEEP to GreeConnector.beepExtra(on)))
        onBeepChanged(on)
        log.info(GreeClient.SOURCE, "$who: beep on commands ${if (on) "on" else "off"}")
    }

    /** Diagnostics are best effort: a unit that does not answer them still reads fine. */
    private suspend fun readDiagnostics() {
        diagAt = config.clock()
        try {
            diag = client.status(GreeModel.DIAGNOSTIC_COLUMNS, attempts = config.diagnosticsTries)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(GreeClient.SOURCE, "$who: diagnostics not answered: ${e.message}")
        }
    }

    /** A pack that no longer decrypts usually means the unit was re-paired (new key): bind again once. */
    private suspend fun statusWithRebind(columns: List<String>): Map<String, Any?> = try {
        client.status(columns)
    } catch (e: GreeProtocolException) {
        log.warn(GreeClient.SOURCE, "$who: ${e.message}; binding again")
        try {
            client.bind()
            client.status(columns)
        } catch (e2: GreeProtocolException) {
            throw ConnectorException("${device.name}: ${e2.message}", e2)
        }
    }

    /**
     * Optional columns asked in their own small request, never merged into the status request. Declared only when the
     * unit answers them at open; then asked again with the diagnostics ([polled]) and right after a change ([due]).
     */
    private inner class ColumnGroup(val columns: List<String>, private val what: String) {
        var values: Map<String, Any?> = emptyMap()
        var polled = false
        var due = false

        /** Best effort, like the diagnostics: false when the unit did not reply at all (the last values stay). */
        suspend fun read(attempts: Int): Boolean {
            due = false
            return try {
                values = client.status(columns, attempts).filterKeys { it in columns }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(GreeClient.SOURCE, "$who: $what not answered: ${e.message}")
                false
            }
        }
    }

    override fun toString(): String = "GreeSession(${device.id})"
}
