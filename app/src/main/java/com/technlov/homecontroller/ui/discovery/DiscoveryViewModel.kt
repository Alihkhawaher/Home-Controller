package com.technlov.homecontroller.ui.discovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.technlov.home.core.Connector
import com.technlov.home.core.ConnectorInfo
import com.technlov.home.core.DeviceKey
import com.technlov.home.core.DeviceRef
import com.technlov.home.core.LocalNetwork
import com.technlov.home.core.key
import com.technlov.homecontroller.AppContainer
import com.technlov.homecontroller.R
import com.technlov.home.connectors.tuya.TuyaConnector
import com.technlov.home.connectors.tuya.TuyaImport
import com.technlov.homecontroller.data.Room
import com.technlov.homecontroller.data.SecretBox
import com.technlov.homecontroller.diag.LogSource
import com.technlov.home.core.info
import com.technlov.home.core.warn
import com.technlov.homecontroller.hub.DeviceIssue
import com.technlov.homecontroller.net.Ipv4
import com.technlov.homecontroller.ui.UiMessage
import com.technlov.homecontroller.ui.UiText
import com.technlov.homecontroller.ui.toUiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class ScanStatus { IDLE, SCANNING, DONE, FAILED }

data class ConnectorScan(
    val info: ConnectorInfo,
    val status: ScanStatus = ScanStatus.IDLE,
    val found: List<DeviceRef> = emptyList(),
    val issue: UiText? = null,
)

/** One device from an import QR, before the user confirms it. [seenOnWifi]: it announced itself just now. */
data class ImportCandidate(val ref: DeviceRef, val seenOnWifi: Boolean, val selected: Boolean = true)

data class DiscoveryUi(
    val scanning: Boolean = false,
    val hasScanned: Boolean = false,
    val network: String? = null,
    val noNetwork: Boolean = false,
    val scans: List<ConnectorScan> = emptyList(),
    val addedKeys: Set<DeviceKey> = emptySet(),
    val rooms: List<Room> = emptyList(),
    val lookingUp: Boolean = false,
    val lookupIssue: UiText? = null,
    /** Looking for the imported devices on the Wi-Fi. */
    val importing: Boolean = false,
    /** Non-null while the user reviews an import. */
    val importCandidates: List<ImportCandidate>? = null,
) {
    val foundCount: Int get() = scans.sumOf { it.found.size }
}

class DiscoveryViewModel(private val container: AppContainer) : ViewModel() {
    private val state = MutableStateFlow(DiscoveryUi(scans = container.registry.all.map { ConnectorScan(it.info) }))

