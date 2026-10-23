package org.matrixvpn.profile

/**
 * A server address is only a candidate hint. It is not proof that the server
 * speaks a particular VPN protocol and is not enough to authenticate a tunnel.
 */
data class VpnEndpoint(val host: String, val port: Int) {
    val displayAddress: String get() = "$host:$port"

    companion object {
        fun parse(input: String): VpnEndpoint {
            val value = input.trim()
            require(value.isNotEmpty()) { "Введите адрес сервера, например vpn.example.net:51820." }
            require(!value.contains("://") && !value.contains("/") && !value.contains("@")) {
                "Введите только хост:порт, без схемы URL, пути или учётных данных."
            }
            val colon = value.lastIndexOf(':')
            require(colon > 0 && colon < value.lastIndex) {
                "Укажите порт, например vpn.example.net:51820."
            }
            val host = value.substring(0, colon).trim()
            val portText = value.substring(colon + 1).trim()
            require(host.isNotEmpty() && host.length <= 253) { "Недопустимое имя или адрес сервера." }
            require(host.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == ':' }) {
                "Адрес сервера содержит недопустимые символы."
            }
            val port = portText.toIntOrNull()
            require(port != null && port in 1..65535) { "Порт должен быть в диапазоне от 1 до 65535." }
            return VpnEndpoint(host, port)
        }
    }
}
