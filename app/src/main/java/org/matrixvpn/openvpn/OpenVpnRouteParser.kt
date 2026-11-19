package org.matrixvpn.openvpn

/**
 * Converts OpenVPN route masks into Android Builder prefixes.
 * Rejects non-contiguous IPv4 netmasks rather than silently creating a broader route.
 */
internal object OpenVpnRouteParser {
    fun ipv4PrefixFromNetmask(mask: String): Int {
        val parts = mask.split('.')
        require(parts.size == 4) { "Некорректная маска IPv4: $mask" }
        val octets = parts.map {
            require(it.isNotBlank() && it.all(Char::isDigit)) { "Некорректная маска IPv4: $mask" }
            it.toInt().also { value -> require(value in 0..255) { "Некорректная маска IPv4: $mask" } }
        }
        var prefix = 0
        var sawZero = false
        for (octet in octets) {
            for (bit in 7 downTo 0) {
                val set = (octet and (1 shl bit)) != 0
                if (set) {
                    require(!sawZero) { "Маска IPv4 содержит несмежные единицы: $mask" }
                    prefix++
                } else {
                    sawZero = true
                }
            }
        }
        return prefix
    }

    fun parseCidr(value: String): Pair<String, Int> {
        val parts = value.trim().split('/', limit = 2)
        require(parts.size == 2) { "Маршрут IPv6 должен содержать префикс: $value" }
        val prefix = parts[1].toIntOrNull()
            ?: throw IllegalArgumentException("Некорректный префикс маршрута: $value")
        require(prefix in 0..128) { "Префикс IPv6 вне диапазона: $value" }
        require(parts[0].contains(':')) { "Ожидался IPv6-маршрут: $value" }
        return parts[0] to prefix
    }
}
