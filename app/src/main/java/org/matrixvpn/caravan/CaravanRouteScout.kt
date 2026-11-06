package org.matrixvpn.caravan

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.InetSocketAddress
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

enum class RouteScoutStatus {
    TCP_REACHABLE,
    TCP_UNREACHABLE,
    UDP_OR_UNSPECIFIED,
    NO_PHYSICAL_NETWORK,
    PROBE_ERROR
}

data class RouteScoutResult(
    val serverId: String,
    val status: RouteScoutStatus,
    val latencyMillis: Long? = null,
    val detail: String
)

/**
 * Bounded parallel preflight over a physical network while the active VPN
 * remains connected. TCP reachability is only a hint: this does not perform
 * OpenVPN authentication or prove tunnel routing. UDP/unknown routes are not
 * marked blocked and remain eligible for a normal connection attempt.
 */
class CaravanRouteScout(context: Context) {
    private val connectivity =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun scout(servers: List<VpnGateServer>): List<RouteScoutResult> {
        if (servers.isEmpty()) return emptyList()
        val network = findPhysicalNetwork()
            ?: return servers.map {
                RouteScoutResult(it.id, RouteScoutStatus.NO_PHYSICAL_NETWORK, detail = "Физическая сеть для отдельной проверки недоступна.")
            }

        val executor = Executors.newFixedThreadPool(MAX_PARALLEL_PROBES)
        val tasks = servers.map { server ->
            executor.submit(Callable { probeTcpEndpoint(network, server) })
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TOTAL_BUDGET_MS)
        return try {
            tasks.mapIndexed { index, future ->
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) {
                    future.cancel(true)
                    RouteScoutResult(servers[index].id, RouteScoutStatus.PROBE_ERROR, detail = "Общий лимит проверки исчерпан.")
                } else {
                    try {
                        future.get(remaining, TimeUnit.NANOSECONDS)
                    } catch (_: Exception) {
                        future.cancel(true)
                        RouteScoutResult(servers[index].id, RouteScoutStatus.PROBE_ERROR, detail = "TCP-проверка не завершилась.")
                    }
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun findPhysicalNetwork(): Network? =
        connectivity.allNetworks.firstOrNull { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@firstOrNull false
            val physical = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            physical &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

    private fun probeTcpEndpoint(network: Network, server: VpnGateServer): RouteScoutResult {
        val profile = runCatching { server.decodeOpenVpnProfile() }.getOrElse {
            return RouteScoutResult(server.id, RouteScoutStatus.PROBE_ERROR, detail = "Профиль не прошёл проверку.")
        }
        val remote = REMOTE.find(profile)
            ?: return RouteScoutResult(server.id, RouteScoutStatus.PROBE_ERROR, detail = "В профиле нет remote endpoint.")
        val host = remote.groupValues[1]
        val port = remote.groupValues[2].toIntOrNull()
            ?: return RouteScoutResult(server.id, RouteScoutStatus.PROBE_ERROR, detail = "Некорректный порт.")
        val protocol = remote.groupValues.getOrNull(3).orEmpty()
            .ifBlank { PROTO.find(profile)?.groupValues?.getOrNull(1).orEmpty() }
            .lowercase()

        if (!protocol.startsWith("tcp")) {
            return RouteScoutResult(
                server.id,
                RouteScoutStatus.UDP_OR_UNSPECIFIED,
                detail = "UDP или неопределённый транспорт оставлен для реальной попытки подключения."
            )
        }

        val started = System.nanoTime()
        return try {
            val addresses = network.getAllByName(host).take(MAX_ADDRESSES_PER_HOST)
            if (addresses.isEmpty()) {
                return RouteScoutResult(server.id, RouteScoutStatus.TCP_UNREACHABLE, detail = "Имя сервера не разрешилось.")
            }
            var lastFailure: Exception? = null
            for (address in addresses) {
                val socket = network.socketFactory.createSocket()
                try {
                    socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
                    return RouteScoutResult(
                        server.id,
                        RouteScoutStatus.TCP_REACHABLE,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                        "TCP endpoint доступен; OpenVPN handshake ещё не проверен."
                    )
                } catch (e: Exception) {
                    lastFailure = e
                } finally {
                    runCatching { socket.close() }
                }
            }
            RouteScoutResult(
                server.id,
                RouteScoutStatus.TCP_UNREACHABLE,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                lastFailure?.javaClass?.simpleName ?: "TCP endpoint недоступен."
            )
        } catch (e: Exception) {
            RouteScoutResult(
                server.id,
                RouteScoutStatus.PROBE_ERROR,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                e.javaClass.simpleName
            )
        }
    }

    companion object {
        private const val MAX_PARALLEL_PROBES = 6
        private const val MAX_ADDRESSES_PER_HOST = 2
        private const val CONNECT_TIMEOUT_MS = 1_500
        private const val TOTAL_BUDGET_MS = 5_500
        private val REMOTE = Regex("(?im)^\\s*remote\\s+([^\\s]+)\\s+(\\d+)(?:\\s+([^\\s]+))?")
        private val PROTO = Regex("(?im)^\\s*proto\\s+([^\\s]+)")
    }
}
