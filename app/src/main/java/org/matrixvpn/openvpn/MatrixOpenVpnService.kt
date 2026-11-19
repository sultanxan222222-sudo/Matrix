package org.matrixvpn.openvpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.content.ContentResolver
import android.net.ConnectivityManager
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import com.tim.basevpn.state.ConnectionState
import com.tim.openvpn.OpenVPNThreadv3
import com.tim.openvpn.model.CIDRIP
import com.tim.openvpn.service.IOpenVPNService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.net.InetAddress

/**
 * OpenVPN 3 adapter that maps the upstream TunBuilder callbacks onto Android VpnService.
 * The library engine and this service remain separate from the existing AmneziaWG backend.
 */
class MatrixOpenVpnService : VpnService(), IOpenVPNService {
    companion object {
        const val ACTION_CONNECT = "org.matrixvpn.openvpn.CONNECT"
        const val ACTION_STOP = "org.matrixvpn.openvpn.STOP"
        const val EXTRA_CONFIG = "org.matrixvpn.openvpn.CONFIG"
        private const val CHANNEL_ID = "matrix_openvpn"
        private const val NOTIFICATION_ID = 7301

        fun connectIntent(context: android.content.Context, rawConfig: String): Intent =
            Intent(context, MatrixOpenVpnService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_CONFIG, rawConfig)
            }