    val ui: StateFlow<DiscoveryUi> = combine(state, container.repository.data) { s, data ->
        s.copy(addedKeys = data.devices.map { it.key }.toSet(), rooms = data.sortedRooms)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), state.value)

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val messages: Flow<UiMessage> = _messages

    /** Runs every connector's discovery on the current Wi-Fi at the same time. */
    fun scan() {
        if (state.value.scanning) return
        viewModelScope.launch {
            val network = withContext(Dispatchers.IO) { container.network.prepare() }
            if (network == null) {
                state.update { it.copy(noNetwork = true, hasScanned = true) }
                return@launch
            }
            val saved = container.repository.current().devices
            container.eventLog.info(
                LogSource.DISCOVERY,
                "Scan of ${network.cidr} with ${container.registry.all.joinToString { it.info.id }} " +
                    "(${saved.size} saved device(s) asked directly)",
            )
            state.update { s ->
                s.copy(
                    scanning = true,
                    hasScanned = true,
                    noNetwork = false,
                    network = network.cidr,
                    scans = s.scans.map { it.copy(status = ScanStatus.SCANNING, issue = null) },
                )
            }
            val started = System.currentTimeMillis()
            try {
                container.network.withMulticastLock {
                    coroutineScope {
                        container.registry.all.forEach { connector ->
                            // Ask the saved devices of this plugin directly too: broadcasts get lost, unicasts rarely.
                            val known = saved.filter { it.ref.connectorId == connector.info.id }
                                .map { it.ref.address }
                                .distinct()
                            launch { runDiscovery(connector, network.local.copy(knownHosts = known), replace = true) }
                        }
                    }
                }
            } finally {
                // Let the radar finish a sweep even when every connector answers at once.
                val elapsed = System.currentTimeMillis() - started
                if (elapsed < MIN_SCAN_MS) delay(MIN_SCAN_MS - elapsed)
                state.update { it.copy(scanning = false) }
            }
        }
    }

    /** "Add by IP address": asks every connector to look at one host, for networks that block broadcast. */
    fun lookUp(address: String) {
        val network = Ipv4.forHost(address)
        if (network == null) {
            state.update { it.copy(lookupIssue = UiText.res(R.string.discovery_ip_invalid)) }
            return
        }
        if (state.value.lookingUp) return
        viewModelScope.launch {
            container.eventLog.info(LogSource.DISCOVERY, "Look up ${network.broadcast}")
            state.update { it.copy(lookingUp = true, lookupIssue = null) }
            withContext(Dispatchers.IO) { container.network.prepare() }
            val before = state.value.foundCount
            try {
                coroutineScope {
                    container.registry.all.map { connector ->
                        async { runDiscovery(connector, network, replace = false, quietFailure = true) }
                    }.awaitAll()
                }
            } finally {
                val nothing = state.value.foundCount == before &&
                    state.value.scans.none { scan -> scan.found.any { it.address == network.broadcast } }
                state.update {
                    it.copy(
                        lookingUp = false,
                        lookupIssue = if (nothing) UiText.res(R.string.discovery_ip_nothing, network.broadcast) else null,
                    )
                }
            }
        }
    }

    private suspend fun runDiscovery(connector: Connector, network: LocalNetwork, replace: Boolean, quietFailure: Boolean = false) {
        val id = connector.info.id
        val started = System.currentTimeMillis()
        try {
            val found = withTimeout(DISCOVERY_TIMEOUT_MS + 5_000) {
                withContext(Dispatchers.IO) { connector.discover(network, DISCOVERY_TIMEOUT_MS) }
            }.distinctBy { it.key() }
            container.eventLog.info(
                LogSource.DISCOVERY,
                "$id: ${found.size} found in ${System.currentTimeMillis() - started} ms" +
                    found.joinToString(prefix = if (found.isEmpty()) "" else ": ") { "${it.name} at ${it.address}" },
            )
            // Devices already saved may have moved to a new address; keep them reachable.
            found.forEach { runCatching { container.repository.refreshDeviceRef(it) } }
            state.update { s ->
                s.copy(scans = s.scans.map { scan ->
                    if (scan.info.id != id) scan
                    else scan.copy(
                        status = ScanStatus.DONE,
                        found = (if (replace) found else (scan.found + found)).distinctBy { it.key() },
                        issue = null,
                    )
                })
            }
        } catch (e: CancellationException) {
            if (e !is TimeoutCancellationException) throw e
            container.eventLog.warn(LogSource.DISCOVERY, "$id: no result in time")
            markFailed(id, DeviceIssue.Timeout.toUiText(), quietFailure)
        } catch (e: Exception) {
            container.eventLog.warn(LogSource.DISCOVERY, "$id: search failed", e)
            markFailed(id, DeviceIssue.from(e).toUiText(), quietFailure)
        }
    }

    private fun markFailed(connectorId: String, issue: UiText, quiet: Boolean) {
        state.update { s ->
            s.copy(scans = s.scans.map { scan ->
                when {
                    scan.info.id != connectorId -> scan
                    quiet -> scan.copy(status = if (scan.status == ScanStatus.SCANNING) ScanStatus.DONE else scan.status)
                    else -> scan.copy(status = ScanStatus.FAILED, issue = issue)
                }
            })
        }
    }

    /** Saves a found device with the user's name and room (a new room is created first when named). */
    fun add(ref: DeviceRef, name: String, roomId: String?, newRoomName: String?) {
        viewModelScope.launch {
            val room = newRoomName?.takeIf { it.isNotBlank() }?.let { container.repository.addRoom(it).id } ?: roomId
            val saved = container.repository.addDevice(ref, name, room)
            _messages.tryEmit(UiMessage(UiText.res(R.string.message_device_added, saved.name)))
        }
    }

    fun clearLookupIssue() = state.update { it.copy(lookupIssue = null) }

    // ---- import from the PC (a QR with each Tuya device's id, key and data-point spec) ----

    /**
     * Reads an import QR, then listens for the devices' own broadcasts so each one gets its current address and
     * protocol version. Devices that are switched off are kept too: the hub finds them when they come online.
     * The keys stay in memory until [confirmImport] seals them; nothing about them is logged.
     */
    fun importScanned(text: String) {
        if (state.value.importing) return
        val refs = try {
            TuyaImport.parse(text)
        } catch (e: Exception) {
            _messages.tryEmit(UiMessage(UiText.res(R.string.import_invalid)))
            return
        }
        if (refs.isEmpty()) {
            _messages.tryEmit(UiMessage(UiText.res(R.string.import_invalid)))
            return
        }
        viewModelScope.launch {
            state.update { it.copy(importing = true) }
            val seen: Map<String, DeviceRef> = try {
                withContext(Dispatchers.IO) { container.network.prepare() }
                val tuya = container.registry[TuyaConnector.ID]
                if (tuya == null) emptyMap() else container.network.withMulticastLock {
                    withContext(Dispatchers.IO) { tuya.discover(LocalNetwork("255.255.255.255", ""), IMPORT_LISTEN_MS) }
                }.associateBy { it.id }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                container.eventLog.warn(LogSource.DISCOVERY, "import: could not listen for the devices", e)
                emptyMap()
            }
            val candidates = refs.map { r ->
                val hit = seen[r.id]
                val version = hit?.extras?.get(TuyaConnector.EXTRA_VERSION)
                val ref = if (hit == null) r else r.copy(
                    address = hit.address,
                    extras = if (version != null) r.extras + (TuyaConnector.EXTRA_VERSION to version) else r.extras,
                )
                ImportCandidate(ref, seenOnWifi = hit != null)
            }
            container.eventLog.info(
                LogSource.DISCOVERY,
                "import: ${refs.size} device(s) in the QR, ${candidates.count { it.seenOnWifi }} on this Wi-Fi now",
            )
            state.update { it.copy(importing = false, importCandidates = candidates) }
        }
    }

    fun toggleImport(id: String) = state.update { s ->
        s.copy(importCandidates = s.importCandidates?.map { if (it.ref.id == id) it.copy(selected = !it.selected) else it })
    }

    fun cancelImport() = state.update { it.copy(importCandidates = null) }

    /** Saves the chosen devices with their keys SEALED (Android Keystore); the plaintext is never written. */
    fun confirmImport() {
        val chosen = state.value.importCandidates.orEmpty().filter { it.selected }
        state.update { it.copy(importCandidates = null) }
        if (chosen.isEmpty()) return
        viewModelScope.launch {
            var added = 0
            for (c in chosen) {
                try {
                    container.repository.addDevice(sealSecrets(c.ref), c.ref.name, null)
                    added++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    container.eventLog.warn(LogSource.DISCOVERY, "import: could not save ${c.ref.name}", e)
                }
            }
            container.eventLog.info(LogSource.DISCOVERY, "import: $added device(s) added")
            _messages.tryEmit(UiMessage(UiText.plural(R.plurals.message_devices_imported, added, added)))
        }
    }

    private fun sealSecrets(ref: DeviceRef): DeviceRef {
        val key = ref.extras[TuyaConnector.EXTRA_LOCAL_KEY] ?: return ref
        return ref.copy(
            extras = ref.extras - TuyaConnector.EXTRA_LOCAL_KEY +
                (TuyaConnector.EXTRA_LOCAL_KEY + SecretBox.SEALED_SUFFIX to SecretBox.seal(key)),
        )
    }

    private companion object {
        const val DISCOVERY_TIMEOUT_MS = 4_000L
        const val MIN_SCAN_MS = 2_400L
        /** Tuya devices announce themselves about every 5 s. */
        const val IMPORT_LISTEN_MS = 7_000L
    }
}
