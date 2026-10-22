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
    enum class Format { WIREGUARD, AMNEZIAWG, OPENVPN, UNKNOWN }
}

object VpnProfileInspector {
    fun inspect(text: String): ImportedVpnProfile {
        val trimmed = text.trim()
        require(trimmed.isNotEmpty()) { "The selected profile is empty." }
        require(trimmed.length <= 2_000_000) { "The profile is too large to preview safely." }
        require(!trimmed.contains('\u0000')) { "The selected file is not a readable text profile." }

        val lines = trimmed.lineSequence().map { it.trim() }.toList()
        val hasInterface = lines.any { it.equals("[Interface]", ignoreCase = true) }
        val hasPeer = lines.any { it.equals("[Peer]", ignoreCase = true) }
        val isWireGuard = hasInterface && hasPeer
        val awgKeys = setOf(
            "jc", "jmin", "jmax", "s1", "s2", "s3", "s4",
            "h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5",
            "j1", "j2", "contentpaddingaddition", "maxhandshakeattempts",
            "keepalivetimeout", "rejectaftertime", "rekeyaftertime",
            "rekeytimeout", "randomtrailers", "disablecookies",
            "headerprotectionkey", "randomizedpacketcorruption"
        )
        val hasAwgExtensions = lines.any { line ->
            val key = line.substringBefore('=').trim().lowercase()
            key in awgKeys
        }
        val isOpenVpn = lines.any {
            it.startsWith("client", ignoreCase = true) ||
                it.startsWith("remote ", ignoreCase = true) ||
                it.startsWith("dev ", ignoreCase = true)
        } && lines.any {
            it.startsWith("proto ", ignoreCase = true) ||
                it.startsWith("<ca>", ignoreCase = true) ||
                it.startsWith("remote ", ignoreCase = true)
        }

        val format = when {
            isWireGuard && hasAwgExtensions -> ImportedVpnProfile.Format.AMNEZIAWG
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
