package org.matrixvpn.network

import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks whether an external HTTPS request succeeds while MATRIX has a tunnel active.
 * A successful response alone does not prove the peer identity or guarantee that every
 * app's traffic is routed through the tunnel; it is a practical connectivity signal.
 */
data class TunnelProbeResult(
    val reachable: Boolean,
    val httpCode: Int?,
    val warpStatus: String?,
    val latencyMillis: Long?,
    val message: String
)

object TunnelConnectivityProbe {
    fun check(): TunnelProbeResult {
        var connection: HttpURLConnection? = null
        return try {
            val startNanos = System.nanoTime()
            connection = (URL("https://www.cloudflare.com/cdn-cgi/trace").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 7_000
                readTimeout = 7_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "MATRIX-VPN/0.1")
            }
            val code = connection.responseCode
            val latencyMillis = ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(0L)
            if (code !in 200..299) {
                TunnelProbeResult(false, code, null, latencyMillis, "Внешняя проверка HTTPS вернула код HTTP $code.")
            } else {
                val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().take(32_000) }
                val warp = body.lineSequence().firstOrNull { it.startsWith("warp=") }?.substringAfter('=')
                TunnelProbeResult(
                    true,
                    code,
                    warp,
                    latencyMillis,
                    if (warp == "on" || warp == "plus") {
                        "Внешний HTTPS доступен; статус Cloudflare WARP: $warp."
                    } else {
                        "Внешний HTTPS доступен при активном туннеле. Статус Cloudflare WARP: ${warp ?: "не сообщён"}."
                    }
                )
            }
        } catch (e: Exception) {
            TunnelProbeResult(false, null, null, null, "Не удалось проверить внешний HTTPS: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            connection?.disconnect()
        }
    }
}
