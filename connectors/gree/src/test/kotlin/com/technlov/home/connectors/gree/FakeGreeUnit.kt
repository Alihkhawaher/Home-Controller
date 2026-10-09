package com.technlov.home.connectors.gree

import org.json.JSONArray
import org.json.JSONObject

/**
 * A pretend Gree unit for tests: answers scan, bind, status and cmd the way real modules do. Test data only
 * (192.0.2.x documentation addresses, made-up MACs and keys).
 */
class FakeGreeUnit(
    val host: String = "192.0.2.10",
    val mac: String = "f0fe6b000001",
    val name: String = "",
    val ver: String = "V3.0.0",
    val cipher: Int = 1,
    var unitKey: String = "Fake0Key1Test2Ab",
) {
    val state = linkedMapOf<String, Any>(
        "Pow" to 0, "Mod" to 1, "SetTem" to 21, "TemUn" to 0, "TemRec" to 0, "TemSen" to 68, "WdSpd" to 5,
        "Air" to 0, "Blo" to 1, "Health" to 1, "SwhSlp" to 0, "SlpMod" to 0, "Lig" to 1, "SwingLfRig" to 4,
        "SwUpDn" to 2, "Quiet" to 0, "Tur" to 0, "StHt" to 0, "HeatCoolType" to 0, "SvSt" to 0,
        "AllErr" to 0, "Dfltr" to 0, "hid" to "TEST05RT13V1.00", "time" to "2026-10-10 12:00:00",
    )

    /** Columns this unit does not know (it leaves them out of a status reply). */
    val unknownColumns = mutableSetOf<String>()

    /** The next this-many requests get no reply (UDP loss). */
    var dropNext = 0

    /** Keys the unit still accepts on incoming packs (to simulate a re-pair that changed [unitKey]). */
    private val acceptedOldKeys = mutableListOf<String>()

    /** Every request that arrived (dropped ones too), raw, and the decrypted inner pack of each one answered. */
    val rawRequests = mutableListOf<String>()
    val inner = mutableListOf<JSONObject>()
    val commands get() = inner.filter { it.optString("t") == "cmd" }

    fun rekey(newKey: String) {
        acceptedOldKeys += unitKey
        unitKey = newKey
    }

    fun handle(data: ByteArray): ByteArray? {
        val text = String(data, Charsets.UTF_8)
        rawRequests += text
        if (dropNext > 0) {
            dropNext--
            return null
        }
        val o = JSONObject(text)
        return when (o.optString("t")) {
            "scan" -> reply(
                linkedMapOf(
                    "t" to "dev", "cid" to mac, "bc" to "gree", "brand" to "gree", "catalog" to "gree", "mac" to mac,
                    "mid" to "10001", "model" to "gree", "name" to name, "series" to "gree", "vender" to "1",
                    "ver" to ver, "lock" to 0,
                ),
                GreeCipher.genericKey(cipher),
                i = 0,
            )
            "pack" -> handlePack(o)
            else -> null
        }
    }

    private fun handlePack(o: JSONObject): ByteArray? {
        val generic = o.optInt("i") == 1
        val tag = if (o.has("tag")) o.getString("tag") else null
        val keys = if (generic) listOf(String(GreeCipher.genericKey(cipher))) else listOf(unitKey) + acceptedOldKeys
        // A pack in another cipher or key: real units stay silent.
        val p = keys.firstNotNullOfOrNull { k ->
            runCatching { JSONObject(GreeCipher.decrypt(o.getString("pack"), k.toByteArray(), cipher, tag)) }.getOrNull()
        } ?: return null
        inner += p
        return when (p.optString("t")) {
            "bind" -> reply(
                linkedMapOf("t" to "bindok", "mac" to mac, "key" to unitKey, "r" to 200),
                GreeCipher.genericKey(cipher),
                i = 1,
            )
            "status" -> {
                val asked = JSONArray(p.getJSONArray("cols").toString())
                val cols = (0 until asked.length()).map { asked.getString(it) }
                    .filter { it in state && it !in unknownColumns }
                reply(
                    linkedMapOf("t" to "dat", "mac" to mac, "r" to 200, "cols" to cols, "dat" to cols.map { state[it] }),
                    unitKey.toByteArray(),
                    i = 0,
                )
            }
            "cmd" -> {
                val opt = p.getJSONArray("opt")
                val values = p.getJSONArray("p")
                val names = (0 until opt.length()).map { opt.getString(it) }
                val vals = (0 until values.length()).map { values.getInt(it) }
                names.zip(vals).forEach { (k, v) -> state[k] = v }
                reply(
                    linkedMapOf("t" to "res", "mac" to mac, "r" to 200, "opt" to names, "p" to vals, "val" to vals),
                    unitKey.toByteArray(),
                    i = 0,
                )
            }
            else -> null
        }
    }

    private fun reply(pack: Map<String, Any?>, key: ByteArray, i: Int): ByteArray {
        val sealed = GreeCipher.encrypt(GreeJson.write(pack), key, cipher)
        val outer = linkedMapOf<String, Any?>("t" to "pack", "i" to i, "uid" to 0, "cid" to mac, "tcid" to "", "pack" to sealed.pack)
        if (sealed.tag != null) outer["tag"] = sealed.tag
        return GreeJson.write(outer).toByteArray(Charsets.UTF_8)
    }
}

/** A LAN of fake units behind the [GreeTransport] interface. Records what was sent where. */
class FakeLan(vararg units: FakeGreeUnit) : GreeTransport {
    val units = units.associateBy { it.host }.toMutableMap()
    var exchanges = 0
    val collectTargets = mutableListOf<List<String>>()

    /** Extra datagrams that show up during discovery (noise from other devices). */
    val noise = mutableListOf<GreeDatagram>()

    override suspend fun exchange(host: String, port: Int, payload: ByteArray, timeoutMs: Long): ByteArray? {
        exchanges++
        return units[host]?.handle(payload)
    }

    override suspend fun collect(
        targets: List<String>,
        port: Int,
        payload: ByteArray,
        timeoutMs: Long,
        rounds: Int,
    ): List<GreeDatagram> {
        collectTargets += targets
        val out = mutableListOf<GreeDatagram>()
        repeat(rounds) {
            for (t in targets) {
                val hit = if (t.endsWith(".255")) units.values.toList() else listOfNotNull(units[t])
                for (u in hit) u.handle(payload)?.let { out += GreeDatagram(u.host, it) }
            }
        }
        return out + noise
    }
}
