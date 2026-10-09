package com.technlov.homecontroller.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.technlov.home.core.ConnectorInfo
import com.technlov.homecontroller.AppContainer
import com.technlov.homecontroller.data.AppSettings
import com.technlov.homecontroller.data.Room
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RoomRow(val room: Room, val deviceCount: Int)

data class SettingsUi(
    val settings: AppSettings = AppSettings(),
    val rooms: List<RoomRow> = emptyList(),
    val deviceCount: Int = 0,
)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val ui: StateFlow<SettingsUi> = container.repository.data.map { data ->
        SettingsUi(
            settings = data.settings,
            rooms = data.sortedRooms.map { room -> RoomRow(room, data.devices.count { it.roomId == room.id }) },
            deviceCount = data.devices.size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUi(settings = container.settings.value))

    val connectors: List<ConnectorInfo> = container.registry.all.map { it.info }

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { container.repository.updateSettings(transform) }
    }

    fun addRoom(name: String) = viewModelScope.launch { container.repository.addRoom(name) }
    fun renameRoom(id: String, name: String) = viewModelScope.launch { container.repository.renameRoom(id, name) }
    fun deleteRoom(id: String) = viewModelScope.launch { container.repository.deleteRoom(id) }
    fun moveRoom(id: String, delta: Int) = viewModelScope.launch { container.repository.moveRoom(id, delta) }
}
