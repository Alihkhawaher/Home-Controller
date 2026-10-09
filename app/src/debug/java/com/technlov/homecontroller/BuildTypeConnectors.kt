package com.technlov.homecontroller

import com.technlov.home.core.Capability
import com.technlov.home.core.Connector
import com.technlov.home.core.ConnectorException
import com.technlov.home.core.ConnectorInfo
import com.technlov.home.core.DeviceCategory
import com.technlov.home.core.DeviceRef
import com.technlov.home.core.DeviceSession
import com.technlov.home.core.DeviceState
import com.technlov.home.core.Keys
import com.technlov.home.core.LocalNetwork
import com.technlov.home.core.SetValue
import com.technlov.home.core.Value
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

/** Debug builds add a simulator so the app can be tried on an emulator without real devices. */
internal fun buildTypeConnectors(): List<Connector> = listOf(SimulatedConnector)

/**
 * Pretend devices that live in memory: two air conditioners and a dimmable lamp. Nothing is sent on the network.
 * Addresses are from the documentation range 192.0.2.0/24. Debug builds only.
 */
internal object SimulatedConnector : Connector {
    override val info = ConnectorInfo(
        id = "simulated",
        name = "Simulated devices",
        description = "Pretend devices for trying the app without hardware. Debug builds only; nothing goes on the network.",
        categories = setOf(DeviceCategory.CLIMATE, DeviceCategory.LIGHT),
        version = "1.0.0",
        protocol = "In memory (no network)",
    )

    private val catalogue = listOf(
        DeviceRef("simulated", "sim-ac-1", "192.0.2.21", "Split air conditioner", DeviceCategory.CLIMATE, "Simulated split AC", "1.0.4"),
        DeviceRef("simulated", "sim-ac-2", "192.0.2.22", "Window air conditioner", DeviceCategory.CLIMATE, "Simulated window AC", "1.0.4"),
        DeviceRef("simulated", "sim-lamp-1", "192.0.2.23", "Dimmable lamp", DeviceCategory.LIGHT, "Simulated lamp", "2.1.0"),
    )

    private val states = ConcurrentHashMap<String, MutableMap<String, Value>>()

    override suspend fun discover(network: LocalNetwork, timeoutMs: Long): List<DeviceRef> {
        delay(min(timeoutMs, 1_200))
        return if (network.knownHosts.isEmpty()) catalogue else catalogue.filter { it.address in network.knownHosts }
    }

    override suspend fun open(device: DeviceRef): DeviceSession {
        val known = catalogue.firstOrNull { it.id == device.id } ?: throw ConnectorException("Unknown simulated device")
        delay(150)
        return if (known.category == DeviceCategory.CLIMATE) SimulatedAc(device) else SimulatedLamp(device)
    }

    private fun stateOf(id: String, initial: () -> Map<String, Value>) =
        states.getOrPut(id) { initial().toMutableMap() }

    private class SimulatedAc(override val device: DeviceRef) : DeviceSession {
        override val capabilities: List<Capability> = listOf(
            Capability.Power(),
            Capability.Choice(
                Keys.MODE, "Mode",
                listOf(
                    Capability.Option("auto", "Auto"), Capability.Option("cool", "Cool"),
                    Capability.Option("dry", "Dry"), Capability.Option("fan", "Fan"), Capability.Option("heat", "Heat"),
                ),
            ),
            Capability.Number(Keys.TARGET_TEMPERATURE, "Target temperature", 16.0, 30.0, 1.0, "°C"),
            Capability.Reading(Keys.CURRENT_TEMPERATURE, "Room temperature", "°C"),
            Capability.Choice(
                Keys.FAN_SPEED, "Fan speed",
                listOf(
                    Capability.Option("auto", "Auto"), Capability.Option("low", "Low"),
                    Capability.Option("medium_low", "Medium-low"), Capability.Option("medium", "Medium"),
                    Capability.Option("medium_high", "Medium-high"), Capability.Option("high", "High"),
                ),
            ),
            Capability.Choice(
                Keys.SWING_VERTICAL, "Vertical swing",
                listOf(
                    Capability.Option("default", "Default"), Capability.Option("full", "Full swing"),
                    Capability.Option("fixed_top", "Fixed, top"), Capability.Option("fixed_upper", "Fixed, upper"),
                    Capability.Option("fixed_middle", "Fixed, middle"), Capability.Option("fixed_lower", "Fixed, lower"),
                    Capability.Option("fixed_bottom", "Fixed, bottom"), Capability.Option("swing_upper", "Swing upper"),
                    Capability.Option("swing_lower", "Swing lower"),
                ),
            ),
            Capability.Choice(
                Keys.SWING_HORIZONTAL, "Horizontal swing",
                listOf(
                    Capability.Option("default", "Default"), Capability.Option("full", "Full swing"),
                    Capability.Option("left", "Left"), Capability.Option("center", "Centre"),
                    Capability.Option("right", "Right"),
                ),
            ),
            Capability.Toggle(Keys.DISPLAY_LIGHT, "Display light"),
            Capability.Toggle("turbo", "Turbo"),
            Capability.Toggle("quiet", "Quiet"),
            Capability.Toggle("sleep", "Sleep"),
            Capability.Toggle("health", "Health (ionizer)"),
            Capability.Toggle("x_fan", "X-Fan (dry the coil)"),
            Capability.Reading(Keys.FAULT, "Fault", diagnostic = true),
            Capability.Reading(Keys.FIRMWARE, "Firmware", diagnostic = true),
            Capability.Reading("clean_filter", "Clean filter", diagnostic = true),
            Capability.Reading("clock", "Device clock", diagnostic = true),
        )

