package com.technlov.homecontroller.hub

import com.technlov.home.core.Action
import com.technlov.home.core.Automation
import com.technlov.home.core.AutomationEngine
import com.technlov.home.core.ConnectorRegistry
import com.technlov.home.core.DeviceKey
import com.technlov.home.core.DeviceState
import com.technlov.home.core.EventLog
import com.technlov.home.core.Keys
import com.technlov.home.core.SetValue
import com.technlov.home.core.Trigger
import com.technlov.home.core.Value
import com.technlov.homecontroller.FakeConnector
import com.technlov.homecontroller.InMemoryDataStore
import com.technlov.homecontroller.climateCapabilities
import com.technlov.homecontroller.data.HomeData
import com.technlov.homecontroller.data.HomeRepository
import com.technlov.homecontroller.data.SavedDevice
import com.technlov.homecontroller.exampleRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import kotlin.coroutines.EmptyCoroutineContext

class HubTest {
    private val connector = FakeConnector()
    private val store = InMemoryDataStore(
        HomeData(
            devices = listOf(
                SavedDevice(exampleRef("ac-1"), "Bedroom AC"),
                SavedDevice(exampleRef("lost", connectorId = "missing"), "Old TV"),
            ),
        ),
    )
    private val repository = HomeRepository(store)
    private val notifications = mutableListOf<Pair<String, String>>()
    private val failures = mutableListOf<Triple<String, String, DeviceIssue>>()
    private val events = object : HubEvents {
        override fun notify(title: String, text: String) {
            notifications += title to text
        }

        override fun automationFailed(automation: Automation, deviceName: String, issue: DeviceIssue) {
            failures += Triple(automation.name, deviceName, issue)
        }
    }
    private var due: List<Automation> = emptyList()
    private var seenPrevious: Map<DeviceKey, DeviceState>? = null
    private val engine = object : AutomationEngine {
        override fun due(
            now: LocalDateTime,
            automations: List<Automation>,
            states: Map<DeviceKey, DeviceState>,
            previousStates: Map<DeviceKey, DeviceState>,
            lastRun: Map<String, LocalDateTime>,
        ): List<Automation> {
            seenPrevious = previousStates
            return due
        }
    }
    // Device calls stay on the test dispatcher so virtual time (and withTimeout) behave.
    private val hub = Hub(ConnectorRegistry(listOf(connector)), repository, engine, events, ioContext = EmptyCoroutineContext)

    private val ac = "fake:ac-1"

    @Test
    fun refreshReadsDevicesAndRemembersCapabilities() = runTest {
        hub.refresh()
        val live = hub.live.value.getValue(ac)
        assertEquals(LinkStatus.ONLINE, live.status)
        assertEquals(24.0, live.state!!.number(Keys.TARGET_TEMPERATURE)!!, 0.0)
        assertEquals(climateCapabilities, live.capabilities)
        assertEquals(climateCapabilities, store.value.device(ac)!!.capabilities)
        assertTrue(live.lastSeenMillis != null)
    }

    @Test
    fun aDeviceWithoutItsPluginIsOfflineNotACrash() = runTest {
        hub.refresh()
        val lost = hub.live.value.getValue("missing:lost")
        assertEquals(LinkStatus.OFFLINE, lost.status)
        assertEquals(DeviceIssue.NoConnector("missing"), lost.issue)
    }

