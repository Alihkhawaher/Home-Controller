package com.technlov.home.connectors.gree

import com.technlov.home.core.Connector
import com.technlov.home.core.ConnectorException
import com.technlov.home.core.ConnectorInfo
import com.technlov.home.core.DeviceCategory
import com.technlov.home.core.DeviceRef
import com.technlov.home.core.DeviceSession
import com.technlov.home.core.EventLog
import com.technlov.home.core.LocalNetwork
import com.technlov.home.core.ProvisioningCapable
import com.technlov.home.core.info
import com.technlov.home.core.trace
import com.technlov.home.core.warn
import kotlinx.coroutines.delay

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
    /** Discovery also sends a unicast scan to every address of the /24 (broadcast frames get lost on Wi-Fi). */
    val sweep: Boolean = true,
    /** Broadcast scans per discovery (Wi-Fi does not acknowledge broadcast frames, so some are lost)... */
    val broadcastRounds: Int = 3,
    /** ...this far apart, the first one at the start. */
    val broadcastEveryMs: Long = 1_000,
    /** Unicast scans go out in bursts of this many... */
    val burstSize: Int = 16,
    /** ...with this pause between bursts, so the phone's ARP resolution keeps up with a sweep. */
    val burstPauseMs: Long = 25,
    /** The second unicast pass (to every address that has not answered) starts this long after the first ends. */
    val secondPassAfterMs: Long = 1_500,
    /**
     * The discovery window the timings above are planned for. A shorter `timeoutMs` scales the broadcast spacing and
     * the second-pass delay down (bursts and pauses stay as they are); steps that would start in the last fifth of
     * the window are skipped, so replies have time to arrive.
     */
    val discoveryPlanMs: Long = RECOMMENDED_DISCOVERY_TIMEOUT_MS,
    val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val DEFAULT_PORT = 7000

        /** What `discover`'s `timeoutMs` should be for the full plan (3 broadcasts, 2 unicast passes). */
        const val RECOMMENDED_DISCOVERY_TIMEOUT_MS = 4_000L
    }
}

/**
 * Gree Wi-Fi air conditioners over their local protocol (JSON over UDP 7000, AES-128). No cloud, no account: a unit
 * hands its key to anyone on the LAN who asks (bind), so the key is fetched on every [open] and never stored.
 * Units not on the Wi-Fi yet are adopted through [provisioning].
 */
