package com.technlov.home.connectors.gree

import com.technlov.home.core.Keys
import com.technlov.home.core.LocalNetwork
import com.technlov.home.core.SetValue
import com.technlov.home.core.Value
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException

/** The real UDP transport against a fake unit listening on the loopback interface (no LAN involved). */
class UdpGreeTransportTest {
    private lateinit var server: DatagramSocket
    private lateinit var thread: Thread
    private val unit = FakeGreeUnit(host = "127.0.0.1")

    @Volatile
    private var silent = false

    @Before
    fun start() {
        server = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        thread = Thread {
            val buf = ByteArray(8192)
            while (!server.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                try {
                    server.receive(p)
                } catch (e: SocketException) {
                    break
                }
                if (silent) continue
                val reply = synchronized(unit) { unit.handle(p.data.copyOfRange(0, p.length)) } ?: continue
                server.send(DatagramPacket(reply, reply.size, p.socketAddress))
            }
        }.apply { isDaemon = true; start() }
    }

    @After
    fun stop() {
        server.close()
        thread.join(2_000)
    }

    @Test
    fun exchangeAndSilence() = runBlocking {
        val t = UdpGreeTransport()
        val reply = t.exchange("127.0.0.1", server.localPort, """{"t":"scan"}""".toByteArray(), 2_000)
        val info = GreeClient.parseScanReply("127.0.0.1", reply!!)
        assertEquals("f0fe6b000001", info.id)
        silent = true
        assertNull(t.exchange("127.0.0.1", server.localPort, """{"t":"scan"}""".toByteArray(), 200))
    }

    @Test
    fun collectGathersReplies() = runBlocking {
        val replies = UdpGreeTransport().collect(listOf("127.0.0.1"), server.localPort, """{"t":"scan"}""".toByteArray(), 500, rounds = 2)
        assertEquals(2, replies.size)
        assertTrue(replies.all { it.host == "127.0.0.1" })
    }

    @Test
    fun wholeConnectorOverRealSockets() = runBlocking {
        val gree = GreeConnector(UdpGreeTransport(), GreeConfig(port = server.localPort, sweep = false, requestTimeoutMs = 1_000))
        val found = gree.discover(LocalNetwork("", "", listOf("127.0.0.1")), 400)
        assertEquals(1, found.size)
        val session = gree.open(found[0])
        assertEquals(28.0, session.read().number(Keys.CURRENT_TEMPERATURE)!!, 0.0)
        val s = session.apply(listOf(SetValue(Keys.MODE, Value.Text("heat"))))
        assertEquals("heat", s.text(Keys.MODE))
        assertEquals(4, unit.state["Mod"])
    }
}
