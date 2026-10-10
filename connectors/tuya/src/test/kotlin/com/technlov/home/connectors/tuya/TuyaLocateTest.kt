@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.technlov.home.connectors.tuya

import com.technlov.home.core.ConnectorException
import com.technlov.home.core.DeviceCategory
import com.technlov.home.core.DeviceRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [TuyaConnector.open] finds a device by its own broadcast when the saved address is empty (an imported device) or
 * stale (DHCP moved it), and the session reports the address it actually used so the app can keep it.
 */
class TuyaLocateTest {
    private fun broadcast(ip: String, id: String, ver: String): TuyaDatagram {
        val json = """{"ip":"$ip","gwId":"$id","active":2,"encrypt":true,"productKey":"prodplug00000001","version":"$ver"}"""
        val payload = TuyaCrypto.ecbEncrypt(json.toByteArray(), TuyaCrypto.UDP_KEY)
        return TuyaDatagram(ip, 6667, TuyaCodec.encode55AA(0, 0x13, payload, null, retcode = 0))
    }

    private fun ref(dev: FakeTuyaDevice, address: String, version: String = "3.3") = DeviceRef(
        connectorId = TuyaConnector.ID,
        id = dev.id,
        address = address,
        name = "Imported plug",
        category = DeviceCategory.SWITCH,
        extras = mapOf(
            TuyaConnector.EXTRA_LOCAL_KEY to "0123456789abcdef",
            TuyaConnector.EXTRA_VERSION to version,
            TuyaConnector.EXTRA_CATEGORY to "cz",
        ),
    )

    @Test
    fun anImportedDeviceWithNoAddressIsFoundByItsBroadcast() = runTest {
        val dev = FakeTuyaDevice(host = "192.0.2.71", versionText = "3.4")
        val lan = FakeTuyaLan(dev).apply { broadcasts += broadcast("192.0.2.71", dev.id, "3.4") }
        val tuya = TuyaConnector(lan, TuyaConfig(clock = { testScheduler.currentTime }), scope = backgroundScope)

        tuya.open(ref(dev, address = "", version = "3.3")).use { s ->
            assertEquals("192.0.2.71", s.device.address)
            assertEquals("the broadcast's version wins", "3.4", s.device.extras[TuyaConnector.EXTRA_VERSION])
            assertTrue(s.read().values.isNotEmpty())
        }
    }

    @Test
    fun aDeviceThatMovedIsFoundAtItsNewAddress() = runTest {
        val dev = FakeTuyaDevice(host = "192.0.2.80", versionText = "3.3")
        val lan = FakeTuyaLan(dev).apply { broadcasts += broadcast("192.0.2.80", dev.id, "3.3") }
        val tuya = TuyaConnector(lan, TuyaConfig(clock = { testScheduler.currentTime }), scope = backgroundScope)

        tuya.open(ref(dev, address = "192.0.2.12")).use { s ->   // the old DHCP address answers nothing
            assertEquals("192.0.2.80", s.device.address)
            assertTrue(s.read().values.isNotEmpty())
        }
    }

    @Test
    fun aSwitchedOffDeviceWithNoAddressFailsClearly() = runTest {
        val dev = FakeTuyaDevice(host = "192.0.2.90")
        val tuya = TuyaConnector(FakeTuyaLan(dev), TuyaConfig(clock = { testScheduler.currentTime }), scope = backgroundScope)
        try {
            tuya.open(ref(dev, address = ""))
            fail("opened a device that never announced itself")
        } catch (e: ConnectorException) {
            assertTrue(e.message!!.contains("not found on this Wi-Fi"))
        }
    }
}
