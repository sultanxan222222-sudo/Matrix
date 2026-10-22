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

    @Test fun detectsAmneziaWgProfileWithWarpObfuscationFields() {
        val profile = """
            [Interface]
            PrivateKey = EXAMPLE_PRIVATE_KEY
            Address = 172.16.0.2/32
            DNS = 1.1.1.1
            MTU = 1280
            Jc = 3
            Jmin = 64
            Jmax = 128
            S1 = 0
            S2 = 0
            S3 = 0
            S4 = 0
            H1 = 1
            H2 = 2
            H3 = 3
            H4 = 4
            I1 = <b 0x00>
            [Peer]
            PublicKey = EXAMPLE_PUBLIC_KEY
            Endpoint = engage.cloudflareclient.com:2408
            AllowedIPs = 0.0.0.0/0
        """.trimIndent()

        val result = VpnProfileInspector.inspect(profile)
        assertEquals(ImportedVpnProfile.Format.AMNEZIAWG, result.format)
        assertEquals(listOf("engage.cloudflareclient.com:2408"), result.endpointHints)
        assertEquals(1, result.peerCount)
        assertTrue(result.hasInlineCredentials)
    }

    @Test fun detectsAwg30GeneratorFieldsWithoutLegacyObfuscationKeys() {
        val profile = """
            [Interface]
            PrivateKey = EXAMPLE_PRIVATE_KEY
            Address = 10.0.0.2/32
            ContentPaddingAddition = 8
            MaxHandshakeAttempts = 5
            KeepaliveTimeout = 30
            [Peer]
            PublicKey = EXAMPLE_PUBLIC_KEY
            Endpoint = vpn.example.net:2408
            AllowedIPs = 0.0.0.0/0
        """.trimIndent()

        val result = VpnProfileInspector.inspect(profile)
        assertEquals(ImportedVpnProfile.Format.AMNEZIAWG, result.format)
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
