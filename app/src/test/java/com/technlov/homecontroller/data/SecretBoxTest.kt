package com.technlov.homecontroller.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The plaintext-never-saved rule, on the JVM (the Keystore sealing itself needs a device). When the hub keeps a
 * device's new address, the extras it saves must not carry the key the connector was given in clear.
 */
class SecretBoxTest {
    private val stored = mapOf("local_key_sealed" to "v1:AAAA", "version" to "3.3", "category" to "cz")

    @Test
    fun plaintextTwinOfASealedSecretIsNeverKept() {
        val used = mapOf("local_key" to "0123456789abcdef", "version" to "3.4", "category" to "cz")
        val kept = SecretBox.withoutPlaintext(used, stored)
        assertFalse("plaintext key would be saved", kept.containsKey("local_key"))
        assertEquals("3.4", kept["version"])                     // non-secret news is kept
        val saved = stored + kept
        assertEquals("v1:AAAA", saved["local_key_sealed"])       // the sealed copy survives untouched
        assertFalse(saved.containsKey("local_key"))
    }

    @Test
    fun extrasWithoutSecretsPassThroughUntouched() {
        val gree = mapOf("cipher" to "2", "mac" to "f0fe6b000001")
        assertSame(gree, SecretBox.unsealExtras(gree))           // no Keystore touched when nothing is sealed
        assertEquals(gree, SecretBox.withoutPlaintext(gree, mapOf("cipher" to "1")))
    }
}
