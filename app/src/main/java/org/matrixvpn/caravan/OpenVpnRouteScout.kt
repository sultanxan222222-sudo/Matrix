package org.matrixvpn.caravan

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import org.matrixvpn.profile.OpenVpnEndpointParser
import org.matrixvpn.profile.OpenVpnTransport
import java.net.InetSocketAddress
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class RelayProbeTarget(
    val id: String,
    val encodedProfile: String
)

enum class RelayProbeStatus {
    TCP_REACHABLE, TCP_UNREACHABLE, UDP_OR_UNKNOWN, NO_PHYSICAL_NETWORK, PROBE_ERROR
}

data class RelayProbeResult(
    val id: String,
    val status: RelayProbeStatus,
    val latencyMs: Long? = null,
    val detail: String
)

/**
 * Bounded TCP preflight using an Android physical Network, never the active VPN.
 * It only ranks reachable TCP listeners. It does not authenticate OpenVPN and
 * does not claim that a relay can complete a VPN handshake.
 */
class OpenVpnRouteScout(context: Context) {
    private val connectivity = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun scout(targets: List<RelayProbeTarget>): List<RelayProbeResult> {
        if (targets.isEmpty()) return emptyList()
        val network = physicalNetwork() ?: return targets.map {
            RelayProbeResult(it.id, RelayProbeStatus.NO_PHYSICAL_NETWORK, detail = "Физическая сеть недоступна.")
        }
        val executor = Executors.newFixedThreadPool(MAX_PARALLEL)
        val futures = targets.map { target ->
            executor.submit(Callable { probe(network, target) })
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TOTAL_BUDGET_MS.toLong())
        return try {
            futures.mapIndexed { index, future ->
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) {
                    future.cancel(true)
                    RelayProbeResult(targets[index].id, RelayProbeStatus.PROBE_ERROR, detail = "Лимит проверки исчерпан.")
                } else {
                    try {
                        future.get(remaining, TimeUnit.NANOSECONDS)
                    } catch (_: Exception) {
                        future.cancel(true)
                        RelayProbeResult(targets[index].id, RelayProbeStatus.PROBE_ERROR, detail = "TCP-проверка не завершилась.")
                    }
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun physicalNetwork(): Network? =
        connectivity.allNetworks.firstOrNull { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@firstOrNull false
            val physical = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            physical && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

    private fun probe(network: Network, target: RelayProbeTarget): RelayProbeResult {
        return try {
            val decoded = android.util.Base64.decode(target.encodedProfile, android.util.Base64.DEFAULT)
            require(decoded.size in 128..2_000_000) { "Размер профиля недопустим." }
            val profile = String(decoded, Charsets.UTF_8)
            val endpoint = OpenVpnEndpointParser.parse(profile)
                .firstOrNull { it.transport == OpenVpnTransport.TCP }
                ?: return RelayProbeResult(target.id, RelayProbeStatus.UDP_OR_UNKNOWN, detail = "UDP/неизвестный транспорт оставлен для обычной попытки.")
            val started = System.nanoTime()
            val addresses = network.getAllByName(endpoint.host).take(MAX_ADDRESSES)
            if (addresses.isEmpty()) {
                return RelayProbeResult(target.id, RelayProbeStatus.TCP_UNREACHABLE, detail = "Имя узла не разрешилось.")
            }
            var lastError: Exception? = null
            for (address in addresses) {
                val socket = network.socketFactory.createSocket()
                try {
                    socket.connect(InetSocketAddress(address, endpoint.port), CONNECT_TIMEOUT_MS)
                    return RelayProbeResult(
                        target.id,
                        RelayProbeStatus.TCP_REACHABLE,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                        "TCP-порт доступен; VPN handshake не проверен."
                    )
                } catch (error: Exception) {
                    lastError = error
                } finally {
                    runCatching { socket.close() }
                }
            }
            RelayProbeResult(
                target.id,
                RelayProbeStatus.TCP_UNREACHABLE,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                lastError?.javaClass?.simpleName ?: "TCP-порт недоступен."
            )
        } catch (error: Exception) {
            RelayProbeResult(target.id, RelayProbeStatus.PROBE_ERROR, detail = error.javaClass.simpleName)
        }
    }

    companion object {
        private const val MAX_PARALLEL = 6
        private const val MAX_ADDRESSES = 2
        private const val CONNECT_TIMEOUT_MS = 1_500
        private const val TOTAL_BUDGET_MS = 6_000
    }
}
