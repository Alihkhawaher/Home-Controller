package com.technlov.home.connectors.gree

import com.technlov.home.core.Capability
import com.technlov.home.core.ConnectorException
import com.technlov.home.core.DeviceSession
import com.technlov.home.core.Keys
import com.technlov.home.core.LocalNetwork
import com.technlov.home.core.SetValue
import com.technlov.home.core.Value
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** "Beep on commands": the app's per-device setting; off adds the buzzer flags to every command. */
class GreeBeepTest {
    private val net = LocalNetwork("192.0.2.255", "192.0.2.")

    private fun connector(unit: FakeGreeUnit, log: RecordingLog = RecordingLog()) =
        GreeConnector(FakeLan(unit), GreeConfig(sweep = false), log)

    private suspend fun open(gree: GreeConnector): DeviceSession = gree.open(gree.discover(net, 500).single())

    private fun opt(cmd: JSONObject) = cmd.getJSONArray("opt").toString()
    private fun p(cmd: JSONObject) = cmd.getJSONArray("p").toString()

    @Test
    fun onByDefaultWithCommandsAsBefore() = runTest {
        val unit = FakeGreeUnit()
        val s = open(connector(unit))
        val cap = s.capabilities.single { it.key == GreeKeys.BEEP } as Capability.Toggle
        assertEquals("Beep on commands", cap.label)
        assertTrue(cap.writable)
        assertEquals(true, s.read().bool(GreeKeys.BEEP))
        assertNull(s.device.extras[GreeConnector.EXTRA_BEEP])
        s.apply(listOf(SetValue(Keys.POWER, Value.Bool(true))))
        val cmd = unit.commands.single()
        assertEquals("""["Pow"]""", opt(cmd))   // no buzzer flags: exactly the command sent before this setting
        assertEquals("[1]", p(cmd))
    }

    @Test
    fun offAddsBothFlagsToEveryCommand() = runTest {
        val unit = FakeGreeUnit()
        val log = RecordingLog()
        val s = open(connector(unit, log))
        val st = s.apply(listOf(SetValue(GreeKeys.BEEP, Value.Bool(false))))
        assertTrue(unit.commands.isEmpty())                     // the app's own setting: nothing goes to the unit
        assertEquals(false, st.bool(GreeKeys.BEEP))
        assertEquals("0", s.device.extras[GreeConnector.EXTRA_BEEP])

        s.apply(listOf(SetValue(Keys.POWER, Value.Bool(true))))
        s.apply(listOf(SetValue(Keys.TARGET_TEMPERATURE, Value.Num(23.0))))
        val (first, second) = unit.commands
        assertEquals("""["Pow","Buzzer_ON_OFF","BuzzerCtrl"]""", opt(first))
        assertEquals("[1,1,0]", p(first))
        assertEquals("""["SetTem","TemUn","TemRec","Buzzer_ON_OFF","BuzzerCtrl"]""", opt(second))
        assertEquals("[23,0,0,1,0]", p(second))

        val after = s.read()
        assertEquals(false, after.bool(GreeKeys.BEEP))
        assertEquals(true, after.bool(Keys.POWER))
        assertTrue(log.text.contains("beep on commands off"))
        assertTrue(log.text.contains("command sent: Pow=1, Buzzer_ON_OFF=1, BuzzerCtrl=0"))
        // The flags are never asked for in a status request.
        assertTrue(unit.inner.filter { it.optString("t") == "status" }.none { it.getJSONArray("cols").toString().contains("Buzzer") })
    }

    @Test
    fun backOnSendsCommandsAsBefore() = runTest {
        val unit = FakeGreeUnit()
        val s = open(connector(unit))
        s.apply(listOf(SetValue(GreeKeys.BEEP, Value.Bool(false))))
        val st = s.apply(listOf(SetValue(GreeKeys.BEEP, Value.Text("on"))))
        assertEquals(true, st.bool(GreeKeys.BEEP))
        assertEquals("1", s.device.extras[GreeConnector.EXTRA_BEEP])   // written out, so it replaces a saved "0"
        s.apply(listOf(SetValue(Keys.DISPLAY_LIGHT, Value.Bool(false))))
        assertEquals("""["Lig"]""", opt(unit.commands.single()))
    }

