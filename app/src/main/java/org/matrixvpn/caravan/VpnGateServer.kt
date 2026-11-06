package org.matrixvpn.caravan

import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.math.ln
import kotlin.math.min

/**
 * A public VPN Gate relay entry. The Base64 profile is sensitive connection
 * material and must never be written to logs or analytics.
 */
data class VpnGateServer(
    val host: String,
    val ip: String,
    val score: Long,
    val ping: Long,
    val speed: Long,
    val country: String,
    val countryCode: String,
    val sessions: Int,
    val uptime: Long,
    val totalUsers: Long,
    val totalTraffic: Long,
    val logType: String,
    val operator: String,
    val message: String,
    val openVpnConfigBase64: String
) {
    val id: String get() = "$ip|$host"

    /**
     * Ranking heuristic only. Catalog numbers are not a guarantee of quality
     * or trustworthiness; actual route verification is required after connect.
     */
    val qualityScore: Double
        get() {
            val throughput = (ln(1.0 + (speed.coerceAtLeast(0L) / 1_000_000.0)) / ln(101.0))
                .coerceIn(0.0, 1.0)
            val latency = if (ping > 0) (1.0 / (1.0 + ping / 250.0)) else 0.0
            val uptimeScore = min(uptime.coerceAtLeast(0L) / 2_592_000.0, 1.0)
            val loadScore = 1.0 / (1.0 + sessions.coerceAtLeast(0) / 40.0)
            val relayScore = (ln(1.0 + score.coerceAtLeast(0L)) / ln(1_000_001.0))
                .coerceIn(0.0, 1.0)
            return 0.35 * throughput + 0.30 * latency +
                0.15 * uptimeScore + 0.10 * loadScore + 0.10 * relayScore
        }

    /** Decode and validate a standalone VPN Gate OpenVPN profile. */
    fun decodeOpenVpnProfile(): String {
        val encoded = openVpnConfigBase64.filterNot(Char::isWhitespace)
        require(encoded.length in 128..3_000_000) { "OpenVPN-профиль имеет недопустимый размер." }
        require(encoded.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/' || it == '=' }) {
            "OpenVPN-профиль содержит недопустимые символы Base64."
        }
        val decoded = try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Каталог вернул повреждённый Base64-профиль.")
        }
        require(decoded.size in 128..2_000_000) { "Расшифрованный профиль имеет недопустимый размер." }
        val profile = String(decoded, StandardCharsets.UTF_8)
        require(!profile.contains('\u0000')) { "Профиль содержит нулевые байты." }
        val hasClient = Regex("(?im)^\\s*client\\s*$").containsMatchIn(profile)
        val hasRemote = Regex("(?im)^\\s*remote\\s+\\S+\\s+\\d+").containsMatchIn(profile)
        require(hasClient && hasRemote) { "Это не полноценный клиентский OpenVPN-профиль." }
        return profile
    }
}
