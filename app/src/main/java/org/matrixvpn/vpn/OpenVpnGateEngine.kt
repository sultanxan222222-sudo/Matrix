package org.matrixvpn.vpn

import android.content.Context
import android.content.Intent
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VPNLaunchHelper
import de.blinkt.openvpn.core.VpnStatus
import java.io.StringReader

/**
 * In-process OpenVPN adapter for VPN Gate profiles. OpenVPN remains a distinct
 * backend from WireGuard/AmneziaWG; the caravan controller chooses one backend
 * at a time because Android permits only one active VPN interface per profile.
 */
class OpenVpnGateEngine(context: Context) {
    private val appContext = context.applicationContext

    @Volatile
    var onStateChanged: ((state: String, message: String) -> Unit)? = null

    private val stateListener = object : VpnStatus.StateListener {
        override fun updateState(
            state: String,
            logmessage: String,
            localizedResId: Int,
            level: ConnectionStatus,
            intent: Intent
        ) {
            onStateChanged?.invoke(state, logmessage)
        }

        override fun setConnectedVPN(uuid: String) = Unit
    }

    init {
        VpnStatus.addStateListener(stateListener)
    }

    @Synchronized
    fun connect(profileText: String) {
        require(profileText.length in 128..2_000_000) { "OpenVPN-профиль имеет недопустимый размер." }
        require(Regex("(?im)^\\s*client\\s*$").containsMatchIn(profileText)) {
            "В профиле отсутствует директива client."
        }
        require(Regex("(?im)^\\s*remote\\s+\\S+\\s+\\d+").containsMatchIn(profileText)) {
            "В профиле не найден адрес сервера OpenVPN."
        }

        val parser = ConfigParser()
        parser.parseConfig(StringReader(profileText))
        val profile: VpnProfile = parser.convertProfile()
        // VPN Gate's public relays document these anonymous credentials.
        profile.mUsername = "vpn"
        profile.mPassword = "vpn"
        profile.mProfileCreator = appContext.packageName

        ProfileManager.setTemporaryProfile(appContext, profile)
        VPNLaunchHelper.startOpenVpn(profile, appContext, "MATRIX VPN Gate", true)
    }

    @Synchronized
    fun disconnect() {
        ProfileManager.setConntectedVpnProfileDisconnected(appContext)
        val intent = Intent(appContext, OpenVPNService::class.java).apply {
            action = OpenVPNService.DISCONNECT_VPN
        }
        appContext.startService(intent)
    }

    fun release() {
        VpnStatus.removeStateListener(stateListener)
        onStateChanged = null
    }
}
