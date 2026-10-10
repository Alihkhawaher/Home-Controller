package com.technlov.homecontroller.data

import com.technlov.home.core.Automation
import com.technlov.home.core.Capability
import com.technlov.home.core.DeviceKey
import com.technlov.home.core.DeviceRef
import com.technlov.home.core.key

/**
 * A device the user added: what the connector found ([ref]) plus what the user chose (name, room, visibility,
 * order). Identity is [key] = "connectorId:deviceId", stable across IP changes.
 */
data class SavedDevice(
    val ref: DeviceRef,
    val name: String,
    val roomId: String? = null,
    val hidden: Boolean = false,
    val order: Int = 0,
    /** The capabilities the device last reported, so its controls and the automation editor work while it is offline. */
    val capabilities: List<Capability> = emptyList(),
    /** How the power button behaves for this device -- e.g. a plug that powers a PC is safer behind "Ask first". */
    val powerButton: PowerButton = PowerButton.SHOW,
) {
    val key: DeviceKey get() = ref.key()
}

/**
 * The power button's behaviour for one device. [SHOW]: one tap switches it. [CONFIRM]: every tap asks first, on the
 * home screen and on the device's page. [HIDE]: not on the home screen at all; only on the device's page.
 * Automations are deliberate and are never affected.
 */
enum class PowerButton { SHOW, CONFIRM, HIDE }

data class Room(val id: String, val name: String, val order: Int = 0)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val runInBackground: Boolean = false,
    val foregroundPollSeconds: Int = DEFAULT_FOREGROUND_POLL,
    val backgroundPollSeconds: Int = DEFAULT_BACKGROUND_POLL,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    /** Diagnostics: also record every message to and from devices (the log grows faster). */
    val verboseLogging: Boolean = false,
) {
    companion object {
        const val DEFAULT_FOREGROUND_POLL = 10
        const val DEFAULT_BACKGROUND_POLL = 60
        val FOREGROUND_POLL_OPTIONS = listOf(5, 10, 30)
        val BACKGROUND_POLL_OPTIONS = listOf(30, 60, 300)
    }
}

/** Everything the app saves. */
data class HomeData(
    val devices: List<SavedDevice> = emptyList(),
    val rooms: List<Room> = emptyList(),
    val automations: List<Automation> = emptyList(),
    val settings: AppSettings = AppSettings(),
) {
    val sortedRooms: List<Room> get() = rooms.sortedWith(compareBy({ it.order }, { it.name }))
    val sortedDevices: List<SavedDevice> get() = devices.sortedWith(compareBy({ it.order }, { it.name }))

    fun device(key: DeviceKey): SavedDevice? = devices.firstOrNull { it.key == key }
    fun room(id: String?): Room? = id?.let { rid -> rooms.firstOrNull { it.id == rid } }
    fun automation(id: String): Automation? = automations.firstOrNull { it.id == id }
}
