package com.technlov.home.connectors.tuya

import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom

/**
 * A pretend Tuya device for tests: it speaks the full encrypted LAN protocol (session-key negotiation for 3.4/3.5,
 * DP_QUERY and CONTROL) so a real [TuyaClient] can drive it end to end. Test data only: 192.0.2.x documentation
 * addresses and made-up 16-character keys. It never touches a real device.
 */
class FakeTuyaDevice(
    val host: String = "192.0.2.50",
    val id: String = "bf1234567890abcdef0001",
    localKeyText: String = "0123456789abcdef",
    versionText: String = "3.3",
    val dps: LinkedHashMap<String, Any?> = defaultPlugDps(),
    /** When set, the first plain DP_QUERY answers "data unvalid" so the client switches to device22. */
    var firstQueryDataUnvalid: Boolean = false,
) {
    private val realKey = localKeyText.toByteArray(Charsets.US_ASCII)
    private val version = versionText.trim().toDouble()
    private val versionBytes = versionText.trim().toByteArray(Charsets.US_ASCII)
    private val versionHeaderLen = versionBytes.size + 12
    private val deviceNonce = "FAKEDEVICENONCE1".toByteArray(Charsets.US_ASCII) // 16 bytes
    private var sessionKey = realKey
    private var clientNonce = ByteArray(16)
    private var devSeqno = 1L

    /** The control commands the device received, as applied dp maps (for assertions). */
    val controlCommands = mutableListOf<Map<String, Any?>>()
    val statusQueries get() = queryCount
    private var queryCount = 0

    /** The next this-many requests get no reply (simulated silence / a dropped link). */
    var dropNext = 0

    fun handle(req: ByteArray): List<ByteArray> {
        if (dropNext > 0) {
            dropNext--
            return emptyList()
        }
        val cmd = peekCmd(req)
        val fk = when {
            cmd == TuyaCmd.SESS_KEY_NEG_START || cmd == TuyaCmd.SESS_KEY_NEG_FINISH -> if (version >= 3.4) realKey else null
            version >= 3.4 -> sessionKey
            else -> null
        }
        val frame = TuyaCodec.parse(req, fk, hasRetcode = false)
        return when (cmd) {
            TuyaCmd.SESS_KEY_NEG_START -> {
                clientNonce = if (version == 3.4) TuyaCrypto.ecbDecrypt(frame.payload, realKey) else frame.payload
                val resp = deviceNonce + TuyaCrypto.hmacSha256(realKey, clientNonce)
                listOf(encodeNegResp(resp))
            }
            TuyaCmd.SESS_KEY_NEG_FINISH -> {
                val xor = ByteArray(16) { (clientNonce[it].toInt() xor deviceNonce[it].toInt()).toByte() }
                sessionKey = if (version == 3.4) {
                    TuyaCrypto.ecbEncryptNoPad(xor, realKey)
                } else {
                    TuyaCrypto.gcmEncrypt(xor, realKey, clientNonce.copyOf(12), null).first
                }
                emptyList() // the client sends FINISH without waiting for a reply
            }
            else -> handleData(cmd, decodeRequestJson(frame))
        }
    }

    private fun handleData(cmd: Int, jsonText: String): List<ByteArray> {
        val obj = runCatching { JSONObject(jsonText) }.getOrDefault(JSONObject())
        val requested = extractDps(obj)
        val nonNull = requested.filterValues { it != null }
        val isQuery = cmd == TuyaCmd.DP_QUERY || cmd == TuyaCmd.DP_QUERY_NEW ||
            (cmd == TuyaCmd.CONTROL_NEW && nonNull.isEmpty())
        return if (isQuery) {
            queryCount++
            if (cmd == TuyaCmd.DP_QUERY && firstQueryDataUnvalid) {
                firstQueryDataUnvalid = false
                listOf(encodeReply(cmd, """{"data":"json obj data unvalid"}"""))
            } else {
                listOf(encodeReply(cmd, TuyaJson.write(linkedMapOf("dps" to dps))))
            }
        } else {
            controlCommands += nonNull
            nonNull.forEach { (k, v) -> dps[k] = v }
            listOf(encodeReply(cmd, TuyaJson.write(linkedMapOf("dps" to nonNull))))
        }
    }

    private fun extractDps(o: JSONObject): LinkedHashMap<String, Any?> {
        val dpsObj = o.optJSONObject("dps") ?: o.optJSONObject("data")?.optJSONObject("dps")
        val out = LinkedHashMap<String, Any?>()
        if (dpsObj != null) for (k in dpsObj.keys()) out[k] = if (dpsObj.isNull(k)) null else TuyaJson.plain(dpsObj.opt(k))
        return out
    }

    /** Decrypt and strip the version header, mirroring a real device reading a client request. */
    private fun decodeRequestJson(frame: TuyaFrame): String {
        var p = frame.payload
        if (version == 3.4) p = TuyaCrypto.ecbDecrypt(p, sessionKey)
        if (startsWith(p, versionBytes)) p = p.copyOfRange(versionHeaderLen, p.size)
        if (version in 3.2..3.399) p = TuyaCrypto.ecbDecrypt(p, sessionKey)
        val text = String(p, Charsets.UTF_8)
        val s = text.indexOf('{')
        val e = text.lastIndexOf('}')
        return if (s in 0 until e) text.substring(s, e + 1) else text
    }

    private fun encodeReply(cmd: Int, jsonText: String): ByteArray {
        val body = jsonText.toByteArray(Charsets.UTF_8)
        val seq = devSeqno++
        return when {
            version >= 3.5 -> TuyaCodec.encode6699(seq, cmd, body, sessionKey, iv12(), retcode = 0)
            version >= 3.4 -> TuyaCodec.encode55AA(seq, cmd, TuyaCrypto.ecbEncrypt(body, sessionKey), sessionKey, retcode = 0)
            else -> TuyaCodec.encode55AA(seq, cmd, TuyaCrypto.ecbEncrypt(body, realKey), null, retcode = 0)
        }
    }

    private fun encodeNegResp(payload: ByteArray): ByteArray {
        val seq = devSeqno++
        return if (version >= 3.5) {
            TuyaCodec.encode6699(seq, TuyaCmd.SESS_KEY_NEG_RESP, payload, realKey, iv12(), retcode = 0)
        } else {
            TuyaCodec.encode55AA(seq, TuyaCmd.SESS_KEY_NEG_RESP, TuyaCrypto.ecbEncrypt(payload, realKey), realKey, retcode = 0)
        }
    }

    private fun peekCmd(data: ByteArray): Int {
        val prefix = readU32(data, 0)
        return if (prefix == TuyaCodec.PREFIX_6699) readU32(data, 10).toInt() else readU32(data, 8).toInt()
    }

    private fun startsWith(data: ByteArray, prefix: ByteArray): Boolean {
        if (data.size < prefix.size) return false
        for (i in prefix.indices) if (data[i] != prefix[i]) return false
        return true
    }

    private fun iv12(): ByteArray = ByteArray(12).also { SecureRandom().nextBytes(it) }

    private fun readU32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)

    companion object {
        fun defaultPlugDps(): LinkedHashMap<String, Any?> = linkedMapOf(
            "1" to true, "9" to 0, "17" to 1234, "18" to 270, "19" to 1033, "20" to 2301,
            "26" to 0, "38" to "memory", "39" to "relay", "40" to false,
        )
    }
}

