@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.technlov.home.connectors.tuya

import com.technlov.home.core.DeviceCategory
import com.technlov.home.core.DeviceRef
import com.technlov.home.core.DeviceSession
import com.technlov.home.core.DeviceState
import com.technlov.home.core.Keys
import com.technlov.home.core.SetValue
import com.technlov.home.core.Value
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live connection: one socket per device for the session's life, pushes reaching [DeviceSession.updates],
 * replies matched to their requests with pushes interleaved, heartbeats, reconnects, and UPDATEDPS refreshes.
 * All on virtual time, against [FakeTuyaDevice].
 */
class TuyaLiveConnectionTest {
    private fun TestScope.connector(lan: FakeTuyaLan, log: RecordingLog = RecordingLog(verbose = false)) =
        TuyaConnector(lan, TuyaConfig(clock = { testScheduler.currentTime }), log, backgroundScope)

    private fun ref(dev: FakeTuyaDevice, version: String, extras: Map<String, String> = mapOf(TuyaConnector.EXTRA_CATEGORY to "cz")) =
        DeviceRef(
            connectorId = TuyaConnector.ID,
            id = dev.id,
            address = dev.host,
            name = "Test device",
            category = DeviceCategory.SWITCH,
            extras = mapOf(TuyaConnector.EXTRA_LOCAL_KEY to "0123456789abcdef", TuyaConnector.EXTRA_VERSION to version) + extras,
        )

    /** A plain switch (no meter readings): every read is a status query. */
    private fun switchDps(): LinkedHashMap<String, Any?> = linkedMapOf("1" to true, "9" to 0, "38" to "memory", "40" to false)

    private fun TestScope.collect(session: DeviceSession): MutableList<DeviceState> {
        val got = mutableListOf<DeviceState>()
        backgroundScope.launch { session.updates()!!.collect { got += it } }
        runCurrent()
        return got
    }

    // ---- pushes ----

    @Test fun aPushBetweenPollsReachesUpdates33() = runTest { pushBetweenPolls("3.3") }
    @Test fun aPushBetweenPollsReachesUpdates34() = runTest { pushBetweenPolls("3.4") }
    @Test fun aPushBetweenPollsReachesUpdates35() = runTest { pushBetweenPolls("3.5") }

    private suspend fun TestScope.pushBetweenPolls(version: String) {
        val dev = FakeTuyaDevice(versionText = version)
        val session = connector(FakeTuyaLan(dev)).open(ref(dev, version))
        assertTrue(session.read().online)
        val pushed = async { session.updates()!!.first { it.bool(Keys.POWER) == false } }
        runCurrent()
        advanceTimeBy(3_000) // between polls: nothing is asked
        dev.push(mapOf("1" to false)) // switched off on the plug itself
        val state = pushed.await()
        assertTrue(version, state.online)
        assertEquals("a whole state, not just the pushed dp", 103.3, state.number(TuyaKeys.POWER_W)!!, 1e-9)
        assertEquals(false, session.read().bool(Keys.POWER)) // and the next poll agrees
    }

    @Test fun aPushIsNotTakenForTheReply33() = runTest { pushIsNotTheReply("3.3") }
    @Test fun aPushIsNotTakenForTheReply34() = runTest { pushIsNotTheReply("3.4") }
    @Test fun aPushIsNotTakenForTheReply35() = runTest { pushIsNotTheReply("3.5") }

