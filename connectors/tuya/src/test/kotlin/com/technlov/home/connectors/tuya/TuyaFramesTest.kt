package com.technlov.home.connectors.tuya

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UPDATEDPS and HEART_BEAT request frames, byte for byte against what tinytuya 1.20.0 builds for the same input
 * (`generate_payload` + `_encode_message`; for 3.5, `pack_message` with the same fixed IV, since tinytuya picks a
 * random one). Fixed test input only: a made-up id, local key and session key, sequence number 7.
 */
class TuyaFramesTest {
    private val id = "bf1234567890abcdef0001"
    private val localKey = "0123456789abcdef".toByteArray(Charsets.US_ASCII)
    private val sessionKey = "fedcba9876543210".toByteArray(Charsets.US_ASCII)
    private val iv = ByteArray(12) { it.toByte() }
    private val plugDps = listOf(18, 19, 20)
    private val owonDps = listOf(101, 102, 103, 111, 112, 113, 121, 122, 123, 132, 133)

    private fun header(version: String) = version.toByteArray(Charsets.US_ASCII) + ByteArray(12)
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun frame(version: String, cmd: Int, payload: ByteArray): String {
        val v = version.toDouble()
        val key = if (v >= 3.4) sessionKey else localKey
        return hex(TuyaClient.encodeRequest(v, header(version), cmd, payload, 7, key, if (v >= 3.5) iv else null))
    }

    @Test
    fun theUpdateDpsAndHeartbeatPayloadsAreTinytuyas() {
        assertEquals("""{"dpId":[18,19,20]}""", String(TuyaClient.updateDpsPayload(plugDps), Charsets.UTF_8))
        assertEquals(
            """{"dpId":[101,102,103,111,112,113,121,122,123,132,133]}""",
            String(TuyaClient.updateDpsPayload(owonDps), Charsets.UTF_8),
        )
        assertEquals("""{"gwId":"$id","devId":"$id"}""", String(TuyaClient.heartbeatPayload(id), Charsets.UTF_8))
    }

    @Test
    fun updateDps33() = assertEquals(
        "000055aa0000000700000012000000284f87db9a0c0b1c0ac1ee7f4ae131850e2bd47cdcc79b37e8ddcb25d5ee27100e918e478f" +
            "0000aa55",
        frame("3.3", TuyaCmd.UPDATEDPS, TuyaClient.updateDpsPayload(plugDps)),
    )

    @Test
    fun updateDps34() = assertEquals(
        "000055aa000000070000001200000044f3acfef01d96603036b7b8d410f75143b8904c1d48fa8206c7539d4945637025cdc7d15b" +
            "d413e934a5d4137989f5ea70da264d9cc8c7aa52ce88c7496a988c0e0000aa55",
        frame("3.4", TuyaCmd.UPDATEDPS, TuyaClient.updateDpsPayload(plugDps)),
    )

    @Test
    fun updateDps35() = assertEquals(
        "000066990000000000070000001200000052000102030405060708090a0b5d482d966b55bfee190244ca19ce682dcbeace06530252" +
            "cb056687d10f1d4cd5555f4e357c6f0285bfd4de9aabf6f0e546714b977455dce5eb602d95af18fb300ca56c33951f00009966",
        frame("3.5", TuyaCmd.UPDATEDPS, TuyaClient.updateDpsPayload(owonDps)),
    )

    @Test
    fun heartbeat33() = assertEquals(
        "000055aa00000007000000090000005805008afd0d6b71c01e3489f2007668cef463b6dbe41c79b24fd62697b519d3e1a4e9f6f7" +
            "3049d772595baedbc3bc3a8e14c8231c69326df700a6f8b3432922cc0efea48a0091daa595a3a780fe13bd0e9c69c4040000aa55",
        frame("3.3", TuyaCmd.HEART_BEAT, TuyaClient.heartbeatPayload(id)),
    )

    @Test
    fun heartbeat34() = assertEquals(
        "000055aa0000000700000009000000749f7fb3f5141b462bc50cdaa699f265716bc25cf2a5d6cf2402b328271ff6e47e8d314d67" +
            "037720915601c66e9ee7ab018b3df1b2fc2c71df700cfe1c2c4f6d479438fffddc66acb6cc21ccac93f4747e2e040044867828" +
            "fb7fbaec032ad5c8243f855b664ebc76143c223fe1ddf16a560000aa55",
        frame("3.4", TuyaCmd.HEART_BEAT, TuyaClient.heartbeatPayload(id)),
    )

    @Test
    fun heartbeat35() = assertEquals(
        "00006699000000000007000000090000005e000102030405060708090a0b5d482e916b55bfee605112ca07cc6c2ad1ecc60c4f52" +
            "01994d32d0d3131c4cc4554c186126175495a9c78ecfb6f5f0e35f764f9c1018ff9ca97a7bbd811b987560fec723d12c7865" +
            "9f11a3d24d3a1f3fb05700009966",
        frame("3.5", TuyaCmd.HEART_BEAT, TuyaClient.heartbeatPayload(id)),
    )

    /** What the client really puts on the wire for UPDATEDPS is that same frame (3.3: no IV, the local key). */
    @Test
    fun theClientSendsThatFrame() = kotlinx.coroutines.test.runTest {
        val sent = mutableListOf<ByteArray>()
        val dev = FakeTuyaDevice(versionText = "3.3")
        val lan = object : TuyaTransport by FakeTuyaLan(dev) {
            override suspend fun connect(host: String, port: Int, timeoutMs: Long): TuyaConnection {
                val inner = dev.open()
                return object : TuyaConnection by inner {
                    override suspend fun send(frame: ByteArray) {
                        sent += frame
                        inner.send(frame)
                    }
                }
            }
        }
        val client = TuyaClient(dev.host, dev.id, localKey, "3.3", lan, clock = { 1_000_000L })
        client.connect(backgroundScope)
        client.status() // sequence number 1
        sent.clear()
        assertTrue(client.updateDps(plugDps)) // sequence number 2
        assertEquals(
            hex(TuyaClient.encodeRequest(3.3, header("3.3"), TuyaCmd.UPDATEDPS, TuyaClient.updateDpsPayload(plugDps), 2, localKey, null)),
            hex(sent.single()),
        )
        client.close()
    }
}
