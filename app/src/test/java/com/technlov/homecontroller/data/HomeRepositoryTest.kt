package com.technlov.homecontroller.data

import com.technlov.home.core.Action
import com.technlov.home.core.Automation
import com.technlov.home.core.Trigger
import com.technlov.homecontroller.InMemoryDataStore
import com.technlov.homecontroller.climateCapabilities
import com.technlov.homecontroller.exampleRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRepositoryTest {
    private val store = InMemoryDataStore(HomeData())
    private val repository = HomeRepository(store)

    @Test
    fun addingTheSameDeviceTwiceUpdatesIt() = runTest {
        repository.addDevice(exampleRef("ac-1"), "Bedroom AC", null)
        repository.updateDevice("fake:ac-1") { it.copy(hidden = true) }
        repository.addDevice(exampleRef("ac-1", "192.0.2.20"), "  Main AC ", "room-1")

        val device = store.value.devices.single()
        assertEquals("Main AC", device.name)
        assertEquals("192.0.2.20", device.ref.address)
        assertEquals("room-1", device.roomId)
        assertFalse(device.hidden)
    }

    @Test
    fun blankNameFallsBackToTheReportedOne() = runTest {
        val saved = repository.addDevice(exampleRef("ac-1"), "   ", null)
        assertEquals("Example AC ac-1", saved.name)
    }

    @Test
    fun newDevicesGoLast() = runTest {
        repository.addDevice(exampleRef("a"), "A", null)
        repository.addDevice(exampleRef("b"), "B", null)
        assertEquals(listOf(0, 1), store.value.devices.map { it.order })
    }

    @Test
    fun rediscoveryKeepsNameAndRoomButTakesTheNewAddress() = runTest {
        repository.addDevice(exampleRef("ac-1"), "Bedroom AC", "room-1")
        val changed = repository.refreshDeviceRef(exampleRef("ac-1", "192.0.2.99").copy(name = "AC from network", firmware = null))
        assertTrue(changed)
        val device = store.value.devices.single()
        assertEquals("Bedroom AC", device.name)
        assertEquals("room-1", device.roomId)
        assertEquals("192.0.2.99", device.ref.address)
        assertEquals("1.0", device.ref.firmware) // unknown firmware does not wipe the known one
        assertFalse(repository.refreshDeviceRef(exampleRef("ac-1", "192.0.2.99")))
        assertFalse(repository.refreshDeviceRef(exampleRef("unknown")))
    }

    @Test
    fun capabilitiesAreRemembered() = runTest {
        repository.addDevice(exampleRef(), "AC", null)
        repository.rememberCapabilities("fake:ac-1", climateCapabilities)
        assertEquals(climateCapabilities, store.value.devices.single().capabilities)
    }

    @Test
    fun roomsAreDedupedRenamedMovedAndDeleted() = runTest {
        val bedroom = repository.addRoom(" Bedroom ")
        val again = repository.addRoom("bedroom")
        assertEquals(bedroom, again)
        val office = repository.addRoom("Office")
        val kitchen = repository.addRoom("Kitchen")
        assertEquals(listOf("Bedroom", "Office", "Kitchen"), store.value.sortedRooms.map { it.name })

        repository.moveRoom(kitchen.id, -2)
        assertEquals(listOf("Kitchen", "Bedroom", "Office"), store.value.sortedRooms.map { it.name })
        repository.moveRoom(kitchen.id, -1) // already first: no change
        assertEquals(listOf("Kitchen", "Bedroom", "Office"), store.value.sortedRooms.map { it.name })

        repository.renameRoom(office.id, "Study")
        repository.renameRoom(office.id, "  ") // ignored
        assertEquals("Study", store.value.room(office.id)?.name)

        repository.addDevice(exampleRef(), "AC", bedroom.id)
        repository.deleteRoom(bedroom.id)
        assertNull(store.value.devices.single().roomId)
        assertEquals(2, store.value.rooms.size)
    }

    @Test
    fun automationsAreSavedReplacedToggledAndDeleted() = runTest {
        val a = Automation("a1", "Night", trigger = Trigger.TimeOfDay(22, 0), actions = listOf(Action.Notify("t", "x")))
        repository.saveAutomation(a)
        repository.saveAutomation(a.copy(name = "Late night"))
        assertEquals(listOf("Late night"), store.value.automations.map { it.name })
        repository.setAutomationEnabled("a1", false)
        assertFalse(store.value.automations.single().enabled)
        repository.deleteAutomation("a1")
        assertTrue(store.value.automations.isEmpty())
    }

    @Test
    fun removingADeviceKeepsTheRest() = runTest {
        repository.addDevice(exampleRef("a"), "A", null)
        repository.addDevice(exampleRef("b"), "B", null)
        repository.removeDevice("fake:a")
        assertEquals(listOf("fake:b"), store.value.devices.map { it.key })
    }

    @Test
    fun settingsUpdate() = runTest {
        repository.updateSettings { it.copy(runInBackground = true, theme = ThemeMode.LIGHT) }
        assertTrue(store.value.settings.runInBackground)
        assertEquals(ThemeMode.LIGHT, store.value.settings.theme)
    }
}