    /** The device reports a change just as we ask for the status: the push goes to updates(), the query waits on. */
    private suspend fun TestScope.pushIsNotTheReply(version: String) {
        val dev = FakeTuyaDevice(versionText = version, dps = switchDps())
        val session = connector(FakeTuyaLan(dev)).open(ref(dev, version))
        val pushes = collect(session)
        dev.dps["9"] = 120 // changed on the device; only the status reply carries it
        dev.pushBeforeNextReply = mapOf("40" to true) // the child lock pressed at that moment
        dev.holdNextReply = true

        val reading = async { session.read() }
        runCurrent()
        assertFalse("a STATUS push must not answer the status query", reading.isCompleted)
        assertEquals(true, pushes.last().bool(TuyaKeys.CHILD_LOCK))

        dev.releaseHeldReply()
        val state = reading.await()
        assertTrue(state.online)
        assertEquals("the reply's value", 120.0, state.number(TuyaKeys.COUNTDOWN)!!, 0.0)
        assertEquals(true, state.bool(TuyaKeys.CHILD_LOCK))
        // Still in step: the next request gets its own reply.
        dev.dps["9"] = 240
        assertEquals(240.0, session.read().number(TuyaKeys.COUNTDOWN)!!, 0.0)
    }

    /** Below 3.5 the device echoes the sequence number: a late reply to an earlier query is not this query's answer. */
    @Test
    fun aLateReplyToAnEarlierRequestIsNotTakenForTheReply() = runTest {
        val dev = FakeTuyaDevice(versionText = "3.3", dps = switchDps())
        val session = connector(FakeTuyaLan(dev)).open(ref(dev, "3.3"))
        dev.staleReplyBeforeNextReply = linkedMapOf("1" to true, "9" to 5, "38" to "memory", "40" to false)
        dev.dps["9"] = 120
        dev.holdNextReply = true

        val reading = async { session.read() }
        runCurrent()
        assertFalse("the reply to an earlier sequence number must not answer this query", reading.isCompleted)
        dev.releaseHeldReply()
        assertEquals(120.0, reading.await().number(TuyaKeys.COUNTDOWN)!!, 0.0)
    }

    /**
     * A real device acknowledges a command with an empty frame, then reports the change as a STATUS push -- which
     * can arrive after our read-back query went out. It used to be read as that query's reply, and every later reply
     * then came one request behind.
     */
    @Test
    fun aCommandsLateReportIsNotTakenForTheReadBack() = runTest {
        val dev = FakeTuyaDevice(versionText = "3.3", dps = switchDps())
        val session = connector(FakeTuyaLan(dev)).open(ref(dev, "3.3"))
        dev.reportCommandsLate = true
        dev.holdNextReply = true // the read-back's reply
        dev.dps["9"] = 300

        val applying = async { session.apply(listOf(SetValue(Keys.POWER, Value.Bool(false)))) }
        runCurrent()
        assertFalse("the command's STATUS report must not answer the read-back query", applying.isCompleted)
        dev.releaseHeldReply()
        val after = applying.await()
        assertEquals(false, after.bool(Keys.POWER))
        assertEquals(300.0, after.number(TuyaKeys.COUNTDOWN)!!, 0.0)

        dev.dps["9"] = 600 // and the next reads stay in step
        assertEquals(600.0, session.read().number(TuyaKeys.COUNTDOWN)!!, 0.0)
        assertEquals(4, session.read().values.size)
    }

    @Test
    fun aDeviceThatAnswersQueriesWithStatusFramesStillWorks() = runTest {
        val dev = FakeTuyaDevice(dps = switchDps()).apply { queriesAnsweredWithStatus = true }
        val log = RecordingLog(verbose = false)
        val session = connector(FakeTuyaLan(dev), log).open(ref(dev, "3.3"))
        assertEquals(4, session.capabilities.size)
        assertTrue(log.text.contains("answers status queries with STATUS frames"))
        val before = currentTime
        assertTrue(session.read().online)
        assertEquals("no more waiting once learned", before, currentTime)
    }

    // ---- keeping the connection ----

    @Test
    fun heartbeatsKeepAnIdleConnectionOpen() = runTest {
        val dev = FakeTuyaDevice(dps = switchDps())
        val lan = FakeTuyaLan(dev)
        val session = connector(lan).open(ref(dev, "3.3"))
        assertEquals(0, dev.heartbeats)
        advanceTimeBy(10_001)
        assertEquals(1, dev.heartbeats)
        advanceTimeBy(20_000)
        assertEquals("one every 10 s while idle", 3, dev.heartbeats)
        assertEquals("the connection stays up", 1, lan.connects)
        // A busy connection needs none: polls every 5 s keep it alive.
        repeat(4) {
            advanceTimeBy(5_000)
            assertTrue(session.read().online)
        }
        assertEquals(3, dev.heartbeats)
    }

