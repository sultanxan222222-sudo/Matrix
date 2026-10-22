package org.matrixvpn.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnProfileInspectorTest {
    @Test fun detectsWireGuardAndEndpointWithoutReturningPrivateKey() {
        val profile = """
            [Interface]
            PrivateKey = EXAMPLE_PRIVATE_KEY
            Address = 10.0.0.2/32
            [Peer]
            PublicKey = EXAMPLE_PUBLIC_KEY
            Endpoint = vpn.example.net:51820
            AllowedIPs = 0.0.0.0/0
        """.trimIndent()

        val result = VpnProfileInspector.inspect(profile)
        assertEquals(ImportedVpnProfile.Format.WIREGUARD, result.format)
        assertEquals(listOf("vpn.example.net:51820"), result.endpointHints)
        assertEquals(1, result.peerCount)
        assertTrue(result.hasInlineCredentials)
    }

    @Test fun detectsOpenVpnRemote() {
        val profile = """
            client
            dev tun
            proto udp
            remote vpn.example.net 1194
        """.trimIndent()

        val result = VpnProfileInspector.inspect(profile)
        assertEquals(ImportedVpnProfile.Format.OPENVPN, result.format)
        assertEquals(listOf("vpn.example.net"), result.endpointHints)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyProfile() {
        VpnProfileInspector.inspect("   ")
    }
}
