package com.technlov.home.connectors.tuya

import com.technlov.home.core.Value
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * OPT-IN hardware check, skipped everywhere else (CI included). It runs only when two environment variables are set:
 *  - HC_TUYA_LIVE_QR: path of a file holding an "HCTUYA1:" import payload (the same text the import QR carries);
 *  - HC_TUYA_LIVE_IPS: JSON object mapping device id to "ip|version" for the devices to try.
 *
 * READ-ONLY: it imports the payload, opens each listed device on the LAN and reads its state. It never sets a value.
 * It prints decoded values only -- never the payload, the extras or a key.
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
            val (ip, ver) = where.getString(ref.id).split("|")
            val live = ref.copy(address = ip, extras = ref.extras + (TuyaConnector.EXTRA_VERSION to ver))
            try {
                connector.open(live).use { session ->
                    val state = session.read()
                    println("LIVE ${ref.name} [${ref.category}] $ip v$ver: ${session.capabilities.size} capabilities, " +
                        "${state.values.size} values, online=${state.online}")
                    state.values.toSortedMap().forEach { (k, v) -> println("LIVE   $k = ${render(v)}") }
                }
            } catch (e: Exception) {
                println("LIVE ${ref.name} $ip v$ver: FAILED ${e::class.simpleName}: ${e.message}")
            }
        }
    }

    private fun render(v: Value): String = when (v) {
        is Value.Bool -> v.v.toString()
        is Value.Num -> v.v.toString()
        is Value.Text -> "\"${v.v}\""
    }
}
