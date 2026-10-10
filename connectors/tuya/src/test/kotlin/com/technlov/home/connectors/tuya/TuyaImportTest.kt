package com.technlov.home.connectors.tuya

import com.technlov.home.core.DeviceCategory
import com.technlov.home.core.Keys
import com.technlov.home.core.Value
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class TuyaImportTest {
    // Built by an independent Python run: gzip+base64url of a {"devices":[...]} JSON with fake ids/keys and an
    // Arabic device name. Two devices: a cz plug (with ver + dp spec) and a zndb meter (no ver -> filled by discovery).
    private val payload =
        "HCTUYA1:H4sIAAAAAAAC_32PTU7DMBCFr1LN2kL5a9rkEHTZRRRV_hkLJ21sOU4jWmWBgA0XQRyAuzS3wQkogg2zsEZ-n96bdwWBZ8Wxhby4ghKQA5O0RiWCvxMCgRofvR6EUZys0802o4wLlF44o4U8vksINPSEnhlfx-fxbXxa3d7Hl9vn_H54kFPnVX7xq5nDvKUxVotfKcJMxxQhgbZXjj8cpk-m9RFIQACIsx2WpMi8m-4aJ3TfzIhq3Ey0CxJOTGcPRvf-wB_C--6BSHpssSwH8m_paCktUXBGs-0mXSdxFAawVN3td_erE7o54bvfpRFsaZjZqpKpFTWnWa-rDoZy-AKm1x2PdgEAAA"

    // All devices have an invalid (too short) key -> nothing usable.
    private val badKeys = "HCTUYA1:H4sIAAAAAAAC_6tWSkkty0xOLVayiq5WykxRslKqMFTSUcpOrQQyizPyi0qUanVgMkZKtbG1ADA_1agzAAAA"

    @Test
    fun importsTwoDevicesWithKeysAndSpecs() {
        val devices = TuyaImport.parse(payload)
        assertEquals(2, devices.size)

        val plug = devices[0]
        assertEquals("tuya", plug.connectorId)
        assertEquals("bfakeid0000000000000001", plug.id)
        assertEquals("", plug.address) // discovery fills the IP by id
        assertEquals(DeviceCategory.SWITCH, plug.category)
        assertEquals("keypprod00000001", plug.model)
        assertEquals("0123456789abcdef", plug.extras[TuyaConnector.EXTRA_LOCAL_KEY])
        assertEquals("3.4", plug.extras[TuyaConnector.EXTRA_VERSION])
        assertEquals("cz", plug.extras[TuyaConnector.EXTRA_CATEGORY])

        // The imported dp spec decodes the plug.
        val points = TuyaModel.points(null, null, emptyMap(), TuyaModel.parseDpSpec(plug.extras[TuyaConnector.EXTRA_DP_SPEC]))
        val d = TuyaModel.decode(mapOf("1" to true, "19" to 1033), points)
        assertEquals(true, (d[Keys.POWER] as Value.Bool).v)
        assertEquals(103.3, (d[TuyaKeys.POWER_W] as Value.Num).v, 1e-9)
    }

    @Test
    fun arabicNamesSurvive() {
        assertEquals("مكيف الصالة", TuyaImport.parse(payload)[0].name)
    }

    @Test
    fun offlineDeviceHasNoVersionAndMeterIsASensor() {
        val meter = TuyaImport.parse(payload)[1]
        assertEquals("bfakeid0000000000000002", meter.id)
        assertNull(meter.extras[TuyaConnector.EXTRA_VERSION]) // offline: discovery fills it
        assertEquals(DeviceCategory.SENSOR, meter.category)
        assertEquals("zndb", meter.extras[TuyaConnector.EXTRA_CATEGORY])
    }

    @Test
    fun unknownPrefixOrVersionIsRejectedClearly() {
        assertTrue(message { TuyaImport.parse("hello world") }.contains("not a Home Controller"))
        assertTrue(message { TuyaImport.parse("HCTUYA9:AAAA") }.contains("newer version"))
    }

    @Test
    fun damagedPayloadsAreRejected() {
        assertTrue(message { TuyaImport.parse("HCTUYA1:not base64 ###") }.contains("base64url"))
        val notGzip = Base64.getUrlEncoder().withoutPadding().encodeToString("not gzip".toByteArray())
        assertTrue(message { TuyaImport.parse("HCTUYA1:$notGzip") }.contains("gzip"))
    }

    @Test
    fun anImportWithNoUsableKeysIsRejected() {
        assertTrue(message { TuyaImport.parse(badKeys) }.contains("no usable devices"))
    }

    @Test
    fun keysNeverAppearInAToStringOfTheRef() {
        // The key lives in extras; make sure a ref does not splash it anywhere unexpected in its own fields.
        val plug = TuyaImport.parse(payload)[0]
        assertFalse(plug.name.contains("0123456789abcdef"))
        assertFalse(plug.id.contains("0123456789abcdef"))
    }

    private inline fun message(crossinline block: () -> Unit): String =
        assertThrows(IllegalArgumentException::class.java) { block() }.message ?: ""
}
