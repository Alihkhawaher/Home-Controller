package com.technlov.homecontroller.ui.home

import com.technlov.home.core.Capability
import com.technlov.home.core.DeviceCategory
import com.technlov.home.core.DeviceKey
import com.technlov.home.core.Keys
import com.technlov.home.core.Value
import com.technlov.homecontroller.data.HomeData
import com.technlov.homecontroller.data.SavedDevice
import com.technlov.homecontroller.hub.DeviceLive
import com.technlov.homecontroller.hub.LinkStatus
import com.technlov.homecontroller.ui.formatNumber
import com.technlov.homecontroller.ui.unitSuffix

/** One tile on the home screen. */
data class DeviceTileUi(
    val key: DeviceKey,
    val name: String,
    val category: DeviceCategory,
    val status: LinkStatus,
    val hasPower: Boolean,
    val isOn: Boolean?,
    val mode: String?,
    val modeLabel: String?,
    val currentTemperature: Double?,
    val targetTemperature: Double?,
    /** For devices without temperatures: the main number or reading, e.g. "70%". */
    val readout: String?,
    val readoutLabel: String?,
    val hidden: Boolean,
    val busy: Boolean,
) {
    val isClimate: Boolean get() = category == DeviceCategory.CLIMATE || targetTemperature != null
    val isActive: Boolean get() = status != LinkStatus.OFFLINE && isOn == true
}

data class RoomSection(val roomId: String?, val roomName: String?, val devices: List<DeviceTileUi>)

data class HomeUi(
    val loaded: Boolean = false,
    val sections: List<RoomSection> = emptyList(),
    val deviceCount: Int = 0,
    val onCount: Int = 0,
    val onlineCount: Int = 0,
    val hiddenCount: Int = 0,
    val showHidden: Boolean = false,
    val averageTemperature: Double? = null,
)

/** Builds the home screen from the saved devices and the hub's live states. Pure, unit-tested. */
fun buildHomeUi(data: HomeData, live: Map<DeviceKey, DeviceLive>, showHidden: Boolean): HomeUi {
    val tiles = data.sortedDevices.map { it to tileFor(it, live[it.key]) }
    val visible = tiles.filter { (device, _) -> showHidden || !device.hidden }

    val roomIds = data.rooms.map { it.id }.toSet()
    val sections = buildList {
        data.sortedRooms.forEach { room ->
            val inRoom = visible.filter { it.first.roomId == room.id }.map { it.second }
            if (inRoom.isNotEmpty()) add(RoomSection(room.id, room.name, inRoom))
        }
        val unassigned = visible.filter { it.first.roomId == null || it.first.roomId !in roomIds }.map { it.second }
        if (unassigned.isNotEmpty()) add(RoomSection(null, null, unassigned))
    }

    val all = tiles.map { it.second }
    val temperatures = all.filter { it.status == LinkStatus.ONLINE }.mapNotNull { it.currentTemperature }
    return HomeUi(
        loaded = true,
        sections = sections,
        deviceCount = all.size,
        onCount = all.count { it.isActive },
        onlineCount = all.count { it.status == LinkStatus.ONLINE },
        hiddenCount = data.devices.count { it.hidden },
        showHidden = showHidden,
        averageTemperature = if (temperatures.isEmpty()) null else temperatures.average(),
    )
}

internal fun tileFor(device: SavedDevice, live: DeviceLive?): DeviceTileUi {
    val capabilities = live?.capabilities?.takeIf { it.isNotEmpty() } ?: device.capabilities
    val state = live?.state
    val mode = state?.text(Keys.MODE)
    val modeCapability = capabilities.firstOrNull { it.key == Keys.MODE } as? Capability.Choice
    val hasPower = capabilities.any { it is Capability.Power } || (capabilities.isEmpty() && state?.bool(Keys.POWER) != null)

    // Devices without temperatures show their first number (brightness, volume...) or reading.
    var readout: String? = null
    var readoutLabel: String? = null
    if (state?.number(Keys.CURRENT_TEMPERATURE) == null && state?.number(Keys.TARGET_TEMPERATURE) == null) {
        val main = capabilities.firstOrNull { it is Capability.Number } ?: capabilities.firstOrNull {
            it is Capability.Reading && !it.diagnostic
        }
        val value = main?.let { state?.values?.get(it.key) }
        if (main != null && value != null) {
            val unit = (main as? Capability.Number)?.unit ?: (main as? Capability.Reading)?.unit.orEmpty()
            readout = when (value) {
                is Value.Num -> formatNumber(value.v) + unitSuffix(unit)
                is Value.Text -> value.v
                is Value.Bool -> null
            }
            readoutLabel = main.label
        }
    }

    return DeviceTileUi(
        key = device.key,
        name = device.name,
        category = device.ref.category,
        status = live?.status ?: LinkStatus.CONNECTING,
        hasPower = hasPower,
        isOn = state?.bool(Keys.POWER),
        mode = mode,
        modeLabel = mode?.let { m -> modeCapability?.options?.firstOrNull { it.value == m }?.label ?: m },
        currentTemperature = state?.number(Keys.CURRENT_TEMPERATURE),
        targetTemperature = state?.number(Keys.TARGET_TEMPERATURE),
        readout = readout,
        readoutLabel = readoutLabel,
        hidden = device.hidden,
        busy = live?.busy == true,
    )
}
