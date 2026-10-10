package com.technlov.homecontroller.ui.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.technlov.home.core.Connector
import com.technlov.home.core.ConnectorInfo
import com.technlov.home.core.DeviceKey
import com.technlov.home.core.DeviceRef
import com.technlov.home.core.Provisioning
import com.technlov.home.core.info
import com.technlov.home.core.provisioningSupport
import com.technlov.homecontroller.AppContainer
import com.technlov.homecontroller.data.Room
import com.technlov.homecontroller.diag.LogSource
import com.technlov.homecontroller.net.WifiSpot
import com.technlov.homecontroller.net.WifiSpots
import com.technlov.homecontroller.pairing.PairingProgress
import com.technlov.homecontroller.pairing.PairingRunner
import com.technlov.homecontroller.pairing.WifiSecret
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PairingStep { CHOOSE, STEPS, HOTSPOT, HOME_WIFI, PROGRESS }

data class PairingUi(
    val step: PairingStep = PairingStep.CHOOSE,
    val connectors: List<ConnectorInfo> = emptyList(),
    val connectorId: String? = null,
    val title: String? = null,
    val setupSteps: List<String> = emptyList(),
    val needs24GHz: Boolean = true,
    val setupHotspotPassword: String? = null,
    val canScan: Boolean = false,
    val locationOn: Boolean = true,
    val scanning: Boolean = false,
    val throttled: Boolean = false,
    val hotspots: List<WifiSpot> = emptyList(),
    val setupSsid: String? = null,
    val homeSsid: String = "",
    val fiveGhzOnly: Boolean = false,
    val progress: PairingProgress = PairingProgress.Idle,
    val rooms: List<Room> = emptyList(),
    val addedKey: DeviceKey? = null,
) {
    val stepNumber: Int get() = step.ordinal
}

/**
 * Setting up a device that is not on the Wi-Fi yet. Screens: pick the plugin -> put the device in setup mode -> pick
 * its hotspot -> home Wi-Fi name and password -> progress -> found / added. The password is handed straight to the
 * [PairingRunner] in a [WifiSecret]; this view model never keeps it.
 */
class PairingViewModel(private val container: AppContainer) : ViewModel() {
    private val scanner = container.wifiScanner
    private val log = container.eventLog
    private val runner = PairingRunner(container.pairingPlatform, log)
    private val pairable: List<Connector> = container.registry.all.filter { it.provisioningSupport != null }
    private var allSpots: List<WifiSpot> = emptyList()
    private var job: Job? = null

    private val state = MutableStateFlow(PairingUi(connectors = pairable.map { it.info }))

