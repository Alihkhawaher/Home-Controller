package com.technlov.home.connectors.gree

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentLinkedQueue

/** One datagram that arrived, and the host (IP text) it came from. */
class GreeDatagram(val host: String, val data: ByteArray)

/**
 * How the connector talks UDP. Injectable so tests can put a fake unit behind it; the app uses [UdpGreeTransport].
 * Retries and pacing are not done here: [GreeClient] re-sends on silence and [GreeConnector.discover] plans the
 * discovery passes.
 */
interface GreeTransport {
    /** Send [payload] to host:port and wait up to [timeoutMs] for one reply from that host. Null on silence. */
    suspend fun exchange(host: String, port: Int, payload: ByteArray, timeoutMs: Long): ByteArray?

    /** A socket for discovery: broadcast allowed, replies gathered in the background until it is closed. */
    suspend fun openScanSocket(): GreeScanSocket
}

/** The discovery socket: send scans in batches, pick up whatever answered in between. */
interface GreeScanSocket : AutoCloseable {
    /** Send [payload] to each of [hosts] (broadcast addresses allowed). One unreachable address does not stop the rest. */
    suspend fun send(hosts: List<String>, port: Int, payload: ByteArray)

    /** The replies that arrived since the last call. */
    fun drain(): List<GreeDatagram>

    override fun close()
}

/** The real transport: java.net.DatagramSocket, blocking calls on Dispatchers.IO. */
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

    override suspend fun openScanSocket(): GreeScanSocket = withContext(Dispatchers.IO) { UdpScanSocket() }

    private class UdpScanSocket : GreeScanSocket {
        private val socket = DatagramSocket().apply {
            broadcast = true
            soTimeout = 200
        }
        private val received = ConcurrentLinkedQueue<GreeDatagram>()
        private val reader = Thread({ readLoop() }, "gree-discovery").apply {
            isDaemon = true
            start()
        }

        private fun readLoop() {
            val buf = ByteArray(RECEIVE_BUFFER)
            while (!socket.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(p)
                    val host = p.address?.hostAddress ?: continue
                    received += GreeDatagram(host, p.data.copyOfRange(p.offset, p.offset + p.length))
                } catch (e: SocketTimeoutException) {
                    // poll again (lets the loop notice close())
                } catch (e: SocketException) {
                    if (socket.isClosed) return
                } catch (e: IOException) {
                    // e.g. ICMP port unreachable surfaced on some stacks; keep listening
                }
            }
        }

        override suspend fun send(hosts: List<String>, port: Int, payload: ByteArray) = withContext(Dispatchers.IO) {
            for (h in hosts) {
                runCatching {
                    socket.send(DatagramPacket(payload, payload.size, InetSocketAddress(InetAddress.getByName(h), port)))
                }
            }
        }

        override fun drain(): List<GreeDatagram> {
            val out = ArrayList<GreeDatagram>()
            while (true) out += received.poll() ?: break
            return out
        }

        override fun close() {
            socket.close()
            reader.join(500)
        }
    }

    private companion object {
        const val RECEIVE_BUFFER = 8192
    }
}