        fun stopIntent(context: android.content.Context): Intent =
            Intent(context, MatrixOpenVpnService::class.java).apply { action = ACTION_STOP }
    }

    private val controlExecutor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "matrix-openvpn-control").apply { isDaemon = true }
    }

    @Volatile private var management: OpenVPNThreadv3? = null
    @Volatile private var engineThread: Thread? = null
    @Volatile private var replacingEngine = false
    @Volatile private var stopRequested = false
    @Volatile private var currentBuilder: Builder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val config = intent.getStringExtra(EXTRA_CONFIG)
                if (config.isNullOrBlank() || config.length > 2_000_000) {
                    MatrixOpenVpnRuntime.publish(ConnectionState.DISCONNECTED, "OpenVPN-профиль пустой или слишком большой.")
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                stopRequested = false
                startForegroundCompat()
                controlExecutor.execute { replaceEngine(config) }
            }
            ACTION_STOP -> {
                stopRequested = true
                MatrixOpenVpnRuntime.publish(ConnectionState.DISCONNECTING, "Остановка OpenVPN…")
                controlExecutor.execute { stopEngineAndService() }
            }
            else -> {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun replaceEngine(config: String) {
        val oldEngine = management
        val oldThread = engineThread
        if (oldEngine != null && oldThread?.isAlive == true) {
            replacingEngine = true
            runCatching { oldEngine.stopVPN() }
            try {
                oldThread.join(5_000L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            replacingEngine = false
            if (oldThread.isAlive) {
                MatrixOpenVpnRuntime.publish(
                    ConnectionState.DISCONNECTED,
                    "Предыдущее OpenVPN-соединение не остановилось вовремя; новый профиль не запускается."
                )
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
        }

        if (stopRequested) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        currentBuilder = Builder().setSession("MATRIX OpenVPN")
        MatrixOpenVpnRuntime.publish(ConnectionState.CONNECTING, "Запуск OpenVPN 3…")
        val engine = try {
            OpenVPNThreadv3(this, config)
        } catch (error: Throwable) {
            MatrixOpenVpnRuntime.publish(
                ConnectionState.DISCONNECTED,
                error.message ?: "Не удалось создать OpenVPN 3 engine."
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        management = engine

        val thread = Thread({
            try {
                engine.run()
            } catch (error: Throwable) {
                MatrixOpenVpnRuntime.publish(
                    ConnectionState.DISCONNECTED,
                    error.message ?: "OpenVPN 3 завершился с ошибкой."
                )
            } finally {
                if (MatrixOpenVpnRuntime.state == ConnectionState.CONNECTING ||
                    MatrixOpenVpnRuntime.state == ConnectionState.CONNECTED) {
                    MatrixOpenVpnRuntime.publish(
                        ConnectionState.DISCONNECTED,
                        "OpenVPN-сессия завершилась."
                    )
                }
                if (!replacingEngine) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }, "matrix-openvpn-engine")
        engineThread = thread
        thread.start()
    }

    private fun stopEngineAndService() {
        val engine = management
        val thread = engineThread
        if (engine != null) {
            runCatching { engine.stopVPN() }
        }
        if (thread != null && thread !== Thread.currentThread()) {
            try {
                thread.join(5_000L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        if (thread?.isAlive == true) {
            MatrixOpenVpnRuntime.publish(
                ConnectionState.DISCONNECTED,
                "OpenVPN не подтвердил остановку за отведённое время."
            )
        } else {
            management = null
            engineThread = null
            currentBuilder = null
            MatrixOpenVpnRuntime.publish(ConnectionState.DISCONNECTED, "OpenVPN отключён.")
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        stopRequested = true
        MatrixOpenVpnRuntime.publish(ConnectionState.DISCONNECTING, "Android отозвал разрешение VPN.")
        controlExecutor.execute { stopEngineAndService() }
        super.onRevoke()
    }

    override fun onDestroy() {
        stopRequested = true
        runCatching { management?.stopVPN() }
        controlExecutor.shutdown()
        super.onDestroy()
    }

    // IOpenVPNService: OpenVPN 3 TunBuilder callbacks.

    override fun setMtu(mtu: Int) {
        require(mtu in 576..9000) { "OpenVPN запросил недопустимый MTU: $mtu" }
        builder().setMtu(mtu)
    }

    override fun addDNS(dns: String?) {
        require(!dns.isNullOrBlank()) { "OpenVPN передал пустой DNS-сервер." }
        builder().addDnsServer(dns)
    }

    override fun addRoute(route: CIDRIP?, include: Boolean) {
        requireNotNull(route) { "OpenVPN передал пустой маршрут IPv4." }
        val target = builder()
        if (include) {
            target.addRoute(InetAddress.getByName(route.ip), route.len)
        } else if (Build.VERSION.SDK_INT >= 33) {
            target.excludeRoute(android.net.IpPrefix(InetAddress.getByName(route.ip), route.len))
        }
        // Android < 13 has no excludeRoute API. Omitting an exclusion preserves the
        // full-tunnel safety model; the local subnet may be unavailable on older OS releases.
    }

    override fun addRoute(dest: String?, mask: String?, gateway: String?, device: String?) {
        require(!dest.isNullOrBlank() && !mask.isNullOrBlank()) { "OpenVPN передал неполный маршрут IPv4." }
        val prefix = OpenVpnRouteParser.ipv4PrefixFromNetmask(mask)
        builder().addRoute(InetAddress.getByName(dest), prefix)
    }

    override fun addRoutev6(ip: String?, device: String?) {
        require(!ip.isNullOrBlank()) { "OpenVPN передал пустой маршрут IPv6." }
        val (address, prefix) = OpenVpnRouteParser.parseCidr(ip)
        val target = builder()
        if (device == "wifi0" && Build.VERSION.SDK_INT >= 33) {
            target.excludeRoute(android.net.IpPrefix(InetAddress.getByName(address), prefix))
        } else if (device != "wifi0") {
            target.addRoute(address, prefix)
        }
    }

    override fun setDomain(domain: String?) {
        if (!domain.isNullOrBlank()) builder().addSearchDomain(domain)
    }

    override fun addHttpProxy(proxy: String?, port: Int): Boolean {
        if (Build.VERSION.SDK_INT < 29 || proxy.isNullOrBlank() || port !in 1..65535) return false
        return runCatching {
            builder().setHttpProxy(ProxyInfo.buildDirectProxy(proxy, port))
            true
        }.getOrDefault(false)
    }

    override fun openTun(): ParcelFileDescriptor? {
        return builder().establish()
            ?: throw IllegalStateException("Android не смог создать TUN-интерфейс OpenVPN.")
    }

    override fun setLocalIP(cidrip: CIDRIP?) {
        requireNotNull(cidrip) { "OpenVPN передал пустой IPv4-адрес." }
        builder().addAddress(InetAddress.getByName(cidrip.ip), cidrip.len)
    }

    override fun setLocalIPv6(ipv6addr: String?) {
        require(!ipv6addr.isNullOrBlank()) { "OpenVPN передал пустой IPv6-адрес." }
        val (address, prefix) = OpenVpnRouteParser.parseCidr(ipv6addr)
        builder().addAddress(InetAddress.getByName(address), prefix)
    }

    override fun protectFd(fd: Int): Boolean = protect(fd)

    override fun trigger_sso(info: String?) {
        MatrixOpenVpnRuntime.publish(
            ConnectionState.DISCONNECTED,
            "Профиль требует интерактивную SSO-аутентификацию, которая пока не поддерживается MATRIX."
        )
        management?.stopVPN()
    }

    override val ctResolver: ContentResolver?
        get() = contentResolver

    override val connectivityManager: ConnectivityManager?
        get() = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

    override fun openvpnStopped() {
        MatrixOpenVpnRuntime.publish(ConnectionState.DISCONNECTED, "OpenVPN отключён.")
        if (!replacingEngine) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun updateStateThread(state: ConnectionState) {
        val detail = when (state) {
            ConnectionState.CONNECTED -> "OpenVPN подключён."
            ConnectionState.CONNECTING -> "OpenVPN устанавливает соединение…"
            ConnectionState.DISCONNECTING -> "OpenVPN отключается…"
            ConnectionState.DISCONNECTED -> "OpenVPN отключён или потерял соединение."
            ConnectionState.READYFORCONNECT -> "OpenVPN готов к подключению."
            ConnectionState.PERMISSION_NOT_GRANTED -> "Android не предоставил разрешение VPN."
            ConnectionState.IDLE -> "OpenVPN ожидает."
            else -> "Состояние OpenVPN: $state"
        }
        MatrixOpenVpnRuntime.publish(state, detail)
    }

    private fun builder(): Builder {
        return currentBuilder ?: Builder().setSession("MATRIX OpenVPN").also { currentBuilder = it }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MATRIX OpenVPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Состояние VPN-туннеля MATRIX" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun startForegroundCompat() {
        val launchIntent = Intent(this, org.matrixvpn.MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 7301, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("MATRIX VPN")
            .setContentText("Подключение OpenVPN…")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
