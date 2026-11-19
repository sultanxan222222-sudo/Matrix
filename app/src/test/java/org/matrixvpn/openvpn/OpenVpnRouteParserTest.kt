package org.matrixvpn.openvpn

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenVpnRouteParserTest {
    @Test
    fun parsesDefaultAndSpecificIpv4Masks() {
        assertEquals(0, OpenVpnRouteParser.ipv4PrefixFromNetmask("0.0.0.0"))
        assertEquals(8, OpenVpnRouteParser.ipv4PrefixFromNetmask("255.0.0.0"))
        assertEquals(24, OpenVpnRouteParser.ipv4PrefixFromNetmask("255.255.255.0"))
        assertEquals(32, OpenVpnRouteParser.ipv4PrefixFromNetmask("255.255.255.255"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonContiguousNetmask() {
        OpenVpnRouteParser.ipv4PrefixFromNetmask("255.0.255.0")
    }

    @Test
    fun parsesIpv6DefaultRoute() {
        assertEquals("::" to 0, OpenVpnRouteParser.parseCidr("::/0"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsIpv6RouteWithoutPrefix() {
        OpenVpnRouteParser.parseCidr("2001:db8::1")
    }
}
