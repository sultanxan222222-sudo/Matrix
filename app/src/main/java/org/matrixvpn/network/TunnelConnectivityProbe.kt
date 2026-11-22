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
    val message: String
)

data class YouTubeProbeResult(
    val reachable: Boolean,
    val httpCode: Int?,
    val message: String
)

object TunnelConnectivityProbe {
    fun check(): TunnelProbeResult {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("https://www.cloudflare.com/cdn-cgi/trace").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 7_000
                readTimeout = 7_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "MATRIX-VPN/0.1")
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                TunnelProbeResult(false, code, null, "Внешняя проверка HTTPS вернула код HTTP $code.")
            } else {
                val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().take(32_000) }
                val warp = body.lineSequence().firstOrNull { it.startsWith("warp=") }?.substringAfter('=')
                TunnelProbeResult(
                    true,
                    code,
                    warp,
                    if (warp == "on" || warp == "plus") {
                        "Внешний HTTPS доступен; статус Cloudflare WARP: $warp."
                    } else {
                        "Внешний HTTPS доступен при активном туннеле. Статус Cloudflare WARP: ${warp ?: "не сообщён"}."
                    }
                )
            }
        } catch (e: Exception) {
            TunnelProbeResult(false, null, null, "Не удалось проверить внешний HTTPS: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            connection?.disconnect()
        }
    }
    /**
     * Tests the YouTube web endpoint from the app process while the Android VPN is active.
     * This is a reachability diagnostic only; it does not certify video CDN throughput.
     */
    fun checkYouTube(): YouTubeProbeResult {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("https://www.youtube.com/generate_204").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 8_000
                instanceFollowRedirects = true
                useCaches = false
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) MATRIX-VPN/0.7")
                setRequestProperty("Accept", "*/*")
            }
            val code = connection.responseCode
            val reachable = code in 200..299
            YouTubeProbeResult(
                reachable,
                code,
                if (reachable) "YouTube endpoint responded." else "YouTube endpoint returned HTTP $code."
            )
        } catch (error: Exception) {
            YouTubeProbeResult(
                false,
                null,
                "YouTube endpoint could not be reached: ${error.message ?: error.javaClass.simpleName}"
            )
        } finally {
            connection?.disconnect()
        }
    }


}
