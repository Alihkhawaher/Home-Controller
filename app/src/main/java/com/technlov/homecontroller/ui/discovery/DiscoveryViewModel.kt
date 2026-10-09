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
import com.technlov.homecontroller.data.Room
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
                            launch { runDiscovery(connector, network.local, replace = true) }
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
        try {
            val found = withTimeout(DISCOVERY_TIMEOUT_MS + 5_000) {
                withContext(Dispatchers.IO) { connector.discover(network, DISCOVERY_TIMEOUT_MS) }
            }.distinctBy { it.key() }
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
            if (e is TimeoutCancellationException) markFailed(id, DeviceIssue.Timeout.toUiText(), quietFailure) else throw e
        } catch (e: Exception) {
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

    private companion object {
        const val DISCOVERY_TIMEOUT_MS = 4_000L
        const val MIN_SCAN_MS = 2_400L
    }
}
