package com.technlov.home.connectors.tuya

import com.technlov.home.core.ConnectorException
import com.technlov.home.core.EventLog
import com.technlov.home.core.info
import com.technlov.home.core.trace
import com.technlov.home.core.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The Tuya LAN protocol for one device over ONE TCP connection that stays open while the session lives. [connect]
 * opens it (negotiating a session key on 3.4/3.5) and starts a background reader that takes every frame the device
 * sends:
 *  - the answer to the request in flight goes to that request. It is matched by command, and below 3.4 also by
 *    sequence number, which those devices echo. 3.4 and 3.5 devices number their frames on their own (seen live: a
 *    3.4 plug answered our seq 3 with 21396, a 3.3 alarm host echoed every one). A device that first sends an empty
 *    acknowledgement and then the data (the "null ack" tinytuya's `_send_receive` waits out) is waited out too;
 *  - a STATUS frame (0x08) is the device reporting a change on its own: a push;
 *  - a HEART_BEAT answer only proves the connection is alive.
 * The data points of every frame that carries some go to [onDps] in the order they arrive, so the session's view is
 * never rolled back by a reply that a push overtook.
 *
 * Ported from tinytuya's XenonDevice/Device (MIT, Jason Cox and contributors): framing, ciphers, session-key
 * negotiation and payloads, including UPDATEDPS and HEART_BEAT. [TuyaSession] sends one request at a time;
 * [heartbeat] may go out alongside one (writes are serialised here).
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
    @Volatile private var sessionKey = realKey
    val version: Double = versionText.trim().toDoubleOrNull() ?: 3.3
    private val versionStr = versionText.trim()
    private val versionHeader = versionStr.toByteArray(Charsets.US_ASCII) + ByteArray(12)

    /** Guards writes: the sequence counter, the session key while it is negotiated, and the socket's output. */
    private val sendLock = Mutex()
    private var seqno = 1L
    private val conn = AtomicReference<TuyaConnection?>(null)
    @Volatile private var reader: Job? = null
    private val pending = AtomicReference<Pending?>(null)
    private val heard = AtomicLong()
    private val sent = AtomicLong()

    /** "default" or "device22" (some 22-char-id devices need CONTROL_NEW for the status query). */
    @Volatile var devType = "default"
        private set

    /**
     * Where the data points of each frame go, from the reader, in arrival order. [push] is false for the answer to
     * the request in flight and true for what the device sent on its own (a STATUS frame, a late reply).
     */
    @Volatile var onDps: (dps: Map<String, Any?>, push: Boolean) -> Unit = { _, _ -> }

    /** Called from the reader when the live connection drops (not when it is closed on purpose). */
    @Volatile var onDropped: (reason: String) -> Unit = {}

    /** Set once a device answered a status query with a STATUS frame instead of the query's own command. */
    @Volatile private var statusAnswersQueries = false

    val isConnected: Boolean get() = conn.get() != null

    /** Frames received and sent so far (over every connection): the session's keep-alive watches them. */
    val framesHeard: Long get() = heard.get()
    val framesSent: Long get() = sent.get()

    init {
        require(realKey.size == 16) { "a Tuya local key is 16 bytes" }
    }

    /**
     * Opens the TCP connection -- closing any previous one first, as many devices take one local connection only --
     * negotiates the session key on 3.4/3.5, and starts the reader in [scope].
     */
    suspend fun connect(scope: CoroutineScope) {
        close()
        sendLock.withLock {
            sessionKey = realKey
            seqno = 1L
            val c = try {
                transport.connect(host, port, timeoutMs)
            } catch (e: IOException) {
                throw ConnectorException("cannot reach $host:$port (${e.message ?: e.javaClass.simpleName})", e)
            }
            try {
                if (version >= 3.4) negotiateSessionKey(c)
                if (!scope.isActive) throw ConnectorException("$host: the session was closed while connecting")
            } catch (e: Throwable) {
                runCatching { c.close() }
                throw e
            }
            conn.set(c)
            reader = scope.launch(CoroutineName("tuya-reader")) { readLoop(c) }
        }
        log.info(SOURCE, "$host: connected (protocol $versionStr${if (version >= 3.4) ", session key negotiated" else ""})")
    }

    override fun toString(): String = "TuyaClient($host, v$versionStr, ${id.takeLast(6)})"

    /** The current data points the device reports, as `dp id (string) -> raw value`. Switches to device22 if needed. */
    suspend fun status(): Map<String, Any?> {
        val text = query()
        if (devType == "default" && (version == 3.3 || version == 3.4) && text.contains("data unvalid")) {
            log.trace(SOURCE) { "$host: 'data unvalid' -> switching to device22" }
            devType = "device22"
            return TuyaJson.dps(parseJson(query()))
        }
        return TuyaJson.dps(parseJson(text))
    }

    /**
     * CHANGES THE DEVICE: one CONTROL setting [dps] (dp id string -> raw value). Returns any data points the device
     * echoed (often none: most acknowledge with an empty frame and report the change as a STATUS push, which goes to
     * [onDps]). Throws on a refusal or silence.
     */
    suspend fun set(dps: Map<String, Any?>): Map<String, Any?> {
        require(dps.isNotEmpty()) { "nothing to set" }
        val (cmd, payload) = controlPayload(dps)
        val answer = send(Pending(cmd, Wait.ACK_OR_STATUS), payload, timeoutMs * 2)
            ?: throw ConnectorException("no answer from $host on TCP $port")
        if (answer.frame.retcode != 0L) throw TuyaProtocolException("device refused the command (retcode=${answer.frame.retcode})")
        val text = answer.text.getOrDefault("")
        return if (text.isBlank()) emptyMap() else TuyaJson.dps(parseJson(text))
    }

    /**
     * UPDATEDPS (0x12): asks the device to measure [dpIds] afresh -- metering plugs and meters only refresh some
     * readings when asked (tinytuya `updatedps`, tuya-local's `force` dps). The device acknowledges and reports the
     * new values as a STATUS push, which goes to [onDps]. Returns whether it answered within the request timeout;
     * some devices ignore the command.
     */
    suspend fun updateDps(dpIds: List<Int>): Boolean {
        require(dpIds.isNotEmpty()) { "no data points to refresh" }
        return send(Pending(TuyaCmd.UPDATEDPS, Wait.ACK_OR_STATUS), updateDpsPayload(dpIds), timeoutMs) != null
    }

    /** HEART_BEAT (0x09): keeps the connection open (devices drop an idle one). Not waited for: the answer only proves life. */
    suspend fun heartbeat() {
        sendLock.withLock {
            val c = conn.get() ?: return
            log.trace(SOURCE) { "-> $host heartbeat seq=$seqno" }
            try {
                c.send(encodeFrame(TuyaCmd.HEART_BEAT, heartbeatPayload(id)))
                sent.incrementAndGet()
            } catch (e: IOException) {
                dropped(c, "heartbeat not sent (${e.message ?: e.javaClass.simpleName})")
            }
        }
    }

    /** Closes the connection on purpose (no [onDropped]); a request in flight fails at once. */
    fun close() {
        val c = conn.getAndSet(null)
        reader?.cancel()
        reader = null
        pending.getAndSet(null)?.answer?.completeExceptionally(ConnectorException("$host: connection closed"))
        c?.let { runCatching { it.close() } }
    }

    /** Gives up on the live connection (it went silent): closes it and reports it like a drop. */
    fun drop(reason: String) {
        conn.get()?.let { dropped(it, reason) }
    }

    // ---- requests ----

    /** How a request's answer is recognised. */
    private enum class Wait {
        /** A query: the matching reply with data. An empty matching ack means the data follows, possibly as STATUS. */
        DATA,

        /** A command or UPDATEDPS: the matching ack (empty or not), or a STATUS frame reporting the result. */
        ACK_OR_STATUS,
    }

    private class Answer(val frame: TuyaFrame, val text: Result<String>)

    /** The request in flight. [seqno] is set (under the send lock) before it is published to the reader. */
    private class Pending(val cmd: Int, val wait: Wait) {
        @Volatile var seqno = -1L
        val answer = CompletableDeferred<Answer>()
        @Volatile var acked = false

        /** Data that came in some other frame while a query waited: its answer if the proper reply never comes. */
        @Volatile var other: Answer? = null
    }

    /**
     * The status query; returns the decoded reply text. If the proper reply never comes but the device sent its data
     * in another frame meanwhile, that is the answer. A device that answered with a STATUS frame gets STATUS frames
     * accepted as query answers from then on (logged once), so it keeps working without the timeout.
     */
    private suspend fun query(): String {
        val (cmd, payload) = queryPayload()
        val p = Pending(cmd, Wait.DATA)
        val answer = send(p, payload, timeoutMs * 2)
        if (answer != null) return answer.text.getOrThrow()
        val other = p.other ?: throw ConnectorException("no answer from $host on TCP $port")
        if (other.frame.cmd == TuyaCmd.STATUS && !statusAnswersQueries) {
            statusAnswersQueries = true
            log.warn(SOURCE, "$host: answers status queries with STATUS frames; accepting them from now on")
        }
        return other.text.getOrThrow()
    }

    /**
     * Sends [p]'s request and waits up to [timeout] for its answer; null on silence. Throws [ConnectorException] when
     * not connected or when the connection drops meanwhile.
     */
    private suspend fun send(p: Pending, payload: ByteArray, timeout: Long): Answer? {
        sendLock.withLock {
            val c = conn.get() ?: throw ConnectorException("$host: not connected")
            p.seqno = seqno
            log.trace(SOURCE) { "-> $host cmd=0x${p.cmd.toString(16)} seq=${p.seqno} ${String(payload, Charsets.UTF_8)}" }
            pending.set(p) // before sending: the answer may arrive before send() returns
            try {
                c.send(encodeFrame(p.cmd, payload))
                sent.incrementAndGet()
            } catch (e: IOException) {
                pending.compareAndSet(p, null)
                val why = e.message ?: e.javaClass.simpleName
                dropped(c, "send failed ($why)")
                throw ConnectorException("$host: send failed ($why)", e)
            }
        }
        return try {
            withTimeoutOrNull(timeout) { p.answer.await() }
        } finally {
            pending.compareAndSet(p, null)
        }
    }

    // ---- the reader ----

    private suspend fun readLoop(c: TuyaConnection) {
        try {
            while (true) {
                val raw = c.receive(READ_POLL_MS) ?: continue
                if (conn.get() !== c) return // closed or replaced meanwhile
                heard.incrementAndGet()
                val frame = try {
                    TuyaCodec.parse(raw, frameKey())
                } catch (e: TuyaProtocolException) {
                    log.trace(SOURCE) { "<- $host unreadable frame (${e.message})" }
                    // A request in flight fails at once (a wrong key, typically) rather than timing out.
                    pending.getAndSet(null)?.answer?.completeExceptionally(TuyaProtocolException("$host: ${e.message}"))
                    continue
                }
                route(frame)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            dropped(c, e.message ?: e.javaClass.simpleName)
        } catch (e: Exception) {
            // One odd frame must not leave a dead reader behind: drop the connection; the session reconnects.
            log.warn(SOURCE, "$host: reader stopped (${e.javaClass.simpleName}: ${e.message})")
            dropped(c, "reader error")
        }
    }

    /** Gives one frame to the request it answers, to [onDps] as a push, or to nobody (a heartbeat answer, a stray ack). */
    private fun route(frame: TuyaFrame) {
        val p = pending.get()
        val replyMatch = p != null && frame.cmd == p.cmd && (version >= 3.4 || frame.seqno == p.seqno)
        if (version < 3.5 && !frame.crcGood) {
            log.trace(SOURCE) { "<- $host cmd=0x${frame.cmd.toString(16)} seq=${frame.seqno} failed its integrity check" }
            if (replyMatch) finish(p!!, null, TuyaProtocolException("$host: reply failed its integrity check"))
            return
        }
        log.trace(SOURCE) { "<- $host cmd=0x${frame.cmd.toString(16)} seq=${frame.seqno} retcode=${frame.retcode} (${frame.payload.size} bytes)" }
        if (frame.cmd == TuyaCmd.HEART_BEAT) return

        val text: Result<String> = if (frame.payload.isEmpty()) Result.success("") else runCatching { decodeReplyText(frame) }
        val isStatus = frame.cmd == TuyaCmd.STATUS
        val answers = p != null && when (p.wait) {
            Wait.ACK_OR_STATUS -> replyMatch || isStatus
            Wait.DATA -> (replyMatch && frame.payload.isNotEmpty()) || (isStatus && (p.acked || statusAnswersQueries))
        }
        if (replyMatch && !answers) p!!.acked = true // an empty ack: the data comes next

        val dps = text.getOrNull()?.takeIf { it.isNotBlank() }?.let { dpsOrNull(it) }
        if (!dps.isNullOrEmpty()) onDps(dps, isStatus || !answers)
        when {
            answers -> finish(p!!, Answer(frame, text), null)
            p != null && p.wait == Wait.DATA && !dps.isNullOrEmpty() -> p.other = Answer(frame, text)
            !isStatus && !replyMatch && dps.isNullOrEmpty() ->
                log.trace(SOURCE) { "<- $host cmd=0x${frame.cmd.toString(16)} seq=${frame.seqno}: not for a request in flight; ignored" }
        }
    }

    private fun finish(p: Pending, answer: Answer?, error: Exception?) {
        if (!pending.compareAndSet(p, null)) return
        if (answer != null) p.answer.complete(answer) else p.answer.completeExceptionally(error!!)
    }

    private fun dropped(c: TuyaConnection, reason: String) {
        if (!conn.compareAndSet(c, null)) return // already closed on purpose, or replaced
        runCatching { c.close() }
        pending.getAndSet(null)?.answer?.completeExceptionally(ConnectorException("$host: connection dropped ($reason)"))
        log.info(SOURCE, "$host: connection dropped ($reason)")
        onDropped(reason)
    }

    private fun dpsOrNull(text: String): Map<String, Any?>? = try {
        TuyaJson.dps(TuyaJson.parse(text))
    } catch (e: TuyaProtocolException) {
        null
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

    /** Call with [sendLock] held: takes the next sequence number. */
    private fun encodeFrame(cmd: Int, payload: ByteArray): ByteArray {
        val seq = seqno
        seqno += 1
        return encodeRequest(version, versionHeader, cmd, payload, seq, sessionKey, if (version >= 3.5) iv12() else null)
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

    private fun parseJson(text: String): JSONObject {
        if (text.isBlank()) return JSONObject()
        return TuyaJson.parse(text)
    }

    // ---- session-key negotiation (3.4 and 3.5), mirrors tinytuya _negotiate_session_key* ----

    /** Runs with [sendLock] held, before the reader starts: reads the device's answer straight off [c]. */
    private suspend fun negotiateSessionKey(c: TuyaConnection) {
        val localNonce = rng16()
        sessionKey = realKey // the negotiation frames and HMAC use the real key

        // Step 1: send our nonce, expect the device's nonce + HMAC(realKey, ourNonce).
        sendDirect(c, encodeFrame(TuyaCmd.SESS_KEY_NEG_START, localNonce))
        val resp = receiveDirect(c)
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
        sendDirect(c, encodeFrame(TuyaCmd.SESS_KEY_NEG_FINISH, TuyaCrypto.hmacSha256(realKey, remoteNonce)))

        // Step 3: the session key is the encrypted XOR of the two nonces.
        val xor = ByteArray(16) { (localNonce[it].toInt() xor remoteNonce[it].toInt()).toByte() }
        sessionKey = if (version == 3.4) {
            TuyaCrypto.ecbEncryptNoPad(xor, realKey)
        } else {
            TuyaCrypto.gcmEncrypt(xor, realKey, localNonce.copyOf(12), null).first
        }
        log.info(SOURCE, "$host: session key received")
    }

    private suspend fun sendDirect(c: TuyaConnection, frame: ByteArray) {
        try {
            c.send(frame)
            sent.incrementAndGet()
        } catch (e: IOException) {
            throw ConnectorException("$host: send failed during negotiation (${e.message ?: e.javaClass.simpleName})", e)
        }
    }

    /** Reads frames off [c] until a non-heartbeat one arrives (or time out). Only used before the reader runs. */
    private suspend fun receiveDirect(c: TuyaConnection): TuyaFrame {
        val deadline = clock() + timeoutMs * 2
        var skipped = 0
        while (true) {
            val raw = try {
                c.receive((deadline - clock()).coerceIn(1, timeoutMs * 2))
            } catch (e: IOException) {
                throw ConnectorException("$host: connection dropped (${e.message ?: e.javaClass.simpleName})", e)
            } ?: throw ConnectorException("no answer from $host on TCP $port")
            heard.incrementAndGet()
            val frame = TuyaCodec.parse(raw, frameKey())
            // Bounded by a count as well as the clock, so a stalled clock and a chatty device cannot keep us here.
            if (frame.cmd == TuyaCmd.HEART_BEAT && skipped++ < 8) continue
            if (version < 3.5 && !frame.crcGood) throw TuyaProtocolException("$host: reply failed its integrity check")
            log.trace(SOURCE) { "<- $host cmd=0x${frame.cmd.toString(16)} retcode=${frame.retcode}" }
            return frame
        }
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

        /** How long one wait of the reader lasts; closing the connection ends it at once anyway. */
        private const val READ_POLL_MS = 5_000L

        /** The UPDATEDPS payload, as tinytuya's payload_dict builds it for every version: `{"dpId":[18,19,20]}`. */
        internal fun updateDpsPayload(dpIds: List<Int>): ByteArray =
            TuyaJson.write(linkedMapOf("dpId" to dpIds)).toByteArray(Charsets.UTF_8)

        /** The HEART_BEAT payload, as tinytuya's payload_dict builds it for every version: `{"gwId":id,"devId":id}`. */
        internal fun heartbeatPayload(id: String): ByteArray =
            TuyaJson.write(linkedMapOf("gwId" to id, "devId" to id)).toByteArray(Charsets.UTF_8)

        /**
         * One request frame as tinytuya's `_encode_message` builds it. The `3.x`+12-null version header goes in front
         * of the payload unless the command is header-less (queries, UPDATEDPS, HEART_BEAT, negotiation). 3.5: 6699
         * frame, AES-GCM with [key] and [iv]. 3.4: 55AA, AES-ECB with [key] over header+payload, HMAC-SHA256 with
         * [key]. 3.3: 55AA, AES-ECB with [key] (the local key) over the payload, the clear header in front, CRC32.
         * [key] is the negotiated session key on 3.4/3.5.
         */
        internal fun encodeRequest(
            version: Double,
            versionHeader: ByteArray,
            cmd: Int,
            payload: ByteArray,
            seqno: Long,
            key: ByteArray,
            iv: ByteArray?,
        ): ByteArray {
            val withHeader = if (cmd !in TuyaCmd.NO_PROTOCOL_HEADER) versionHeader + payload else payload
            return when {
                version >= 3.5 -> TuyaCodec.encode6699(seqno, cmd, withHeader, key, requireNotNull(iv) { "3.5 needs an IV" })
                version >= 3.4 -> TuyaCodec.encode55AA(seqno, cmd, TuyaCrypto.ecbEncrypt(withHeader, key), key)
                else -> {
                    val enc = TuyaCrypto.ecbEncrypt(payload, key)
                    val body = if (cmd !in TuyaCmd.NO_PROTOCOL_HEADER) versionHeader + enc else enc
                    TuyaCodec.encode55AA(seqno, cmd, body, null)
                }
            }
        }
    }
}
