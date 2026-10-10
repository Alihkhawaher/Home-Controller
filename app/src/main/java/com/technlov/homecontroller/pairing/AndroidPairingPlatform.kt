package com.technlov.homecontroller.pairing

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import androidx.annotation.RequiresApi
import com.technlov.home.core.EventLog
import com.technlov.home.core.LocalNetwork
import com.technlov.home.core.ProvisioningChannel
import com.technlov.home.core.trace
import com.technlov.homecontroller.diag.LogSource
import com.technlov.homecontroller.net.LocalNetworkProvider
import com.technlov.homecontroller.net.WifiScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlin.coroutines.resumeWithException

class HotspotJoinException(message: String) : IOException(message)

/**
 * Android side of pairing. On Android 10+ the app asks for the device's hotspot with a [WifiNetworkSpecifier] (the
 * system shows its own "Connect to device?" prompt) and sends through sockets bound to that network only, so the rest
 * of the phone stays on its usual connection. On Android 8 and 9 the user joins the hotspot in Wi-Fi settings and
 * the app waits until the phone is on it.
 */
class AndroidPairingPlatform(
    context: Context,
    private val network: LocalNetworkProvider,
    private val scanner: WifiScanner,
    private val log: EventLog = EventLog.None,
) : PairingPlatform {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override val joinNeedsUser: Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override suspend fun joinHotspot(ssid: String, password: String?, timeoutMs: Long): HotspotLink =
        if (joinNeedsUser) waitForManualJoin(ssid, timeoutMs) else requestHotspot(ssid, password, timeoutMs)

    @RequiresApi(Build.VERSION_CODES.Q)
    @OptIn(ExperimentalCoroutinesApi::class) // resume(value, onCancellation): releases a link nobody received
    private suspend fun requestHotspot(ssid: String, password: String?, timeoutMs: Long): HotspotLink {
        val cm = connectivity ?: throw HotspotJoinException("No connectivity service")
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .apply { if (password != null) setWpa2Passphrase(password) }
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        return suspendCancellableCoroutine { cont ->
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (!cont.isActive) return
                    val link = BoundHotspotLink(network, this, cm, log)
                    // If the caller is cancelled before it receives the link, release the hotspot anyway.
                    cont.resume(link) { link.close() }
                }

                override fun onUnavailable() {
                    if (cont.isActive) {
                        cont.resumeWithException(HotspotJoinException("The phone could not join \"$ssid\""))
                    }
                }
            }
            try {
                cm.requestNetwork(request, callback, timeoutMs.coerceIn(1_000, Int.MAX_VALUE.toLong()).toInt())
            } catch (e: RuntimeException) {
                cont.resumeWithException(HotspotJoinException("Android refused the request: ${e.javaClass.simpleName}"))
                return@suspendCancellableCoroutine
            }
            cont.invokeOnCancellation { runCatching { cm.unregisterNetworkCallback(callback) } }
        }
    }

    /** Android 8/9: wait until the user has joined [ssid] in Wi-Fi settings, then talk over the Wi-Fi network. */
    private suspend fun waitForManualJoin(ssid: String, timeoutMs: Long): HotspotLink = try {
        withTimeout(timeoutMs) {
            while (scanner.current()?.ssid != ssid) delay(1_000)
            val wifiNetwork = network.current()?.network
            object : HotspotLink {
                override val channel: ProvisioningChannel = DatagramChannel(wifiNetwork, log)
                override fun close() {} // the phone goes back to its usual Wi-Fi once the hotspot closes
            }
        }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        throw HotspotJoinException("The phone did not join \"$ssid\" in time")
    }

    override suspend fun homeNetwork(setupSsid: String): LocalNetwork? {
        // Still on the device's hotspot (it can linger a few seconds): not home yet.
        if (scanner.current()?.ssid == setupSsid) return null
        return network.prepare()?.local
    }

    override suspend fun <T> discoveryWindow(block: suspend () -> T): T = network.withMulticastLock { block() }
}

/** A hotspot joined through a network request; closing it releases the request. */
private class BoundHotspotLink(
    private val network: Network,
    private val callback: ConnectivityManager.NetworkCallback,
    private val cm: ConnectivityManager,
    log: EventLog,
) : HotspotLink {
    override val channel: ProvisioningChannel = DatagramChannel(network, log)

    override fun close() {
        runCatching { cm.unregisterNetworkCallback(callback) }
    }
}

/** UDP request/reply over one network ([network] null = the default one). */
private class DatagramChannel(private val network: Network?, private val log: EventLog) : ProvisioningChannel {
    override suspend fun sendAndReceive(host: String, port: Int, payload: ByteArray, timeoutMs: Long): ByteArray? =
        withContext(Dispatchers.IO) {
            DatagramSocket().use { socket ->
                network?.bindSocket(socket)
                socket.soTimeout = timeoutMs.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
                val address = network?.getByName(host) ?: InetAddress.getByName(host)
                // The payload may hold the Wi-Fi password: only its size is logged.
                log.trace(LogSource.PAIRING) { "UDP ${payload.size} bytes to $host:$port" }
                socket.send(DatagramPacket(payload, payload.size, address, port))
                val buffer = ByteArray(MAX_DATAGRAM)
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                    log.trace(LogSource.PAIRING) { "UDP reply, ${packet.length} bytes from ${packet.address?.hostAddress}" }
                    buffer.copyOf(packet.length)
                } catch (e: SocketTimeoutException) {
                    null
                }
            }
        }

    private companion object {
        const val MAX_DATAGRAM = 65_507
    }
}
