package com.technlov.home.connectors.tuya

import com.technlov.home.core.DeviceRef
import com.technlov.home.core.Value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * OPT-IN hardware checks, skipped everywhere else (CI included). They run only when these environment variables are
 * set:
 *  - HC_TUYA_LIVE_QR: path of a file holding an "HCTUYA1:" import payload (the same text the import QR carries);
 *  - HC_TUYA_LIVE_IPS: JSON object mapping device id to "ip|version" for the devices to try;
 *  - HC_TUYA_LIVE_FOLLOW_S (the follow check only): how long to keep each live connection, in seconds.
 *
 * READ-ONLY: they import the payload, open each listed device on the LAN and read its state. They never set a value.
 * They print decoded values and the connection's own log (frame commands, sequence numbers) only -- never the payload,
 * the extras or a key.
 */
class TuyaLiveTest {
    @Test
    fun readsRealDevicesReadOnly() = runBlocking {
        val qrPath = System.getenv("HC_TUYA_LIVE_QR")
        val ips = System.getenv("HC_TUYA_LIVE_IPS")
        assumeTrue("set HC_TUYA_LIVE_QR and HC_TUYA_LIVE_IPS to run", qrPath != null && ips != null)
        val where = JSONObject(ips!!)
        val refs = TuyaImport.parse(File(qrPath!!).readText().trim())
        println("LIVE imported ${refs.size} device(s) from the payload")
        val connector = TuyaConnector()
        for (ref in refs) {
            if (!where.has(ref.id)) continue
            val live = at(ref, where)
            try {
                connector.open(live).use { session ->
                    val state = session.read()
                    println("LIVE ${ref.name} [${ref.category}] ${live.address} v${version(live)}: " +
                        "${session.capabilities.size} capabilities, ${state.values.size} values, online=${state.online}")
                    state.values.toSortedMap().forEach { (k, v) -> println("LIVE   $k = ${render(v)}") }
                }
            } catch (e: Exception) {
                println("LIVE ${ref.name} ${live.address} v${version(live)}: FAILED ${e::class.simpleName}: ${e.message}")
            }
        }
    }

    /**
     * The live connection on real devices: each listed device's session is kept for HC_TUYA_LIVE_FOLLOW_S seconds --
     * polled every 5 s for the first third (status queries interleaved with whatever the device pushes; UPDATEDPS on
     * alternate reads for meters), then left idle so only heartbeats keep the socket, then read once more. Prints the
     * pushes seen (changed values only), the reads, and the connection log.
     */
    @Test
    fun followsRealDevicesReadOnly() = runBlocking {
        val qrPath = System.getenv("HC_TUYA_LIVE_QR")
        val ips = System.getenv("HC_TUYA_LIVE_IPS")
        val seconds = System.getenv("HC_TUYA_LIVE_FOLLOW_S")?.toLongOrNull()
        assumeTrue("set HC_TUYA_LIVE_QR, HC_TUYA_LIVE_IPS and HC_TUYA_LIVE_FOLLOW_S to run",
            qrPath != null && ips != null && seconds != null)
        val where = JSONObject(ips!!)
        val refs = TuyaImport.parse(File(qrPath!!).readText().trim()).filter { where.has(it.id) }
        val log = RecordingLog(verbose = true)
        val links = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val connector = TuyaConnector(log = log, scope = links)
        try {
            refs.map { ref -> async(Dispatchers.IO) { follow(connector, at(ref, where), seconds!! * 1_000) } }
                .awaitAll()
                .forEach { print(it) }
        } finally {
            links.cancel()
        }
        log.lines.forEach { println("LIVELOG $it") }
    }

    private suspend fun follow(connector: TuyaConnector, ref: DeviceRef, totalMs: Long): String {
        val out = StringBuilder("LIVE ${ref.name} ${ref.address} v${version(ref)}\n")
        val started = System.currentTimeMillis()
        fun elapsed() = (System.currentTimeMillis() - started) / 1_000
        try {
            connector.open(ref).use { session ->
                val pushes = mutableListOf<String>()
                val watcher = CoroutineScope(Dispatchers.IO)
                session.updates()?.let { updates ->
                    watcher.launch {
                        var last: Map<String, Value> = emptyMap()
                        updates.collect { state ->
                            val changed = state.values.filter { (k, v) -> last[k] != v }
                            last = state.values
                            synchronized(pushes) {
                                pushes += "+${elapsed()}s online=${state.online} " +
                                    changed.toSortedMap().entries.joinToString { "${it.key}=${render(it.value)}" }
                            }
                        }
                    }
                }
                var ok = 0
                val offline = mutableListOf<String>()
                val readMs = mutableListOf<Long>()
                while (System.currentTimeMillis() - started < totalMs / 3) {
                    val asked = System.currentTimeMillis()
                    val state = session.read()
                    readMs += System.currentTimeMillis() - asked
                    if (state.online) ok++ else offline += "+${elapsed()}s ${state.error}"
                    delay(5_000)
                }
                delay((totalMs - (System.currentTimeMillis() - started)).coerceAtLeast(0)) // idle: heartbeats only
                val last = session.read()
                watcher.cancel()
                out.append("  updates(): ${if (session.updates() == null) "not offered (polled only)" else "offered"}\n")
                out.append("  polls: $ok online, ${offline.size} offline ${offline.joinToString()}; each took ${readMs.joinToString(" ")} ms\n")
                out.append("  read after ${elapsed()}s (idle since ${totalMs / 3_000}s): online=${last.online} ${last.error ?: ""}\n")
                synchronized(pushes) {
                    out.append("  updates seen: ${pushes.size}\n")
                    pushes.forEach { out.append("    $it\n") }
                }
            }
        } catch (e: Exception) {
            out.append("  FAILED after ${elapsed()}s: ${e::class.simpleName}: ${e.message}\n")
        }
        return out.toString()
    }

    private fun at(ref: DeviceRef, where: JSONObject): DeviceRef {
        val (ip, ver) = where.getString(ref.id).split("|")
        return ref.copy(address = ip, extras = ref.extras + (TuyaConnector.EXTRA_VERSION to ver))
    }

    private fun version(ref: DeviceRef) = ref.extras[TuyaConnector.EXTRA_VERSION]

    private fun render(v: Value): String = when (v) {
        is Value.Bool -> v.v.toString()
        is Value.Num -> v.v.toString()
        is Value.Text -> "\"${v.v}\""
    }
}
