package com.technlov.home.connectors.tuya

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Tuya frame layout and round-trips for 55AA (CRC and HMAC) and 6699 (GCM). */
class TuyaCodecTest {
    private val key = "0123456789abcdef".toByteArray(Charsets.US_ASCII)
    private fun u32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)

    @Test
    fun frame55AALayoutAndOffsets() {
        val payload = "hi".toByteArray()
        val f = TuyaCodec.encode55AA(seqno = 7, cmd = TuyaCmd.DP_QUERY, payload = payload, hmacKey = null)
        assertEquals(0x000055AAL, u32(f, 0))                 // prefix
        assertEquals(7L, u32(f, 4))                          // seqno
        assertEquals(TuyaCmd.DP_QUERY.toLong(), u32(f, 8))   // command
        assertEquals((payload.size + 8).toLong(), u32(f, 12)) // length = payload + crc(4) + suffix(4)
        assertEquals(0x0000AA55L, u32(f, f.size - 4))        // suffix
        assertEquals(TuyaCodec.HEADER_55AA + payload.size + 8, f.size)
        assertEquals(f.size, TuyaCodec.frameLength(f))
    }

    @Test
    fun frame6699LayoutAndOffsets() {
        val payload = "hello".toByteArray()
        val iv = ByteArray(12) { 1 }
        val f = TuyaCodec.encode6699(seqno = 3, cmd = TuyaCmd.CONTROL_NEW, payload = payload, key = key, iv = iv, retcode = null)
        assertEquals(0x00006699L, u32(f, 0))                 // prefix
        assertEquals(0L, (f[4].toInt() and 0xFF).toLong() * 256 + (f[5].toInt() and 0xFF)) // reserved 2 bytes
        assertEquals(3L, u32(f, 6))                          // seqno
        assertEquals(TuyaCmd.CONTROL_NEW.toLong(), u32(f, 10)) // command
        assertEquals((12 + payload.size + 16).toLong(), u32(f, 14)) // length = iv + ct + tag
        assertEquals(0x00009966L, u32(f, f.size - 4))        // suffix
        assertEquals(f.size, TuyaCodec.frameLength(f))
    }

    @Test
    fun roundTrip55AACrcRequestAndReply() {
        val payload = """{"t":"status"}""".toByteArray()
        val req = TuyaCodec.encode55AA(1, TuyaCmd.DP_QUERY, payload, null, retcode = null)
        val parsedReq = TuyaCodec.parse(req, null, hasRetcode = false)
        assertTrue(parsedReq.crcGood)
        assertArrayEquals(payload, parsedReq.payload)

        val reply = TuyaCodec.encode55AA(1, TuyaCmd.DP_QUERY, payload, null, retcode = 0)
        val parsedReply = TuyaCodec.parse(reply, null, hasRetcode = true)
        assertEquals(0L, parsedReply.retcode)
        assertArrayEquals(payload, parsedReply.payload)
        assertTrue(parsedReply.crcGood)
    }

    @Test
    fun roundTrip55AAHmac() {
        val payload = "secret".toByteArray()
        val reply = TuyaCodec.encode55AA(5, TuyaCmd.CONTROL_NEW, payload, hmacKey = key, retcode = 0)
        val ok = TuyaCodec.parse(reply, key, hasRetcode = true)
        assertTrue(ok.crcGood)
        assertArrayEquals(payload, ok.payload)
        // Wrong key fails the HMAC check (but still parses the structure).
        val bad = TuyaCodec.parse(reply, "wrongkeywrongkey".toByteArray(), hasRetcode = true)
        assertFalse(bad.crcGood)
    }

    @Test
    fun roundTrip6699Gcm() {
        val payload = """{"dps":{"1":false}}""".toByteArray()
        val iv = ByteArray(12) { (it * 3).toByte() }
        val reply = TuyaCodec.encode6699(9, TuyaCmd.DP_QUERY_NEW, payload, key, iv, retcode = 0)
        val parsed = TuyaCodec.parse(reply, key, hasRetcode = true)
        assertEquals(0L, parsed.retcode)
        assertArrayEquals(payload, parsed.payload)
        // A request (no retcode) round-trips when parsed as such.
        val req = TuyaCodec.encode6699(9, TuyaCmd.DP_QUERY_NEW, payload, key, iv, retcode = null)
        assertArrayEquals(payload, TuyaCodec.parse(req, key, hasRetcode = false).payload)
    }

    @Test
    fun corruptionFailsTheCrc() {
        val f = TuyaCodec.encode55AA(1, TuyaCmd.DP_QUERY, "data".toByteArray(), null, retcode = 0)
        f[TuyaCodec.HEADER_55AA + 5] = (f[TuyaCodec.HEADER_55AA + 5] + 1).toByte()
        assertFalse(TuyaCodec.parse(f, null, hasRetcode = true).crcGood)
    }

    @Test
    fun frameLengthNeedsEnoughHeader() {
        assertNull(TuyaCodec.frameLength(byteArrayOf(0, 0, 0x55.toByte())))
        assertNull(TuyaCodec.frameLength(byteArrayOf(0, 0, 0x55.toByte(), 0xAA.toByte(), 0, 0))) // header incomplete
    }

    @Test
    fun badPrefixThrows() {
        org.junit.Assert.assertThrows(TuyaProtocolException::class.java) {
            TuyaCodec.parse(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), null)
        }
    }
}
