package com.technlov.home.connectors.tuya

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A pretend Tuya device for tests: it speaks the full encrypted LAN protocol (session-key negotiation for 3.4/3.5,
 * DP_QUERY, CONTROL, HEART_BEAT, UPDATEDPS, STATUS pushes) so a real [TuyaClient] can drive it end to end, and it
 * behaves like the real thing where that matters:
 *  - it takes ONE connection at a time and refuses a second while one is open ([exclusive]);
 *  - below 3.5 a reply carries the request's sequence number; a 3.5 device numbers its frames itself;
 *  - a command is acknowledged with an empty frame and the change then reported as a STATUS push ([echoControl]
 *    switches to the other style, the change echoed in the reply);
 *  - meter readings ([measure]) only reach a status query after an UPDATEDPS asked for them.
 * Test data only: 192.0.2.x documentation addresses and made-up 16-character keys. It never touches a real device.
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
    private val versionHeader = versionBytes + ByteArray(12)
    private val deviceNonce = "FAKEDEVICENONCE1".toByteArray(Charsets.US_ASCII) // 16 bytes
    private var devSeqno = 1L

    /** The control commands the device received, as applied dp maps (for assertions). */
    val controlCommands = mutableListOf<Map<String, Any?>>()
    val statusQueries get() = queryCount
    private var queryCount = 0

    /** Every command received, in order (heartbeats included). */
    val received = CopyOnWriteArrayList<Int>()
    val heartbeats get() = received.count { it == TuyaCmd.HEART_BEAT }

    /** The dp id lists of the UPDATEDPS requests received. */
    val updateDpsRequests = mutableListOf<List<Int>>()

    /** The next this-many requests (heartbeats aside) get no answer: a lost reply. */
    var dropNext = 0

    /** Answers nothing at all, heartbeats included, while set: a device that hung without closing the socket. */
    var silent = false

    /** Refuses a second connection while one is open, as many Tuya devices do. */
    var exclusive = true

    /** Answers a command with its dps in the reply instead of an empty ack followed by a STATUS push. */
    var echoControl = false

    /** Acknowledges UPDATEDPS and pushes the refreshed readings; when false, ignores the command. */
    var answersUpdateDps = true

    /** Answers status queries with a STATUS frame instead of the query's own command (an odd firmware). */
    var queriesAnsweredWithStatus = false

    /** Closes the connection right after each answer, as some older firmware does. */
    var hangUpAfterEachAnswer = false

    /** Sent as a STATUS push just before the next status reply: a push and a reply crossing on the wire. */
    var pushBeforeNextReply: Map<String, Any?>? = null

    /**
     * Sent just before the next status reply: a reply to the same command carrying these dps but the PREVIOUS
     * request's sequence number -- a late answer to an earlier query, still on the wire.
     */
    var staleReplyBeforeNextReply: Map<String, Any?>? = null

    /** Holds the next status reply back until [releaseHeldReply]; anything sent before it goes out at once. */
    var holdNextReply = false
    private var held: Pair<Link, ByteArray>? = null

    /** Reports a command's change (the STATUS push after the empty ack) only when the next query comes in. */
    var reportCommandsLate = false
    private var lateReport: Map<String, Any?>? = null

    fun releaseHeldReply() {
        val (link, frame) = held ?: error("no reply is held")
        held = null
        link.deliver(frame)
    }

    /** What the meter reads now; status queries see it only after an UPDATEDPS for that dp. */
    private val readings = LinkedHashMap<String, Any?>()

    private val links = CopyOnWriteArrayList<Link>()
    val openConnections get() = links.size

    fun measure(dp: String, value: Any?) {
        readings[dp] = value
    }

    /** The device reports a change on its own (a button press, an alarm): a STATUS frame on every open connection. */
    fun push(changes: Map<String, Any?>) {
        changes.forEach { (k, v) -> dps[k] = v }
        for (link in links) if (link.ready) link.deliver(statusFrame(link, changes))
    }

    /** The device closes every connection (a reboot, a Wi-Fi drop): the client's reader sees the socket end. */
    fun hangUp() {
        links.toList().forEach { it.hangUp() }
    }

    internal fun open(): Link {
        if (exclusive && links.isNotEmpty()) throw IOException("connection refused: one is already open")
        return Link().also { links += it }
    }

    /** One TCP connection, device side: its own negotiated session key, and the frames on their way to the client. */
    inner class Link : TuyaConnection {
        internal var sessionKey = realKey
        internal var clientNonce = ByteArray(16)
        internal var ready = version < 3.4
        private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
        @Volatile private var closed = false

        override suspend fun send(frame: ByteArray) {
            if (closed) throw IOException("connection closed")
            val answers = handle(this, frame)
            answers.forEach { deliver(it) }
            if (hangUpAfterEachAnswer && ready && answers.isNotEmpty()) hangUp() // the answers still arrive first
        }

        override suspend fun receive(timeoutMs: Long): ByteArray? {
            val result = withTimeoutOrNull(timeoutMs) { inbox.receiveCatching() } ?: return null
            return result.getOrNull() ?: throw IOException("connection closed by the device")
        }

        override fun close() {
            closed = true
            inbox.close()
            links.remove(this)
        }

        internal fun deliver(frame: ByteArray) {
            inbox.trySend(frame)
        }

        internal fun hangUp() = close()
    }

    private fun handle(link: Link, req: ByteArray): List<ByteArray> {
        val cmd = peekCmd(req)
        received += cmd
        if (silent) return emptyList()
        if (cmd != TuyaCmd.HEART_BEAT && dropNext > 0) {
            dropNext--
            return emptyList()
        }
        val fk = when {
            cmd == TuyaCmd.SESS_KEY_NEG_START || cmd == TuyaCmd.SESS_KEY_NEG_FINISH -> if (version >= 3.4) realKey else null
            version >= 3.4 -> link.sessionKey
            else -> null
        }
        val frame = TuyaCodec.parse(req, fk, hasRetcode = false)
        return when (cmd) {
            TuyaCmd.SESS_KEY_NEG_START -> {
                link.clientNonce = if (version == 3.4) TuyaCrypto.ecbDecrypt(frame.payload, realKey) else frame.payload
                listOf(encodeNegResp(deviceNonce + TuyaCrypto.hmacSha256(realKey, link.clientNonce)))
            }
            TuyaCmd.SESS_KEY_NEG_FINISH -> {
                val xor = ByteArray(16) { (link.clientNonce[it].toInt() xor deviceNonce[it].toInt()).toByte() }
                link.sessionKey = if (version == 3.4) {
                    TuyaCrypto.ecbEncryptNoPad(xor, realKey)
                } else {
                    TuyaCrypto.gcmEncrypt(xor, realKey, link.clientNonce.copyOf(12), null).first
                }
                link.ready = true
                emptyList() // the client sends FINISH without waiting for a reply
            }
            TuyaCmd.HEART_BEAT -> listOf(encodeOut(link, TuyaCmd.HEART_BEAT, replySeqno(frame.seqno), null))
            TuyaCmd.UPDATEDPS -> handleUpdateDps(link, frame.seqno, decodeRequestJson(link, frame))
            else -> handleData(link, cmd, frame.seqno, decodeRequestJson(link, frame))
        }
    }

    private fun handleData(link: Link, cmd: Int, seq: Long, jsonText: String): List<ByteArray> {
        val obj = runCatching { JSONObject(jsonText) }.getOrDefault(JSONObject())
        val requested = extractDps(obj)
        val nonNull = requested.filterValues { it != null }
        val isQuery = cmd == TuyaCmd.DP_QUERY || cmd == TuyaCmd.DP_QUERY_NEW ||
            (cmd == TuyaCmd.CONTROL_NEW && nonNull.isEmpty())
        val out = mutableListOf<ByteArray>()
        if (isQuery) {
            queryCount++
            lateReport?.let {
                lateReport = null
                out += statusFrame(link, it)
            }
            pushBeforeNextReply?.let {
                pushBeforeNextReply = null
                it.forEach { (k, v) -> dps[k] = v }
                out += statusFrame(link, it)
            }
            staleReplyBeforeNextReply?.let {
                staleReplyBeforeNextReply = null
                out += encodeOut(link, cmd, seq - 1, TuyaJson.write(linkedMapOf("dps" to it)))
            }
            val reply = if (cmd == TuyaCmd.DP_QUERY && firstQueryDataUnvalid) {
                firstQueryDataUnvalid = false
                encodeOut(link, cmd, replySeqno(seq), """{"data":"json obj data unvalid"}""")
            } else if (queriesAnsweredWithStatus) {
                statusFrame(link, dps)
            } else {
                encodeOut(link, cmd, replySeqno(seq), TuyaJson.write(linkedMapOf("dps" to dps)))
            }
            if (holdNextReply) {
                holdNextReply = false
                held = link to reply
            } else {
                out += reply
            }
        } else {
            controlCommands += nonNull
            nonNull.forEach { (k, v) -> dps[k] = v }
            if (echoControl) {
                out += encodeOut(link, cmd, replySeqno(seq), TuyaJson.write(linkedMapOf("dps" to nonNull)))
            } else {
                out += encodeOut(link, cmd, replySeqno(seq), null) // the empty "null ack"
                if (reportCommandsLate) lateReport = nonNull else out += statusFrame(link, nonNull) // then the change
            }
        }
        return out
    }

    private fun handleUpdateDps(link: Link, seq: Long, jsonText: String): List<ByteArray> {
        val arr = runCatching { JSONObject(jsonText).optJSONArray("dpId") }.getOrNull()
        val ids = if (arr == null) emptyList() else (0 until arr.length()).map { arr.optInt(it) }
        updateDpsRequests += ids
        if (!answersUpdateDps) return emptyList()
        val fresh = LinkedHashMap<String, Any?>()
        for (dp in ids.map { it.toString() }) {
            if (dp in readings) fresh[dp] = readings[dp] else if (dp in dps) fresh[dp] = dps[dp]
        }
        fresh.forEach { (k, v) -> dps[k] = v }
        val ack = encodeOut(link, TuyaCmd.UPDATEDPS, replySeqno(seq), null)
        return if (fresh.isEmpty()) listOf(ack) else listOf(ack, statusFrame(link, fresh))
    }

    /** Below 3.5 a reply echoes the request's sequence number; 3.5 devices count on their own. */
    private fun replySeqno(requestSeqno: Long): Long = if (version >= 3.5) devSeqno++ else requestSeqno

    /** A STATUS push, with the version header real devices put in front of it (tinytuya strips it). */
    private fun statusFrame(link: Link, changes: Map<String, Any?>): ByteArray {
        val body = if (version >= 3.4) {
            TuyaJson.write(linkedMapOf("protocol" to 4, "t" to 1_700_000_000, "data" to linkedMapOf("dps" to changes)))
        } else {
            TuyaJson.write(linkedMapOf("devId" to id, "dps" to changes, "t" to 1_700_000_000))
        }
        return encodeOut(link, TuyaCmd.STATUS, devSeqno++, body, withVersionHeader = true)
    }

    private fun extractDps(o: JSONObject): LinkedHashMap<String, Any?> {
        val dpsObj = o.optJSONObject("dps") ?: o.optJSONObject("data")?.optJSONObject("dps")
        val out = LinkedHashMap<String, Any?>()
        if (dpsObj != null) for (k in dpsObj.keys()) out[k] = if (dpsObj.isNull(k)) null else TuyaJson.plain(dpsObj.opt(k))
        return out
    }

    /** Decrypt and strip the version header, mirroring a real device reading a client request. */
    private fun decodeRequestJson(link: Link, frame: TuyaFrame): String {
        var p = frame.payload
        if (version == 3.4) p = TuyaCrypto.ecbDecrypt(p, link.sessionKey)
        if (startsWith(p, versionBytes)) p = p.copyOfRange(versionHeader.size, p.size)
        if (version in 3.2..3.399) p = TuyaCrypto.ecbDecrypt(p, link.sessionKey)
        val text = String(p, Charsets.UTF_8)
        val s = text.indexOf('{')
        val e = text.lastIndexOf('}')
        return if (s in 0 until e) text.substring(s, e + 1) else text
    }

    /** A frame to the client; [jsonText] null makes the empty payload of an acknowledgement. */
    private fun encodeOut(link: Link, cmd: Int, seq: Long, jsonText: String?, withVersionHeader: Boolean = false): ByteArray {
        val body = jsonText?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        return when {
            version >= 3.5 -> {
                val plain = if (withVersionHeader && body.isNotEmpty()) versionHeader + body else body
                TuyaCodec.encode6699(seq, cmd, plain, link.sessionKey, iv12(), retcode = 0)
            }
            version >= 3.4 -> {
                val enc = when {
                    body.isEmpty() -> body
                    withVersionHeader -> TuyaCrypto.ecbEncrypt(versionHeader + body, link.sessionKey)
                    else -> TuyaCrypto.ecbEncrypt(body, link.sessionKey)
                }
                TuyaCodec.encode55AA(seq, cmd, enc, link.sessionKey, retcode = 0)
            }
            else -> {
                val enc = if (body.isEmpty()) body else TuyaCrypto.ecbEncrypt(body, realKey)
                val payload = if (withVersionHeader && body.isNotEmpty()) versionHeader + enc else enc
                TuyaCodec.encode55AA(seq, cmd, payload, null, retcode = 0)
            }
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

    /** The most connections ever open at once to any one device. */
    var maxOpenToOneDevice = 0
    val broadcasts = mutableListOf<TuyaDatagram>()

    override suspend fun connect(host: String, port: Int, timeoutMs: Long): TuyaConnection {
        connects++
        val dev = devices[host] ?: throw IOException("no device at $host")
        return dev.open().also { maxOpenToOneDevice = maxOf(maxOpenToOneDevice, dev.openConnections) }
    }

    override suspend fun openDiscoverySocket(ports: List<Int>): TuyaDiscoverySocket {
        val inbox = ArrayDeque(broadcasts)
        return object : TuyaDiscoverySocket {
            override fun drain(): List<TuyaDatagram> = inbox.toList().also { inbox.clear() }
            override fun close() {}
        }
    }
}

/** An [com.technlov.home.core.EventLog] that keeps every line, verbose on, for leak checks. */
class RecordingLog(override val verbose: Boolean = true) : com.technlov.home.core.EventLog {
    val lines = CopyOnWriteArrayList<String>()
    override fun log(level: com.technlov.home.core.EventLog.Level, source: String, message: String, error: Throwable?) {
        lines += "$level $source $message" + (error?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: "")
    }
    val text get() = lines.joinToString("\n")
}
