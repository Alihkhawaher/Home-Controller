package com.technlov.homecontroller.ui

import com.technlov.home.core.Action
import com.technlov.home.core.Automation
import com.technlov.home.core.Capability
import com.technlov.home.core.DeviceState
import com.technlov.home.core.Keys
import com.technlov.home.core.Op
import com.technlov.home.core.SetValue
import com.technlov.home.core.Trigger
import com.technlov.home.core.Value
import com.technlov.homecontroller.climateCapabilities
import com.technlov.homecontroller.data.HomeData
import com.technlov.homecontroller.data.Room
import com.technlov.homecontroller.data.SavedDevice
import com.technlov.homecontroller.exampleRef
import com.technlov.homecontroller.hub.DeviceLive
import com.technlov.homecontroller.hub.LinkStatus
import com.technlov.homecontroller.ui.automations.AutomationDraft
import com.technlov.homecontroller.ui.automations.DraftProblem
import com.technlov.homecontroller.ui.automations.TriggerKind
import com.technlov.homecontroller.ui.automations.defaultValue
import com.technlov.homecontroller.ui.automations.deviceOptions
import com.technlov.homecontroller.ui.automations.opsFor
import com.technlov.homecontroller.ui.device.dialValueAt
import com.technlov.homecontroller.ui.device.snapToStep
import com.technlov.homecontroller.ui.home.buildHomeUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import kotlin.math.cos
import kotlin.math.sin

class UiLogicTest {

    // ---- home screen ----

    private val bedroom = Room("r1", "Bedroom", 0)
    private val office = Room("r2", "Office", 1)
    private val data = HomeData(
        devices = listOf(
            SavedDevice(exampleRef("a"), "Office AC", roomId = office.id, order = 0),
            SavedDevice(exampleRef("b"), "Bedroom AC", roomId = bedroom.id, order = 1),
            SavedDevice(exampleRef("c"), "Hall AC", roomId = "deleted-room", order = 2),
            SavedDevice(exampleRef("d"), "Spare AC", hidden = true, order = 3),
        ),
        rooms = listOf(office, bedroom).sortedByDescending { it.order },
    )

    private fun live(key: String, on: Boolean, current: Double, status: LinkStatus = LinkStatus.ONLINE) = DeviceLive(
        key = key,
        status = status,
        state = DeviceState(
            mapOf(
                Keys.POWER to Value.Bool(on),
                Keys.MODE to Value.Text("cool"),
                Keys.CURRENT_TEMPERATURE to Value.Num(current),
                Keys.TARGET_TEMPERATURE to Value.Num(22.0),
            ),
        ),
        capabilities = climateCapabilities,
    )

    @Test
    fun homeGroupsByRoomOrderWithLooseDevicesLast() {
        val ui = buildHomeUi(
            data,
            mapOf(
                "fake:a" to live("fake:a", on = true, current = 26.0),
                "fake:b" to live("fake:b", on = false, current = 24.0),
                "fake:c" to live("fake:c", on = true, current = 30.0, status = LinkStatus.OFFLINE),
            ),
            showHidden = false,
        )
        assertEquals(listOf("Bedroom", "Office", null), ui.sections.map { it.roomName })
        assertEquals(listOf("Hall AC"), ui.sections.last().devices.map { it.name })
        assertEquals(4, ui.deviceCount)
        assertEquals(1, ui.hiddenCount)
        assertEquals(1, ui.onCount) // the offline one does not count as on
        assertEquals(2, ui.onlineCount)
        assertEquals(25.0, ui.averageTemperature!!, 0.001) // online devices only

        val tile = ui.sections[1].devices.single()
        assertEquals("Cool", tile.modeLabel)
        assertEquals(22.0, tile.targetTemperature!!, 0.0)
        assertEquals(true, tile.isActive)
    }

    @Test
    fun hiddenDevicesShowOnlyWhenAsked() {
        val shown = buildHomeUi(data, emptyMap(), showHidden = true)
        assertEquals(listOf("Hall AC", "Spare AC"), shown.sections.last().devices.map { it.name })
        assertEquals(LinkStatus.CONNECTING, shown.sections.last().devices.last().status)
    }

    // ---- automation drafts ----

    @Test
    fun draftsRoundTripAutomations() {
        val time = Automation(
            "t", "Morning", enabled = false,
            trigger = Trigger.TimeOfDay(7, 15, setOf(DayOfWeek.FRIDAY)),
            actions = listOf(Action.SetValues("fake:a", listOf(SetValue(Keys.POWER, Value.Bool(true))))),
        )
        assertEquals(time, AutomationDraft.from(time).toAutomation("unused"))

        val state = Automation(
            "s", "Hot",
            trigger = Trigger.StateMatches("fake:a", Keys.CURRENT_TEMPERATURE, Op.GE, Value.Num(30.0)),
            actions = listOf(Action.Notify("Hot", "It is hot")),
        )
        assertEquals(state, AutomationDraft.from(state).toAutomation("unused"))
    }

