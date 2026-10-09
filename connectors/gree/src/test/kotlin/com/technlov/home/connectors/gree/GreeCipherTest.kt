package com.technlov.home.connectors.gree

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

/**
 * Known answers produced by the proven Python connector (netinv/connectors/gree.py `encrypt`, which matches
 * greeclimate), with a made-up MAC and a made-up unit key.
 */
class GreeCipherTest {
    private val fakeUnitKey = "Fake0Key1Test2Ab".toByteArray()

    @Test
    fun v1ScanMatchesPython() {
        val s = GreeCipher.encrypt("""{"t":"scan"}""", GreeCipher.GENERIC_V1, 1)
        assertEquals("VT6PQOWv+8FeevjTXFZdyQ==", s.pack)
        assertNull(s.tag)
    }

    @Test
    fun v1BindMatchesPython() {
        val json = GreeJson.write(linkedMapOf("t" to "bind", "mac" to "f0fe6b000001", "uid" to 0))
        assertEquals("""{"t":"bind","mac":"f0fe6b000001","uid":0}""", json)
        assertEquals(
            "LbyXlIr/T0/qWhgSNSKzEpzHQmRp/h87PxQH+DSyG2Oj9mrs/0p7GUTdWxmNaBUt",
            GreeCipher.encrypt(json, GreeCipher.GENERIC_V1, 1).pack,
        )
    }

    @Test
    fun v2BindMatchesPython() {
        val s = GreeCipher.encrypt("""{"t":"bind","mac":"f0fe6b000001","uid":0}""", GreeCipher.GENERIC_V2, 2)
        assertEquals("JtoKliwtq5m94t6beCQUVI17qoUShELwYlRkDerbl+rdNHgGiUNkepU=", s.pack)
        assertEquals("2z+9/sxHhbG4QeXsmQsqIQ==", s.tag)
    }

    @Test
    fun v1StatusWithUnitKeyMatchesPython() {
        val json = GreeJson.write(
            linkedMapOf("t" to "status", "mac" to "f0fe6b000001", "cols" to listOf("Pow", "Mod", "SetTem", "TemSen")),
        )
        assertEquals("""{"t":"status","mac":"f0fe6b000001","cols":["Pow","Mod","SetTem","TemSen"]}""", json)
        assertEquals(
            "Cjgmpvzj/lcuqeJTIaV/2n7h8dGcjAFzfjDLuMA9V0fzRtreBTtdRHe9OmUdbcf+07Xv7GgdaTocdGNPW3p8UU4y51quBNWhZQ11guoKoi0=",
            GreeCipher.encrypt(json, fakeUnitKey, 1).pack,
        )
    }

    @Test
    fun commandMatchesPythonInBothCiphers() {
        val json = GreeJson.write(
            linkedMapOf("t" to "cmd", "opt" to listOf("Pow", "SetTem", "TemUn", "TemRec"), "p" to listOf(1, 24, 0, 0)),
        )
        assertEquals("""{"t":"cmd","opt":["Pow","SetTem","TemUn","TemRec"],"p":[1,24,0,0]}""", json)
        assertEquals(
            "0hz+UpSmZRvzTzqvfdb1m7CuviNZtNUMlBHG471CBv15lxi0bYdkOuv99BXa8UDac5W2AN2d4kaCKZXs5AnP51UZ2VLUyfrB8TMMwVLU2ec=",
            GreeCipher.encrypt(json, fakeUnitKey, 1).pack,
        )
        val v2 = GreeCipher.encrypt(json, fakeUnitKey, 2)
        assertEquals("9dBGl+w5Rhp9r1RhJJ0k+kWZwF5hOw1EXK1cCsoioA7JWuqhIjgpNop0eRGep6BXXlosDOmOY5qYmbdttvC92OJh", v2.pack)
        assertEquals("FBbJdbjvCfno5Q2KInGcuA==", v2.tag)
    }

    @Test
    fun roundTripsBothCiphersAcrossBlockBoundaries() {
        for (v in listOf(1, 2)) {
            for (len in 0..40) {
                val json = """{"a":"${"x".repeat(len)}"}"""
                val s = GreeCipher.encrypt(json, fakeUnitKey, v)
                assertEquals("v$v len $len", json, GreeCipher.decrypt(s.pack, fakeUnitKey, v, s.tag))
            }
        }
    }

    @Test
    fun v1PadsAWholeBlockWhenAligned() {
        val json = """{"t":"x","a":1}""" + " "        // 16 bytes
        assertEquals(16, json.length)
        val ct = Base64.getDecoder().decode(GreeCipher.encrypt(json, fakeUnitKey, 1).pack)
        assertEquals(32, ct.size)
    }

    @Test
    fun decryptCutsAfterTheLastBrace() {
        val s = GreeCipher.encrypt("""{"t":"dat","x":[1]}  junk""", fakeUnitKey, 1)
        assertEquals("""{"t":"dat","x":[1]}""", GreeCipher.decrypt(s.pack, fakeUnitKey, 1, null))
    }

    @Test
    fun nonAsciiNamesSurvive() {
        // Outgoing: escaped to ASCII like Python's json.dumps ("\u00e9" as six characters).
        val json = GreeJson.write(mapOf("name" to "Salon \u00e9"))
        assertEquals("{\"name\":\"Salon \\u00e9\"}", json)
        // Incoming: a unit may send raw UTF-8; it survives both ciphers.
        val utf8 = "{\"name\":\"Salon \u00e9\"}"
        for (v in listOf(1, 2)) {
            val s = GreeCipher.encrypt(utf8, fakeUnitKey, v)
            assertEquals(utf8, GreeCipher.decrypt(s.pack, fakeUnitKey, v, s.tag))
        }
    }

    @Test
    fun wrongKeyMissingOrBadTagFail() {
        val v1 = GreeCipher.encrypt("""{"t":"scan"}""", fakeUnitKey, 1)
        assertThrows(GreeProtocolException::class.java) {
            GreeCipher.decrypt(v1.pack, GreeCipher.GENERIC_V1, 1, null)
        }
        val v2 = GreeCipher.encrypt("""{"t":"scan"}""", fakeUnitKey, 2)
        assertThrows(GreeProtocolException::class.java) { GreeCipher.decrypt(v2.pack, fakeUnitKey, 2, null) }
        val badTag = Base64.getEncoder().encodeToString(ByteArray(16))
        assertThrows(GreeProtocolException::class.java) { GreeCipher.decrypt(v2.pack, fakeUnitKey, 2, badTag) }
        assertThrows(GreeProtocolException::class.java) { GreeCipher.decrypt("not base64!", fakeUnitKey, 1, null) }
    }

    @Test
    fun errorsNeverContainTheKey() {
        val v2 = GreeCipher.encrypt("""{"t":"scan"}""", fakeUnitKey, 2)
        val e = assertThrows(GreeProtocolException::class.java) {
            GreeCipher.decrypt(v2.pack, GreeCipher.GENERIC_V2, 2, v2.tag)
        }
        assertFalse(e.message!!.contains("Fake0Key1Test2Ab"))
        assertFalse(e.message!!.contains(String(GreeCipher.GENERIC_V2)))
    }
}
