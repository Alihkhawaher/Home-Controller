package com.technlov.home.connectors.gree

import com.technlov.home.core.ConnectorException
import org.json.JSONObject
import java.io.IOException

/** What a unit says about itself in its scan reply. [mac] as the unit sent it; [id] is the normalised form. */
data class GreeScanInfo(
    val host: String,
    val mac: String,
    val name: String?,
    val firmware: String?,
    val brand: String?,
    val cipher: Int,
) {
    val id: String get() = GreeClient.normaliseMac(mac)
}

/**
 * The Gree protocol for one unit: scan, bind, status, cmd. Not thread-safe; [GreeSession] serialises calls.
 *
 * UDP has no retransmission and a unit on weak Wi-Fi drops packets, so every request is re-sent on silence -- up to
 * [tries] times for reads and [commandTries] for commands. A request is re-sent only when nothing came back, so a
 * command the unit answered is never repeated.
 *
 * The unit's own key (from bind) lives in this object only: never logged, never in a message or [toString].
 */
class GreeClient(
    val host: String,
    private val transport: GreeTransport,
    private val port: Int = GreeConfig.DEFAULT_PORT,
    private val timeoutMs: Long = 2_500,
    private val tries: Int = 4,
    private val commandTries: Int = 2,
    cipherHint: Int = 1,
) {
    /** The unit's MAC as it reports it (used as `tcid` and in packs). Set by [scan] or given by the caller. */
    var mac: String? = null

    /** 1 = AES-ECB, 2 = AES-GCM. Learnt from the scan reply, confirmed by bind. */
    var cipher: Int = if (cipherHint == 2) 2 else 1
        private set

    private var key: ByteArray? = null
    val isBound: Boolean get() = key != null

    /** Unicast scan: who is at [host]. */
    suspend fun scan(): GreeScanInfo {
        val reply = askRaw(SCAN_FRAME, tries)
        val info = parseScanReply(host, reply)
        mac = info.mac
        cipher = info.cipher
        return info
    }

    /** Ask the unit for its own key. Changes nothing on the unit. Tries the known cipher first, then the other. */
    suspend fun bind() {
        val m = mac ?: scan().mac
        var last: Exception? = null
        for (v in listOf(cipher, if (cipher == 1) 2 else 1)) {
            val gk = GreeCipher.genericKey(v)
            val inner = linkedMapOf<String, Any?>("t" to "bind", "mac" to m, "uid" to 0)
            try {
                val p = openPack(ask(frame(1, m, inner, gk, v), tries), gk, v)
                val k = GreeJson.optString(p, "key")
                if (p.optString("t") == "bindok" && !k.isNullOrEmpty()) {
                    key = k.toByteArray(Charsets.UTF_8)
                    cipher = v
                    return
                }
                last = GreeProtocolException("bind answered '${p.optString("t")}'")
            } catch (e: GreeProtocolException) {
                last = e
            } catch (e: ConnectorException) {
                last = e
            }
        }
        throw ConnectorException("$host: bind failed (${last?.message})")
    }

    /** The asked [columns] the unit knows, with their raw values (it leaves out names it does not know). */
    suspend fun status(columns: List<String>, attempts: Int = tries): Map<String, Any?> {
        val (m, k) = requireBound()
        val inner = linkedMapOf<String, Any?>("t" to "status", "mac" to m, "cols" to columns)
        val p = openPack(ask(frame(0, m, inner, k, cipher), attempts), k, cipher)
        if (p.optString("t") != "dat") throw GreeProtocolException("status answered '${p.optString("t")}'")
        return GreeJson.list(p.optJSONArray("cols")).map { it.toString() }
            .zip(GreeJson.list(p.optJSONArray("dat")))
            .toMap(LinkedHashMap())
    }

    /**
     * CHANGES THE UNIT: one `cmd` setting [values] (column -> code). Returns what the unit confirmed (`p`, or `val`
     * on some modules). Throws [ConnectorException] on silence and [GreeProtocolException] on a refusal.
     */
    suspend fun command(values: Map<String, Int>): Map<String, Any?> {
        require(values.isNotEmpty()) { "nothing to set" }
        val (m, k) = requireBound()
        val inner = linkedMapOf<String, Any?>("t" to "cmd", "opt" to values.keys.toList(), "p" to values.values.toList())
        val p = openPack(ask(frame(0, m, inner, k, cipher), commandTries), k, cipher)
        if (p.optString("t") != "res") throw GreeProtocolException("cmd answered '${p.optString("t")}'")
        if (p.has("r") && p.optInt("r", 200) != 200) throw GreeProtocolException("cmd refused (r=${p.opt("r")})")
        val confirmed = GreeJson.list(p.optJSONArray("p")).ifEmpty { GreeJson.list(p.optJSONArray("val")) }
        return GreeJson.list(p.optJSONArray("opt")).map { it.toString() }.zip(confirmed).toMap(LinkedHashMap())
    }

    override fun toString(): String = "GreeClient($host, v$cipher, bound=$isBound)"

    private fun requireBound(): Pair<String, ByteArray> {
        val m = mac ?: throw IllegalStateException("scan or set mac first")
        val k = key ?: throw IllegalStateException("bind first")
        return m to k
    }

    private fun frame(i: Int, mac: String, inner: Map<String, Any?>, key: ByteArray, v: Int): String {
        val sealed = GreeCipher.encrypt(GreeJson.write(inner), key, v)
        val outer = linkedMapOf<String, Any?>(
            "cid" to "app", "i" to i, "t" to "pack", "uid" to 0, "tcid" to mac, "pack" to sealed.pack,
        )
        if (sealed.tag != null) outer["tag"] = sealed.tag
        return GreeJson.write(outer)
    }

    private suspend fun ask(frame: String, attempts: Int): JSONObject = GreeJson.parse(String(askRaw(frame, attempts), Charsets.UTF_8))

    private suspend fun askRaw(frame: String, attempts: Int): ByteArray {
        val bytes = frame.toByteArray(Charsets.UTF_8)
        val n = attempts.coerceAtLeast(1)
        repeat(n) {
            val reply = try {
                transport.exchange(host, port, bytes, timeoutMs)
            } catch (e: IOException) {
                throw ConnectorException("cannot reach $host: ${e.message ?: e.javaClass.simpleName}", e)
            }
            if (reply != null) return reply
        }
        throw ConnectorException("no answer from $host on UDP $port ($n tries)")
    }

    companion object {
        internal const val SCAN_FRAME = "{\"t\":\"scan\"}"

        /** "F0:FE:6B:00:00:01" -> "f0fe6b000001": lowercase hex, no separators. */
        fun normaliseMac(mac: String): String = mac.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }

        private fun openPack(o: JSONObject, key: ByteArray, v: Int): JSONObject {
            val pack = GreeJson.optString(o, "pack")
            if (o.optString("t") != "pack" || pack == null) throw GreeProtocolException("reply is not a pack")
            return GreeJson.parse(GreeCipher.decrypt(pack, key, v, GreeJson.optString(o, "tag")))
        }

        /** Parses a scan reply (from discovery or a unicast scan). Throws [GreeProtocolException] if it is not one. */
        fun parseScanReply(host: String, data: ByteArray): GreeScanInfo {
            val o = GreeJson.parse(String(data, Charsets.UTF_8))
            val v = if (GreeJson.optString(o, "tag") != null) 2 else 1
            val dev = openPack(o, GreeCipher.genericKey(v), v)
            if (dev.has("t") && dev.optString("t") != "dev") throw GreeProtocolException("scan answered '${dev.optString("t")}'")
            val mac = (GreeJson.optString(dev, "mac") ?: GreeJson.optString(o, "cid"))?.trim()
            if (mac.isNullOrEmpty() || normaliseMac(mac).isEmpty()) throw GreeProtocolException("scan reply without a MAC")
            return GreeScanInfo(
                host = host,
                mac = mac,
                name = GreeJson.optString(dev, "name")?.trim()?.takeIf { it.isNotEmpty() },
                firmware = GreeJson.optString(dev, "ver")?.trim()?.takeIf { it.isNotEmpty() },
                brand = GreeJson.optString(dev, "brand")?.trim()?.takeIf { it.isNotEmpty() },
                cipher = v,
            )
        }
    }
}