    @Test
    fun aDroppedConnectionIsReopenedAndPushesResume() = runTest {
        val dev = FakeTuyaDevice(versionText = "3.4", dps = switchDps())
        val lan = FakeTuyaLan(dev)
        val log = RecordingLog(verbose = false)
        val session = connector(lan, log).open(ref(dev, "3.4"))
        val pushes = collect(session)

        dev.hangUp() // the device closed the socket (a reboot, a Wi-Fi drop)
        runCurrent()
        assertEquals(0, dev.openConnections)
        assertTrue(log.text.contains("connection dropped"))
        advanceTimeBy(1_001) // the first retry comes after 1 s
        assertEquals(2, lan.connects)
        assertEquals(1, dev.openConnections)
        assertTrue(log.text.contains("connection restored"))
        assertTrue("the state is read again on reconnecting", pushes.isNotEmpty())

        dev.push(mapOf("1" to false))
        runCurrent()
        assertEquals(false, pushes.last().bool(Keys.POWER))
        assertEquals("never two sockets to one device", 1, lan.maxOpenToOneDevice)
    }

    @Test
    fun aPollDuringADropReconnectsWithoutASecondSocket() = runTest {
        val dev = FakeTuyaDevice(dps = switchDps())
        val lan = FakeTuyaLan(dev)
        val session = connector(lan).open(ref(dev, "3.3"))
        dev.hangUp()
        runCurrent()
        assertTrue(session.read().online) // reconnects at once instead of waiting for the keep-alive
        advanceTimeBy(5_000) // the keep-alive's retry then finds it connected
        assertEquals(2, lan.connects)
        assertEquals(1, lan.maxOpenToOneDevice)
    }

    @Test
    fun aSilentConnectionIsGivenUpAndReopened() = runTest {
        val dev = FakeTuyaDevice(dps = switchDps())
        val lan = FakeTuyaLan(dev)
        val log = RecordingLog(verbose = false)
        val session = connector(lan, log).open(ref(dev, "3.3"))
        dev.silent = true // hung: the socket stays open but nothing comes back, not even heartbeats
        advanceTimeBy(30_001) // two unanswered heartbeats, then three silent intervals
        assertEquals(2, dev.heartbeats)
        assertTrue(log.text.contains("nothing heard for 30 s; reconnecting"))
        advanceTimeBy(1_000) // the first retry, 1 s later: connected, but the status query goes unanswered
        assertEquals(2, lan.connects)
        dev.silent = false
        advanceTimeBy(20_000)
        assertTrue(log.text.contains("connection restored"))
        assertTrue(session.read().online)
        assertEquals(1, lan.maxOpenToOneDevice)
    }

    @Test
    fun aDeviceThatHangsUpAfterEveryAnswerIsNotReconnectedToEverySecond() = runTest {
        val dev = FakeTuyaDevice(dps = switchDps()).apply { hangUpAfterEachAnswer = true }
        val lan = FakeTuyaLan(dev)
        val session = connector(lan).open(ref(dev, "3.3"))
        advanceTimeBy(120_000)
        assertTrue("reconnects back off (1, 2, 4 ... s): ${lan.connects} in 2 minutes", lan.connects in 2..8)
        assertTrue("a poll still gets its answer on a fresh connection", session.read().online)
        assertEquals(1, lan.maxOpenToOneDevice)
    }

    @Test
    fun closingTheSessionClosesTheSocketAndStopsTheKeepAlive() = runTest {
        val dev = FakeTuyaDevice(dps = switchDps())
        val lan = FakeTuyaLan(dev)
        val session = connector(lan).open(ref(dev, "3.3"))
        session.close()
        assertEquals(0, dev.openConnections)
        advanceTimeBy(60_000)
        assertEquals(0, dev.heartbeats)
        assertEquals("no reconnect after a close", 1, lan.connects)
        val after = session.read()
        assertFalse(after.online)
        assertNotNull(after.error)
        assertEquals(1, lan.connects)
    }

