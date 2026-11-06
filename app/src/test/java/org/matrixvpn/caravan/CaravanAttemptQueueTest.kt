package org.matrixvpn.caravan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaravanAttemptQueueTest {
    private fun server(ip: String, host: String) = VpnGateServer(
        host = host, ip = ip, score = 1, ping = 20, speed = 10_000_000,
        country = "Test", countryCode = "TST", sessions = 1, uptime = 1000,
        totalUsers = 1, totalTraffic = 1, logType = "", operator = "", message = "",
        openVpnConfigBase64 = "unused"
    )

    @Test
    fun preferredRouteIsTriedFirstAndNeverRepeated() {
        val a = server("192.0.2.1", "a")
        val b = server("192.0.2.2", "b")
        val queue = CaravanAttemptQueue(listOf(a, b), preferredId = b.id)

        assertEquals(b.id, queue.next()?.id)
        assertEquals(a.id, queue.next()?.id)
        assertNull(queue.next())
        assertEquals(setOf(a.id, b.id), queue.attemptedIds())
    }
}
