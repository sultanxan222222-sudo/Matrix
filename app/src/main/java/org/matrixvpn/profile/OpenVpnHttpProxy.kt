package org.matrixvpn.profile

/**
 * Applies an HTTP CONNECT proxy to a TCP-capable OpenVPN client profile.
 *
 * This is a proxy transport, not obfs4/Snowflake/WebTunnel and not a guarantee
 * of DPI evasion. The proxy must be trusted and must permit CONNECT to the
 * profile's remote server. UDP-only profiles cannot use this transport.
 */
object OpenVpnHttpProxy {
    fun apply(profile: String, proxyHost: String, proxyPort: Int): String {
        require(profile.isNotBlank()) { "Профиль OpenVPN пуст." }
        require(proxyPort in 1..65535) { "Порт HTTP-прокси должен быть от 1 до 65535." }
        val host = proxyHost.trim()
        require(host.matches(Regex("(?i)[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?"))) {
            "Укажите корректное имя узла или IPv4-адрес HTTP-прокси."
        }
        require(!profile.contains("<connection>", ignoreCase = true)) {
            "Профили с блоками <connection> требуют отдельной настройки прокси внутри каждого блока."
        }
        require(!Regex("(?im)^\\s*http-proxy\\b").containsMatchIn(profile)) {
            "В профиле уже задан HTTP-прокси. Удалите старую настройку вручную или импортируйте исходный профиль."
        }
        require(!Regex("(?im)^\\s*socks-proxy\\b").containsMatchIn(profile)) {
            "Профиль использует SOCKS-прокси; автоматическая замена не выполняется."
        }
        val remotes = OpenVpnEndpointParser.parse(profile)
        require(remotes.any { it.transport == OpenVpnTransport.TCP }) {
            "Этот профиль не содержит TCP-подключения. HTTP CONNECT работает только с сервером OpenVPN, который принимает TCP."
        }

        return profile.trimEnd() +
            "\n\n# MATRIX HTTP CONNECT proxy transport\n" +
            "http-proxy $host $proxyPort\n" +
            "http-proxy-option VERSION 1.1\n"
    }
}

enum class OpenVpnTransport { TCP, UDP, UNKNOWN }

data class OpenVpnRemote(val host: String, val port: Int, val transport: OpenVpnTransport)

object OpenVpnEndpointParser {
    private val remotePattern = Regex("(?im)^\\s*remote\\s+(\\S+)\\s+(\\d+)(?:\\s+(\\S+))?")
    private val protoPattern = Regex("(?im)^\\s*proto\\s+(\\S+)")

    fun parse(profile: String): List<OpenVpnRemote> {
        val defaultProtocol = protoPattern.find(profile)?.groupValues?.getOrNull(1).orEmpty()
        return remotePattern.findAll(profile).mapNotNull { match ->
            val port = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            if (port !in 1..65535) return@mapNotNull null
            val protocol = match.groupValues.getOrNull(3).orEmpty().ifBlank { defaultProtocol }
            val transport = when {
                protocol.startsWith("tcp", ignoreCase = true) -> OpenVpnTransport.TCP
                protocol.startsWith("udp", ignoreCase = true) -> OpenVpnTransport.UDP
                else -> OpenVpnTransport.UNKNOWN
            }
            OpenVpnRemote(match.groupValues[1], port, transport)
        }.toList()
    }
}
