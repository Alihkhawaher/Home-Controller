package com.technlov.homecontroller.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import com.technlov.home.core.LocalNetwork
import java.net.Inet4Address

/** The phone's current home network, found from the Wi-Fi (or Ethernet) link. */
data class CurrentNetwork(
    val local: LocalNetwork,
    /** "192.0.2.0/24", shown while scanning. */
    val cidr: String,
    val network: Network,
    /** True when Android already routes the app's traffic through this network. */
    val isDefault: Boolean,
)

class LocalNetworkProvider(context: Context) {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val wifi = appContext.getSystemService(WifiManager::class.java)
    @Volatile private var boundTo: Network? = null

    /** The Wi-Fi/Ethernet network with an IPv4 address, or null when the phone is not on one. */
    fun current(): CurrentNetwork? {
        val cm = connectivity ?: return null
        val active = cm.activeNetwork
        @Suppress("DEPRECATION") // allNetworks is the only way to see a Wi-Fi network that is not the default one.
        val candidates = (listOfNotNull(active) + cm.allNetworks.toList()).distinct()
        for (network in candidates) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            val local = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!local) continue
            val link = cm.getLinkProperties(network) ?: continue
            val v4 = link.linkAddresses.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
                ?: continue
            val host = v4.address.hostAddress ?: continue
            val localNetwork = Ipv4.localNetwork(host, v4.prefixLength) ?: continue
            val cidr = Ipv4.cidr(host, v4.prefixLength) ?: continue
            return CurrentNetwork(localNetwork, cidr, network, isDefault = network == active)
        }
        return null
    }

    /**
     * Makes sure the app's sockets go through the home Wi-Fi. When the Wi-Fi has no internet, Android may route
     * apps through mobile data instead, and local devices become unreachable. Home Controller talks only to the
     * local network, so binding the whole process to the Wi-Fi is safe. Returns the network in use, or null.
     */
    fun prepare(network: CurrentNetwork? = current()): CurrentNetwork? {
        val cm = connectivity ?: return network
        try {
            val target = network?.network?.takeIf { !network.isDefault }
            if (target != boundTo) {
                cm.bindProcessToNetwork(target)
                boundTo = target
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not bind to the local network", e)
        }
        return network
    }

    /**
     * Runs [block] while holding a Wi-Fi multicast lock: some phones drop broadcast and multicast replies to save
     * battery unless an app holds one, which makes discovery find nothing.
     */
    suspend fun <T> withMulticastLock(block: suspend () -> T): T {
        val lock = try {
            wifi?.createMulticastLock("HomeController:discovery")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Multicast lock unavailable", e)
            null
        }
        try {
            return block()
        } finally {
            try {
                if (lock?.isHeld == true) lock.release()
            } catch (e: RuntimeException) {
                Log.w(TAG, "Multicast lock release failed", e)
            }
        }
    }

    private companion object {
        const val TAG = "LocalNetwork"
    }
}
