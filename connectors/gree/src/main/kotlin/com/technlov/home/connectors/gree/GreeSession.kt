package com.technlov.home.connectors.gree

import com.technlov.home.core.Capability
import com.technlov.home.core.ConnectorException
import com.technlov.home.core.DeviceRef
import com.technlov.home.core.DeviceSession
import com.technlov.home.core.DeviceState
import com.technlov.home.core.EventLog
import com.technlov.home.core.SetValue
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
 */
class GreeSession internal constructor(
    device: DeviceRef,
    private val client: GreeClient,
    private val config: GreeConfig,
    private val log: EventLog = EventLog.None,
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

    /** The [GreeModel.EXTRA_COLUMNS] values; asked only when the unit answered some of them at open. */
    private var extras: Map<String, Any?> = emptyMap()
    private var extrasSupported = false
    private var extrasDue = false

    private val who get() = "${device.name} (${device.address})"

    /** First read: learns which columns the unit answers and declares only those capabilities. */
    internal suspend fun prepare() = lock.withLock {
        raw = statusWithRebind(GreeModel.STATUS_COLUMNS)
        readDiagnostics()
        readExtras()
        extrasSupported = extras.isNotEmpty()
        val answered = raw.keys + diag.keys
        // A unit that answered no diagnostics (or could not be asked) keeps them declared: the next read may get them.
        val withDiag = if (diag.isEmpty()) answered + GreeModel.DIAGNOSTIC_COLUMNS else answered
        // The extras, by contrast, only when answered (a module without ChildLock just leaves it out).
        capabilities = GreeModel.capabilities(withDiag.takeIf { raw.isNotEmpty() }, extras.keys)
    }

    override suspend fun read(): DeviceState = lock.withLock { readLocked() }

    override suspend fun apply(changes: List<SetValue>): DeviceState = lock.withLock {
        val columns = try {
            GreeModel.encode(changes)
        } catch (e: IllegalArgumentException) {
            log.warn(GreeClient.SOURCE, "$who: change refused before sending: ${e.message}")
            throw ConnectorException("${device.name}: ${e.message}", e)
        }
        val declared = capabilities.mapTo(HashSet()) { it.key }
        changes.firstOrNull { it.key !in declared }?.let {
            log.warn(GreeClient.SOURCE, "$who: change refused before sending: this unit has no '${it.key}'")
            throw ConnectorException("${device.name}: this unit has no '${it.key}'")
        }
        if (columns.isEmpty()) return@withLock readLocked()
        val sent = columns.entries.joinToString { "${it.key}=${it.value}" }
        log.info(GreeClient.SOURCE, "$who: command sent: $sent")
        val confirmed = try {
            client.command(columns)
        } catch (e: GreeProtocolException) {
            log.warn(GreeClient.SOURCE, "$who: command refused: ${e.message}")
            throw ConnectorException("${device.name}: ${e.message}", e)
        } catch (e: ConnectorException) {
            log.warn(GreeClient.SOURCE, "$who: command not confirmed: ${e.message}")
            throw e
        }
        log.info(GreeClient.SOURCE, "$who: command confirmed: ${confirmed.entries.joinToString { "${it.key}=${it.value}" }}")
        raw = raw + confirmed.filterKeys { it !in GreeModel.EXTRA_COLUMNS }
        if (columns.keys.any { it in GreeModel.EXTRA_COLUMNS }) {
            extras = extras + confirmed.filterKeys { it in GreeModel.EXTRA_COLUMNS }
            extrasDue = true // read them back now, not in 5 minutes
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
                if (extrasSupported) extrasDue = true
            }
            if (extrasDue) readExtras()
            if (!online) log.info(GreeClient.SOURCE, "$who: answering again")
            online = true
            DeviceState(GreeModel.decode(raw + diag + extras), online = true, updatedAtMillis = now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (online) log.warn(GreeClient.SOURCE, "$who: went silent: ${e.message}")
            online = false
            DeviceState(GreeModel.decode(raw + diag + extras), online = false, updatedAtMillis = config.clock(), error = e.message)
        }
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

    /** The extras, in their own small request (never merged into the status request). Best effort, like diagnostics. */
    private suspend fun readExtras() {
        extrasDue = false
        try {
            extras = client.status(GreeModel.EXTRA_COLUMNS, attempts = config.diagnosticsTries)
                .filterKeys { it in GreeModel.EXTRA_COLUMNS }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(GreeClient.SOURCE, "$who: extra columns not answered: ${e.message}")
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

    override fun toString(): String = "GreeSession(${device.id})"
}
