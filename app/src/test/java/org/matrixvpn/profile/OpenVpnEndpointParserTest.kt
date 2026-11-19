package org.matrixvpn.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenVpnEndpointParserTest {
    @Test
    fun readsRemoteTransportOverrideAndGlobalProto() {
        val entries = OpenVpnEndpointParser.parse(
            "client\nproto udp\nremote one.example 1194 tcp-client\nremote two.example 1194\n"
        )
        assertEquals(2, entries.size)
        assertEquals(OpenVpnTransport.TCP, entries[0].transport)
        assertEquals(OpenVpnTransport.UDP, entries[1].transport)
    }

    @Test
    fun marksMissingProtocolUnknown() {
        val entries = OpenVpnEndpointParser.parse("client\nremote vpn.example 443\n")
        assertTrue(entries.single().transport == OpenVpnTransport.UNKNOWN)
    }
}
