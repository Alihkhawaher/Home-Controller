package com.technlov.homecontroller.data

import androidx.datastore.core.DataStore
import com.technlov.home.core.Automation
import com.technlov.home.core.Capability
import com.technlov.home.core.DeviceKey
import com.technlov.home.core.DeviceRef
import com.technlov.home.core.key
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * The user's devices, rooms, automations and settings, saved as one JSON document. Every change is one atomic
 * [DataStore.updateData], so the UI and the hub service always see a consistent picture.
 */
class HomeRepository(private val store: DataStore<HomeData>) {

    val data: Flow<HomeData> = store.data

    suspend fun current(): HomeData = store.data.first()

    private suspend fun edit(transform: (HomeData) -> HomeData): HomeData = store.updateData { transform(it) }

    // ---- devices ----

    /** Adds [ref] (or, if it is already saved, updates it and makes it visible again). */
    suspend fun addDevice(ref: DeviceRef, name: String, roomId: String?): SavedDevice {
        val key = ref.key()
        val after = edit { d ->
            val existing = d.device(key)
            val displayName = name.trim().ifEmpty { ref.name }
            if (existing != null) {
                d.copy(devices = d.devices.map {
                    if (it.key == key) it.copy(ref = ref, name = displayName, roomId = roomId, hidden = false) else it
                })
            } else {
                val order = (d.devices.maxOfOrNull { it.order } ?: -1) + 1
                d.copy(devices = d.devices + SavedDevice(ref = ref, name = displayName, roomId = roomId, order = order))
            }
        }
        return after.device(key)!!
    }

    suspend fun updateDevice(key: DeviceKey, transform: (SavedDevice) -> SavedDevice) {
        edit { d -> d.copy(devices = d.devices.map { if (it.key == key) transform(it) else it }) }
    }

    suspend fun removeDevice(key: DeviceKey) {
        edit { d -> d.copy(devices = d.devices.filterNot { it.key == key }) }
    }

    /**
     * A connector found a device that is already saved: keep the user's name and room, but take the new address,
     * model, firmware and extras (DHCP may have moved it). Returns true when something changed.
     */
    suspend fun refreshDeviceRef(found: DeviceRef): Boolean {
        val key = found.key()
        var changed = false
        edit { d ->
            val existing = d.device(key) ?: return@edit d
            val merged = existing.ref.copy(
                address = found.address,
                model = found.model ?: existing.ref.model,
                firmware = found.firmware ?: existing.ref.firmware,
                extras = existing.ref.extras + found.extras,
            )
            if (merged == existing.ref) return@edit d
            changed = true
            d.copy(devices = d.devices.map { if (it.key == key) it.copy(ref = merged) else it })
        }
        return changed
    }

    suspend fun rememberCapabilities(key: DeviceKey, capabilities: List<Capability>) {
        edit { d ->
            val existing = d.device(key)
            if (existing == null || existing.capabilities == capabilities) d
            else d.copy(devices = d.devices.map { if (it.key == key) it.copy(capabilities = capabilities) else it })
        }
    }

    // ---- rooms ----

    suspend fun addRoom(name: String): Room {
        val clean = name.trim()
        var result: Room? = null
        edit { d ->
            val existing = d.rooms.firstOrNull { it.name.equals(clean, ignoreCase = true) }
            if (existing != null) {
                result = existing
                d
            } else {
                val room = Room(UUID.randomUUID().toString(), clean, (d.rooms.maxOfOrNull { it.order } ?: -1) + 1)
                result = room
                d.copy(rooms = d.rooms + room)
            }
        }
        return result!!
    }

    suspend fun renameRoom(id: String, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        edit { d -> d.copy(rooms = d.rooms.map { if (it.id == id) it.copy(name = clean) else it }) }
    }

    /** Deletes the room; its devices stay, without a room. */
    suspend fun deleteRoom(id: String) {
        edit { d ->
            d.copy(
                rooms = d.rooms.filterNot { it.id == id },
                devices = d.devices.map { if (it.roomId == id) it.copy(roomId = null) else it },
            )
        }
    }

    /** Moves a room up (delta < 0) or down (delta > 0) in the home screen order. */
    suspend fun moveRoom(id: String, delta: Int) {
        edit { d ->
            val sorted = d.sortedRooms.toMutableList()
            val from = sorted.indexOfFirst { it.id == id }
            if (from < 0) return@edit d
            val to = (from + delta).coerceIn(0, sorted.lastIndex)
            if (to == from) return@edit d
            sorted.add(to, sorted.removeAt(from))
            d.copy(rooms = sorted.mapIndexed { index, room -> room.copy(order = index) })
        }
    }

    // ---- automations ----

    suspend fun saveAutomation(automation: Automation) {
        edit { d ->
            val exists = d.automations.any { it.id == automation.id }
            d.copy(
                automations = if (exists) d.automations.map { if (it.id == automation.id) automation else it }
                else d.automations + automation,
            )
        }
    }

    suspend fun deleteAutomation(id: String) {
        edit { d -> d.copy(automations = d.automations.filterNot { it.id == id }) }
    }

    suspend fun setAutomationEnabled(id: String, enabled: Boolean) {
        edit { d -> d.copy(automations = d.automations.map { if (it.id == id) it.copy(enabled = enabled) else it }) }
    }

    // ---- settings ----

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        edit { d -> d.copy(settings = transform(d.settings)) }
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}
