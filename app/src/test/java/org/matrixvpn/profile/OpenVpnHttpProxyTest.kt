package org.matrixvpn.profile

import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class OpenVpnHttpProxyTest {
    private val tcpProfile = "client\nproto tcp-client\nremote vpn.example 443\n"

    @Test
    fun addsHttpConnectDirectiveToTcpProfile() {
        val result = OpenVpnHttpProxy.apply(tcpProfile, "proxy.example", 8080)
        assertTrue(result.contains("http-proxy proxy.example 8080"))
        assertTrue(result.contains("http-proxy-option VERSION 1.1"))
        assertTrue(result.contains("remote vpn.example 443"))
    }

    @Test
    fun refusesUdpOnlyProfile() {
        assertThrows(IllegalArgumentException::class.java) {
            OpenVpnHttpProxy.apply("client\nproto udp\nremote vpn.example 1194\n", "proxy.example", 8080)
        }
    }

    @Test
    fun refusesInvalidHostPortAndDuplicateDirective() {
        assertThrows(IllegalArgumentException::class.java) { OpenVpnHttpProxy.apply(tcpProfile, "bad host", 8080) }
        assertThrows(IllegalArgumentException::class.java) { OpenVpnHttpProxy.apply(tcpProfile, "proxy.example", 70000) }
        assertThrows(IllegalArgumentException::class.java) {
            OpenVpnHttpProxy.apply(tcpProfile + "http-proxy old.example 3128\n", "proxy.example", 8080)
        }
    }
}