    // ---- UPDATEDPS refresh ----

    @Test
    fun aMeteringPlugAlternatesUpdateDpsWithStatusQueries() = runTest {
        val dev = FakeTuyaDevice(versionText = "3.3") // reports 18/19/20
        val session = connector(FakeTuyaLan(dev)).open(ref(dev, "3.3"))
        val pushes = collect(session)
        dev.received.clear()
        dev.measure("19", 2000) // the load changed; the plug reports it only when asked to measure

        session.read()
        runCurrent()
        assertEquals(listOf(TuyaCmd.UPDATEDPS), dev.received.toList())
        assertEquals(listOf(listOf(18, 19, 20)), dev.updateDpsRequests)
        assertEquals("the fresh reading arrives as a push", 200.0, pushes.last().number(TuyaKeys.POWER_W)!!, 1e-9)

        val second = session.read()
        assertEquals(listOf(TuyaCmd.UPDATEDPS, TuyaCmd.DP_QUERY), dev.received.toList())
        assertEquals(200.0, second.number(TuyaKeys.POWER_W)!!, 1e-9)
        session.read()
        assertEquals(listOf(TuyaCmd.UPDATEDPS, TuyaCmd.DP_QUERY, TuyaCmd.UPDATEDPS), dev.received.toList())
    }

    @Test
    fun aDeviceWithoutMeterReadingsGetsNoUpdateDps() = runTest {
        val dev = FakeTuyaDevice(dps = switchDps())
        val session = connector(FakeTuyaLan(dev)).open(ref(dev, "3.3"))
        dev.received.clear()
        repeat(3) { session.read() }
        assertEquals(List(3) { TuyaCmd.DP_QUERY }, dev.received.toList())
        assertTrue(dev.updateDpsRequests.isEmpty())
    }

    @Test
    fun theOwonMeterRefreshesItsForcedDps() = runTest {
        val dps = linkedMapOf<String, Any?>()
        for (base in listOf(100, 110, 120)) {
            dps["${base + 1}"] = 1270; dps["${base + 2}"] = 1500; dps["${base + 3}"] = 190
            dps["${base + 4}"] = 99; dps["${base + 6}"] = 12_345; dps["${base + 7}"] = 0
        }
        dps["131"] = 99_999; dps["132"] = 4_500; dps["133"] = 570; dps["135"] = 60; dps["136"] = 350; dps["139"] = 0
        val dev = FakeTuyaDevice(versionText = "3.5", dps = dps)
        val extras = mapOf(TuyaConnector.EXTRA_CATEGORY to "zndb", TuyaConnector.EXTRA_PRODUCT_ID to "9rjjf6rdkca9woju")
        val session = connector(FakeTuyaLan(dev)).open(ref(dev, "3.5", extras))
        dev.measure("133", 3_600)
        session.read()
        assertEquals(listOf(listOf(101, 102, 103, 111, 112, 113, 121, 122, 123, 132, 133)), dev.updateDpsRequests)
        assertEquals(3_600.0, session.read().number("power_total")!!, 0.0)
    }

    @Test
    fun aDeviceThatIgnoresUpdateDpsIsReadByQueryAlone() = runTest {
        val dev = FakeTuyaDevice(versionText = "3.3").apply { answersUpdateDps = false }
        val log = RecordingLog(verbose = false)
        val session = connector(FakeTuyaLan(dev), log).open(ref(dev, "3.3"))
        repeat(8) { assertTrue(session.read().online) } // each unanswered UPDATEDPS falls back to a query
        assertEquals("given up after three misses", 3, dev.updateDpsRequests.size)
        assertTrue(log.text.contains("does not answer UPDATEDPS"))
    }
}