    @Test
    fun aBatchWithTheBeepChangeUsesItForItsOwnCommand() = runTest {
        val unit = FakeGreeUnit()
        val s = open(connector(unit))
        val st = s.apply(listOf(SetValue(Keys.POWER, Value.Bool(true)), SetValue(GreeKeys.BEEP, Value.Bool(false))))
        assertEquals("""["Pow","Buzzer_ON_OFF","BuzzerCtrl"]""", opt(unit.commands.single()))
        assertEquals(false, st.bool(GreeKeys.BEEP))
        assertEquals(true, st.bool(Keys.POWER))
        assertEquals("0", s.device.extras[GreeConnector.EXTRA_BEEP])
    }

    @Test
    fun aBatchTheUnitDoesNotTakeLeavesTheBeepAsItWas() = runTest {
        val unit = FakeGreeUnit()
        val s = open(connector(unit))
        unit.dropNext = 2 // both sends of the command lost
        try {
            s.apply(listOf(SetValue(Keys.POWER, Value.Bool(true)), SetValue(GreeKeys.BEEP, Value.Bool(false))))
            fail("expected the command to fail")
        } catch (e: ConnectorException) {
            assertTrue(e.message!!, e.message!!.contains("no answer"))
        }
        assertEquals(true, s.read().bool(GreeKeys.BEEP))
        assertNull(s.device.extras[GreeConnector.EXTRA_BEEP])
    }

    @Test
    fun aBadValueIsRefusedBeforeSending() = runTest {
        val unit = FakeGreeUnit()
        val s = open(connector(unit))
        try {
            s.apply(listOf(SetValue(Keys.POWER, Value.Bool(true)), SetValue(GreeKeys.BEEP, Value.Text("loud"))))
            fail("expected a refusal")
        } catch (e: ConnectorException) {
            assertTrue(e.message!!, e.message!!.contains("on/off"))
        }
        assertTrue(unit.commands.isEmpty())
        assertEquals(true, s.read().bool(GreeKeys.BEEP))
    }

    @Test
    fun theSavedSettingIsReadAtOpen() = runTest {
        val unit = FakeGreeUnit()
        val log = RecordingLog()
        val gree = connector(unit, log)
        val found = gree.discover(net, 500).single()
        val s = gree.open(found.copy(extras = found.extras + (GreeConnector.EXTRA_BEEP to "0")))
        assertEquals(false, s.read().bool(GreeKeys.BEEP))
        assertEquals("0", s.device.extras[GreeConnector.EXTRA_BEEP])
        assertTrue(log.text, log.text.contains("capabilities, beep off)"))
        s.apply(listOf(SetValue(Keys.POWER, Value.Bool(true))))
        assertEquals("""["Pow","Buzzer_ON_OFF","BuzzerCtrl"]""", opt(unit.commands.single()))
        // Anything but "0" is on.
        for (v in listOf("1", "", "yes")) {
            val on = gree.open(found.copy(extras = found.extras + (GreeConnector.EXTRA_BEEP to v)))
            assertEquals(v, true, on.read().bool(GreeKeys.BEEP))
        }
    }

    @Test
    fun aReopenedSessionKeepsTheChoiceUntilTheAppSavesIt() = runTest {
        val unit = FakeGreeUnit()
        val gree = connector(unit)
        val saved = gree.discover(net, 500).single() // as the app saved it: no beep extra
        gree.open(saved).apply(listOf(SetValue(GreeKeys.BEEP, Value.Bool(false))))
        // The hub opens the unit again from the saved device (after it went silent, say).
        val again = gree.open(saved)
        assertEquals(false, again.read().bool(GreeKeys.BEEP))
        assertEquals("0", again.device.extras[GreeConnector.EXTRA_BEEP]) // the app saves session.device at open
        again.apply(listOf(SetValue(Keys.POWER, Value.Bool(true))))
        assertEquals("""["Pow","Buzzer_ON_OFF","BuzzerCtrl"]""", opt(unit.commands.single()))
        // A new connector (the app started again) goes by what was saved.
        assertEquals(true, connector(unit).open(saved).read().bool(GreeKeys.BEEP))
    }