        private val values = stateOf(device.id) {
            mapOf(
                Keys.POWER to Value.Bool(device.id == "sim-ac-1"),
                Keys.MODE to Value.Text("cool"),
                Keys.TARGET_TEMPERATURE to Value.Num(23.0),
                Keys.CURRENT_TEMPERATURE to Value.Num(if (device.id == "sim-ac-1") 26.0 else 29.0),
                Keys.FAN_SPEED to Value.Text("auto"),
                Keys.SWING_VERTICAL to Value.Text("default"),
                Keys.SWING_HORIZONTAL to Value.Text("default"),
                Keys.DISPLAY_LIGHT to Value.Bool(true),
                "turbo" to Value.Bool(false),
                "quiet" to Value.Bool(false),
                "sleep" to Value.Bool(false),
                "health" to Value.Bool(true),
                "x_fan" to Value.Bool(true),
                Keys.FAULT to Value.Text("None"),
                Keys.FIRMWARE to Value.Text(device.firmware ?: "1.0.0"),
                "clean_filter" to Value.Text("No"),
            )
        }

        override suspend fun read(): DeviceState {
            delay(120)
            synchronized(values) {
                val on = (values[Keys.POWER] as? Value.Bool)?.v == true
                val mode = (values[Keys.MODE] as? Value.Text)?.v
                val current = (values[Keys.CURRENT_TEMPERATURE] as? Value.Num)?.v ?: 27.0
                val target = (values[Keys.TARGET_TEMPERATURE] as? Value.Num)?.v ?: 24.0
                val goal = if (on && mode != "fan") target else 29.0
                val step = min(0.5, abs(goal - current)) * sign(goal - current)
                values[Keys.CURRENT_TEMPERATURE] = Value.Num(current + step)
                values["clock"] = Value.Text(java.time.LocalTime.now().withNano(0).toString())
                return DeviceState(values.toMap())
            }
        }

        override suspend fun apply(changes: List<SetValue>): DeviceState {
            delay(250)
            synchronized(values) { changes.forEach { values[it.key] = it.value } }
            return read()
        }
    }

    private class SimulatedLamp(override val device: DeviceRef) : DeviceSession {
        override val capabilities: List<Capability> = listOf(
            Capability.Power(),
            Capability.Number(Keys.BRIGHTNESS, "Brightness", 1.0, 100.0, 1.0, "%"),
            Capability.Choice(
                "color_temperature", "Colour",
                listOf(
                    Capability.Option("warm", "Warm"), Capability.Option("neutral", "Neutral"),
                    Capability.Option("cool", "Cool"),
                ),
            ),
            Capability.Toggle("night_mode", "Night mode"),
            Capability.Reading("power_draw", "Power draw", "W"),
            Capability.Reading(Keys.FIRMWARE, "Firmware", diagnostic = true),
        )

        private val values = stateOf(device.id) {
            mapOf(
                Keys.POWER to Value.Bool(true),
                Keys.BRIGHTNESS to Value.Num(70.0),
                "color_temperature" to Value.Text("warm"),
                "night_mode" to Value.Bool(false),
                Keys.FIRMWARE to Value.Text(device.firmware ?: "2.1.0"),
            )
        }

        override suspend fun read(): DeviceState {
            delay(100)
            synchronized(values) {
                val on = (values[Keys.POWER] as? Value.Bool)?.v == true
                val brightness = (values[Keys.BRIGHTNESS] as? Value.Num)?.v ?: 0.0
                values["power_draw"] = Value.Num(if (on) (brightness * 0.09 * 10).toInt() / 10.0 else 0.3)
                return DeviceState(values.toMap())
            }
        }

        override suspend fun apply(changes: List<SetValue>): DeviceState {
            delay(200)
            synchronized(values) { changes.forEach { values[it.key] = it.value } }
            return read()
        }
    }
}
