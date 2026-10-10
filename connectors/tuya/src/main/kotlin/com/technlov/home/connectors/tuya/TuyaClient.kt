package com.technlov.home.connectors.tuya

import com.technlov.home.core.ConnectorException
import com.technlov.home.core.EventLog
import com.technlov.home.core.info
import com.technlov.home.core.trace
import com.technlov.home.core.warn
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom

/**
 * The Tuya LAN protocol for one device over a single TCP connection: connect (negotiate a session key on 3.4/3.5),
 * DP_QUERY to read, CONTROL to set. Ported from tinytuya's XenonDevice/Device. Not thread-safe; [TuyaSession]
 * serialises calls.
 *
 * The device's `local_key` (and the negotiated session key) live in this object only: never logged (verbose frames
 * show the plaintext JSON, which has no key in it), never in a message or [toString].
 */
class TuyaClient(
    val host: String,
    val id: String,
    localKey: ByteArray,
    versionText: String,
    private val transport: TuyaTransport,
    private val port: Int = DEFAULT_PORT,
    private val timeoutMs: Long = 2_000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val rng: () -> ByteArray = { ByteArray(16).also { SecureRandom().nextBytes(it) } },
    private val log: EventLog = EventLog.None,
) {
    private val realKey = localKey.copyOf()
    private var sessionKey = realKey
    val version: Double = versionText.trim().toDoubleOrNull() ?: 3.3
    private val versionStr = versionText.trim()
    private val versionHeader = versionStr.toByteArray(Charsets.US_ASCII) + ByteArray(12)

    private var seqno = 1L
    private var conn: TuyaConnection? = null

    /** "default" or "device22" (some 22-char-id devices need CONTROL_NEW for the status query). */
    var devType = "default"
        private set

    val isConnected: Boolean get() = conn != null

    init {
        require(realKey.size == 16) { "a Tuya local key is 16 bytes" }
    }

    /** Open the TCP connection and, for 3.4/3.5, negotiate the session key. */
    suspend fun connect() {
        close()
        sessionKey = realKey
        seqno = 1L
        conn = try {
            transport.connect(host, port, timeoutMs)
        } catch (e: IOException) {
            throw ConnectorException("cannot reach $host:$port (${e.message ?: e.javaClass.simpleName})", e)
        }
        if (version >= 3.4) negotiateSessionKey()
        log.info(SOURCE, "$host: connected (protocol $versionStr${if (version >= 3.4) ", session key negotiated" else ""})")
    }

    override fun toString(): String = "TuyaClient($host, v$versionStr, ${id.takeLast(6)})"

    /** The current data points the device reports, as `dp id (string) -> raw value`. Switches to device22 if needed. */
    suspend fun status(): Map<String, Any?> {
        val (cmd, payload) = queryPayload()
        val reply = exchange(cmd, payload)
        val text = decodeReplyText(reply)
        if (devType == "default" && (version == 3.3 || version == 3.4) && text.contains("data unvalid")) {
            log.trace(SOURCE) { "$host: 'data unvalid' -> switching to device22" }
            devType = "device22"
            val (cmd2, payload2) = queryPayload()
            return TuyaJson.dps(parseJson(decodeReplyText(exchange(cmd2, payload2))))
        }
        return TuyaJson.dps(parseJson(text))
    }

    /**
     * CHANGES THE DEVICE: one CONTROL setting [dps] (dp id string -> raw value). Returns any data points the device
     * echoed (often empty; [TuyaSession] reads the state back). Throws on a refusal or silence.
     */
    suspend fun set(dps: Map<String, Any?>): Map<String, Any?> {
        require(dps.isNotEmpty()) { "nothing to set" }
        val (cmd, payload) = controlPayload(dps)
        val reply = exchange(cmd, payload)
        if (reply.retcode != 0L) throw TuyaProtocolException("device refused the command (retcode=${reply.retcode})")
        val text = decodeReplyTextOrEmpty(reply)
        return if (text.isBlank()) emptyMap() else TuyaJson.dps(parseJson(text))
    }

    fun close() {
        conn?.let { runCatching { it.close() } }
        conn = null
    }

    // ---- payload building (mirrors tinytuya generate_payload / payload_dict) ----

    private fun epochString() = (clock() / 1000).toString()
    private fun epochInt() = clock() / 1000

    private fun queryPayload(): Pair<Int, ByteArray> = when {
        devType == "device22" -> TuyaCmd.CONTROL_NEW to json(
            linkedMapOf("devId" to id, "uid" to id, "t" to epochString(), "dps" to linkedMapOf("1" to null)),
        )
        version >= 3.4 -> TuyaCmd.DP_QUERY_NEW to json(linkedMapOf<String, Any?>())
        else -> TuyaCmd.DP_QUERY to json(
            linkedMapOf("gwId" to id, "devId" to id, "uid" to id, "t" to epochString()),
        )
    }

    private fun controlPayload(dps: Map<String, Any?>): Pair<Int, ByteArray> = when {
        version >= 3.4 -> TuyaCmd.CONTROL_NEW to json(
            linkedMapOf("protocol" to 5, "t" to epochInt(), "data" to linkedMapOf("dps" to dps)),
        )
        else -> TuyaCmd.CONTROL to json(
            linkedMapOf("devId" to id, "uid" to id, "t" to epochString(), "dps" to dps),
        )
    }

    private fun json(map: Map<String, Any?>): ByteArray = TuyaJson.write(map).toByteArray(Charsets.UTF_8)

    // ---- framing (mirrors tinytuya _encode_message / _decode_payload) ----

    private fun frameKey(): ByteArray? = if (version >= 3.4) sessionKey else null

    private fun encodeFrame(cmd: Int, payload: ByteArray): ByteArray {
        val seq = seqno
        seqno += 1
        val withHeader = if (cmd !in TuyaCmd.NO_PROTOCOL_HEADER) versionHeader + payload else payload
        return when {
            version >= 3.5 -> TuyaCodec.encode6699(seq, cmd, withHeader, sessionKey, iv12())
            version >= 3.4 -> TuyaCodec.encode55AA(seq, cmd, TuyaCrypto.ecbEncrypt(withHeader, sessionKey), sessionKey)
            else -> {
                // 3.3: encrypt first, then prepend the cleartext version header (not on the header-less query).
                val enc = TuyaCrypto.ecbEncrypt(payload, sessionKey)
                val body = if (cmd !in TuyaCmd.NO_PROTOCOL_HEADER) versionHeader + enc else enc
                TuyaCodec.encode55AA(seq, cmd, body, null)
            }
        }
    }

    /** The plaintext JSON text of a reply, decrypting and stripping the version header as the protocol version needs. */
    private fun decodeReplyText(frame: TuyaFrame): String {
        var p = frame.payload
        if (version == 3.4) p = TuyaCrypto.ecbDecrypt(p, sessionKey)
        if (startsWith(p, versionHeader.copyOf(versionStr.length))) {
            p = p.copyOfRange(versionHeader.size, p.size)
        } else if (devType == "device22" && p.size % 16 != 0 && p.size > versionHeader.size) {
            p = p.copyOfRange(versionHeader.size, p.size)
        }
        if (version in 3.2..3.399) p = TuyaCrypto.ecbDecrypt(p, sessionKey)
        return cleanJson(String(p, Charsets.UTF_8))
    }

    /** Like [decodeReplyText] but tolerant of an empty-payload ack (CONTROL often returns nothing to decode). */
    private fun decodeReplyTextOrEmpty(frame: TuyaFrame): String {
        if (frame.payload.isEmpty()) return ""
        return try {
            decodeReplyText(frame)
        } catch (e: TuyaProtocolException) {
            ""
        }
    }

    private fun parseJson(text: String): JSONObject {
        if (text.isBlank()) return JSONObject()
        return TuyaJson.parse(text)
    }

    // ---- exchange ----

    private suspend fun exchange(cmd: Int, payload: ByteArray): TuyaFrame {
        val c = conn ?: throw ConnectorException("$host: not connected")
        log.trace(SOURCE) { "-> $host cmd=0x${cmd.toString(16)} ${String(payload, Charsets.UTF_8)}" }
        try {
            c.send(encodeFrame(cmd, payload))
        } catch (e: IOException) {
            throw ConnectorException("$host: send failed (${e.message ?: e.javaClass.simpleName})", e)
        }
        return readDataFrame()
    }

    /** Read frames until a non-heartbeat one arrives (or time out). */
    private suspend fun readDataFrame(): TuyaFrame {
        val c = conn ?: throw ConnectorException("$host: not connected")
        val deadline = clock() + timeoutMs * 2
        while (true) {
            val raw = try {
                c.receive((deadline - clock()).coerceAtLeast(1))
            } catch (e: IOException) {
                throw ConnectorException("$host: connection dropped (${e.message ?: e.javaClass.simpleName})", e)
            } ?: throw ConnectorException("no answer from $host on TCP $port")
            val frame = TuyaCodec.parse(raw, frameKey())
            if (frame.cmd == TuyaCmd.HEART_BEAT) continue
            if (version < 3.5 && !frame.crcGood) throw TuyaProtocolException("$host: reply failed its integrity check")
            log.trace(SOURCE) { "<- $host cmd=0x${frame.cmd.toString(16)} retcode=${frame.retcode}" }
            return frame
        }
    }

    // ---- session-key negotiation (3.4 and 3.5), mirrors tinytuya _negotiate_session_key* ----

    private suspend fun negotiateSessionKey() {
        val localNonce = rng16()
        sessionKey = realKey // the negotiation frames and HMAC use the real key

        // Step 1: send our nonce, expect the device's nonce + HMAC(realKey, ourNonce).
        val resp = exchange(TuyaCmd.SESS_KEY_NEG_START, localNonce)
        if (resp.cmd != TuyaCmd.SESS_KEY_NEG_RESP) {
            throw ConnectorException("$host: session key negotiation got command 0x${resp.cmd.toString(16)}")
        }
        var payload = resp.payload
        if (version == 3.4) payload = TuyaCrypto.ecbDecrypt(payload, realKey)
        if (payload.size < 48) throw ConnectorException("$host: session key negotiation reply too short")
        val remoteNonce = payload.copyOfRange(0, 16)
        val expect = TuyaCrypto.hmacSha256(realKey, localNonce)
        if (!expect.contentEquals(payload.copyOfRange(16, 48))) {
            throw ConnectorException("$host: session key negotiation failed its HMAC check (wrong local key?)")
        }

        // Step 2: send HMAC(realKey, deviceNonce); the device needs no reply.
        val finish = TuyaCrypto.hmacSha256(realKey, remoteNonce)
        val c = conn ?: throw ConnectorException("$host: not connected")
        try {
            c.send(encodeFrame(TuyaCmd.SESS_KEY_NEG_FINISH, finish))
        } catch (e: IOException) {
            throw ConnectorException("$host: send failed during negotiation (${e.message ?: e.javaClass.simpleName})", e)
        }

        // Step 3: the session key is the encrypted XOR of the two nonces.
        val xor = ByteArray(16) { (localNonce[it].toInt() xor remoteNonce[it].toInt()).toByte() }
        sessionKey = if (version == 3.4) {
            TuyaCrypto.ecbEncryptNoPad(xor, realKey)
        } else {
            TuyaCrypto.gcmEncrypt(xor, realKey, localNonce.copyOf(12), null).first
        }
        log.info(SOURCE, "$host: session key received")
    }

    private fun rng16(): ByteArray = rng().let { if (it.size == 16) it else ByteArray(16) { i -> it[i % it.size] } }
    private fun iv12(): ByteArray = rng().copyOf(12)

    private fun startsWith(data: ByteArray, prefix: ByteArray): Boolean {
        if (data.size < prefix.size) return false
        for (i in prefix.indices) if (data[i] != prefix[i]) return false
        return true
    }

    /** Keep only the JSON object: from the first '{' to the last '}'. Some modules pad the plaintext. */
    private fun cleanJson(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start in 0 until end) text.substring(start, end + 1) else text.trim()
    }

    companion object {
        internal const val SOURCE = "tuya"
        const val DEFAULT_PORT = 6668
    }
}
