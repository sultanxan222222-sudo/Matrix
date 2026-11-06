package org.matrixvpn.caravan

object VpnGateRanker {
    fun rank(servers: List<VpnGateServer>): List<VpnGateServer> {
        // A relay can appear more than once in the feed. Keep its best row.
        val unique = servers.groupBy { it.ip }
            .values
            .mapNotNull { rows -> rows.maxByOrNull { it.qualityScore } }

        return unique.sortedWith(
            compareByDescending<VpnGateServer> { it.qualityScore }
                .thenBy { if (it.ping > 0) it.ping else Long.MAX_VALUE }
                .thenByDescending { it.speed }
                .thenByDescending { it.uptime }
        )
    }
}
