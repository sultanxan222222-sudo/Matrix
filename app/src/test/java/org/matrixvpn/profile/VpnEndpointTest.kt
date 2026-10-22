package org.matrixvpn.profile

import org.junit.Assert.assertEquals
import org.junit.Test

class VpnEndpointTest {
    @Test fun parsesHostAndPort() {
        val endpoint = VpnEndpoint.parse(" vpn.example.net:51820 ")
        assertEquals("vpn.example.net", endpoint.host)
        assertEquals(51820, endpoint.port)
        assertEquals("vpn.example.net:51820", endpoint.displayAddress)
    }

    @Test(expected = IllegalArgumentException::class)
    fun requiresPort() {
        VpnEndpoint.parse("vpn.example.net")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOutOfRangePort() {
        VpnEndpoint.parse("vpn.example.net:70000")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUrlsContainingCredentialsOrPaths() {
        VpnEndpoint.parse("https://user:pass@vpn.example.net:443/path")
    }
}
