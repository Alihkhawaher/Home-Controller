package com.technlov.home.connectors.tuya

import com.technlov.home.core.Connector
import com.technlov.home.core.ConnectorException
import com.technlov.home.core.ConnectorInfo
import com.technlov.home.core.DeviceCategory
import com.technlov.home.core.DeviceRef
import com.technlov.home.core.DeviceSession
import com.technlov.home.core.EventLog
import com.technlov.home.core.LocalNetwork
import com.technlov.home.core.error
import com.technlov.home.core.info
import com.technlov.home.core.trace
import com.technlov.home.core.warn
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException
import org.json.JSONObject

/** Tuning for [TuyaConnector]. */
data class TuyaConfig(
    val port: Int = TuyaClient.DEFAULT_PORT,
    /** How long to wait for one TCP reply (and for the connect itself). */
    val requestTimeoutMs: Long = 2_000,
    /** The UDP ports discovery listens on: 6666 (3.1 plaintext), 6667 (3.3 AES-ECB), 6699 (3.5 AES-GCM). */
    val discoveryPorts: List<Int> = listOf(6666, 6667, 6699),
    /** How long [TuyaConnector.open] listens for a device's own broadcast when its address is unknown or stale
     *  (devices announce themselves about every 5 s). */
    val locateTimeoutMs: Long = 7_000,
    /** An open session sends a HEART_BEAT after this long with nothing sent: devices drop an idle socket
     *  (tinytuya's monitor uses 12 s, tuya-local 5 s). */
    val heartbeatIntervalMs: Long = 10_000,
    /** A connection that has heard nothing for this many heartbeat intervals is closed and opened again. */
    val silentIntervals: Int = 3,
    /** A dropped connection is reopened after 1 s, then 2, 4 ... up to this. */
    val reconnectMaxDelayMs: Long = 60_000,
    val clock: () -> Long = System::currentTimeMillis,
)

/**
 * Tuya Wi-Fi devices over their local LAN protocol (JSON over TCP 6668, AES, protocol 3.3/3.4/3.5). No cloud, no
 * account: the user supplies each device's `device id`, `local key`, IP and protocol version (read from the Tuya app
 * or a cloud export). The key stays on the phone and is never logged.
 *
 * Discovery is passive: Tuya devices broadcast their presence on the LAN (UDP 6666/6667/6699), which tells us a
 * device's id, IP and protocol version -- but never its key, which is why [open] needs the key in [DeviceRef.extras].
 *
 * An open session keeps a live connection to its device (pushes, heartbeats, reconnects); that work runs in [scope].
 * Tests pass their own scope (a test's background scope) so it runs on virtual time.
 */
