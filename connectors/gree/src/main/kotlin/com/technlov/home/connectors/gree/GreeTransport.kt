package com.technlov.home.connectors.gree

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/** One datagram that arrived, and the host (IP text) it came from. */
class GreeDatagram(val host: String, val data: ByteArray)

/**
 * How the connector talks UDP. Injectable so tests can put a fake unit behind it; the app uses [UdpGreeTransport].
 * Retries are not done here: [GreeClient] re-sends on silence.
 */
interface GreeTransport {
    /** Send [payload] to host:port and wait up to [timeoutMs] for one reply from that host. Null on silence. */
    suspend fun exchange(host: String, port: Int, payload: ByteArray, timeoutMs: Long): ByteArray?

    /**
     * Discovery: send [payload] to every target (broadcast addresses allowed) from one socket, [rounds] times spread
     * over the first half of the window, and return every datagram that arrives within [timeoutMs].
     */
    suspend fun collect(targets: List<String>, port: Int, payload: ByteArray, timeoutMs: Long, rounds: Int = 1): List<GreeDatagram>
}

/** The real transport: java.net.DatagramSocket on Dispatchers.IO. */
class UdpGreeTransport : GreeTransport {
    override suspend fun exchange(host: String, port: Int, payload: ByteArray, timeoutMs: Long): ByteArray? =
        withContext(Dispatchers.IO) {
            val target = InetAddress.getByName(host)
            DatagramSocket().use { s ->
                s.send(DatagramPacket(payload, payload.size, InetSocketAddress(target, port)))
                receiveFrom(s, target, System.currentTimeMillis() + timeoutMs)
            }
        }

    /** The first datagram from [target] before [deadline]; anything else on this ephemeral port is ignored. */
    private fun receiveFrom(s: DatagramSocket, target: InetAddress, deadline: Long): ByteArray? {
        val buf = ByteArray(RECEIVE_BUFFER)
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return null
            s.soTimeout = left.toInt().coerceAtLeast(1)
            val p = DatagramPacket(buf, buf.size)
            try {
                s.receive(p)
            } catch (e: SocketTimeoutException) {
                return null
            }
            if (p.address == target) return p.data.copyOfRange(p.offset, p.offset + p.length)
        }
    }

    override suspend fun collect(
        targets: List<String>,
        port: Int,
        payload: ByteArray,
        timeoutMs: Long,
        rounds: Int,
    ): List<GreeDatagram> = withContext(Dispatchers.IO) {
        val out = ArrayList<GreeDatagram>()
        val addresses = targets.distinct().mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
        DatagramSocket().use { s ->
            s.broadcast = true
            val start = System.currentTimeMillis()
            val deadline = start + timeoutMs
            val n = rounds.coerceAtLeast(1)
            val gap = if (n > 1) timeoutMs / 2 / (n - 1) else 0L
            var sent = 0
            val buf = ByteArray(RECEIVE_BUFFER)
            while (true) {
                val now = System.currentTimeMillis()
                if (now >= deadline) break
                if (sent < n && now >= start + sent * gap) {
                    for (a in addresses) {
                        // One unreachable address (no route, ICMP error) must not stop the sweep.
                        runCatching { s.send(DatagramPacket(payload, payload.size, InetSocketAddress(a, port))) }
                    }
                    sent++
                    continue
                }
                val nextSend = if (sent < n) start + sent * gap else Long.MAX_VALUE
                val wait = minOf(deadline, nextSend) - now
                if (wait <= 0) continue
                s.soTimeout = wait.toInt().coerceAtLeast(1)
                val p = DatagramPacket(buf, buf.size)
                try {
                    s.receive(p)
                    val host = p.address?.hostAddress ?: continue
                    out += GreeDatagram(host, p.data.copyOfRange(p.offset, p.offset + p.length))
                } catch (e: SocketTimeoutException) {
                    // next send round or the end of the window
                } catch (e: java.io.IOException) {
                    // e.g. ICMP port unreachable surfaced on some stacks; keep listening
                }
            }
        }
        out
    }

    private companion object {
        const val RECEIVE_BUFFER = 8192
    }
}
