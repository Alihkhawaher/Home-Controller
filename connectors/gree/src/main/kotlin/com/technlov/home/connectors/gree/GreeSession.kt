package com.technlov.home.connectors.gree

import com.technlov.home.core.Capability
import com.technlov.home.core.ConnectorException
import com.technlov.home.core.DeviceRef
import com.technlov.home.core.DeviceSession
import com.technlov.home.core.DeviceState
import com.technlov.home.core.SetValue
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
) : DeviceSession {
    override var device: DeviceRef = device
        private set

    override var capabilities: List<Capability> = GreeModel.capabilities()
        private set

    private val lock = Mutex()
    private var raw: Map<String, Any?> = emptyMap()
    private var diag: Map<String, Any?> = emptyMap()
    private var diagAt: Long? = null

    /** First read: learns which columns the unit answers and declares only those capabilities. */
    internal suspend fun prepare() = lock.withLock {
        raw = statusWithRebind(GreeModel.STATUS_COLUMNS)
        readDiagnostics()
        val answered = raw.keys + diag.keys
        // A unit that answered no diagnostics (or could not be asked) keeps them declared: the next read may get them.
        val withDiag = if (diag.isEmpty()) answered + GreeModel.DIAGNOSTIC_COLUMNS else answered
        capabilities = GreeModel.capabilities(withDiag.takeIf { raw.isNotEmpty() })
    }

    override suspend fun read(): DeviceState = lock.withLock { readLocked() }

    override suspend fun apply(changes: List<SetValue>): DeviceState = lock.withLock {
        val columns = try {
            GreeModel.encode(changes)
        } catch (e: IllegalArgumentException) {
            throw ConnectorException("${device.name}: ${e.message}", e)
        }
        if (columns.isEmpty()) return@withLock readLocked()
        val confirmed = try {
            client.command(columns)
        } catch (e: GreeProtocolException) {
            throw ConnectorException("${device.name}: ${e.message}", e)
        }
        raw = raw + confirmed
        readLocked()
    }

    private suspend fun readLocked(): DeviceState {
        return try {
            raw = statusWithRebind(GreeModel.STATUS_COLUMNS)
            val now = config.clock()
            val last = diagAt
            if (last == null || now - last >= config.diagnosticsEveryMs) readDiagnostics()
            DeviceState(GreeModel.decode(raw + diag), online = true, updatedAtMillis = now)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            DeviceState(GreeModel.decode(raw + diag), online = false, updatedAtMillis = config.clock(), error = e.message)
        }
    }

    /** Diagnostics are best effort: a unit that does not answer them still reads fine. */
    private suspend fun readDiagnostics() {
        diagAt = config.clock()
        try {
            diag = client.status(GreeModel.DIAGNOSTIC_COLUMNS, attempts = config.diagnosticsTries)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
    }

    /** A pack that no longer decrypts usually means the unit was re-paired (new key): bind again once. */
    private suspend fun statusWithRebind(columns: List<String>): Map<String, Any?> = try {
        client.status(columns)
    } catch (e: GreeProtocolException) {
        try {
            client.bind()
            client.status(columns)
        } catch (e2: GreeProtocolException) {
            throw ConnectorException("${device.name}: ${e2.message}", e2)
        }
    }

    override fun toString(): String = "GreeSession(${device.id})"
}