class GreeConnector(
    private val transport: GreeTransport = UdpGreeTransport(),
    private val config: GreeConfig = GreeConfig(),
    private val log: EventLog = EventLog.None,
) : Connector, ProvisioningCapable {
    override val info = ConnectorInfo(
        id = ID,
        name = "Gree air conditioners",
        description = "Gree, EWPE and Gree-made Wi-Fi air conditioners, controlled directly on your Wi-Fi.",
        categories = setOf(DeviceCategory.CLIMATE),
        version = "0.2.0",
        protocol = "Local UDP 7000, AES-128 (no cloud, no account)",
    )

    override val provisioning: GreeProvisioning = GreeProvisioning(log)

    /** Where discovery sends its scans. Known hosts never repeat in [sweep]. */
    internal data class DiscoveryPlan(val known: List<String>, val broadcast: String?, val sweep: List<String>) {
        val isEmpty get() = known.isEmpty() && broadcast == null && sweep.isEmpty()
    }

    internal fun plan(network: LocalNetwork): DiscoveryPlan {
        val known = network.knownHosts.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val broadcast = network.broadcast.trim().takeIf { it.isNotEmpty() && it !in known }
        val sweep = if (!config.sweep) {
            emptyList()
        } else {
            val p = network.prefix.trim().let { if (it.endsWith(".")) it else "$it." }
            if (PREFIX.matches(p)) (1..254).map { "$p$it" }.filter { it !in known && it != broadcast } else emptyList()
        }
        return DiscoveryPlan(known, broadcast, sweep)
    }

    /**
     * Finds units in one run, despite lost frames (with the defaults and a 4 s window):
     *  - pass 1 at 0 ms: the known hosts first, the broadcast, then the /24 sweep, in bursts of 16 every 25 ms so the
     *    phone's ARP keeps up (about 0.4 s);
     *  - broadcasts again at 1 s and 2 s, each with the known hosts that have not answered yet;
     *  - pass 2, 1.5 s after pass 1 ends: every address that has not answered (ARP is warm by then), paced the same;
     *  - replies are gathered until [timeoutMs] and de-duplicated by MAC.
     * A shorter window scales the spacing down (see [GreeConfig.discoveryPlanMs]).
     */
    override suspend fun discover(network: LocalNetwork, timeoutMs: Long): List<DeviceRef> {
        val plan = plan(network)
        if (plan.isEmpty) return emptyList()
        val clock = config.clock
        val start = clock()
        val scale = (timeoutMs.toDouble() / config.discoveryPlanMs.coerceAtLeast(1)).coerceIn(0.0, 1.0)
        val cutoff = start + timeoutMs * 4 / 5
        val payload = GreeClient.SCAN_FRAME.toByteArray(Charsets.UTF_8)
        val found = LinkedHashMap<String, DeviceRef>()
        val answered = HashSet<String>()
        var replies = 0
        var sent = 0
        var secondPass = 0
        var broadcasts = 0

        transport.openScanSocket().use { socket ->
            fun gather() {
                for (d in socket.drain()) {
                    replies++
                    val info = try {
                        GreeClient.parseScanReply(d.host, d.data, log)
                    } catch (e: GreeProtocolException) {
                        continue // not a Gree unit, or a reply we cannot read
                    }
                    answered += d.host
                    if (info.id !in found) found[info.id] = toDeviceRef(info)
                }
            }

            suspend fun burst(hosts: List<String>) {
                socket.send(hosts, config.port, payload)
                sent += hosts.size
                gather()
            }

            // Broadcast rounds after the first; each also re-asks the known hosts that have not answered yet. They
            // fire on time even in the middle of a pass (between two bursts).
            val broadcastAt = ArrayDeque(
                (1 until config.broadcastRounds).map { start + (it * config.broadcastEveryMs * scale).toLong() }
                    .filter { it < cutoff },
            )

            suspend fun nextBroadcast() {
                broadcastAt.removeFirst()
                if (plan.broadcast != null) broadcasts++
                val targets = plan.known.filter { it !in answered } + listOfNotNull(plan.broadcast)
                if (targets.isEmpty()) return
                log.trace(GreeClient.SOURCE) { "-> scan to ${targets.size} address(es) (broadcast round)" }
                targets.chunked(config.burstSize.coerceAtLeast(1)).forEach { burst(it) }
            }

            suspend fun broadcastIfDue() {
                while (broadcastAt.isNotEmpty() && clock() >= broadcastAt.first()) nextBroadcast()
            }

            /** Paced: bursts of [GreeConfig.burstSize], [GreeConfig.burstPauseMs] apart. */
            suspend fun pass(hosts: List<String>, what: String) {
                if (hosts.isEmpty()) return
                log.trace(GreeClient.SOURCE) { "-> scan to ${hosts.size} address(es) ($what)" }
                hosts.chunked(config.burstSize.coerceAtLeast(1)).forEachIndexed { i, b ->
                    if (i > 0) delay(config.burstPauseMs)
                    broadcastIfDue()
                    burst(b)
                }
            }

            /** Wait until [at], sending the broadcast rounds that fall due meanwhile. */
            suspend fun until(at: Long) {
                while (true) {
                    val next = broadcastAt.firstOrNull()?.takeIf { it < at }
                    val wait = (next ?: at) - clock()
                    if (wait > 0) delay(wait)
                    if (next == null) break
                    nextBroadcast()
                }
                gather()
            }

            // Pass 1: known hosts first, then the first broadcast, then the sweep.
            if (plan.broadcast != null) broadcasts++
            pass(plan.known + listOfNotNull(plan.broadcast) + plan.sweep, "pass 1")

            // Pass 2: every address that has not answered, once ARP has caught up.
            val secondAt = clock() + (config.secondPassAfterMs * scale).toLong()
            if (secondAt < cutoff) {
                until(secondAt)
                val targets = (plan.known + plan.sweep).filter { it !in answered }
                secondPass = targets.size
                pass(targets, "pass 2")
            }
            until(start + timeoutMs)
        }

        val sweepText = if (plan.sweep.isEmpty()) "no sweep" else "sweep ${plan.sweep.first()}..${plan.sweep.last().substringAfterLast('.')}"
        log.info(
            GreeClient.SOURCE,
            "discovery: ${found.size} unit(s) in ${clock() - start} ms; ${plan.known.size} known host(s), " +
                "broadcast ${plan.broadcast ?: "none"} x$broadcasts, $sweepText; $sent scan(s) sent, " +
                "second pass to $secondPass silent address(es); $replies repl${if (replies == 1) "y" else "ies"}",
        )
        return found.values.toList()
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
            log = log,
        )
        val who = "${device.name} (${device.address})"
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
            val session = GreeSession(ref, client, config, log).also { it.prepare() }
            log.info(
                GreeClient.SOURCE,
                "$who: opened (cipher v${client.cipher}, firmware ${ref.firmware ?: "?"}, " +
                    "${session.capabilities.size} capabilities)",
            )
            return session
        } catch (e: GreeProtocolException) {
            log.warn(GreeClient.SOURCE, "$who: open failed: ${e.message}")
            throw ConnectorException("${device.name}: ${e.message}", e)
        } catch (e: ConnectorException) {
            log.warn(GreeClient.SOURCE, "$who: open failed: ${e.message}")
            throw e
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