class TuyaConnector(
    private val transport: TuyaTransport = TcpTuyaTransport(),
    private val config: TuyaConfig = TuyaConfig(),
    private val log: EventLog = EventLog.None,
    scope: CoroutineScope? = null,
) : Connector {
    private val links: CoroutineScope = scope ?: CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("tuya-links") +
            CoroutineExceptionHandler { _, e -> log.error(TuyaClient.SOURCE, "live connection error", e) },
    )

    override val info = ConnectorInfo(
        id = ID,
        name = "Tuya devices",
        description = "Tuya Wi-Fi plugs, meters and switches (and Tuya-made devices under other brands), controlled directly on your Wi-Fi.",
        categories = setOf(DeviceCategory.SWITCH, DeviceCategory.SENSOR, DeviceCategory.OTHER),
        version = "0.1.0",
        protocol = "Local TCP 6668, AES 3.3/3.4/3.5 (no cloud, no account)",
    )

    /**
     * Listens for Tuya broadcasts for [timeoutMs] and returns each device that announced itself, once. The device's
     * key is not in a broadcast, so the returned [DeviceRef]s carry no key: the app collects it from the user before
     * [open]. [network] is unused (discovery is passive).
     */
    override suspend fun discover(network: LocalNetwork, timeoutMs: Long): List<DeviceRef> = scan(timeoutMs, null)

    /** Listens for broadcasts; with [wantId], stops as soon as that device has announced itself. */
    private suspend fun scan(timeoutMs: Long, wantId: String?): List<DeviceRef> {
        val found = LinkedHashMap<String, DeviceRef>()
        val start = config.clock()
        var datagrams = 0
        val socket = try {
            transport.openDiscoverySocket(config.discoveryPorts)
        } catch (e: Exception) {
            log.warn(TuyaClient.SOURCE, "discovery: cannot open the UDP listeners (${e.message ?: e.javaClass.simpleName})")
            return emptyList()
        }
        socket.use {
            fun gather() {
                for (d in socket.drain()) {
                    datagrams++
                    val info = decodeBroadcast(d) ?: continue
                    val id = TuyaJson.optString(info, "gwId")?.trim()?.takeIf { it.isNotEmpty() }
                        ?: TuyaJson.optString(info, "id")?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    if (id !in found) found[id] = toDeviceRef(id, info, d.host)
                }
            }
            // Bounded by rounds as well as by the clock, so a stalled clock can never make this spin.
            var rounds = 0
            val maxRounds = timeoutMs / 200 + 2
            while (config.clock() - start < timeoutMs && rounds++ < maxRounds) {
                gather()
                if (wantId != null && wantId in found) break
                val left = timeoutMs - (config.clock() - start)
                if (left <= 0) break
                delay(left.coerceAtMost(200))
            }
            gather()
        }
        log.info(
            TuyaClient.SOURCE,
            "discovery: ${found.size} device(s) in ${config.clock() - start} ms; $datagrams broadcast(s) seen on " +
                "UDP ${config.discoveryPorts.joinToString("/")}",
        )
        return found.values.toList()
    }

    override suspend fun open(device: DeviceRef): DeviceSession {
        if (device.connectorId != ID) throw ConnectorException("${device.name} is not a Tuya device")
        val keyText = device.extras[EXTRA_LOCAL_KEY]?.trim().orEmpty()
        if (keyText.length != 16) {
            throw ConnectorException("${device.name}: enter the device's 16-character local key before connecting")
        }
        // A configuration error fails at once: a new address cannot cure it.
        val storedVersion = device.extras[EXTRA_VERSION]?.trim()?.takeIf { it.isNotEmpty() } ?: "3.3"
        if ((storedVersion.toDoubleOrNull() ?: 3.3) < 3.3) {
            throw ConnectorException("${device.name}: protocol $storedVersion is not supported (3.3, 3.4 and 3.5 only)")
        }
        // Imported devices arrive without an address: find it by the device's own broadcast.
        val target = if (device.address.isBlank()) {
            locate(device) ?: throw ConnectorException("${device.name}: not found on this Wi-Fi -- is it switched on?")
        } else {
            device
        }
        return try {
            openAt(target, keyText)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // DHCP may have moved it: look for its broadcast and try once more at the new address.
            val moved = locate(target)
            if (moved == null || moved.address == target.address) throw e
            log.info(TuyaClient.SOURCE, "${device.name}: moved from ${target.address} to ${moved.address}")
            openAt(moved, keyText)
        }
    }

    /**
     * Finds [device] by its own broadcast and returns it at its current address, with the protocol version the
     * broadcast states. The returned session's [DeviceSession.device] carries that address, so the app can keep it.
     */
    private suspend fun locate(device: DeviceRef): DeviceRef? {
        val hit = scan(config.locateTimeoutMs, device.id).firstOrNull { it.id == device.id } ?: return null
        val version = hit.extras[EXTRA_VERSION]
        return device.copy(
            address = hit.address,
            extras = if (version != null) device.extras + (EXTRA_VERSION to version) else device.extras,
        )
    }

    private suspend fun openAt(device: DeviceRef, keyText: String): DeviceSession {
        val version = device.extras[EXTRA_VERSION]?.trim()?.takeIf { it.isNotEmpty() } ?: "3.3"
        val verNum = version.toDoubleOrNull() ?: 3.3
        if (verNum < 3.3) {
            throw ConnectorException("${device.name}: protocol $version is not supported (3.3, 3.4 and 3.5 only)")
        }
        val category = device.extras[EXTRA_CATEGORY]?.trim()?.takeIf { it.isNotEmpty() }
        val productId = device.extras[EXTRA_PRODUCT_ID]?.trim()?.takeIf { it.isNotEmpty() }
        val override = TuyaModel.parseDpMapOverride(device.extras[EXTRA_DP_MAP])
        val spec = TuyaModel.parseDpSpec(device.extras[EXTRA_DP_SPEC])
        val points = TuyaModel.points(category, productId, override, spec)

        val client = TuyaClient(
            host = device.address,
            id = device.id,
            localKey = keyText.toByteArray(Charsets.US_ASCII),
            versionText = version,
            transport = transport,
            port = config.port,
            timeoutMs = config.requestTimeoutMs,
            clock = config.clock,
            log = log,
        )
        val who = "${device.name} (${device.address})"
        val session = TuyaSession(device, client, points, config, log, links)
        var opened = false
        try {
            session.start()
            opened = true
            log.info(
                TuyaClient.SOURCE,
                "$who: opened (protocol $version, ${session.capabilities.size} capabilities, dev_type ${client.devType})",
            )
            return session
        } catch (e: TuyaProtocolException) {
            log.warn(TuyaClient.SOURCE, "$who: open failed: ${e.message}")
            throw ConnectorException("${device.name}: ${e.message}", e)
        } catch (e: ConnectorException) {
            log.warn(TuyaClient.SOURCE, "$who: open failed: ${e.message}")
            throw e
        } finally {
            // Whatever went wrong (a cancellation included), no socket or keep-alive may outlive a failed open.
            if (!opened) session.close()
        }
    }

    /** Decrypts one discovery datagram to its JSON, picking the cipher from the UDP port it arrived on. */
    private fun decodeBroadcast(d: TuyaDatagram): JSONObject? = try {
        val frame = TuyaCodec.parse(d.data, if (frameIs6699(d.data)) TuyaCrypto.UDP_KEY else null)
        val text = if (frame.prefix == TuyaCodec.PREFIX_6699) {
            String(frame.payload, Charsets.UTF_8)
        } else {
            val p = frame.payload
            if (p.isNotEmpty() && p[0] == '{'.code.toByte()) String(p, Charsets.UTF_8)
            else String(TuyaCrypto.ecbDecrypt(p, TuyaCrypto.UDP_KEY), Charsets.UTF_8)
        }
        TuyaJson.parse(cleanJson(text))
    } catch (e: Exception) {
        log.trace(TuyaClient.SOURCE) { "discovery: ignoring a datagram from ${d.host}:${d.port} (${e.javaClass.simpleName})" }
        null
    }

    private fun frameIs6699(data: ByteArray): Boolean =
        data.size >= 4 && data[0] == 0x00.toByte() && data[1] == 0x00.toByte() && data[2] == 0x66.toByte() && data[3] == 0x99.toByte()

    private fun cleanJson(text: String): String {
        val s = text.indexOf('{')
        val e = text.lastIndexOf('}')
        return if (s in 0 until e) text.substring(s, e + 1) else text
    }

    private fun toDeviceRef(id: String, info: JSONObject, host: String): DeviceRef {
        val productId = TuyaJson.optString(info, "productKey")?.trim()?.takeIf { it.isNotEmpty() }
        val version = TuyaJson.optString(info, "version")?.trim()?.takeIf { it.isNotEmpty() }
        val ip = TuyaJson.optString(info, "ip")?.trim()?.takeIf { it.isNotEmpty() } ?: host
        val knownMeter = productId != null && productId in TuyaModel.PRODUCT_SPECS
        val extras = buildMap {
            if (version != null) put(EXTRA_VERSION, version)
            if (productId != null) put(EXTRA_PRODUCT_ID, productId)
            if (knownMeter) put(EXTRA_CATEGORY, "zndb")
        }
        return DeviceRef(
            connectorId = ID,
            id = id,
            address = ip,
            name = if (knownMeter) "Tuya energy meter" else "Tuya device ${id.takeLast(4).uppercase()}",
            category = if (knownMeter) DeviceCategory.SENSOR else DeviceCategory.OTHER,
            model = productId,
            extras = extras,
        )
    }

    companion object {
        const val ID = "tuya"

        /** The device's 16-character local key. Sensitive: supplied by the user, kept on the phone, never logged. */
        const val EXTRA_LOCAL_KEY = "local_key"

        /** Non-secret extras: protocol version ("3.3"/"3.4"/"3.5"), product id, category, and an optional dp-map override. */
        const val EXTRA_VERSION = "version"
        const val EXTRA_PRODUCT_ID = "product_id"
        const val EXTRA_CATEGORY = "category"

        /** A JSON `dp id -> code` map (the device's local strategy), overriding/extending the category default. */
        const val EXTRA_DP_MAP = "dp_map"

        /** A JSON array of `[dp_id, code, type, scale, unit, writable]` rows from a QR import (highest precedence). */
        const val EXTRA_DP_SPEC = "dp_spec"
    }
}