/** A fake LAN of Tuya devices behind the [TuyaTransport] interface; discovery datagrams can be pre-seeded. */
class FakeTuyaLan(vararg devices: FakeTuyaDevice) : TuyaTransport {
    val devices = devices.associateBy { it.host }.toMutableMap()
    var connects = 0
    val broadcasts = mutableListOf<TuyaDatagram>()

    override suspend fun connect(host: String, port: Int, timeoutMs: Long): TuyaConnection {
        connects++
        val dev = devices[host] ?: throw IOException("no device at $host")
        return FakeConnection(dev)
    }

    override suspend fun openDiscoverySocket(ports: List<Int>): TuyaDiscoverySocket {
        val inbox = ArrayDeque(broadcasts)
        return object : TuyaDiscoverySocket {
            override fun drain(): List<TuyaDatagram> = inbox.toList().also { inbox.clear() }
            override fun close() {}
        }
    }

    private class FakeConnection(private val device: FakeTuyaDevice) : TuyaConnection {
        private val queue = ArrayDeque<ByteArray>()
        override suspend fun send(frame: ByteArray) {
            queue.addAll(device.handle(frame))
        }
        override suspend fun receive(timeoutMs: Long): ByteArray? = queue.removeFirstOrNull()
        override fun close() {}
    }
}

/** An [com.technlov.home.core.EventLog] that keeps every line, verbose on, for leak checks. */
class RecordingLog(override val verbose: Boolean = true) : com.technlov.home.core.EventLog {
    val lines = mutableListOf<String>()
    override fun log(level: com.technlov.home.core.EventLog.Level, source: String, message: String, error: Throwable?) {
        lines += "$level $source $message" + (error?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: "")
    }
    val text get() = lines.joinToString("\n")
}
