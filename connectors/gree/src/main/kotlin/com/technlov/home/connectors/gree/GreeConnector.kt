package com.technlov.home.connectors.gree

import com.technlov.home.core.Connector
import com.technlov.home.core.ConnectorException
import com.technlov.home.core.ConnectorInfo
import com.technlov.home.core.DeviceCategory
import com.technlov.home.core.DeviceRef
import com.technlov.home.core.DeviceSession
import com.technlov.home.core.LocalNetwork

/** Tuning for [GreeConnector]. The defaults suit a home Wi-Fi with a unit or two on a weak link. */
data class GreeConfig(
    val port: Int = DEFAULT_PORT,
    /** How long to wait for one reply before re-sending. */
    val requestTimeoutMs: Long = 2_500,
    /** Sends per read request (scan, bind, status) when the unit stays silent. */
    val tries: Int = 4,
    /** Sends per command; re-sent only when no reply arrived. */
    val commandTries: Int = 2,
    /** Sends per diagnostics request (best effort, so fewer). */
    val diagnosticsTries: Int = 2,
    /** Read the diagnostic columns (fault, filter, firmware, clock) at most this often. */
    val diagnosticsEveryMs: Long = 5 * 60_000L,
    /** Discovery also sends a unicast scan to every address of the /24 (broadcast is not always forwarded). */
    val sweep: Boolean = true,
    /** Discovery sends its scans this many times, spread over the first half of the window. */
    val discoveryRounds: Int = 2,
    val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val DEFAULT_PORT = 7000
    }
}

/**
 * Gree Wi-Fi air conditioners over their local protocol (JSON over UDP 7000, AES-128). No cloud, no account: a unit
 * hands its key to anyone on the LAN who asks (bind), so the key is fetched on every [open] and never stored.
 */
class GreeConnector(
    private val transport: GreeTransport = UdpGreeTransport(),
    private val config: GreeConfig = GreeConfig(),
) : Connector {
    override val info = ConnectorInfo(
        id = ID,
        name = "Gree air conditioners",
        description = "Gree, EWPE and Gree-made Wi-Fi air conditioners, controlled directly on your Wi-Fi.",
        categories = setOf(DeviceCategory.CLIMATE),
        version = "0.1.0",
        protocol = "Local UDP 7000, AES-128 (no cloud, no account)",
    )

    override suspend fun discover(network: LocalNetwork, timeoutMs: Long): List<DeviceRef> {
        val targets = discoveryTargets(network)
        if (targets.isEmpty()) return emptyList()
        val replies = transport.collect(
            targets, config.port, GreeClient.SCAN_FRAME.toByteArray(Charsets.UTF_8), timeoutMs, config.discoveryRounds,
        )
        val byId = LinkedHashMap<String, DeviceRef>()
        for (d in replies) {
            val info = try {
                GreeClient.parseScanReply(d.host, d.data)
            } catch (e: GreeProtocolException) {
                continue // not a Gree unit, or a reply we cannot read
            }
            if (info.id !in byId) byId[info.id] = toDeviceRef(info)
        }
        return byId.values.toList()
    }

    /** Broadcast, the known hosts, and (if [GreeConfig.sweep]) prefix.1 .. prefix.254, without repeats. */
    internal fun discoveryTargets(network: LocalNetwork): List<String> {
        val out = LinkedHashSet<String>()
        network.broadcast.trim().takeIf { it.isNotEmpty() }?.let { out += it }
        network.knownHosts.map { it.trim() }.filter { it.isNotEmpty() }.forEach { out += it }
        if (config.sweep) {
            val p = network.prefix.trim().let { if (it.endsWith(".")) it else "$it." }
            if (PREFIX.matches(p)) for (i in 1..254) out += "$p$i"
        }
        return out.toList()
    }

    override suspend fun open(device: DeviceRef): DeviceSession {
        if (device.connectorId != ID) throw ConnectorException("${device.name} is not a Gree device")
        val client = GreeClient(
            host = device.address,
            transport = transport,
            port = config.port,
            timeoutMs = config.requestTimeoutMs,
            tries = config.tries,
            commandTries = config.commandTries,
            cipherHint = device.extras[EXTRA_CIPHER]?.toIntOrNull() ?: 1,
        )
        try {
            // Confirm who answers at this address before binding: after a DHCP change it may be another unit.
            val found = client.scan()
            if (found.id != GreeClient.normaliseMac(device.id)) {
                throw ConnectorException(
                    "${device.name}: a different Gree unit answers at ${device.address}; search for devices again",
                )
            }
            client.bind()
            val ref = device.copy(
                firmware = found.firmware ?: device.firmware,
                extras = device.extras + mapOf(EXTRA_CIPHER to client.cipher.toString(), EXTRA_MAC to found.mac),
            )
            return GreeSession(ref, client, config).also { it.prepare() }
        } catch (e: GreeProtocolException) {
            throw ConnectorException("${device.name}: ${e.message}", e)
        }
    }

    companion object {
        const val ID = "gree"

        /** Non-secret extras kept on the [DeviceRef]: the cipher version and the MAC as the unit spells it. */
        const val EXTRA_CIPHER = "cipher"
        const val EXTRA_MAC = "mac"

        private val PREFIX = Regex("""^(\d{1,3}\.){3}$""")

        internal fun toDeviceRef(info: GreeScanInfo): DeviceRef = DeviceRef(
            connectorId = ID,
            id = info.id,
            address = info.host,
            name = info.name ?: "Gree AC ${info.id.takeLast(4).uppercase()}",
            category = DeviceCategory.CLIMATE,
            model = "Gree",
            firmware = info.firmware,
            extras = mapOf(EXTRA_CIPHER to info.cipher.toString(), EXTRA_MAC to info.mac),
        )
    }
}
