package com.technlov.home.core

import org.junit.Assert.assertNull
import org.junit.Test

/** The optional parts of [DeviceSession] default to "not supported", so a polled connector needs no extra code. */
class DeviceSessionContractTest {
    private val polledOnly = object : DeviceSession {
        override val device = DeviceRef("example", "dev-1", "192.0.2.10", "Example", DeviceCategory.OTHER)
        override val capabilities = listOf(Capability.Power())
        override suspend fun read() = DeviceState(mapOf(Keys.POWER to Value.Bool(true)))
        override suspend fun apply(changes: List<SetValue>) = read()
    }

    @Test
    fun aSessionWithoutAPushChannelOffersNoUpdates() {
        assertNull(polledOnly.updates())
        polledOnly.close() // the default close does nothing and does not throw
    }
}