    @Test
    fun theChoiceIsPerUnit() = runTest {
        val a = FakeGreeUnit()
        val b = FakeGreeUnit(host = "192.0.2.11", mac = "f0fe6b000002")
        val gree = GreeConnector(FakeLan(a, b), GreeConfig(sweep = false))
        val (refA, refB) = gree.discover(net, 500).sortedBy { it.address }
        gree.open(refA).apply(listOf(SetValue(GreeKeys.BEEP, Value.Bool(false))))
        assertEquals(false, gree.open(refA).read().bool(GreeKeys.BEEP))
        val sb = gree.open(refB)
        assertEquals(true, sb.read().bool(GreeKeys.BEEP))
        sb.apply(listOf(SetValue(Keys.POWER, Value.Bool(true))))
        assertEquals("""["Pow"]""", opt(b.commands.single()))
    }

    @Test
    fun aUnitThatDoesNotKnowTheFlagsStillTakesTheCommand() = runTest {
        // Real modules take a command with names they do not know (r 200) and echo it back; some may leave them out.
        for (echo in listOf(true, false)) {
            val unit = FakeGreeUnit().apply {
                unknownColumns += GreeModel.BUZZER_COLUMNS
                echoUnknownInCmd = echo
            }
            val s = open(connector(unit))
            s.apply(listOf(SetValue(GreeKeys.BEEP, Value.Bool(false))))
            val st = s.apply(listOf(SetValue(Keys.POWER, Value.Bool(true)), SetValue(Keys.TARGET_TEMPERATURE, Value.Num(25.0))))
            assertEquals("""["Pow","SetTem","TemUn","TemRec","Buzzer_ON_OFF","BuzzerCtrl"]""", opt(unit.commands.single()))
            assertTrue(st.online)
            assertEquals(true, st.bool(Keys.POWER))
            assertEquals(25.0, st.number(Keys.TARGET_TEMPERATURE)!!, 0.0)
            assertEquals(false, st.bool(GreeKeys.BEEP))
            assertEquals(1, unit.state["Pow"])
            assertFalse(unit.state.keys.any { it in GreeModel.BUZZER_COLUMNS }) // ignored by the unit
            assertTrue(s.read().online)
        }
    }

    @Test
    fun modelPieces() {
        assertEquals(emptyMap<String, Int>(), GreeModel.buzzerColumns(true))
        assertEquals(listOf("Buzzer_ON_OFF" to 1, "BuzzerCtrl" to 0), GreeModel.buzzerColumns(false).toList())
        // The last beep change in a batch wins; a batch without one leaves it alone.
        val twice = listOf(SetValue(GreeKeys.BEEP, Value.Bool(true)), SetValue(GreeKeys.BEEP, Value.Text("off")))
        assertEquals(false, GreeModel.beepSetting(twice))
        assertNull(GreeModel.beepSetting(listOf(SetValue(Keys.POWER, Value.Bool(true)))))
        // No column of its own.
        assertEquals(emptyMap<String, Int>(), GreeModel.encode(listOf(SetValue(GreeKeys.BEEP, Value.Bool(false)))))
        // Declared on every unit, whatever columns it answers.
        assertTrue(GreeModel.capabilities(setOf("Pow")).any { it.key == GreeKeys.BEEP })
        assertEquals(GreeKeys.BEEP, GreeModel.capabilities().last().key)
        // The extra's spelling.
        assertTrue(GreeConnector.beepFrom(emptyMap()))
        assertFalse(GreeConnector.beepFrom(mapOf(GreeConnector.EXTRA_BEEP to "0")))
        assertEquals("0", GreeConnector.beepExtra(false))
        assertEquals("1", GreeConnector.beepExtra(true))
    }
}
