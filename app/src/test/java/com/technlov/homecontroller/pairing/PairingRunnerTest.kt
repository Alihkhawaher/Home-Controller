package com.technlov.homecontroller.pairing

import com.technlov.home.core.Connector
import com.technlov.home.core.ConnectorInfo
import com.technlov.home.core.DeviceCategory
import com.technlov.home.core.DeviceRef
import com.technlov.home.core.DeviceSession
import com.technlov.home.core.EventLog
import com.technlov.home.core.LocalNetwork
import com.technlov.home.core.Provisioning
import com.technlov.home.core.ProvisioningCapable
import com.technlov.home.core.ProvisioningChannel
import com.technlov.home.core.ProvisioningResult
import com.technlov.home.core.provisioningSupport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PairingRunnerTest {
    private val password = "example-pass-1234"
    private val home = LocalNetwork(broadcast = "192.0.2.255", prefix = "192.0.2.")

    /** Records everything, verbose on, so the test can prove the password never reaches the log. */
    private class RecordingLog : EventLog {
        val lines = mutableListOf<String>()
        override val verbose = true
        override fun log(level: EventLog.Level, source: String, message: String, error: Throwable?) {
            lines += "$level $source $message ${error?.message.orEmpty()}"
        }
    }

    private class FakeLink(override val channel: ProvisioningChannel) : HotspotLink {
        var closed = false
        override fun close() {
            closed = true
        }
    }

    private class FakePlatform(override val joinNeedsUser: Boolean = false) : PairingPlatform {
        var joinError: Exception? = null
        var joinGate: CompletableDeferred<Unit>? = null
        var joinedWith: Pair<String, String?>? = null
        var link: FakeLink? = null
        val sent = mutableListOf<ByteArray>()
        var homeAfterCalls = 0
        var homeCalls = 0
        val home = LocalNetwork(broadcast = "192.0.2.255", prefix = "192.0.2.")

        override suspend fun joinHotspot(ssid: String, password: String?, timeoutMs: Long): HotspotLink {
            joinGate?.await()
            joinError?.let { throw it }
            joinedWith = ssid to password
            val channel = object : ProvisioningChannel {
                override suspend fun sendAndReceive(host: String, port: Int, payload: ByteArray, timeoutMs: Long): ByteArray? {
                    sent += payload
                    return "ok".toByteArray()
                }
            }
            return FakeLink(channel).also { link = it }
        }

        override suspend fun homeNetwork(setupSsid: String): LocalNetwork? {
            homeCalls++
            return if (homeCalls > homeAfterCalls) home else null
        }
    }

    private class FakeProvisioning : Provisioning {
        var accept = true
        var throwOnSend: Exception? = null
        var gate: CompletableDeferred<Unit>? = null
        var receivedPassword: String? = null
        override val title = "Add a fake device"
        override val setupSteps = listOf("Hold the button")
        override val setupHotspotPassword = "12345678"
        override fun isSetupHotspot(ssid: String) = ssid.startsWith("setup-")

        override suspend fun sendWifiCredentials(channel: ProvisioningChannel, ssid: String, password: String): ProvisioningResult {
            gate?.await()
            throwOnSend?.let { throw it }
            receivedPassword = password
            channel.sendAndReceive("192.168.1.1", 7000, "creds".toByteArray(), 1_000)
            return ProvisioningResult(accept, if (accept) "Joining $ssid" else "No answer")
        }

        override fun matchesSetupHotspot(device: DeviceRef, setupSsid: String) = device.id == setupSsid.removePrefix("setup-")
    }

    private class FakeConnector(val provisioningImpl: FakeProvisioning) : Connector, ProvisioningCapable {
        override val info = ConnectorInfo("fake", "Fake devices", "For tests", setOf(DeviceCategory.CLIMATE), "1", "None")
        override val provisioning: Provisioning get() = provisioningImpl
        /** What each discover() call returns, in turn (the last one repeats). */
        var rounds: List<List<DeviceRef>> = emptyList()
        var discoveries = 0
        override suspend fun discover(network: LocalNetwork, timeoutMs: Long): List<DeviceRef> {
            val result = rounds.getOrElse(discoveries) { rounds.lastOrNull().orEmpty() }
            discoveries++
            return result
        }

        override suspend fun open(device: DeviceRef): DeviceSession = throw UnsupportedOperationException()
    }

    private fun device(id: String) = DeviceRef("fake", id, "192.0.2.${id.length + 30}", "Fake $id", DeviceCategory.CLIMATE)

    private val provisioning = FakeProvisioning()
    private val connector = FakeConnector(provisioning)
    private val platform = FakePlatform()
    private val log = RecordingLog()

    private fun TestScope.runner(timing: PairingTiming = PairingTiming()) =
        PairingRunner(platform, log, now = { testScheduler.currentTime }, timing = timing)

    private fun assertPasswordNeverLogged() =
        assertTrue("the password must never be logged", log.lines.none { it.contains(password) })

    @Test
    fun theProvisioningContractIsFoundThroughTheConnector() {
        assertSame(provisioning, connector.provisioningSupport)
    }

    @Test
    fun happyPathJoinsSendsReleasesAndFindsTheNewDevice() = runTest {
        platform.homeAfterCalls = 1 // the phone is not back home on the first search
        connector.rounds = listOf(listOf(device("other")), listOf(device("other"), device("a1b2")))
        val secret = WifiSecret(password)
        val r = runner()

        val result = r.run(connector, provisioning, "setup-a1b2", "ExampleNet", secret)

        assertEquals(PairingProgress.Found(device("a1b2"), "Joining ExampleNet"), result)
        assertEquals(result, r.progress.value)
        assertEquals("setup-a1b2" to "12345678", platform.joinedWith)
        assertTrue("the hotspot is released after sending", platform.link!!.closed)
        assertEquals(password, provisioning.receivedPassword)
        assertTrue("the secret is used up", secret.isUsed)
        assertEquals("", secret.take())
        assertEquals(1, platform.sent.size)
        assertEquals(2, connector.discoveries)
        assertTrue(result.credentialsGone)
        assertPasswordNeverLogged()
    }

    @Test
    fun progressGoesThroughEveryStage() = runTest {
        val joinGate = CompletableDeferred<Unit>()
        val sendGate = CompletableDeferred<Unit>()
        platform.joinGate = joinGate
        provisioning.gate = sendGate
        platform.homeAfterCalls = 1
        connector.rounds = listOf(listOf(device("a1b2")))
        val r = runner()
        assertEquals(PairingProgress.Idle, r.progress.value)
        val job = launch { r.run(connector, provisioning, "setup-a1b2", "ExampleNet", WifiSecret(password)) }

        runCurrent()
        assertEquals(PairingProgress.Joining("setup-a1b2", manual = false), r.progress.value)
        joinGate.complete(Unit)
        runCurrent()
        assertEquals(PairingProgress.Sending, r.progress.value)
        sendGate.complete(Unit)
        runCurrent()
        val waiting = r.progress.value as PairingProgress.Waiting
        assertEquals(1, waiting.searches)
        assertTrue(waiting.credentialsGone)
        advanceUntilIdle()
        job.join()
        assertTrue(r.progress.value is PairingProgress.Found)
    }

    @Test
    fun aHotspotThatCannotBeJoinedFailsAndKeepsThePasswordForARetry() = runTest {
        platform.joinError = IOException("The phone could not join")
        val secret = WifiSecret(password)
        val result = runner().run(connector, provisioning, "setup-a1b2", "ExampleNet", secret)
        assertEquals(PairingFailure.JOIN_FAILED, (result as PairingProgress.Failed).reason)
        assertFalse(result.credentialsGone)
        assertFalse("nothing was sent", secret.isUsed)
        assertNull(provisioning.receivedPassword)
        assertPasswordNeverLogged()
    }

    @Test
    fun anUnconfirmedHandOverStopsButCanStillBeWaitedFor() = runTest {
        provisioning.accept = false
        connector.rounds = listOf(listOf(device("a1b2")))
        val r = runner()
        val result = r.run(connector, provisioning, "setup-a1b2", "ExampleNet", WifiSecret(password))
        assertEquals(PairingProgress.Failed(PairingFailure.NOT_CONFIRMED, "No answer"), result)
        assertTrue(platform.link!!.closed)
        assertEquals(0, connector.discoveries)

        val waited = r.waitForDevice(connector, provisioning, "setup-a1b2", "No answer")
        assertTrue(waited is PairingProgress.Found)
        assertPasswordNeverLogged()
    }

    @Test
    fun aSendErrorReleasesTheHotspotAndForgetsThePassword() = runTest {
        provisioning.throwOnSend = IllegalStateException("socket closed after sending $password")
        val secret = WifiSecret(password)
        val result = runner().run(connector, provisioning, "setup-a1b2", "ExampleNet", secret)
        assertEquals(PairingProgress.Failed(PairingFailure.SEND_FAILED, null), result)
        assertTrue(platform.link!!.closed)
        assertTrue(secret.isUsed)
        assertPasswordNeverLogged()
    }

    @Test
    fun aDeviceThatNeverAppearsTimesOut() = runTest {
        connector.rounds = listOf(listOf(device("other")))
        val timing = PairingTiming(waitTimeoutMs = 30_000, searchEveryMs = 5_000)
        val result = runner(timing).run(connector, provisioning, "setup-a1b2", "ExampleNet", WifiSecret(password))
        assertEquals(PairingProgress.Failed(PairingFailure.NOT_FOUND, null), result)
        assertEquals(6, connector.discoveries)
        assertTrue(testScheduler.currentTime >= 30_000)
    }

    @Test
    fun cancellingWhileSendingStillReleasesTheHotspot() = runTest {
        provisioning.gate = CompletableDeferred()
        val r = runner()
        val job = launch { r.run(connector, provisioning, "setup-a1b2", "ExampleNet", WifiSecret(password)) }
        runCurrent()
        assertEquals(PairingProgress.Sending, r.progress.value)
        job.cancel()
        r.markCancelled()
        runCurrent()
        assertTrue(platform.link!!.closed)
        assertEquals(PairingProgress.Cancelled, r.progress.value)
    }

    @Test
    fun manualJoinIsReported() = runTest {
        val manual = FakePlatform(joinNeedsUser = true)
        manual.joinGate = CompletableDeferred()
        val r = PairingRunner(manual, log, now = { testScheduler.currentTime })
        val job = launch { r.run(connector, provisioning, "setup-a1b2", "ExampleNet", WifiSecret(password)) }
        runCurrent()
        assertEquals(PairingProgress.Joining("setup-a1b2", manual = true), r.progress.value)
        assertFalse(r.progress.value.credentialsGone)
        job.cancel()
    }

    @Test
    fun theSecretHidesItself() {
        val secret = WifiSecret(password)
        assertFalse(secret.toString().contains(password))
        assertEquals(password, secret.take())
        assertEquals("", secret.take())
    }
}
