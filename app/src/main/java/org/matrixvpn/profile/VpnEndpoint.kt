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
            require(value.isNotEmpty()) { "Enter a server address, for example vpn.example.net:51820." }
            require(!value.contains("://") && !value.contains("/") && !value.contains("@")) {
                "Enter host:port only, without a URL scheme, path, or credentials."
            }
            val colon = value.lastIndexOf(':')
            require(colon > 0 && colon < value.lastIndex) {
                "Include a port, for example vpn.example.net:51820."
            }
            val host = value.substring(0, colon).trim()
            val portText = value.substring(colon + 1).trim()
            require(host.isNotEmpty() && host.length <= 253) { "The server host is invalid." }
            require(host.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == ':' }) {
                "The server host contains unsupported characters."
            }
            val port = portText.toIntOrNull()
            require(port != null && port in 1..65535) { "Port must be between 1 and 65535." }
            return VpnEndpoint(host, port)
        }
    }
}
