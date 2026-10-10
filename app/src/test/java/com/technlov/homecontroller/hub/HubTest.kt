package com.technlov.homecontroller.hub

import com.technlov.home.core.Action
import com.technlov.home.core.Automation
import com.technlov.home.core.AutomationEngine
import com.technlov.home.core.ConnectorRegistry
import com.technlov.home.core.DefaultAutomationEngine
import com.technlov.home.core.DeviceKey
import com.technlov.home.core.DeviceState
import com.technlov.home.core.EventLog
import com.technlov.home.core.Keys
import com.technlov.home.core.SetValue
import com.technlov.home.core.SunClock
import com.technlov.home.core.SunEvent
import com.technlov.home.core.Trigger
import com.technlov.home.core.Value
import com.technlov.homecontroller.FakeConnector
import com.technlov.homecontroller.InMemoryDataStore
import com.technlov.homecontroller.climateCapabilities
import com.technlov.homecontroller.data.HomeData
import com.technlov.homecontroller.data.HomeLocation
import com.technlov.homecontroller.data.HomeRepository
import com.technlov.homecontroller.data.SavedDevice
import com.technlov.homecontroller.data.sunClock
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
import java.time.ZoneId
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
    private var seenPreviousCheck: LocalDateTime? = null
    private val engine = object : AutomationEngine {
        override fun due(
            now: LocalDateTime,
            automations: List<Automation>,
            states: Map<DeviceKey, DeviceState>,
            previousStates: Map<DeviceKey, DeviceState>,
            lastRun: Map<String, LocalDateTime>,
            previousCheck: LocalDateTime?,
            sun: SunClock,
        ): List<Automation> {
            seenPrevious = previousStates
            seenPreviousCheck = previousCheck
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
        assertEquals("the run is saved", now, store.value.lastRuns["a1"])
        assertEquals(emptyMap<DeviceKey, DeviceState>(), seenPrevious)
        assertNull("nothing checked before the first tick", seenPreviousCheck)

        due = emptyList()
        hub.tick(now.plusMinutes(1))
        assertTrue("previous states are handed to the engine", seenPrevious!!.containsKey(ac))
        assertEquals("so is the previous check", now, seenPreviousCheck)
    }

    // ---- catch-up: the real engine, with runs saved in the repository ----

    private val morning = Automation("m", "Morning", trigger = Trigger.TimeOfDay(7, 30), actions = listOf(Action.Notify("Morning", "x")))

    private fun hubWithEngine() =
        Hub(ConnectorRegistry(listOf(connector)), repository, DefaultAutomationEngine(), events, ioContext = EmptyCoroutineContext)

    private fun at(h: Int, m: Int, day: Int = 12) = LocalDateTime.of(2026, 10, day, h, m)

    @Test
    fun aTimeMissedByThreeMinutesRunsOnceWhenTheHubWakes() = runTest {
        repository.saveAutomation(morning)
        val hub = hubWithEngine()
        hub.tick(at(7, 29))
        hub.tick(at(7, 33)) // Android dozed through 07:30
        hub.tick(at(7, 34))
        hub.tick(at(7, 35))
        assertEquals(listOf("Morning" to "x"), notifications)
        assertEquals(at(7, 33), store.value.lastRuns["m"])
    }

    @Test
    fun aTimeMissedByThirtyMinutesIsSkipped() = runTest {
        repository.saveAutomation(morning)
        val hub = hubWithEngine()
        hub.tick(at(7, 29))
        hub.tick(at(8, 0))
        hub.tick(at(8, 1))
        assertTrue(notifications.isEmpty())
        assertNull(store.value.lastRuns["m"])
    }

    @Test
    fun aRestartAfterTheRunDoesNotRepeatIt() = runTest {
        repository.saveAutomation(morning)
        hubWithEngine().tick(at(7, 30))
        // The app restarts: a new hub, nothing in memory, the saved run in the repository.
        val restarted = hubWithEngine()
        restarted.tick(at(7, 32))
        restarted.tick(at(7, 33))
        assertEquals(1, notifications.size)
    }

    @Test
    fun aRestartThatMissedTheTimeCatchesItUp() = runTest {
        repository.saveAutomation(morning)
        hubWithEngine().tick(at(7, 30, day = 11)) // yesterday
        // Today the app was ended at 07:28 and is back at 07:33.
        val restarted = hubWithEngine()
        restarted.tick(at(7, 33))
        restarted.tick(at(7, 34))
        assertEquals(2, notifications.size)
        assertEquals(at(7, 33), store.value.lastRuns["m"])
    }

    // ---- sunrise / sunset ----

    @Test
    fun aSunsetScheduleWaitsForTheHomeLocationThenRunsAtSunsetMinusItsOffset() = runTest {
        val london = ZoneId.of("Europe/London")
        val hub = Hub(
            ConnectorRegistry(listOf(connector)), repository, DefaultAutomationEngine(), events,
            ioContext = EmptyCoroutineContext, sunClock = { it.sunClock(london) },
        )
        val dusk = Automation("dusk", "Dusk", trigger = Trigger.Sun(SunEvent.SUNSET, -15), actions = listOf(Action.Notify("Dusk", "x")))
        repository.saveAutomation(dusk)
        hub.tick(LocalDateTime.of(2026, 6, 21, 21, 6))
        assertTrue("no location yet: a sun schedule cannot run", notifications.isEmpty())

        // Greenwich, rounded to 51.48, 0.00: on 22 June the sun sets at 21:21, so the schedule is due at 21:06.
        repository.updateSettings { it.copy(homeLocation = HomeLocation.of(51.4779, -0.0015)) }
        hub.tick(LocalDateTime.of(2026, 6, 22, 21, 5))
        assertTrue(notifications.isEmpty())
        hub.tick(LocalDateTime.of(2026, 6, 22, 21, 6))
        hub.tick(LocalDateTime.of(2026, 6, 22, 21, 7))
        assertEquals(listOf("Dusk" to "x"), notifications)
    }

    // ---- run once ----

    private val powerOn = Action.SetValues(ac, listOf(SetValue(Keys.POWER, Value.Bool(true))))

    @Test
    fun aRunOnceAutomationTurnsItselfOffAfterASuccessfulRun() = runTest {
        hub.refresh()
        val once = Automation("once", "Once", trigger = Trigger.TimeOfDay(18, 0), actions = listOf(powerOn), runOnce = true)
        val daily = Automation("daily", "Daily", trigger = Trigger.TimeOfDay(18, 0), actions = listOf(powerOn))
        repository.saveAutomation(once)
        repository.saveAutomation(daily)
        due = listOf(once, daily)

        hub.tick(LocalDateTime.of(2026, 10, 10, 18, 0))

        assertEquals(true, hub.live.value.getValue(ac).state!!.bool(Keys.POWER))
        assertFalse("run once: off after it ran", store.value.automation("once")!!.enabled)
        assertTrue("an ordinary automation stays on", store.value.automation("daily")!!.enabled)
    }

    @Test
    fun aRunOnceAutomationStaysOnWhenADeviceDidNotTakeTheChange() = runTest {
        hub.refresh()
        connector.failApplies = true
        val once = Automation("once", "Once", trigger = Trigger.TimeOfDay(18, 0), actions = listOf(powerOn), runOnce = true)
        repository.saveAutomation(once)
        due = listOf(once)

        hub.tick(LocalDateTime.of(2026, 10, 10, 18, 0))

        assertEquals(listOf(Triple("Once", "Bedroom AC", DeviceIssue.Failed("refused"))), failures)
        assertTrue("tries again next time", store.value.automation("once")!!.enabled)
    }

    @Test
    fun runNowReportsWhetherEveryDeviceTookTheChange() = runTest {
        hub.refresh()
        val once = Automation("once", "Once", trigger = Trigger.TimeOfDay(18, 0), actions = listOf(powerOn), runOnce = true)
        repository.saveAutomation(once)
        assertTrue(hub.execute(once))
        assertTrue("Run now is a try-out: a run-once automation stays on", store.value.automation("once")!!.enabled)
        val lost = Action.SetValues("missing:lost", listOf(SetValue(Keys.POWER, Value.Bool(true))))
        assertFalse(hub.execute(once.copy(actions = listOf(powerOn, lost, Action.Notify("t", "x")))))
    }
}
