package org.matrixvpn.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Observes Android's default network only. It does not probe remote VPN servers
 * and does not claim that an internet route or VPN tunnel is healthy.
 */
class NetworkWatcher(
    context: Context,
    private val onStateChanged: (NetworkState) -> Unit
) {
    data class NetworkState(
        val connected: Boolean,
        val transports: Set<String>,
        val validatedInternet: Boolean
    ) {
        fun summary(): String {
            if (!connected) return "Network: disconnected"
            val route = if (validatedInternet) "internet validated" else "internet not validated"
            return "Network: ${transports.ifEmpty { setOf("unknown") }.joinToString("/")}, $route"
        }
    }

    private val manager = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = publishCurrent()
        override fun onLost(network: Network) = publishCurrent()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
            publish(capabilities)
    }

    fun start() {
        if (registered) return
        registered = true
        try {
            manager.registerDefaultNetworkCallback(callback)
            publishCurrent()
        } catch (e: RuntimeException) {
            registered = false
            onStateChanged(NetworkState(false, emptySet(), false))
        }
    }

    fun stop() {
        if (!registered) return
        registered = false
        try {
            manager.unregisterNetworkCallback(callback)
        } catch (_: RuntimeException) {
            // Callback may already have been removed by the system.
        }
    }

    private fun publishCurrent() {
        val network = manager.activeNetwork
        val capabilities = network?.let { manager.getNetworkCapabilities(it) }
        if (capabilities == null) {
            onStateChanged(NetworkState(false, emptySet(), false))
        } else {
            publish(capabilities)
        }
    }

    private fun publish(capabilities: NetworkCapabilities) {
        val transports = buildSet {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("Wi-Fi")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("mobile")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("Ethernet")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("Bluetooth")
        }
        onStateChanged(
            NetworkState(
                connected = true,
                transports = transports,
                validatedInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            )
        )
    }
}
