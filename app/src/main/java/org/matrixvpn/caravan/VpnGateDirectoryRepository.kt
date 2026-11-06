package org.matrixvpn.caravan

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches the public VPN Gate directory. Call from a worker thread; this class
 * does not retain profiles on disk and does not log relay configuration data.
 */
class VpnGateDirectoryRepository(
    private val endpoint: String = "https://www.vpngate.net/api/iphone/",
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 20_000
) {
    fun fetchServers(): List<VpnGateServer> {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MATRIX-VPN-Android/0.6.0")
            setRequestProperty("Accept", "text/csv,text/plain,*/*")
        }
        try {
            val code = connection.responseCode
            require(code in 200..299) { "VPN Gate ответил HTTP $code." }
            val declaredSize = connection.contentLengthLong
            require(declaredSize <= MAX_FEED_BYTES) { "Каталог VPN Gate слишком большой." }

            val body = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_FEED_BYTES) { "Каталог VPN Gate превысил допустимый размер." }
                    output.write(buffer, 0, count)
                }
                output.toByteArray().toString(Charsets.UTF_8)
            }
            return VpnGateRanker.rank(VpnGateCsvParser.parse(body))
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val MAX_FEED_BYTES = 20L * 1024L * 1024L
    }
}