    @Test
    fun oneMissedReplyKeepsAnOnlineDeviceOnlineTheSecondMarksItOffline() = runTest {
        hub.refresh(listOf(ac))
        connector.failReads = true
        hub.refresh(listOf(ac))
        assertEquals(LinkStatus.ONLINE, hub.live.value.getValue(ac).status)
        hub.refresh(listOf(ac))
        val live = hub.live.value.getValue(ac)
        assertEquals(LinkStatus.OFFLINE, live.status)
        assertEquals(DeviceIssue.Failed("no reply"), live.issue)
        // The last known values stay visible while offline.
        assertEquals(24.0, live.state!!.number(Keys.TARGET_TEMPERATURE)!!, 0.0)

        connector.failReads = false
        hub.refresh(listOf(ac))
        assertEquals(LinkStatus.ONLINE, hub.live.value.getValue(ac).status)
        assertNull(hub.live.value.getValue(ac).issue)
        assertTrue("a failed session is reopened", connector.opens >= 2)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun applyShowsTheChangeAtOnceThenKeepsTheDevicesAnswer() = runTest {
        hub.refresh(listOf(ac))
        val gate = CompletableDeferred<Unit>()
        connector.applyGate = gate
        val pending = async { hub.apply(ac, listOf(SetValue(Keys.TARGET_TEMPERATURE, Value.Num(20.0)))) }
        runCurrent()

        val during = hub.live.value.getValue(ac)
        assertTrue(during.busy)
        assertEquals(20.0, during.state!!.number(Keys.TARGET_TEMPERATURE)!!, 0.0)

        gate.complete(Unit)
        assertTrue(pending.await().isSuccess)
        val after = hub.live.value.getValue(ac)
        assertFalse(after.busy)
        assertEquals(20.0, after.state!!.number(Keys.TARGET_TEMPERATURE)!!, 0.0)
    }

    @Test
    fun aRefusedChangeSnapsBack() = runTest {
        hub.refresh(listOf(ac))
        connector.failApplies = true
        val result = hub.apply(ac, listOf(SetValue(Keys.POWER, Value.Bool(true))))
        assertTrue(result.isFailure)
        assertEquals(DeviceIssue.Failed("refused"), DeviceIssue.from(result.exceptionOrNull()!!))
        val live = hub.live.value.getValue(ac)
        assertEquals(false, live.state!!.bool(Keys.POWER))
        assertFalse(live.busy)
    }

    @Test
    fun applyToAnUnknownDeviceFails() = runTest {
        val result = hub.apply("fake:nope", listOf(SetValue(Keys.POWER, Value.Bool(true))))
        assertEquals(DeviceIssue.UnknownDevice, DeviceIssue.from(result.exceptionOrNull()!!))
    }

    @Test
    fun removedDevicesLeaveTheLiveMap() = runTest {
        hub.refresh()
        repository.removeDevice("missing:lost")
        hub.refresh()
        assertEquals(setOf(ac), hub.live.value.keys)
    }

    @Test
    fun linkChangesAndChangesAreLogged() = runTest {
        val lines = mutableListOf<String>()
        val log = object : EventLog {
            override val verbose = false
            override fun log(level: EventLog.Level, source: String, message: String, error: Throwable?) {
                lines += "$level $source $message"
            }
        }
        val logged = Hub(ConnectorRegistry(listOf(connector)), repository, engine, events, ioContext = EmptyCoroutineContext, log = log)
        logged.refresh(listOf(ac))
        logged.apply(ac, listOf(SetValue(Keys.TARGET_TEMPERATURE, Value.Num(21.0))))
        connector.failReads = true
        logged.refresh(listOf(ac))
        logged.refresh(listOf(ac))
        connector.failReads = false
        logged.refresh(listOf(ac))

        assertTrue(lines.any { it == "INFO hub Bedroom AC: added (fake at 192.0.2.10)" })
        assertTrue(lines.any { it.startsWith("INFO hub Bedroom AC: connected at 192.0.2.10") })
        assertTrue(lines.any { it == "INFO hub Bedroom AC: set target_temperature=21" })
        assertTrue(lines.any { it == "WARN hub Bedroom AC is offline: no reply" })
        assertTrue(lines.any { it.startsWith("INFO hub Bedroom AC: no reply (failure 2, next try in 10 s)") })
        assertTrue(lines.any { it.startsWith("INFO hub Bedroom AC is online again") })
        assertTrue("no DEBUG lines unless verbose", lines.none { it.startsWith("DEBUG") })
    }

    @Test
    fun tickRunsDueAutomationsAndReportsProblems() = runTest {
        hub.refresh()
        val coolDown = Automation(
            id = "a1",
            name = "Cool down",
            trigger = Trigger.TimeOfDay(18, 0),
            actions = listOf(
                Action.SetValues(ac, listOf(SetValue(Keys.POWER, Value.Bool(true)))),
                Action.SetValues("missing:lost", listOf(SetValue(Keys.POWER, Value.Bool(true)))),
                Action.Notify("Cooling", "Bedroom AC is on"),
            ),
        )
        repository.saveAutomation(coolDown)
        due = listOf(coolDown)
        val now = LocalDateTime.of(2026, 10, 10, 18, 0)

        hub.tick(now)

        assertEquals(true, hub.live.value.getValue(ac).state!!.bool(Keys.POWER))
        assertEquals(listOf("Cooling" to "Bedroom AC is on"), notifications)
        assertEquals(listOf(Triple("Cool down", "Old TV", DeviceIssue.NoConnector("missing"))), failures)
        assertEquals(now, hub.lastRuns.value["a1"])
        assertEquals(emptyMap<DeviceKey, DeviceState>(), seenPrevious)

        due = emptyList()
        hub.tick(now.plusMinutes(1))
        assertTrue("previous states are handed to the engine", seenPrevious!!.containsKey(ac))
    }
}
