package org.matrixvpn.profile

/**
 * Identifies and summarizes a user-selected VPN profile without persisting its
 * contents. This is an import/preview layer only; it does not establish a tunnel.
 */
data class ImportedVpnProfile(
    val format: Format,
    val endpointHints: List<String>,
    val peerCount: Int,
    val hasInlineCredentials: Boolean
) {
    enum class Format { WIREGUARD, OPENVPN, UNKNOWN }
}

object VpnProfileInspector {
    fun inspect(text: String): ImportedVpnProfile {
        val trimmed = text.trim()
        require(trimmed.isNotEmpty()) { "The selected profile is empty." }
        require(trimmed.length <= 2_000_000) { "The profile is too large to preview safely." }
        require(!trimmed.contains('\u0000')) { "The selected file is not a readable text profile." }

        val lines = trimmed.lineSequence().map { it.trim() }.toList()
        val isWireGuard = lines.any { it.equals("[Interface]", ignoreCase = true) } &&
            lines.any { it.equals("[Peer]", ignoreCase = true) }
        val isOpenVpn = lines.any {
            it.startsWith("client", ignoreCase = true) ||
                it.startsWith("remote ", ignoreCase = true) ||
                it.startsWith("dev ", ignoreCase = true)
        } && lines.any { it.startsWith("proto ", ignoreCase = true) ||
            it.startsWith("<ca>", ignoreCase = true) ||
            it.startsWith("remote ", ignoreCase = true) }

        val format = when {
            isWireGuard -> ImportedVpnProfile.Format.WIREGUARD
            isOpenVpn -> ImportedVpnProfile.Format.OPENVPN
            else -> ImportedVpnProfile.Format.UNKNOWN
        }
        val endpoints = lines.mapNotNull { line ->
            when {
                line.startsWith("Endpoint =", ignoreCase = true) ->
                    line.substringAfter('=').trim().takeIf { it.isNotBlank() }
                line.startsWith("remote ", ignoreCase = true) ->
                    line.split(Regex("\\s+")).getOrNull(1)?.takeIf { it.isNotBlank() }
                else -> null
            }
        }.distinct().take(10)

        val peers = if (isWireGuard) lines.count { it.equals("[Peer]", ignoreCase = true) } else 0
        val inlineCredentials = lines.any {
            it.startsWith("<key>", ignoreCase = true) ||
                it.startsWith("<cert>", ignoreCase = true) ||
                it.startsWith("<auth-user-pass>", ignoreCase = true) ||
                it.startsWith("PrivateKey =", ignoreCase = true) ||
                it.startsWith("-----BEGIN PRIVATE KEY-----", ignoreCase = true)
        }
        return ImportedVpnProfile(format, endpoints, peers, inlineCredentials)
    }
}
