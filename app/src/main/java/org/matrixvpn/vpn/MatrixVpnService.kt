package org.matrixvpn.vpn

import android.net.VpnService
import android.util.Log

/** Placeholder only: no TUN interface or VPN tunnel is established by this scaffold. */
class MatrixVpnService : VpnService() {
    override fun onRevoke() {
        Log.i(TAG, "VPN permission revoked; no tunnel is active in this scaffold.")
        super.onRevoke()
    }
    companion object { private const val TAG = "MATRIX-VPN" }
}
