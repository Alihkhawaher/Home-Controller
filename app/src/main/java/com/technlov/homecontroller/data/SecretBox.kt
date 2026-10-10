package com.technlov.homecontroller.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals device secrets (a Tuya device's local key) before they are written to the app's storage, which is plain JSON
 * in DataStore. The sealing key is an AES-256-GCM key in the Android Keystore: it never leaves the phone's secure
 * hardware and cannot be exported, so a copy of the app's files (a backup, a rooted read) holds only ciphertext.
 *
 * Convention used across the app: a secret extra is stored as `"<name>_sealed"`; [unsealExtras] turns it back into
 * `"<name>"` in memory, just before a connector opens the device, and [withoutPlaintext] strips the plaintext again
 * before anything is saved.
 */
object SecretBox {
    private const val ALIAS = "home_controller_device_secrets"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val PREFIX = "v1:"
    const val SEALED_SUFFIX = "_sealed"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    /** Encrypts [plain]; the result is safe to store. */
    fun seal(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /** Decrypts a value made by [seal]. */
    fun open(sealed: String): String {
        require(sealed.startsWith(PREFIX)) { "not a sealed value" }
        val raw = Base64.decode(sealed.removePrefix(PREFIX), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        return String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    }

    /** `"x_sealed"` -> `"x"` (plaintext, in memory only). Extras without a sealed entry are returned unchanged. */
    fun unsealExtras(extras: Map<String, String>): Map<String, String> {
        if (extras.keys.none { it.endsWith(SEALED_SUFFIX) }) return extras
        return buildMap {
            for ((k, v) in extras) {
                if (k.endsWith(SEALED_SUFFIX)) put(k.removeSuffix(SEALED_SUFFIX), open(v)) else put(k, v)
            }
        }
    }

    /** Drops every plaintext extra whose sealed twin exists in [stored], so nothing secret is ever saved in clear. */
    fun withoutPlaintext(extras: Map<String, String>, stored: Map<String, String>): Map<String, String> {
        val secret = stored.keys.filter { it.endsWith(SEALED_SUFFIX) }.map { it.removeSuffix(SEALED_SUFFIX) }.toSet()
        return if (secret.isEmpty()) extras else extras.filterKeys { it !in secret && !it.endsWith(SEALED_SUFFIX) }
    }
}
