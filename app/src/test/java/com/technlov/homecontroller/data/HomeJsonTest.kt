package com.technlov.homecontroller.data

import com.technlov.home.core.Action
import com.technlov.home.core.Automation
import com.technlov.home.core.Capability
import com.technlov.home.core.Condition
import com.technlov.home.core.DeviceCategory
import com.technlov.home.core.Keys
import com.technlov.home.core.Op
import com.technlov.home.core.SetValue
import com.technlov.home.core.Trigger
import com.technlov.home.core.Value
import com.technlov.homecontroller.climateCapabilities
import com.technlov.homecontroller.exampleRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

class HomeJsonTest {

    private val full = HomeData(
        devices = listOf(
            SavedDevice(
                ref = exampleRef("ac-1").copy(extras = mapOf("protocol" to "v2")),
                name = "Bedroom AC",
                roomId = "room-1",
                hidden = false,
                order = 0,
                capabilities = climateCapabilities,
            ),
            SavedDevice(ref = exampleRef("ac-2", "192.0.2.11"), name = "Office AC", hidden = true, order = 1),
        ),
        rooms = listOf(Room("room-1", "Bedroom", 0), Room("room-2", "Office", 1)),
        automations = listOf(
            Automation(
                id = "a1",
                name = "Morning cool-down",
                enabled = true,
                trigger = Trigger.TimeOfDay(6, 45, setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY)),
                conditions = listOf(
                    Condition.TimeBetween(6, 0, 9, 30),
                    Condition.StateCompare("fake:ac-1", Keys.POWER, Op.EQ, Value.Bool(false)),
                ),
                actions = listOf(
                    Action.SetValues(
                        "fake:ac-1",
                        listOf(
                            SetValue(Keys.POWER, Value.Bool(true)),
                            SetValue(Keys.MODE, Value.Text("cool")),
                            SetValue(Keys.TARGET_TEMPERATURE, Value.Num(22.5)),
                        ),
                    ),
                    Action.Notify("Cooling", "Bedroom AC is on"),
                ),
            ),
            Automation(
                id = "a2",
                name = "Too hot",
                enabled = false,
                trigger = Trigger.StateMatches("fake:ac-2", Keys.CURRENT_TEMPERATURE, Op.GE, Value.Num(30.0)),
                actions = listOf(Action.Notify("Hot", "Office is above 30")),
            ),
        ),
        settings = AppSettings(
            runInBackground = true,
            foregroundPollSeconds = 5,
            backgroundPollSeconds = 300,
            theme = ThemeMode.DARK,
            dynamicColor = false,
            verboseLogging = true,
        ),
    )

    @Test
    fun roundTripKeepsEverything() {
        val decoded = HomeJson.decode(HomeJson.encode(full))
        assertEquals(full, decoded)
    }

    @Test
    fun emptyDataRoundTrips() {
        assertEquals(HomeData(), HomeJson.decode(HomeJson.encode(HomeData())))
    }

    @Test
    fun everyCapabilityKindRoundTrips() {
        val caps = listOf(
            Capability.Power("power", "Power"),
            Capability.Choice("input", "Input", listOf(Capability.Option("hdmi1", "HDMI 1")), writable = false),
            Capability.Number("volume", "Volume", 0.0, 100.0, 5.0, "%", writable = true),
            Capability.Toggle("mute", "Mute", writable = false),
            Capability.Reading("clock", "Clock", "", diagnostic = true),
        )
        val data = HomeData(devices = listOf(SavedDevice(exampleRef(), "TV", capabilities = caps)))
        assertEquals(caps, HomeJson.decode(HomeJson.encode(data)).devices.single().capabilities)
    }

    @Test
    fun toleratesUnknownKeysAndValues() {
        val json = """
            {"version":9,"future":"ignored",
             "devices":[{"connectorId":"fake","id":"x","address":"192.0.2.9","name":"Lamp","category":"HOLOGRAM","extra":1}],
             "rooms":[],
             "automations":[{"id":"a","name":"n","trigger":{"type":"state","device":"fake:x","key":"power","op":"SOMETIMES","value":{"type":"bool","v":true}},"actions":[]}],
             "settings":{"theme":"NEON","foregroundPollSeconds":0}}
        """.trimIndent()
        val data = HomeJson.decode(json)
        val device = data.devices.single()
        assertEquals(DeviceCategory.OTHER, device.ref.category)
        assertEquals("Lamp", device.ref.name)
        assertEquals(Op.EQ, (data.automations.single().trigger as Trigger.StateMatches).op)
        assertEquals(ThemeMode.SYSTEM, data.settings.theme)
        assertEquals(1, data.settings.foregroundPollSeconds)
    }

    @Test
    fun storedShapeUsesReadableTypeNames() {
        val text = HomeJson.encode(full)
        assertTrue(text.contains("\"type\":\"time\""))
        assertTrue(text.contains("\"type\":\"set\""))
        assertTrue(text.contains("\"type\":\"choice\""))
        assertTrue(text.contains("\"days\":[\"MONDAY\",\"SUNDAY\"]"))
    }
}
