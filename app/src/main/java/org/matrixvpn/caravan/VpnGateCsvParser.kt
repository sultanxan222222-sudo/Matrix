package org.matrixvpn.caravan

/**
 * Parser for the official VPN Gate CSV directory. It resolves columns by header
 * name rather than relying on a fragile hard-coded column index.
 */
object VpnGateCsvParser {
    private const val MAX_FEED_CHARS = 20_000_000
    private const val MAX_ROWS = 10_000

    fun parse(csv: String): List<VpnGateServer> {
        require(csv.length <= MAX_FEED_CHARS) { "Каталог VPN Gate превысил допустимый размер." }
        val lines = csv.lineSequence().toList()
        val headerIndex = lines.indexOfFirst { line ->
            line.trimStart().removePrefix("#").trimStart()
                .startsWith("HostName,IP,Score", ignoreCase = true)
        }
        require(headerIndex >= 0) { "VPN Gate вернул каталог неизвестного формата." }

        val headerText = lines[headerIndex].trim().removePrefix("#").trim()
        val header = parseRecord(headerText)
            ?: throw IllegalArgumentException("Не удалось разобрать заголовок VPN Gate.")
        val columns = header.mapIndexed { index, name -> name.trim() to index }.toMap()

        fun value(fields: List<String>, key: String): String {
            val index = columns[key] ?: return ""
            return fields.getOrNull(index)?.trim().orEmpty()
        }

        val result = ArrayList<VpnGateServer>()
        for (line in lines.drop(headerIndex + 1)) {
            if (result.size >= MAX_ROWS) break
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("*") || trimmed.startsWith("#")) continue
            val fields = parseRecord(trimmed) ?: continue

            val host = value(fields, "HostName")
            val ip = value(fields, "IP")
            val encoded = value(fields, "OpenVPN_ConfigData_Base64")
            if (host.isBlank() || ip.isBlank() || encoded.length < 128) continue

            val server = VpnGateServer(
                host = host,
                ip = ip,
                score = value(fields, "Score").toLongOrNull() ?: 0L,
                ping = value(fields, "Ping").toLongOrNull() ?: -1L,
                speed = value(fields, "Speed").toLongOrNull() ?: 0L,
                country = value(fields, "CountryLong").ifBlank { "Неизвестная страна" },
                countryCode = value(fields, "CountryShort"),
                sessions = value(fields, "NumVpnSessions").toIntOrNull() ?: 0,
                uptime = value(fields, "Uptime").toLongOrNull() ?: 0L,
                totalUsers = value(fields, "TotalUsers").toLongOrNull() ?: 0L,
                totalTraffic = value(fields, "TotalTraffic").toLongOrNull() ?: 0L,
                logType = value(fields, "LogType"),
                operator = value(fields, "Operator"),
                message = value(fields, "Message"),
                openVpnConfigBase64 = encoded
            )

            // Reject malformed profiles before they enter selection or UI code.
            runCatching { server.decodeOpenVpnProfile() }.onSuccess { result.add(server) }
        }
        return result
    }

    /** RFC-4180-style row parser, including quoted commas and doubled quotes. */
    private fun parseRecord(line: String): List<String>? {
        val fields = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val ch = line[index]
            when {
                ch == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                    field.append('"')
                    index++
                }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> {
                    fields.add(field.toString())
                    field.setLength(0)
                }
                else -> field.append(ch)
            }
            index++
        }
        if (quoted) return null
        fields.add(field.toString())
        return fields
    }
}