    @Test
    fun draftProblemsBlockSaving() {
        val draft = AutomationDraft(id = "x", isNew = true)
        assertEquals(DraftProblem.NO_ACTIONS, draft.problem())
        assertNull(draft.toAutomation("At 07:00"))

        val device = draft.copy(kind = TriggerKind.DEVICE, actions = listOf(Action.Notify("a", "")))
        assertEquals(DraftProblem.NO_TRIGGER_DEVICE, device.problem())
        assertEquals(DraftProblem.NO_TRIGGER_VALUE, device.copy(device = "fake:a", key = Keys.POWER).problem())

        val emptyChange = draft.copy(actions = listOf(Action.SetValues("fake:a", emptyList())))
        assertEquals(DraftProblem.EMPTY_DEVICE_ACTION, emptyChange.problem())
        val emptyNote = draft.copy(actions = listOf(Action.Notify(" ", "")))
        assertEquals(DraftProblem.EMPTY_NOTIFICATION, emptyNote.problem())
    }

    @Test
    fun unnamedDraftsAndNotificationsGetTheFallbackName() {
        val saved = AutomationDraft(id = "x", isNew = true, actions = listOf(Action.Notify(" ", "Done")))
            .toAutomation("At 07:00")!!
        assertEquals("At 07:00", saved.name)
        assertEquals(Action.Notify("At 07:00", "Done"), saved.actions.single())
        assertEquals(Trigger.TimeOfDay(7, 0, emptySet()), saved.trigger)
    }

    @Test
    fun comparisonsAndStartingValuesFitTheCapability() {
        val target = climateCapabilities.first { it.key == Keys.TARGET_TEMPERATURE }
        assertEquals(6, opsFor(target).size)
        assertEquals(listOf(Op.EQ, Op.NE), opsFor(Capability.Power()))
        assertEquals(Value.Num(23.0), defaultValue(target, null))
        assertEquals(Value.Num(25.0), defaultValue(target, Value.Num(24.6)))
        assertEquals(Value.Bool(true), defaultValue(Capability.Power(), null))
        val mode = climateCapabilities.first { it.key == Keys.MODE }
        assertEquals(Value.Text("cool"), defaultValue(mode, null))
    }

    @Test
    fun editorDevicesFallBackToRememberedCapabilitiesThenPower() {
        val options = deviceOptions(
            HomeData(
                devices = listOf(
                    SavedDevice(exampleRef("a"), "Known", capabilities = climateCapabilities),
                    SavedDevice(exampleRef("b"), "Never seen"),
                ),
            ),
            emptyMap(),
        )
        assertEquals(climateCapabilities, options[0].capabilities)
        assertEquals(listOf(Capability.Power()), options[1].capabilities)
        // Readings cannot be set.
        assertEquals(listOf(Keys.POWER, Keys.MODE, Keys.TARGET_TEMPERATURE, "turbo"), options[0].settable.map { it.key })
    }

    // ---- dial ----

    @Test
    fun dialSnapsToSteps() {
        assertEquals(22.0, snapToStep(22.4, 16.0, 30.0, 1.0), 0.0)
        assertEquals(22.5, snapToStep(22.4, 16.0, 30.0, 0.5), 0.0)
        assertEquals(30.0, snapToStep(31.0, 16.0, 30.0, 1.0), 0.0)
        assertEquals(16.0, snapToStep(3.0, 16.0, 30.0, 1.0), 0.0)
    }

    private fun touchAt(degrees: Double): Pair<Float, Float> {
        val rad = Math.toRadians(degrees)
        return (cos(rad) * 100).toFloat() to (sin(rad) * 100).toFloat()
    }

    @Test
    fun dialMapsAnglesAlongTheArc() {
        // The arc starts at 135° (bottom left), passes 270° (top) halfway, and ends at 45° (bottom right).
        val (sx, sy) = touchAt(135.0)
        assertEquals(16.0, dialValueAt(sx, sy, 16.0, 30.0, 1.0), 0.0)
        val (tx, ty) = touchAt(270.0)
        assertEquals(23.0, dialValueAt(tx, ty, 16.0, 30.0, 1.0), 0.0)
        val (ex, ey) = touchAt(45.0)
        assertEquals(30.0, dialValueAt(ex, ey, 16.0, 30.0, 1.0), 0.0)
        // In the gap at the bottom: the nearer end wins.
        val (rx, ry) = touchAt(80.0)
        assertEquals(30.0, dialValueAt(rx, ry, 16.0, 30.0, 1.0), 0.0)
        val (lx, ly) = touchAt(100.0)
        assertEquals(16.0, dialValueAt(lx, ly, 16.0, 30.0, 1.0), 0.0)
    }
}
