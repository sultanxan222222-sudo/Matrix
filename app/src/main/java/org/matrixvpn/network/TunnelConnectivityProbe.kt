package org.matrixvpn.network

import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One end-to-end HTTPS request through Android's currently selected network route.
 * These timings are not ICMP RTT and do not directly measure UDP packet loss at a VPN peer.
 */
internal data class HttpsProbeSample(
    val httpCode: Int,
    val elapsedMillis: Long,
    val warpStatus: String?
)

data class TunnelProbeResult(
    val reachable: Boolean,
    val httpCode: Int?,
    val warpStatus: String?,
    /** Median duration of an HTTPS request including DNS/connect/TLS and response headers. */
    val latencyMillis: Long?,
    val message: String,
    val sampleCount: Int = 1,
    val successfulSamples: Int = if (reachable) 1 else 0,
    val probeFailurePercent: Int = if (reachable) 0 else 100,
    /** Mean absolute change between consecutive successful HTTPS request durations. */
    val jitterMillis: Long? = null
)

internal object HttpsProbeMetrics {
    fun summarize(
        samples: List<HttpsProbeSample>,
        attempts: Int,
        lastHttpCode: Int?,
        lastError: String?
    ): TunnelProbeResult {
        require(attempts > 0 && samples.size <= attempts)

        val sorted = samples.map { it.elapsedMillis }.sorted()
        val median = when {
            sorted.isEmpty() -> null
            sorted.size % 2 == 1 -> sorted[sorted.size / 2]
            else -> sorted[sorted.size / 2 - 1] / 2L + sorted[sorted.size / 2] / 2L +
                ((sorted[sorted.size / 2 - 1] % 2L + sorted[sorted.size / 2] % 2L) / 2L)
        }
        val changes = samples.zipWithNext { first, second ->
            abs(second.elapsedMillis - first.elapsedMillis)
        }
        val jitter = changes.takeIf { it.isNotEmpty() }?.average()?.roundToInt()?.toLong()
        val successCount = samples.size
        val failurePercent = (((attempts - successCount) * 100.0) / attempts).roundToInt()
        val reachable = successCount >= (attempts / 2 + 1)
        val code = samples.lastOrNull()?.httpCode ?: lastHttpCode
        val warp = samples.lastOrNull()?.warpStatus

        val message = if (reachable) {
            buildString {
                append("HTTPS: ")
                append(successCount)
                append("/")
                append(attempts)
                append(" успешных; неудач ")
                append(failurePercent)
                append("%; медиана ")
                append(median ?: 0L)
                append(" мс")
                jitter?.let {
                    append("; вариация ")
                    append(it)
                    append(" мс")
                }
                append("; WARP=")
                append(warp ?: "не сообщён")
                append(".")
            }
        } else {
            "Большинство HTTPS-проверок не прошло ($successCount/$attempts). " +
                (lastError ?: "Проверьте маршрут и доступность внешнего сервиса.")
        }

        return TunnelProbeResult(
            reachable = reachable,
            httpCode = code,
            warpStatus = warp,
            latencyMillis = median,
            message = message,
            sampleCount = attempts,
            successfulSamples = successCount,
            probeFailurePercent = failurePercent,
            jitterMillis = jitter
        )
    }
}

object TunnelConnectivityProbe {
    private const val SAMPLE_COUNT = 3
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000

    fun check(): TunnelProbeResult {
        val successful = mutableListOf<HttpsProbeSample>()
        var lastCode: Int? = null
        var lastError: String? = null

        repeat(SAMPLE_COUNT) { sample ->
            var connection: HttpURLConnection? = null
            try {
                val url = URL("https://www.cloudflare.com/cdn-cgi/trace?matrix_probe=$sample")
                val start = System.nanoTime()
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = false
                    useCaches = false
                    setRequestProperty("Cache-Control", "no-cache")
                    setRequestProperty("Pragma", "no-cache")
                    setRequestProperty("User-Agent", "MATRIX-VPN/0.7.0")
                }

                val code = connection.responseCode
                lastCode = code
                val elapsed = ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(0L)
                if (code !in 200..299) {
                    lastError = "HTTPS вернул HTTP $code."
                } else {
                    val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                        val output = StringBuilder()
                        val buffer = CharArray(1024)
                        var total = 0
                        while (total < 32_000) {
                            val count = reader.read(buffer, 0, minOf(buffer.size, 32_000 - total))
                            if (count < 0) break
                            output.append(buffer, 0, count)
                            total += count
                        }
                        output.toString()
                    }
                    val warp = body.lineSequence()
                        .firstOrNull { it.startsWith("warp=") }
                        ?.substringAfter('=')
                    successful += HttpsProbeSample(code, elapsed, warp)
                }
            } catch (error: Exception) {
                lastError = error.message ?: error.javaClass.simpleName
            } finally {
                connection?.disconnect()
            }
        }

        return HttpsProbeMetrics.summarize(successful, SAMPLE_COUNT, lastCode, lastError)
    }
}
