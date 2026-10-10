package com.technlov.home.connectors.tuya

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentLinkedQueue

/** One datagram that arrived during discovery: the host (IP text), the UDP port it came in on, and the bytes. */
class TuyaDatagram(val host: String, val port: Int, val data: ByteArray)

/**
 * How the connector reaches the network. Injectable so tests put a fake device behind it; the app uses
 * [TcpTuyaTransport]. Tuya talks a persistent TCP connection per device (session-key negotiation and then
 * status/commands on the same socket), plus UDP broadcasts for discovery.
 */
interface TuyaTransport {
    /** Open a TCP connection to one device. */
    suspend fun connect(host: String, port: Int, timeoutMs: Long): TuyaConnection

    /** Listen on the given UDP ports for discovery broadcasts; replies are gathered until the socket is closed. */
    suspend fun openDiscoverySocket(ports: List<Int>): TuyaDiscoverySocket
}

/** One open TCP connection to a device. Calls are serialised by [TuyaSession]; not thread-safe on its own. */
interface TuyaConnection : AutoCloseable {
    suspend fun send(frame: ByteArray)

    /** The next whole Tuya frame, or null if none arrived within [timeoutMs]. Throws [IOException] if the link drops. */
    suspend fun receive(timeoutMs: Long): ByteArray?

    override fun close()
}

/** The discovery socket: pick up whatever broadcasts have arrived. */
interface TuyaDiscoverySocket : AutoCloseable {
    /** The datagrams that arrived since the last call. */
    fun drain(): List<TuyaDatagram>

    override fun close()
}

/** The real transport: a TCP socket per device and UDP listeners for discovery, all blocking on Dispatchers.IO. */
class TcpTuyaTransport : TuyaTransport {
    override suspend fun connect(host: String, port: Int, timeoutMs: Long): TuyaConnection = withContext(Dispatchers.IO) {
        val socket = Socket()
        socket.tcpNoDelay = true
        try {
            socket.connect(InetSocketAddress(host, port), timeoutMs.toInt().coerceAtLeast(1))
        } catch (e: IOException) {
            runCatching { socket.close() }
            throw e
        }
        TcpTuyaConnection(socket)
    }

    override suspend fun openDiscoverySocket(ports: List<Int>): TuyaDiscoverySocket =
        withContext(Dispatchers.IO) { UdpDiscoverySocket(ports) }

    /** Reassembles whole Tuya frames from the TCP byte stream; keeps any leftover bytes for the next [receive]. */
    private class TcpTuyaConnection(private val socket: Socket) : TuyaConnection {
        private val input = socket.getInputStream()
        private val output = socket.getOutputStream()
        private var buffer = ByteArray(0)

        override suspend fun send(frame: ByteArray) = withContext(Dispatchers.IO) {
            output.write(frame)
            output.flush()
        }

        override suspend fun receive(timeoutMs: Long): ByteArray? = withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + timeoutMs
            val chunk = ByteArray(RECEIVE_BUFFER)
            while (true) {
                extractFrame()?.let { return@withContext it }
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return@withContext null
                socket.soTimeout = left.toInt().coerceAtLeast(1)
                val n = try {
                    input.read(chunk)
                } catch (e: SocketTimeoutException) {
                    return@withContext null
                }
                if (n < 0) throw IOException("connection closed by the device")
                buffer += chunk.copyOf(n)
            }
            @Suppress("UNREACHABLE_CODE") null
        }

        /** Cut one whole frame off the front of [buffer], skipping any bytes before a valid prefix. */
        private fun extractFrame(): ByteArray? {
            val start = indexOfPrefix(buffer)
            if (start < 0) {
                if (buffer.size > 3) buffer = buffer.copyOfRange(buffer.size - 3, buffer.size) // keep a possible split prefix
                return null
            }
            if (start > 0) buffer = buffer.copyOfRange(start, buffer.size)
            val len = TuyaCodec.frameLength(buffer) ?: return null
            if (buffer.size < len) return null
            val frame = buffer.copyOfRange(0, len)
            buffer = buffer.copyOfRange(len, buffer.size)
            return frame
        }

        override fun close() {
            runCatching { socket.close() }
        }
    }

    private class UdpDiscoverySocket(ports: List<Int>) : TuyaDiscoverySocket {
        private val received = ConcurrentLinkedQueue<TuyaDatagram>()
        private val sockets = ports.map { port ->
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 200
                bind(InetSocketAddress(port))
            }
        }
        private val readers = sockets.map { socket ->
            Thread({ readLoop(socket) }, "tuya-discovery-${socket.localPort}").apply {
                isDaemon = true
                start()
            }
        }

        private fun readLoop(socket: DatagramSocket) {
            val buf = ByteArray(RECEIVE_BUFFER)
            while (!socket.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(p)
                    val host = p.address?.hostAddress ?: continue
                    received += TuyaDatagram(host, socket.localPort, p.data.copyOfRange(p.offset, p.offset + p.length))
                } catch (e: SocketTimeoutException) {
                    // poll again (lets the loop notice close())
                } catch (e: SocketException) {
                    if (socket.isClosed) return
                } catch (e: IOException) {
                    // keep listening
                }
            }
        }

        override fun drain(): List<TuyaDatagram> {
            val out = ArrayList<TuyaDatagram>()
            while (true) out += received.poll() ?: break
            return out
        }

        override fun close() {
            sockets.forEach { it.close() }
            readers.forEach { it.join(500) }
        }
    }

    private companion object {
        const val RECEIVE_BUFFER = 8192
    }
}

/** The first index in [data] of a Tuya frame prefix (55AA or 6699), or -1. */
internal fun indexOfPrefix(data: ByteArray): Int {
    var i = 0
    while (i + 4 <= data.size) {
        if (data[i] == 0x00.toByte() && data[i + 1] == 0x00.toByte()) {
            val b2 = data[i + 2]; val b3 = data[i + 3]
            if ((b2 == 0x55.toByte() && b3 == 0xAA.toByte()) || (b2 == 0x66.toByte() && b3 == 0x99.toByte())) return i
        }
        i++
    }
    return -1
}
