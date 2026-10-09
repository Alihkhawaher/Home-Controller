package com.technlov.homecontroller.net

import com.technlov.home.core.LocalNetwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Ipv4Test {

    @Test
    fun parsesStrictDottedQuads() {
        assertTrue(Ipv4.isValid("192.0.2.10"))
        assertTrue(Ipv4.isValid(" 198.51.100.255 "))
        assertTrue(Ipv4.isValid("0.0.0.0"))
        assertFalse(Ipv4.isValid("192.0.2"))
        assertFalse(Ipv4.isValid("192.0.2.256"))
        assertFalse(Ipv4.isValid("192.0.2.1.5"))
        assertFalse(Ipv4.isValid("192.0..1"))
        assertFalse(Ipv4.isValid("192.0.2.-1"))
        assertFalse(Ipv4.isValid("192.0.2.a"))
        assertFalse(Ipv4.isValid("1923.0.2.1"))
        assertFalse(Ipv4.isValid(""))
    }

    @Test
    fun formatRoundTrips() {
        assertEquals("203.0.113.7", Ipv4.format(Ipv4.parse("203.0.113.7")!!))
        assertEquals("255.255.255.255", Ipv4.format(Ipv4.parse("255.255.255.255")!!))
    }

    @Test
    fun slash24Network() {
        assertEquals(LocalNetwork(broadcast = "192.0.2.255", prefix = "192.0.2."), Ipv4.localNetwork("192.0.2.37", 24))
        assertEquals("192.0.2.0/24", Ipv4.cidr("192.0.2.37", 24))
    }

    @Test
    fun widerAndNarrowerNetworks() {
        // /16: broadcast covers the whole range; the sweep window stays the phone's own /24.
        assertEquals(LocalNetwork("198.51.255.255", "198.51.100."), Ipv4.localNetwork("198.51.100.20", 16))
        assertEquals("198.51.0.0/16", Ipv4.cidr("198.51.100.20", 16))
        // /23 and /26.
        assertEquals("198.51.101.255", Ipv4.localNetwork("198.51.100.20", 23)!!.broadcast)
        assertEquals("203.0.113.127", Ipv4.localNetwork("203.0.113.70", 25)!!.broadcast)
        assertEquals("203.0.113.64/26", Ipv4.cidr("203.0.113.70", 26))
        // Edge prefixes.
        assertEquals("203.0.113.70", Ipv4.localNetwork("203.0.113.70", 32)!!.broadcast)
        assertEquals("255.255.255.255", Ipv4.localNetwork("203.0.113.70", 0)!!.broadcast)
    }

    @Test
    fun rejectsBadInput() {
        assertNull(Ipv4.localNetwork("not an address", 24))
        assertNull(Ipv4.localNetwork("192.0.2.1", 33))
        assertNull(Ipv4.cidr("192.0.2.1", -1))
    }

    @Test
    fun hostLookupPointsAtOneAddress() {
        val network = Ipv4.forHost(" 192.0.2.44 ")!!
        assertEquals("192.0.2.44", network.broadcast)
        assertEquals("", network.prefix)          // no /24 sweep: ask only this address
        assertEquals(listOf("192.0.2.44"), network.knownHosts)
        assertNull(Ipv4.forHost("192.0.2"))
    }
}
