package com.technlov.homecontroller.net

import com.technlov.home.core.LocalNetwork

/** IPv4 helpers for building a [LocalNetwork]. Pure Kotlin, unit-tested on the JVM. */
object Ipv4 {

    /** Parses a dotted quad ("192.0.2.10") strictly: four decimal octets 0..255, nothing else. */
    fun parse(text: String): Int? {
        val parts = text.trim().split('.')
        if (parts.size != 4) return null
        var result = 0
        for (part in parts) {
            if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' }) return null
            val octet = part.toInt()
            if (octet > 255) return null
            result = (result shl 8) or octet
        }
        return result
    }

    fun isValid(text: String): Boolean = parse(text) != null

    fun format(address: Int): String =
        "${address ushr 24 and 0xFF}.${address ushr 16 and 0xFF}.${address ushr 8 and 0xFF}.${address and 0xFF}"

    private fun mask(prefixLength: Int): Int = if (prefixLength <= 0) 0 else (-1 shl (32 - prefixLength.coerceAtMost(32)))

    /** "a.b.c." of an address: the /24 window a connector sweeps when it scans host by host. */
    fun sweepPrefix(address: Int): String = format(address).substringBeforeLast('.') + "."

    /**
     * The network the phone is on, from its own address and prefix length (as reported by LinkProperties):
     * broadcast = address | ~mask, prefix = the first three octets for a host sweep.
     */
    fun localNetwork(address: String, prefixLength: Int): LocalNetwork? {
        val ip = parse(address) ?: return null
        if (prefixLength !in 0..32) return null
        val broadcast = ip or mask(prefixLength).inv()
        return LocalNetwork(broadcast = format(broadcast), prefix = sweepPrefix(ip))
    }

    /** "192.0.2.0/24", for showing the user what is being scanned. */
    fun cidr(address: String, prefixLength: Int): String? {
        val ip = parse(address) ?: return null
        if (prefixLength !in 0..32) return null
        return "${format(ip and mask(prefixLength))}/$prefixLength"
    }

    /**
     * A network that points at one host, for "Add by IP address" on networks that drop broadcast: the connector
     * sends its discovery packet straight to [host] (unicast) and also finds it in [LocalNetwork.knownHosts].
     * The prefix is left empty so connectors that sweep the /24 (Gree) ask only this one address.
     */
    fun forHost(host: String): LocalNetwork? {
        val ip = parse(host) ?: return null
        val clean = format(ip)
        return LocalNetwork(broadcast = clean, prefix = "", knownHosts = listOf(clean))
    }
}
