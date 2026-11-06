package org.matrixvpn.caravan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class VpnGateCsvParserTest {
    private fun encodedProfile(): String {
        val certificatePayload = "A".repeat(180)
        val text = "client\nproto udp\nremote 192.0.2.10 1194\n<ca>\n-----BEGIN CERTIFICATE-----\n$certificatePayload\n-----END CERTIFICATE-----\n</ca>\n"
        return Base64.getEncoder().encodeToString(text.toByteArray())
    }

    @Test
    fun parsesHeaderAndQuotedMessageWithComma() {
        val profile = encodedProfile()
        val csv = """
            *vpn gate*
            #HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,Uptime,TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64
            relay.example,192.0.2.10,1000,35,50000000,Japan,JP,12,864000,500,1000000,none,operator,"hello, world",$profile
        """.trimIndent()

        val servers = VpnGateCsvParser.parse(csv)
        assertEquals(1, servers.size)
        assertEquals("hello, world", servers[0].message)
        assertEquals("Japan", servers[0].country)
        assertTrue(servers[0].decodeOpenVpnProfile().contains("remote 192.0.2.10 1194"))
    }

    @Test
    fun ignoresMalformedOrNonOpenVpnRows() {
        val profile = Base64.getEncoder().encodeToString("not a profile".toByteArray())
        val csv = """
            #HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,Uptime,TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64
            malformed,192.0.2.2,1,50,100,Japan,JP,1,100,2,3,none,operator,message,$profile
        """.trimIndent()
        assertTrue(VpnGateCsvParser.parse(csv).isEmpty())
    }
}
