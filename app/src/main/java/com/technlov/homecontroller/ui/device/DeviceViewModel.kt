package com.technlov.homecontroller.ui.device

import java.time.ZoneId
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import com.technlov.homecontroller.history.PowerHistoryUi
import com.technlov.homecontroller.history.HistoryRange
import com.technlov.homecontroller.history.HistoryQuery
import com.technlov.homecontroller.history.HistoryMath
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.technlov.homecontroller.data.PowerButton
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.technlov.home.core.Capability
import com.technlov.home.core.DeviceKey
import com.technlov.home.core.DeviceState
import com.technlov.home.core.SetValue
import com.technlov.homecontroller.AppContainer
import com.technlov.homecontroller.R
import com.technlov.homecontroller.data.Room
import com.technlov.homecontroller.data.SavedDevice
import com.technlov.homecontroller.hub.DeviceIssue
import com.technlov.homecontroller.hub.LinkStatus
import com.technlov.homecontroller.ui.UiMessage
import com.technlov.homecontroller.ui.UiText
import com.technlov.homecontroller.ui.toUiText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DeviceDetailUi(
    val loaded: Boolean = false,
    val device: SavedDevice? = null,
    val roomName: String? = null,
    val rooms: List<Room> = emptyList(),
    val status: LinkStatus = LinkStatus.CONNECTING,
    val state: DeviceState? = null,
    val capabilities: List<Capability> = emptyList(),
    val issue: DeviceIssue? = null,
    val lastSeenMillis: Long? = null,
    val busy: Boolean = false,
    val connectorName: String? = null,
)

class DeviceViewModel(private val container: AppContainer, private val key: DeviceKey) : ViewModel() {

    val ui: StateFlow<DeviceDetailUi> = combine(container.repository.data, container.hub.live) { data, live ->
        val device = data.device(key)
        val link = live[key]
        DeviceDetailUi(
            loaded = true,
            device = device,
            roomName = data.room(device?.roomId)?.name,
            rooms = data.sortedRooms,
            status = link?.status ?: LinkStatus.CONNECTING,
            state = link?.state,
            capabilities = link?.capabilities?.takeIf { it.isNotEmpty() } ?: device?.capabilities.orEmpty(),
            issue = link?.issue,
            lastSeenMillis = link?.lastSeenMillis,
            busy = link?.busy == true,
            connectorName = device?.let { container.registry[it.ref.connectorId]?.info?.name },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeviceDetailUi())

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val messages: Flow<UiMessage> = _messages

    private val _retrying = MutableStateFlow(false)
    val retrying: StateFlow<Boolean> = _retrying.asStateFlow()

    init {
        // Fresh values when the screen opens, without waiting for the next poll.
        viewModelScope.launch { container.hub.refresh(listOf(key)) }
    }

    /** Sends [changes]; the hub shows them at once and snaps back with a message if the device refuses. */
    // ---- power history ----
    private val historyRange = MutableStateFlow(HistoryRange.DAY_1)
    private val _history = MutableStateFlow<PowerHistoryUi?>(null)
    /** Null for a device with no power reading (no card at all). Reloaded every minute and on a range change. */
    val history: StateFlow<PowerHistoryUi?> = _history.asStateFlow()

    init {
        viewModelScope.launch {
            combine(historyRange, ui.map { it.capabilities }.distinctUntilChanged()) { r, caps -> r to caps }
                .collectLatest { (range, caps) ->
                    val plan = HistoryMath.plan(caps)
                    if (plan.powerKey == null) {
                        _history.value = null
                        return@collectLatest
                    }
                    while (true) {
                        _history.value = withContext(Dispatchers.IO) {
                            runCatching {
                                HistoryQuery.build(
                                    range, System.currentTimeMillis(), ZoneId.systemDefault(), plan,
                                    minutes = { m, f, t -> container.historyStore.minutes(key, m, f, t) },
                                    hours = { m, f, t -> container.historyStore.hours(key, m, f, t) },
                                )
                            }.getOrNull()
                        }
                        delay(HISTORY_REFRESH_MS)
                    }
                }
        }
    }

    fun setHistoryRange(range: HistoryRange) {
        historyRange.value = range
    }

    fun apply(changes: List<SetValue>, what: String) {
        viewModelScope.launch {
            container.hub.apply(key, changes).onFailure { error ->
                _messages.tryEmit(
                    UiMessage(UiText.res(R.string.message_change_failed, what, DeviceIssue.from(error).toUiText())) {
                        apply(changes, what)
                    },
                )
            }
        }
    }

    fun retry() {
        if (_retrying.value) return
        viewModelScope.launch {
            _retrying.value = true
            try {
                container.hub.refresh(listOf(key))
            } finally {
                _retrying.value = false
            }
        }
    }

    fun rename(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { container.repository.updateDevice(key) { it.copy(name = name.trim()) } }
    }

    fun setRoom(roomId: String?) {
        viewModelScope.launch { container.repository.updateDevice(key) { it.copy(roomId = roomId) } }
    }

    fun createRoomAndMove(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val room = container.repository.addRoom(name)
            container.repository.updateDevice(key) { it.copy(roomId = room.id) }
        }
    }

    fun setPowerButton(mode: PowerButton) {
        viewModelScope.launch { container.repository.updateDevice(key) { it.copy(powerButton = mode) } }
    }

    fun setHidden(hidden: Boolean) {
        viewModelScope.launch {
            container.repository.updateDevice(key) { it.copy(hidden = hidden) }
            _messages.tryEmit(UiMessage(UiText.res(if (hidden) R.string.message_device_hidden else R.string.message_device_shown)))
        }
    }

    fun remove(onRemoved: () -> Unit) {
        viewModelScope.launch {
            container.repository.removeDevice(key)
            runCatching { withContext(Dispatchers.IO) { container.historyStore.forget(key) } }
            onRemoved()
        }
    }

    private companion object {
        const val HISTORY_REFRESH_MS = 60_000L
    }
}
