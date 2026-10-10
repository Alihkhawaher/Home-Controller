@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.technlov.home.connectors.tuya

import com.technlov.home.core.DeviceCategory
import com.technlov.home.core.LocalNetwork
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Passive discovery: the connector decodes Tuya broadcasts on 6666 (plaintext), 6667 (AES-ECB) and 6699 (GCM). */
class TuyaDiscoveryTest {
    private val net = LocalNetwork("192.0.2.255", "192.0.2.")

    private fun json(ip: String, id: String, ver: String, pid: String) =
        """{"ip":"$ip","gwId":"$id","active":2,"encrypt":true,"productKey":"$pid","version":"$ver"}"""

    private fun on6667(ip: String, id: String, ver: String, pid: String): TuyaDatagram {
        val payload = TuyaCrypto.ecbEncrypt(json(ip, id, ver, pid).toByteArray(), TuyaCrypto.UDP_KEY)
        return TuyaDatagram(ip, 6667, TuyaCodec.encode55AA(0, 0x13, payload, null, retcode = 0))
    }

    private fun on6666(ip: String, id: String, ver: String, pid: String): TuyaDatagram =
        TuyaDatagram(ip, 6666, TuyaCodec.encode55AA(0, 0x13, json(ip, id, ver, pid).toByteArray(), null, retcode = 0))

    private fun on6699(ip: String, id: String, ver: String, pid: String): TuyaDatagram =
        TuyaDatagram(ip, 6699, TuyaCodec.encode6699(0, 0x13, json(ip, id, ver, pid).toByteArray(), TuyaCrypto.UDP_KEY, ByteArray(12), retcode = 0))

    @Test
    fun discoversAcrossAllThreeCiphersAndDeduplicates() = runTest {
        val lan = FakeTuyaLan()
        lan.broadcasts += on6667("192.0.2.61", "plugidaaaa", "3.3", "prodplug00000001")
        lan.broadcasts += on6699("192.0.2.62", "meteridbbbb", "3.5", "9rjjf6rdkca9woju") // a known OWON meter
        lan.broadcasts += on6666("192.0.2.63", "oldid31cccc", "3.1", "prodold000000001")
        lan.broadcasts += on6667("192.0.2.61", "plugidaaaa", "3.3", "prodplug00000001") // duplicate id
        lan.broadcasts += TuyaDatagram("192.0.2.99", 6667, "not a tuya frame".toByteArray()) // noise

        val tuya = TuyaConnector(lan, TuyaConfig(clock = { testScheduler.currentTime }))
        val found = tuya.discover(net, 1_000)
        val byId = found.associateBy { it.id }
        assertEquals(3, found.size)

        val plug = byId.getValue("plugidaaaa")
        assertEquals("192.0.2.61", plug.address)
        assertEquals("3.3", plug.extras[TuyaConnector.EXTRA_VERSION])
        assertEquals("prodplug00000001", plug.extras[TuyaConnector.EXTRA_PRODUCT_ID])
        assertEquals(DeviceCategory.OTHER, plug.category)

        val meter = byId.getValue("meteridbbbb")
        assertEquals("192.0.2.62", meter.address)
        assertEquals("3.5", meter.extras[TuyaConnector.EXTRA_VERSION])
        assertEquals(DeviceCategory.SENSOR, meter.category)
        assertEquals("Tuya energy meter", meter.name)
        assertEquals("zndb", meter.extras[TuyaConnector.EXTRA_CATEGORY])

        assertTrue("3.1 plaintext broadcast missed", byId.containsKey("oldid31cccc"))
    }

    @Test
    fun noBroadcastsMeansNoDevices() = runTest {
        val found = TuyaConnector(FakeTuyaLan(), TuyaConfig(clock = { testScheduler.currentTime })).discover(net, 500)
        assertTrue(found.isEmpty())
    }
}
