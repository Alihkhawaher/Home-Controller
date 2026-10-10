package com.technlov.home.connectors.tuya

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Known-answer tests for the Tuya crypto primitives. The expected values come from an independent Python run
 * (hashlib/zlib/hmac and the `cryptography` library), with a made-up 16-character key.
 */
class TuyaCryptoTest {
    private val key = "0123456789abcdef".toByteArray(Charsets.US_ASCII)

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun udpKeyIsThePublishedMd5() {
        assertEquals("6c1ec8e2bb9bb59ab50b0daf649b410a", hex(TuyaCrypto.UDP_KEY))
        assertEquals(16, TuyaCrypto.UDP_KEY.size)
    }

    @Test
    fun crc32MatchesZlib() {
        assertEquals(222957957L, TuyaCrypto.crc32("hello world".toByteArray()))
    }

    @Test
    fun hmacSha256MatchesPython() {
        assertEquals(
            "0a7a9680ef2dce2e7a3b2c7dc5f0e9ad860e39d35d1d67bbafa6e4ff6c603301",
            hex(TuyaCrypto.hmacSha256(key, "the message".toByteArray())),
        )
    }

    @Test
    fun ecbMatchesPythonAndRoundTrips() {
        val plain = """{"dps":{"1":true}}""".toByteArray()
        val ct = TuyaCrypto.ecbEncrypt(plain, key)
        assertEquals("c27ee03f8be481e63320de7dc6eb429664215f365b0dad6b2ac8fab15b1d40bd", hex(ct))
        assertArrayEquals(plain, TuyaCrypto.ecbDecrypt(ct, key))
    }

    @Test
    fun ecbPadsAWholeBlockWhenAligned() {
        val aligned = "0123456789abcdef".toByteArray() // 16 bytes
        assertEquals(32, TuyaCrypto.ecbEncrypt(aligned, key).size)
        assertArrayEquals(aligned, TuyaCrypto.ecbDecrypt(TuyaCrypto.ecbEncrypt(aligned, key), key))
    }

    @Test
    fun ecbNoPadIsExactBlocks() {
        val sixteen = "sessionkeyxor016".toByteArray()
        assertEquals(16, TuyaCrypto.ecbEncryptNoPad(sixteen, key).size)
        assertThrows(IllegalArgumentException::class.java) { TuyaCrypto.ecbEncryptNoPad("short".toByteArray(), key) }
    }

    @Test
    fun ecbRejectsRubbish() {
        assertThrows(TuyaProtocolException::class.java) { TuyaCrypto.ecbDecrypt(ByteArray(15), key) }
        assertThrows(TuyaProtocolException::class.java) { TuyaCrypto.ecbDecrypt("not-a-block!".toByteArray(), key) }
    }

    @Test
    fun gcmMatchesPythonAndRoundTrips() {
        val iv = ByteArray(12) { it.toByte() }
        val aad = "header-aad".toByteArray()
        val (ct, tag) = TuyaCrypto.gcmEncrypt("secret payload".toByteArray(), key, iv, aad)
        assertEquals("8e5aa76f7b001b46895f6b75a1ff", hex(ct))
        assertEquals("0b1d817c9b7740e2bcfb916fedf97de1", hex(tag))
        assertArrayEquals("secret payload".toByteArray(), TuyaCrypto.gcmDecrypt(ct, tag, key, iv, aad))
    }

    @Test
    fun gcmFailsOnWrongTagOrAad() {
        val iv = ByteArray(12) { it.toByte() }
        val (ct, tag) = TuyaCrypto.gcmEncrypt("x".toByteArray(), key, iv, "aad".toByteArray())
        assertThrows(TuyaProtocolException::class.java) { TuyaCrypto.gcmDecrypt(ct, ByteArray(16), key, iv, "aad".toByteArray()) }
        assertThrows(TuyaProtocolException::class.java) { TuyaCrypto.gcmDecrypt(ct, tag, key, iv, "other".toByteArray()) }
    }

    @Test
    fun errorsNeverContainTheKey() {
        val e: TuyaProtocolException = assertThrows(TuyaProtocolException::class.java) { TuyaCrypto.ecbDecrypt(ByteArray(15), key) }
        assertFalse(e.message!!.contains("0123456789abcdef"))
        assertTrue(e.message!!.contains("AES"))
    }
}
