package com.technlov.homecontroller.data

import com.technlov.home.core.Action
import com.technlov.home.core.Automation
import com.technlov.home.core.Capability
import com.technlov.home.core.Condition
import com.technlov.home.core.DeviceCategory
import com.technlov.home.core.Keys
import com.technlov.home.core.Op
import com.technlov.home.core.SetValue
import com.technlov.home.core.SunEvent
import com.technlov.home.core.Trigger
import com.technlov.home.core.Value
import com.technlov.homecontroller.climateCapabilities
import com.technlov.homecontroller.exampleRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime

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
            SavedDevice(ref = exampleRef("ac-2", "192.0.2.11"), name = "Office AC", hidden = true, order = 1,
                powerButton = PowerButton.CONFIRM),
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
                runOnce = true,
            ),
            Automation(
                id = "a3",
                name = "Before sunset",
                trigger = Trigger.Sun(SunEvent.SUNSET, -15, setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)),
                actions = listOf(Action.SetValues("fake:ac-1", listOf(SetValue(Keys.POWER, Value.Bool(false))))),
            ),
        ),
        settings = AppSettings(
            runInBackground = true,
            foregroundPollSeconds = 5,
            backgroundPollSeconds = 300,
            theme = ThemeMode.DARK,
            dynamicColor = false,
            verboseLogging = true,
            homeLocation = HomeLocation(51.48, -0.01),
        ),
        lastRuns =mapOf("a1" to LocalDateTime.of(2026, 10, 12, 6, 45), "a2" to LocalDateTime.of(2026, 10, 11, 14, 3, 27)),
    )

    @Test
    fun roundTripKeepsEverything() {
        val decoded = HomeJson.decode(HomeJson.encode(full))
        assertEquals(full, decoded)
    }

    @Test
    fun powerButtonDefaultsToShowAndToleratesUnknownValues() {
        val missing = """{"devices":[{"connectorId":"fake","id":"a","address":"192.0.2.9","name":"Plug"}]}"""
        assertEquals(PowerButton.SHOW, HomeJson.decode(missing).devices.single().powerButton)
        val unknown = """{"devices":[{"connectorId":"fake","id":"a","address":"192.0.2.9","name":"Plug","powerButton":"LATER"}]}"""
        assertEquals(PowerButton.SHOW, HomeJson.decode(unknown).devices.single().powerButton)
        val hidden = """{"devices":[{"connectorId":"fake","id":"a","address":"192.0.2.9","name":"Plug","powerButton":"HIDE"}]}"""
        assertEquals(PowerButton.HIDE, HomeJson.decode(hidden).devices.single().powerButton)
    }

    @Test
    fun automationsSavedBeforeRunOnceKeepRepeating() {
        val old = """{"automations":[{"id":"a","name":"n","trigger":{"type":"time","hour":7,"minute":0},
            "actions":[{"type":"notify","title":"t","text":"x"}]}]}"""
        val automation = HomeJson.decode(old).automations.single()
        assertFalse(automation.runOnce)
        assertTrue(automation.enabled)
    }

    @Test
    fun lastRunsAreSavedAsLocalTimesAndAnUnreadableOneIsDropped() {
        assertTrue(HomeJson.encode(full).contains("\"lastRuns\":{\"a1\":\"2026-10-12T06:45\""))
        val json = """{"lastRuns":{"a":"2026-10-12T07:30","b":"yesterday"}}"""
        assertEquals(mapOf("a" to LocalDateTime.of(2026, 10, 12, 7, 30)), HomeJson.decode(json).lastRuns)
        assertTrue("saved before runs were kept", HomeJson.decode("""{"automations":[]}""").lastRuns.isEmpty())
    }

    @Test
    fun sunTriggersAndTheHomeLocationAreStoredReadablyAndCheckedOnLoad() {
        val text = HomeJson.encode(full)
        assertTrue(text.contains("""{"type":"sun","event":"SUNSET","offsetMinutes":-15,"days":["FRIDAY","SATURDAY"]}"""))
        assertTrue(text.contains(""""homeLocation":{"latitude":51.48,"longitude":-0.01}"""))
        assertNull("saved before sun schedules: no location", HomeJson.decode("""{"settings":{"theme":"DARK"}}""").settings.homeLocation)

        val odd = """{"automations":[{"id":"s","name":"n","trigger":{"type":"sun","event":"DAWN","offsetMinutes":900},
            "actions":[{"type":"notify","title":"t","text":"x"}]}],
            "settings":{"homeLocation":{"latitude":123.0,"longitude":10.0}}}"""
        val data = HomeJson.decode(odd)
        assertEquals(Trigger.Sun(SunEvent.SUNSET, Trigger.Sun.MAX_OFFSET_MINUTES), data.automations.single().trigger)
        assertNull("a place off the map is dropped", data.settings.homeLocation)
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
