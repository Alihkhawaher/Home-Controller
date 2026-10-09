package com.technlov.home.connectors.gree

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The two ciphers Gree Wi-Fi modules use for the `pack` field (from the open-source greeclimate library):
 *  - v1: AES-128-ECB, PKCS#7 padding, base64;
 *  - v2: AES-128-GCM with a fixed nonce and associated data; the 16-byte tag travels next to the pack as `"tag"`.
 *
 * Every unit answers the generic key of its cipher during scan and bind; bind hands out the unit's own key, which is
 * used for everything after. Only `javax.crypto`, so this runs the same on Android and on the JVM.
 */
object GreeCipher {
    /** Published generic keys (the same in every Gree app). Not secrets. */
    val GENERIC_V1: ByteArray = "a3K8Bx%2r8Y7#xDh".toByteArray(Charsets.US_ASCII)
    val GENERIC_V2: ByteArray = "{yxAHAY_Lm6pbC/<".toByteArray(Charsets.US_ASCII)

    private val NONCE = hex("5440784449675a516c5e6313")
    private val AAD = "qualcomm-test".toByteArray(Charsets.US_ASCII)
    private const val TAG_BYTES = 16

    fun genericKey(version: Int): ByteArray = if (version == 2) GENERIC_V2 else GENERIC_V1

    /** A pack ready to send: base64 cipher text, plus the base64 GCM tag for v2 (null for v1). */
    class Sealed(val pack: String, val tag: String?)

    fun encrypt(plain: String, key: ByteArray, version: Int): Sealed {
        val raw = plain.toByteArray(Charsets.UTF_8)
        val b64 = Base64.getEncoder()
        return if (version == 2) {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, NONCE))
            c.updateAAD(AAD)
            val out = c.doFinal(raw)
            Sealed(
                b64.encodeToString(out.copyOfRange(0, out.size - TAG_BYTES)),
                b64.encodeToString(out.copyOfRange(out.size - TAG_BYTES, out.size)),
            )
        } else {
            val pad = 16 - raw.size % 16
            val padded = raw + ByteArray(pad) { pad.toByte() }
            val c = Cipher.getInstance("AES/ECB/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            Sealed(b64.encodeToString(c.doFinal(padded)), null)
        }
    }

    /**
     * The JSON text inside a pack, cut after its last `}` (units pad with PKCS#7, some with junk). Throws
     * [GreeProtocolException] when the pack does not decrypt (wrong key or cipher, missing or bad tag).
     */
    fun decrypt(pack: String, key: ByteArray, version: Int, tag: String?): String {
        val raw = try {
            val ct = Base64.getDecoder().decode(pack.trim())
            if (version == 2) {
                val t = Base64.getDecoder().decode((tag ?: throw GreeProtocolException("v2 reply without a tag")).trim())
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, NONCE))
                c.updateAAD(AAD)
                c.doFinal(ct + t)
            } else {
                if (ct.isEmpty() || ct.size % 16 != 0) throw GreeProtocolException("v1 pack is not whole AES blocks")
                val c = Cipher.getInstance("AES/ECB/NoPadding")
                c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
                c.doFinal(ct)
            }
        } catch (e: GreeProtocolException) {
            throw e
        } catch (e: Exception) {
            // Never put the key in the message.
            throw GreeProtocolException("pack does not decrypt with the v$version cipher (${e.javaClass.simpleName})")
        }
        val end = raw.lastIndexOf('}'.code.toByte())
        if (end < 0) throw GreeProtocolException("pack decrypts to something that is not JSON")
        return String(raw, 0, end + 1, Charsets.UTF_8)
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/** The unit answered, but not with what the protocol expects (bad JSON, wrong cipher, unexpected message type). */
class GreeProtocolException(message: String) : Exception(message)
