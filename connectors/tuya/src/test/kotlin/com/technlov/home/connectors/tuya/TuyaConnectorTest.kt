package com.technlov.home.connectors.tuya

import com.technlov.home.core.ConnectorException
import com.technlov.home.core.DeviceCategory
import com.technlov.home.core.DeviceRef
import com.technlov.home.core.Keys
import com.technlov.home.core.SetValue
import com.technlov.home.core.Value
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TuyaConnectorTest {
    private fun connector(lan: FakeTuyaLan, log: RecordingLog = RecordingLog(verbose = false)) =
        TuyaConnector(lan, TuyaConfig(clock = { 1_000_000L }), log)

    private fun ref(dev: FakeTuyaDevice, version: String, extras: Map<String, String> = emptyMap()) = DeviceRef(
        connectorId = TuyaConnector.ID,
        id = dev.id,
        address = dev.host,
        name = "Test plug",
        category = DeviceCategory.SWITCH,
        extras = buildMap {
            put(TuyaConnector.EXTRA_LOCAL_KEY, "0123456789abcdef")
            put(TuyaConnector.EXTRA_VERSION, version)
            put(TuyaConnector.EXTRA_CATEGORY, "cz")
            putAll(extras)
        },
    )

    private suspend inline fun <reified T : Throwable> expect(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw e
        }
        fail("expected ${T::class.simpleName}")
        throw IllegalStateException()
    }

    // ---- full status + set cycle, one per protocol version ----

    @Test fun fullFlow33() = runTest { fullFlow("3.3") }
    @Test fun fullFlow34() = runTest { fullFlow("3.4") }
    @Test fun fullFlow35() = runTest { fullFlow("3.5") }

    private suspend fun fullFlow(version: String) {
        val dev = FakeTuyaDevice(versionText = version)
        val lan = FakeTuyaLan(dev)
        val session = connector(lan).open(ref(dev, version))

        val keys = session.capabilities.map { it.key }
        assertTrue("power missing for $version", Keys.POWER in keys)
        assertTrue("power monitoring missing for $version", TuyaKeys.POWER_W in keys)

        val s1 = session.read()
        assertTrue(version, s1.online)
        assertEquals(true, s1.bool(Keys.POWER))
        assertEquals(103.3, s1.number(TuyaKeys.POWER_W)!!, 1e-9)
        assertEquals(230.1, s1.number(TuyaKeys.VOLTAGE)!!, 1e-9)
        assertEquals("memory", s1.text(TuyaKeys.RELAY_STATUS))

        val s2 = session.apply(listOf(SetValue(Keys.POWER, Value.Bool(false)), SetValue(TuyaKeys.COUNTDOWN, Value.Num(60.0))))
        assertEquals(false, s2.bool(Keys.POWER))
        assertEquals(60.0, s2.number(TuyaKeys.COUNTDOWN)!!, 0.0)
        assertEquals(false, dev.dps["1"])
        assertEquals(60, dev.dps["9"])
        assertEquals(mapOf("1" to false, "9" to 60), dev.controlCommands.last())

        // Native on-board timer: set and read back (works even though the device did not report it at open).
        val tuya = session as TuyaSession
        tuya.setInching(listOf(TuyaTimers.Inching(channel = 0, enabled = true, seconds = 30)))
        assertEquals(listOf(TuyaTimers.Inching(0, true, 30)), tuya.currentInching())
    }

    // ---- key safety and logging ----

    @Test
    fun theLocalKeyAndSessionKeyNeverAppearInTheLog() = runTest {
        val dev = FakeTuyaDevice(versionText = "3.4")
        val log = RecordingLog(verbose = true)
        val session = connector(FakeTuyaLan(dev), log).open(ref(dev, "3.4"))
        session.read()
        session.apply(listOf(SetValue(Keys.POWER, Value.Bool(false))))

        val t = log.text
        assertFalse("local key leaked", t.contains("0123456789abcdef"))
        assertTrue(t.contains("session key received"))
        assertTrue(t.contains("connected (protocol 3.4, session key negotiated)"))
        assertTrue(t.contains("opened (protocol 3.4"))
        assertTrue(t.contains("command sent: 1=false"))
    }

    // ---- silence, reconnect and offline ----

    @Test
    fun aDroppedReadReconnectsAndRecovers() = runTest {
        val dev = FakeTuyaDevice(versionText = "3.3")
        val lan = FakeTuyaLan(dev)
        val session = connector(lan).open(ref(dev, "3.3"))
        assertTrue(session.read().online)
        val connectsBefore = lan.connects
        dev.dropNext = 2 // drop the status, then the status after the reconnect
        val s = session.read()
        assertFalse(s.online)
        assertNotNull(s.error)
        assertEquals(true, s.bool(Keys.POWER)) // last known values stay
        assertTrue(lan.connects > connectsBefore) // it tried to reconnect
        assertTrue(session.read().online) // and it recovers
    }

    // ---- validation ----

    @Test
    fun openNeedsA16CharKeyAndASupportedVersion() = runTest {
        val dev = FakeTuyaDevice()
        val lan = FakeTuyaLan(dev)
        val noKey = ref(dev, "3.3").copy(extras = ref(dev, "3.3").extras - TuyaConnector.EXTRA_LOCAL_KEY)
        assertTrue(expect<ConnectorException> { connector(lan).open(noKey) }.message!!.contains("local key"))
        val old = ref(dev, "3.1")
        assertTrue(expect<ConnectorException> { connector(lan).open(old) }.message!!.contains("not supported"))
    }

    @Test
    fun badChangesAreRefusedBeforeSending() = runTest {
        val dev = FakeTuyaDevice()
        val session = connector(FakeTuyaLan(dev)).open(ref(dev, "3.3"))
        val before = dev.controlCommands.size
        expect<ConnectorException> { session.apply(listOf(SetValue("not_a_key", Value.Bool(true)))) }
        expect<ConnectorException> { session.apply(listOf(SetValue(TuyaKeys.POWER_W, Value.Num(5.0)))) } // read-only
        assertEquals(before, dev.controlCommands.size)
    }

    @Test
    fun wrongConnectorIdIsRejected() = runTest {
        val dev = FakeTuyaDevice()
        val alien = ref(dev, "3.3").copy(connectorId = "gree")
        expect<ConnectorException> { connector(FakeTuyaLan(dev)).open(alien) }
    }

    // ---- device22 fallback ----

    @Test
    fun device22FallbackIsDetectedAndWorks() = runTest {
        val dev = FakeTuyaDevice(versionText = "3.3", firstQueryDataUnvalid = true)
        val log = RecordingLog(verbose = true)
        val session = connector(FakeTuyaLan(dev), log).open(ref(dev, "3.3"))
        assertTrue(session.read().online)
        assertEquals(true, session.read().bool(Keys.POWER))
        assertTrue(log.text.contains("device22"))
    }
}
