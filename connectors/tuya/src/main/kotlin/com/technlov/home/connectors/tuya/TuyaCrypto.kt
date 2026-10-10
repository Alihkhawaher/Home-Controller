package com.technlov.home.connectors.tuya

import java.security.MessageDigest
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The crypto primitives of the Tuya LAN protocol, ported from tinytuya's `crypto_helper.py` (AESCipher) and
 * `message_helper.py`. Everything here works on raw bytes (the LAN protocol does not base64 its `pack`, unlike the
 * cloud API):
 *  - AES-128-ECB with PKCS#7 padding: the payload cipher for protocol 3.3 and 3.4;
 *  - AES-128-GCM (12-byte IV, 16-byte tag, the frame header as associated data): the payload cipher for 3.5;
 *  - HMAC-SHA256: the frame integrity check for 3.3+'s session frames (3.4) and the session-key negotiation;
 *  - CRC32: the frame integrity check for 3.3;
 *  - MD5: only to derive the published UDP discovery key.
 *
 * Only `javax.crypto`, so this runs the same on Android and on the JVM. A device key is never put in an exception
 * message (a decrypt failure reports the algorithm, not the key).
 */
object TuyaCrypto {
    private const val GCM_TAG_BYTES = 16

    /**
     * The one published key that decrypts Tuya UDP discovery broadcasts (port 6667 with AES-ECB, port 6699 with
     * AES-GCM): `md5(b"yGAdlopoPVldABfn")`. It is the same in every Tuya app and tinytuya; it is not a secret and it
     * is not a device key.
     */
    val UDP_KEY: ByteArray = md5("yGAdlopoPVldABfn".toByteArray(Charsets.US_ASCII))

    fun md5(data: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(data)

    fun crc32(data: ByteArray): Long = CRC32().apply { update(data) }.value

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    /** AES-128-ECB encrypt with PKCS#7 padding (a whole padding block is added when the input is already aligned). */
    fun ecbEncrypt(plain: ByteArray, key: ByteArray): ByteArray {
        val pad = 16 - plain.size % 16
        val padded = plain + ByteArray(pad) { pad.toByte() }
        val c = Cipher.getInstance("AES/ECB/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return c.doFinal(padded)
    }

    /** AES-128-ECB encrypt with no padding; [plain] must be a whole number of 16-byte blocks (the 3.4 session key). */
    fun ecbEncryptNoPad(plain: ByteArray, key: ByteArray): ByteArray {
        require(plain.size % 16 == 0 && plain.isNotEmpty()) { "input must be whole AES blocks" }
        val c = Cipher.getInstance("AES/ECB/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return c.doFinal(plain)
    }

    /**
     * AES-128-ECB decrypt, then strip PKCS#7 padding the lenient way tinytuya does (take the last byte as the pad
     * length, 1..16, and cut it; the padding bytes themselves are not checked). Throws [TuyaProtocolException] when
     * the input is not whole AES blocks or the pad length is impossible.
     */
    fun ecbDecrypt(enc: ByteArray, key: ByteArray): ByteArray {
        if (enc.isEmpty() || enc.size % 16 != 0) throw TuyaProtocolException("payload is not whole AES blocks")
        val raw = try {
            val c = Cipher.getInstance("AES/ECB/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
            c.doFinal(enc)
        } catch (e: Exception) {
            throw TuyaProtocolException("payload does not decrypt (AES-ECB, ${e.javaClass.simpleName})")
        }
        val padLen = raw.last().toInt() and 0xFF
        if (padLen < 1 || padLen > 16 || padLen > raw.size) throw TuyaProtocolException("invalid AES-ECB padding")
        return raw.copyOfRange(0, raw.size - padLen)
    }

    /** AES-128-GCM encrypt. Returns the cipher text and the 16-byte tag separately. [aad] is authenticated, not encrypted. */
    fun gcmEncrypt(plain: ByteArray, key: ByteArray, iv: ByteArray, aad: ByteArray?): Pair<ByteArray, ByteArray> {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BYTES * 8, iv))
        if (aad != null) c.updateAAD(aad)
        val out = c.doFinal(plain) // cipher text followed by the tag
        val ct = out.copyOfRange(0, out.size - GCM_TAG_BYTES)
        val tag = out.copyOfRange(out.size - GCM_TAG_BYTES, out.size)
        return ct to tag
    }

    /** AES-128-GCM decrypt and verify. Throws [TuyaProtocolException] when the tag does not check out. */
    fun gcmDecrypt(ct: ByteArray, tag: ByteArray, key: ByteArray, iv: ByteArray, aad: ByteArray?): ByteArray {
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BYTES * 8, iv))
            if (aad != null) c.updateAAD(aad)
            c.doFinal(ct + tag)
        } catch (e: Exception) {
            throw TuyaProtocolException("payload does not decrypt (AES-GCM, ${e.javaClass.simpleName})")
        }
    }
}

/** The device answered, but not with what the protocol expects (bad frame, wrong key or cipher, unexpected type). */
class TuyaProtocolException(message: String) : Exception(message)
