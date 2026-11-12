package org.matrixvpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.IpPrefix
import android.net.VpnService
import java.net.InetAddress
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.matrixvpn.MainActivity

/**
 * Android VpnService adapter for the in-process OpenVPN 3 Core.
 * OpenVPN 3 owns the TUN descriptor returned by nativeEstablish().
 */
class MatrixVpnService : VpnService() {
    private var tunnelBuilder: Builder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startOpenVpn(intent)
            ACTION_STOP -> stopOpenVpn()
        }
        return START_NOT_STICKY
    }

    private fun startOpenVpn(intent: Intent) {
        val profile = intent.getStringExtra(EXTRA_PROFILE).orEmpty()
        val username = intent.getStringExtra(EXTRA_USERNAME).orEmpty()
        val password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty()
        if (profile.isBlank()) {
            publishState(STATE_ERROR, "Профиль OpenVPN пуст.")
            stopSelf()
            return
        }
        startForeground(NOTIFICATION_ID, notification("Подключение к VPN…"))
        publishState(STATE_CONNECTING, "Запускается встроенный OpenVPN 3 Core…")
        try {
            ensureNativeLoaded()
            if (!nativeStart(profile, username, password)) {
                publishState(STATE_ERROR, "OpenVPN 3 не смог запустить сеанс.")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        } catch (error: Throwable) {
            publishState(STATE_ERROR, error.message ?: "Ошибка запуска встроенного OpenVPN 3.")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopOpenVpn() {
        try {
            publishState(STATE_DISCONNECTING, "Останавливается OpenVPN…")
            if (isNativeLoaded()) nativeStop()
            updateNotification("Отключение VPN…")
        } catch (error: Throwable) {
            publishState(STATE_ERROR, error.message ?: "Не удалось остановить OpenVPN.")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    @Synchronized
    fun nativeNewTunnel(): Boolean = try {
        tunnelBuilder = Builder().setSession("MATRIX • OpenVPN 3")
        true
    } catch (_: Exception) {
        false
    }

    fun nativeSetLayer(layer: Int): Boolean = layer == 3

    @Synchronized
    fun nativeAddAddress(address: String, prefix: Int, gateway: String, ipv6: Boolean, net30: Boolean): Boolean {
        return try {
            val builder = tunnelBuilder ?: return false
            builder.addAddress(address, prefix)
            true
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun nativeRerouteGateway(ipv4: Boolean, ipv6: Boolean, flags: Int): Boolean {
        return try {
            val builder = tunnelBuilder ?: return false
            if (ipv4) builder.addRoute("0.0.0.0", 0)
            if (ipv6) builder.addRoute("::", 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun nativeAddRoute(address: String, prefix: Int, metric: Int, ipv6: Boolean): Boolean {
        return try {
            val builder = tunnelBuilder ?: return false
            builder.addRoute(address, prefix)
            true
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun nativeExcludeRoute(address: String, prefix: Int, metric: Int, ipv6: Boolean): Boolean {
        return try {
            if (Build.VERSION.SDK_INT < 33) return false
            val builder = tunnelBuilder ?: return false
            builder.excludeRoute(IpPrefix(InetAddress.getByName(address), prefix))
            true
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun nativeSetDnsServers(addresses: String): Boolean {
        return try {
            val builder = tunnelBuilder ?: return false
            addresses.split(';').map(String::trim).filter(String::isNotEmpty).distinct()
                .forEach { builder.addDnsServer(it) }
            true
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun nativeSetMtu(mtu: Int): Boolean {
        return try {
            if (mtu !in 576..9000) return false
            val builder = tunnelBuilder ?: return false
            builder.setMtu(mtu)
            true
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun nativeSetSessionName(name: String): Boolean {
        return try {
            val builder = tunnelBuilder ?: return false
            builder.setSession(name.take(64))
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Transfers ownership of the established TUN descriptor to OpenVPN 3 Core. */
    @Synchronized
    fun nativeEstablish(): Int {
        return try {
            val builder = tunnelBuilder ?: return -1
            val descriptor = builder.establish() ?: return -1
            tunnelBuilder = null
            descriptor.detachFd()
        } catch (_: Exception) {
            -1
        }
    }

    @Synchronized
    fun nativeTeardown(disconnect: Boolean) {
        tunnelBuilder = null
    }

    fun nativeProtectSocket(fd: Int): Boolean = protect(fd)

    /** Native OpenVPN callbacks are delivered from its dedicated worker thread. */
    fun nativeOnOpenVpnEvent(name: String, info: String, error: Boolean) {
        val state = when {
            error -> STATE_ERROR
            name.equals("CONNECTED", true) || name.equals("RECONNECTED", true) -> STATE_CONNECTED
            name.equals("SESSION_FINISHED", true) -> if (error) STATE_ERROR else STATE_DISCONNECTED
            name.contains("AUTH_FAILED", true) || name.contains("FATAL", true) -> STATE_ERROR
            name.equals("DISCONNECTED", true) -> STATE_DISCONNECTED
            name.equals("RECONNECTING", true) || name.equals("CONNECTING", true) -> STATE_CONNECTING
            else -> null
        }
        if (state != null) publishState(state, info)
        else sendStatusBroadcast(
            getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_STATE, STATE_CONNECTING) ?: STATE_CONNECTING,
            "$name: $info"
        )

        if (name.equals("SESSION_FINISHED", true) || state == STATE_ERROR) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else if (state == STATE_CONNECTED) {
            updateNotification("VPN подключён")
        }
    }

    override fun onRevoke() {
        stopOpenVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        try {
            if (isNativeLoaded()) nativeStop()
        } catch (_: UnsatisfiedLinkError) {
        }
        nativeTeardown(false)
        super.onDestroy()
    }

    private fun publishState(state: String, message: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_STATE, state)
            .putString(KEY_MESSAGE, message.take(1500))
            .apply()
        sendStatusBroadcast(state, message)
        if (state == STATE_CONNECTED) updateNotification("VPN подключён")
        if (state == STATE_ERROR || state == STATE_DISCONNECTED) stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun sendStatusBroadcast(state: String, message: String) {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName)
            .putExtra(EXTRA_STATE, state).putExtra(EXTRA_MESSAGE, message))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL_ID, "MATRIX VPN", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun notification(content: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("MATRIX • OpenVPN 3")
            .setContentText(content)
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(content: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(content))
    }

    private external fun nativeStart(profile: String, username: String, password: String): Boolean
    private external fun nativeStop()

    companion object {
        const val ACTION_START = "org.matrixvpn.action.OPENVPN_START"
        const val ACTION_STOP = "org.matrixvpn.action.OPENVPN_STOP"
        const val ACTION_STATUS = "org.matrixvpn.action.OPENVPN_STATUS"
        const val EXTRA_PROFILE = "openvpn_profile"
        const val EXTRA_USERNAME = "openvpn_username"
        const val EXTRA_PASSWORD = "openvpn_password"
        const val EXTRA_STATE = "openvpn_state"
        const val EXTRA_MESSAGE = "openvpn_message"
        const val STATE_CONNECTING = "CONNECTING"
        const val STATE_CONNECTED = "CONNECTED"
        const val STATE_DISCONNECTING = "DISCONNECTING"
        const val STATE_DISCONNECTED = "DISCONNECTED"
        const val STATE_ERROR = "ERROR"

        private const val PREFS = "matrix_openvpn_session"
        private const val KEY_STATE = "state"
        private const val KEY_MESSAGE = "message"
        private const val CHANNEL_ID = "matrix_openvpn3"
        private const val NOTIFICATION_ID = 4317
        @Volatile private var nativeLoaded = false

        @Synchronized
        private fun ensureNativeLoaded() {
            if (!nativeLoaded) {
                System.loadLibrary("matrix_openvpn")
                nativeLoaded = true
            }
        }

        private fun isNativeLoaded(): Boolean = nativeLoaded

        fun start(context: Context, profile: String, username: String = "", password: String = "") {
            val intent = Intent(context, MatrixVpnService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PROFILE, profile)
                putExtra(EXTRA_USERNAME, username)
                putExtra(EXTRA_PASSWORD, password)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MatrixVpnService::class.java).setAction(ACTION_STOP))
        }

        fun currentState(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_STATE, STATE_DISCONNECTED) ?: STATE_DISCONNECTED
    }
}