    val ui: StateFlow<PairingUi> = combine(state, runner.progress, container.repository.data) { s, progress, data ->
        s.copy(progress = progress, rooms = data.sortedRooms)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), state.value)

    private val connector: Connector? get() = pairable.firstOrNull { it.info.id == state.value.connectorId }
    private val provisioning: Provisioning? get() = connector?.provisioningSupport

    init {
        pairable.singleOrNull()?.let { choose(it.info.id) }
        viewModelScope.launch { scanner.scanUpdates().collect { readResults(scanDone = true) } }
    }

    fun choose(connectorId: String) {
        val p = pairable.firstOrNull { it.info.id == connectorId }?.provisioningSupport ?: return
        log.info(LogSource.PAIRING, "Pairing: chose ${p.title}")
        state.update {
            it.copy(
                step = PairingStep.STEPS,
                connectorId = connectorId,
                title = p.title,
                setupSteps = p.setupSteps,
                needs24GHz = p.needs24GHz,
                setupHotspotPassword = p.setupHotspotPassword,
            )
        }
    }

    fun toHotspots() {
        state.update { it.copy(step = PairingStep.HOTSPOT) }
        scan()
    }

    /** Re-reads permission and Location (after the permission prompt or the system settings). */
    fun refreshEnvironment() {
        state.update { it.copy(canScan = scanner.hasPermission(), locationOn = scanner.locationEnabled()) }
        if (state.value.step == PairingStep.HOTSPOT && state.value.canScan) readResults(scanDone = false)
    }

    fun scan() {
        refreshEnvironment()
        if (!state.value.canScan) return
        val started = scanner.startScan()
        log.info(LogSource.PAIRING, if (started) "Scanning for setup hotspots" else "Wi-Fi scan throttled by Android; showing the last results")
        state.update { it.copy(scanning = started, throttled = !started) }
        readResults(scanDone = !started)
        if (started) {
            viewModelScope.launch {
                delay(SCAN_TIMEOUT_MS)
                state.update { it.copy(scanning = false) }
            }
        }
    }

    private fun readResults(scanDone: Boolean) {
        val p = provisioning ?: return
        allSpots = scanner.results()
        val hotspots = WifiSpots.setupHotspots(allSpots, p::isSetupHotspot)
        state.update { it.copy(hotspots = hotspots, scanning = if (scanDone) false else it.scanning) }
    }

    fun pickHotspot(ssid: String) {
        val clean = WifiSpots.cleanSsid(ssid) ?: return
        val current = scanner.current()
        val p = provisioning
        // Prefill the home network's name, unless the phone is on a setup hotspot right now.
        val home = current?.ssid?.takeUnless { p?.isSetupHotspot(it) == true }
        log.info(LogSource.PAIRING, "Picked hotspot \"$clean\"")
        state.update {
            it.copy(
                step = PairingStep.HOME_WIFI,
                setupSsid = clean,
                homeSsid = it.homeSsid.ifEmpty { home.orEmpty() },
                fiveGhzOnly = (p?.needs24GHz ?: true) && WifiSpots.looksFiveGhzOnly(current, allSpots),
            )
        }
    }

    /** What to ask for before scanning (location, and "Nearby devices" on Android 13+). */
    val scanPermissions: Array<String> get() = scanner.permissions

    fun setHomeSsid(ssid: String) = state.update { it.copy(homeSsid = ssid) }

    fun looksLikeSetupHotspot(ssid: String): Boolean = provisioning?.isSetupHotspot(ssid.trim()) == true

    /** Starts the hand-over. [password] goes into a one-time [WifiSecret]; nothing here keeps it. */
    fun start(password: String) {
        val c = connector ?: return
        val p = provisioning ?: return
        val setup = state.value.setupSsid ?: return
        val home = state.value.homeSsid.trim()
        if (home.isEmpty()) return
        val secret = WifiSecret(password)
        state.update { it.copy(step = PairingStep.PROGRESS) }
        job?.cancel()
        job = viewModelScope.launch { runner.run(c, p, setup, home, secret) }
    }

    /** The device did not confirm (some never do) or did not show up yet: keep searching. */
    fun waitAnyway() {
        val c = connector ?: return
        val p = provisioning ?: return
        val setup = state.value.setupSsid ?: return
        val message = (runner.progress.value as? PairingProgress.Failed)?.detail.orEmpty()
        job?.cancel()
        job = viewModelScope.launch { runner.waitForDevice(c, p, setup, message) }
    }

    fun cancel() {
        job?.cancel()
        job = null
        runner.markCancelled()
    }

    fun tryAgain() {
        job?.cancel()
        runner.reset()
        state.update { it.copy(step = PairingStep.HOME_WIFI, addedKey = null) }
    }

    /** One step back; false when there is nothing to go back to (leave the screen). */
    fun back(): Boolean {
        val s = state.value
        return when (s.step) {
            PairingStep.CHOOSE -> false
            PairingStep.STEPS -> if (pairable.size > 1) {
                state.update { it.copy(step = PairingStep.CHOOSE) }
                true
            } else {
                false
            }
            PairingStep.HOTSPOT -> {
                state.update { it.copy(step = PairingStep.STEPS) }
                true
            }
            PairingStep.HOME_WIFI -> {
                state.update { it.copy(step = PairingStep.HOTSPOT) }
                true
            }
            PairingStep.PROGRESS -> {
                if (runner.progress.value.isRunning) cancel()
                if (s.addedKey != null) return false
                runner.reset()
                state.update { it.copy(step = PairingStep.HOME_WIFI) }
                true
            }
        }
    }

    fun add(ref: DeviceRef, name: String, roomId: String?, newRoom: String?) {
        viewModelScope.launch {
            val room = newRoom?.takeIf { it.isNotBlank() }?.let { container.repository.addRoom(it).id } ?: roomId
            val saved = container.repository.addDevice(ref, name, room)
            log.info(LogSource.PAIRING, "Added ${saved.name}")
            state.update { it.copy(addedKey = saved.key) }
        }
    }

    override fun onCleared() {
        job?.cancel()
    }

    private companion object {
        const val SCAN_TIMEOUT_MS = 12_000L
    }
}
