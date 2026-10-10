package com.technlov.home.connectors.gree

import com.technlov.home.core.DeviceCategory
import com.technlov.home.core.DeviceRef
import com.technlov.home.core.ProvisioningChannel
import com.technlov.home.core.ProvisioningResult
import com.technlov.home.core.provisioningSupport
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class GreeProvisioningTest {
    private val password = "S3cret-Pass!word"

    /** Answers with [replies] in order (null = silence); records every datagram. */
    private class FakeChannel(vararg replies: String?, val failWith: Exception? = null) : ProvisioningChannel {
        private val queue = ArrayDeque(replies.toList())
        val sent = mutableListOf<Triple<String, Int, String>>()
        override suspend fun sendAndReceive(host: String, port: Int, payload: ByteArray, timeoutMs: Long): ByteArray? {
            sent += Triple(host, port, String(payload, Charsets.UTF_8))
            failWith?.let { throw it }
            return queue.removeFirstOrNull()?.toByteArray(Charsets.UTF_8)
        }
    }

    private fun assertNoLeak(result: ProvisioningResult, log: RecordingLog, secret: String = password) {
        assertFalse("password in message", result.message.contains(secret))
        assertFalse("password in toString", result.toString().contains(secret))
        assertFalse("password in log:\n${log.text}", log.text.contains(secret))
    }

    @Test
    fun sendsThePlainWlanFrameToTheHotspot() = runTest {
        val log = RecordingLog()
        val ch = FakeChannel("""{"t":"ret","r":200}""")
        val r = GreeProvisioning(log).sendWifiCredentials(ch, "HomeNet", password)
        assertTrue(r.accepted)
        assertTrue(r.message, r.message.contains("HomeNet"))
        assertEquals(1, ch.sent.size)
        val (host, port, frame) = ch.sent[0]
        assertEquals("192.168.1.1", host)
        assertEquals(7000, port)
        assertEquals("""{"psw":"S3cret-Pass!word","ssid":"HomeNet","t":"wlan"}""", frame)
        assertNoLeak(r, log)
        assertTrue(log.text, log.text.contains("\"psw\":\"<hidden>\""))   // the verbose frame, password hidden
    }

    @Test
    fun escapesQuotesBackslashesAndUnicode() = runTest {
        val ssid = "Ali's \"Home\" \\ Wi-Fi \u00e9"
        val pw = "p\"w\\d \u00fc\uD83D\uDE00"                 // quote, backslash, umlaut, emoji (surrogate pair)
        val ch = FakeChannel("""{"t":"ret","r":200}""")
        val log = RecordingLog()
        val r = GreeProvisioning(log).sendWifiCredentials(ch, ssid, pw)
        val frame = ch.sent.single().third
        assertEquals(
            "{\"psw\":\"p\\\"w\\\\d \\u00fc\\ud83d\\ude00\",\"ssid\":\"Ali's \\\"Home\\\" \\\\ Wi-Fi \\u00e9\",\"t\":\"wlan\"}",
            frame,
        )
        assertTrue(frame.all { it.code in 0x20..0x7e })                // plain ASCII on the wire
        val parsed = JSONObject(frame)
        assertEquals(pw, parsed.getString("psw"))
        assertEquals(ssid, parsed.getString("ssid"))
        assertEquals("wlan", parsed.getString("t"))
        assertNoLeak(r, log, pw)
    }

    @Test
    fun retriesOnSilenceThenAccepts() = runTest {
        val ch = FakeChannel(null, null, """{"t":"ret","r":200}""")
        val r = GreeProvisioning().sendWifiCredentials(ch, "HomeNet", password)
        assertTrue(r.accepted)
        assertEquals(3, ch.sent.size)
        assertEquals(1, ch.sent.map { it.third }.distinct().size)    // the same frame each time
    }

    @Test
    fun silenceAfterThreeTriesIsNotAcceptedButHopeful() = runTest {
        val log = RecordingLog()
        val ch = FakeChannel()
        val r = GreeProvisioning(log).sendWifiCredentials(ch, "HomeNet", password)
        assertFalse(r.accepted)
        assertEquals(3, ch.sent.size)
        assertTrue(r.message, r.message.contains("Some units take the Wi-Fi details without answering"))
        assertTrue(r.message, r.message.contains("search for devices"))
        assertTrue(log.text.contains("WARN gree pairing: no reply after 3 tries"))
        assertNoLeak(r, log)
    }

    @Test
    fun repliesThatCount() = runTest {
        suspend fun send(reply: String) = GreeProvisioning().sendWifiCredentials(FakeChannel(reply), "HomeNet", password)
        assertTrue(send("""{"t":"ret","r":200}""").accepted)
        assertTrue(send("""{"r":200}""").accepted)
        assertTrue(send("""{"t":"ret"}""").accepted)
        assertTrue(send("""{"t":"ret","r":200}""" + "\u0000\u0000").accepted)   // padding after the JSON
        assertFalse(send("""{"t":"ret","r":400}""").accepted)
        assertFalse(send("""{"t":"other"}""").accepted)
        val junk = send("garbage")
        assertFalse(junk.accepted)
        assertTrue(junk.message, junk.message.contains("not JSON"))
    }

    @Test
    fun aRefusalTellsWhyWithoutThePassword() = runTest {
        val log = RecordingLog()
        // A unit that echoes the details back.
        val ch = FakeChannel("""{"t":"ret","r":500,"psw":"$password","ssid":"HomeNet"}""")
        val r = GreeProvisioning(log).sendWifiCredentials(ch, "HomeNet", password)
        assertFalse(r.accepted)
        assertTrue(r.message, r.message.contains("r=500"))
        assertNoLeak(r, log)
        assertTrue(log.text, log.text.contains("\"psw\":\"<hidden>\""))
    }

    @Test
    fun channelFailureNeverLeaksThePassword() = runTest {
        val log = RecordingLog()
        // Even a channel whose error repeats the payload must not carry the password out.
        val ch = FakeChannel(failWith = IOException("sendto failed for {\"psw\":\"$password\"}"))
        val r = GreeProvisioning(log).sendWifiCredentials(ch, "HomeNet", password)
        assertFalse(r.accepted)
        assertTrue(r.message, r.message.contains("IOException"))
        assertNoLeak(r, log)
    }

    @Test
    fun quietLogStillHasNoPassword() = runTest {
        val log = RecordingLog(verbose = false)
        val r = GreeProvisioning(log).sendWifiCredentials(FakeChannel(null, """{"t":"ret","r":200}"""), "HomeNet", password)
        assertTrue(r.accepted)
        assertNoLeak(r, log)
        assertFalse(log.text.contains("->"))
        assertTrue(log.text.contains("INFO gree pairing: the unit accepted the Wi-Fi details for 'HomeNet'"))
    }

    @Test
    fun setupHotspotNames() {
        val p = GreeProvisioning()
        for (good in listOf("1e2f3a4b", "1E2F3A4B", "00000000", "\"a1b2c3d4\"", " a1b2c3d4 ")) assertTrue(good, p.isSetupHotspot(good))
        for (bad in listOf("1e2f3a4", "1e2f3a4b9", "1e2f3a4g", "HomeNet", "", "\"\"", "1e2f-3a4b")) assertFalse(bad, p.isSetupHotspot(bad))
        assertEquals("12345678", p.setupHotspotPassword)
        assertTrue(p.needs24GHz)
        assertEquals("Add a new Gree air conditioner", p.title)
        assertEquals(4, p.setupSteps.size)
        assertTrue(p.setupSteps[1].contains("10 seconds") && p.setupSteps[1].contains("MODE and TURBO"))
        assertTrue(p.setupSteps[2].contains("last 8 characters of its MAC") && p.setupSteps[2].contains("12345678"))
    }

    @Test
    fun matchesTheDeviceThatOpenedTheHotspot() {
        val p = GreeProvisioning()
        fun dev(id: String, connector: String = "gree") =
            DeviceRef(connector, id, "192.0.2.10", "Gree AC", DeviceCategory.CLIMATE)
        assertTrue(p.matchesSetupHotspot(dev("f0fe6b1a2b3c"), "6b1a2b3c"))
        assertTrue(p.matchesSetupHotspot(dev("f0fe6b1a2b3c"), "6B1A2B3C"))
        assertTrue(p.matchesSetupHotspot(dev("f0fe6b1a2b3c"), "\"6b1a2b3c\""))
        assertFalse(p.matchesSetupHotspot(dev("f0fe6b1a2b3d"), "6b1a2b3c"))
        assertFalse(p.matchesSetupHotspot(dev("f0fe6b1a2b3c", connector = "other"), "6b1a2b3c"))
        assertFalse(p.matchesSetupHotspot(dev("f0fe6b1a2b3c"), "HomeNet"))
        assertFalse(p.matchesSetupHotspot(dev("f0fe6b1a2b3c"), ""))
    }

    @Test
    fun connectorOffersPairing() {
        val p = GreeConnector().provisioningSupport
        assertNotNull(p)
        assertEquals("Add a new Gree air conditioner", p!!.title)
    }
}
