package org.matrixvpn

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.ClipboardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Animatable
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.backend.TunnelActionHandler
import org.amnezia.awg.backend.Tunnel.State
import org.amnezia.awg.config.Config
import org.matrixvpn.network.NetworkWatcher
import org.matrixvpn.network.TunnelConnectivityProbe
import org.matrixvpn.caravan.OpenVpnRouteScout
import org.matrixvpn.caravan.RelayProbeStatus
import org.matrixvpn.caravan.RelayProbeTarget
import org.matrixvpn.profile.OpenVpnHttpProxy
import org.matrixvpn.profile.ImportedVpnProfile
import org.matrixvpn.profile.VpnProfileInspector
import org.matrixvpn.profile.SecureProfileVault
import org.matrixvpn.vpn.MatrixVpnService
import androidx.core.content.ContextCompat
import org.matrixvpn.recovery.NodeHealthStore
import org.matrixvpn.recovery.TunnelQuality
import org.matrixvpn.recovery.TunnelQualityTracker
import org.matrixvpn.ai.GarlicProfileSelector
import org.matrixvpn.ai.GarlicModelPackInstaller
import java.io.ByteArrayInputStream
import java.io.File
import java.lang.ref.WeakReference

private const val QUALITY_INTERVAL_MS = 30_000L
private const val QUALITY_FIRST_PROBE_DELAY_MS = 4_000L
private const val MAX_CROSS_ENGINE_CANDIDATES = 12
private const val MAX_VPN_GATE_AUTO_CANDIDATES = 6

private object MatrixWireGuardSession {
    @Volatile var backend: GoBackend? = null
    @Volatile private var activityRef: WeakReference<MainActivity>? = null

    val actions = object : TunnelActionHandler {
        override fun runPreUp(scripts: MutableCollection<String>) {}
        override fun runPostUp(scripts: MutableCollection<String>) {}
        override fun runPreDown(scripts: MutableCollection<String>) {}
        override fun runPostDown(scripts: MutableCollection<String>) {}
    }

    // One stable Tunnel identity is required because GoBackend tracks the active tunnel by object identity.
    val tunnel = object : Tunnel {
        override fun getName(): String = "matrix"
        override fun onStateChange(newState: State) {
            activityRef?.get()?.handleWireGuardStateChange(newState)
        }
        override fun isIpv4ResolutionPreferred(): Boolean = true
        override fun isMetered(): Boolean = false
    }

    fun attach(activity: MainActivity) {
        activityRef = WeakReference(activity)
    }

    fun detach(activity: MainActivity) {
        if (activityRef?.get() === activity) activityRef = null
    }
}

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var networkStatus: TextView
    private lateinit var connectButton: Button
    private lateinit var warpModeButton: Button
    private lateinit var networkWatcher: NetworkWatcher
    private val pickProfileRequest = 4102
    private val vpnPermissionRequest = 4103
    private val wireGuardBackend by lazy {
        synchronized(MatrixWireGuardSession) {
            MatrixWireGuardSession.backend
                ?: GoBackend(applicationContext, MatrixWireGuardSession.actions).also { MatrixWireGuardSession.backend = it }
        }
    }
    private var wireGuardConfig: Config? = null
    @Volatile private var tunnelUp = false
    private var activeProfileId: String? = null
    private var activeProfileName: String = "Нет профиля"
    private var activeProfileProvider: String = "Импорт"
    private var openVpnProfileText: String? = null
    private var pendingOpenVpnStart = false
    private var pendingRecoveryReason: String? = null
    private var pendingRecoveryExcludedId: String? = null
    private var pendingRecoveryPreferredIds: List<String> = emptyList()
    @Volatile private var pendingRecoveryGarlicToken: Long? = null
    @Volatile private var pendingGarlicPermissionToken: Long? = null
    private var openVpnRunning = false
    private var openVpnState: String = MatrixVpnService.STATE_DISCONNECTED
    private var openVpnReceiverRegistered = false
    private val qualityHandler = Handler(Looper.getMainLooper())
    private var fistIconAnimation: Animatable? = null
    private var splashFistIconAnimation: Animatable? = null
    private var activityStarted = false
    private var mainScreenShown = false
    private val qualityTracker = TunnelQualityTracker(failoverFailureThreshold = 3)
    @Volatile private var qualityProbeInProgress = false
    @Volatile private var autoRecoveryInProgress = false
    @Volatile private var manualDisconnectInProgress = false
    @Volatile private var wireGuardStartupInProgress = false
    @Volatile private var engineSwitchInProgress = false
    private lateinit var garlicStatus: TextView
    private lateinit var emergencyStopButton: Button
    private val garlicProfileSelector by lazy { GarlicProfileSelector(applicationContext) }
    private val garlicModelPackInstaller by lazy { GarlicModelPackInstaller(applicationContext) }
    private val garlicModelPackRequest = 4110
    @Volatile private var garlicRunToken = 0L
    private val qualityMonitorRunnable = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            val serviceState = MatrixVpnService.currentState(this@MainActivity)
            if (openVpnProfileText != null && serviceState == MatrixVpnService.STATE_ERROR &&
                isMatrixLockdownEnabled() && !autoRecoveryInProgress && !manualDisconnectInProgress) {
                startCrossEngineRecovery(activeProfileId, "OpenVPN остановился с ошибкой в фоновом режиме.")
            } else if (isVpnSessionConnected()) {
                runQualityProbe()
            }
            qualityHandler.postDelayed(this, QUALITY_INTERVAL_MS)
        }
    }
    private val openVpnStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            openVpnState = intent?.getStringExtra(MatrixVpnService.EXTRA_STATE)
                ?: MatrixVpnService.currentState(this@MainActivity)
            openVpnRunning = openVpnState == MatrixVpnService.STATE_CONNECTING ||
                openVpnState == MatrixVpnService.STATE_CONNECTED ||
                openVpnState == MatrixVpnService.STATE_DISCONNECTING
            val message = intent?.getStringExtra(MatrixVpnService.EXTRA_MESSAGE).orEmpty()
            if (openVpnRunning || openVpnState == MatrixVpnService.STATE_ERROR) {
                status.text = "Встроенный OpenVPN 3 • $openVpnState\n$message"
            }
            connectButton.isEnabled = openVpnState != MatrixVpnService.STATE_CONNECTING &&
                openVpnState != MatrixVpnService.STATE_DISCONNECTING
            refreshModeButtons()
            when (openVpnState) {
                MatrixVpnService.STATE_CONNECTED -> {
                    if (!autoRecoveryInProgress) checkYouTubeAfterOpenVpnConnect()
                    scheduleQualityMonitor(QUALITY_FIRST_PROBE_DELAY_MS)
                }
                MatrixVpnService.STATE_ERROR -> {
                    qualityTracker.reset()
                    val userStopped = manualDisconnectInProgress
                    manualDisconnectInProgress = false
                    if (userStopped) {
                        engineSwitchInProgress = false
                        connectButton.isEnabled = true
                        warpModeButton.isEnabled = true
                    }
                    if (!userStopped && !autoRecoveryInProgress && isMatrixLockdownEnabled()) {
                        startCrossEngineRecovery(activeProfileId, message.ifBlank { "OpenVPN завершился с ошибкой." })
                    }
                }
                MatrixVpnService.STATE_DISCONNECTED -> {
                    qualityTracker.reset()
                    val userStopped = manualDisconnectInProgress
                    manualDisconnectInProgress = false
                    // Do not unlock a user-initiated mode switch when the old engine reports down.
                    // The exclusive-start worker still has to stop and verify both backends.
                    if (userStopped) {
                        engineSwitchInProgress = false
                        connectButton.isEnabled = true
                        warpModeButton.isEnabled = true
                    }
                }
            }
        }
    }

    private var youtubeProbeInProgress = false
    private var lastYoutubeProbeAt = 0L

    private fun checkYouTubeAfterOpenVpnConnect() {
        val now = SystemClock.elapsedRealtime()
        if (youtubeProbeInProgress || now - lastYoutubeProbeAt < 60_000L) return
        youtubeProbeInProgress = true
        lastYoutubeProbeAt = now
        status.text = "Встроенный OpenVPN 3 • CONNECTED\nПроверяю доступность YouTube через VPN…"
        val testedProfileId = activeProfileId
        val probeStartedAt = SystemClock.elapsedRealtime()
        Thread {
            val result = TunnelConnectivityProbe.checkYouTube()
            val latencyMs = (SystemClock.elapsedRealtime() - probeStartedAt).coerceAtLeast(1L)
            runOnUiThread {
                youtubeProbeInProgress = false
                if (testedProfileId != null) {
                    NodeHealthStore.record(this, testedProfileId, result.reachable, latencyMs)
                }
                if (openVpnState != MatrixVpnService.STATE_CONNECTED) return@runOnUiThread
                status.text = if (result.reachable) {
                    "Встроенный OpenVPN 3 • CONNECTED\nYouTube доступен через VPN (HTTP " + result.httpCode + ")."
                } else {
                    "Встроенный OpenVPN 3 • CONNECTED\nYouTube не прошёл проверку" +
                        (result.httpCode?.let { " (HTTP " + it + ")" } ?: "") +
                        ". Возможны ограничения узла, DNS или маршрутизации."
                }
            }
        }.start()
    }

    private val matrixTunnel: Tunnel
        get() = MatrixWireGuardSession.tunnel

    internal fun handleWireGuardStateChange(newState: State) {
        val wasUp = tunnelUp
        runOnUiThread {
            tunnelUp = newState == State.UP
            status.text = if (tunnelUp) {
                "VPN-туннель: подключён\nПрофиль: $activeProfileName\nПроверяется доступ через туннель…"
            } else {
                "VPN-туннель: отключён\nАктивный профиль: $activeProfileName"
            }
            refreshModeButtons()
            if (tunnelUp) {
                scheduleQualityMonitor(QUALITY_FIRST_PROBE_DELAY_MS)
            } else {
                qualityTracker.reset()
                val userStopped = manualDisconnectInProgress
                manualDisconnectInProgress = false
                if (wasUp && !userStopped && !wireGuardStartupInProgress &&
                    !autoRecoveryInProgress && !engineSwitchInProgress && isMatrixLockdownEnabled()) {
                    startCrossEngineRecovery(activeProfileId, "WireGuard/AmneziaWG остановился во время работы.")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MatrixWireGuardSession.attach(this)
        val iconSize = (108f * resources.displayMetrics.density).toInt()
        val title = ImageView(this).apply {
            contentDescription = "MATRIX VPN — кулак пробивает бетонную стену"
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(R.drawable.matrix_fist_loop)
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            fistIconAnimation = drawable as? Animatable
        }
        val menuSubtitle = TextView(this).apply {
            text = "PRIVATE NETWORK  /  0.8.2"
            textSize = 11f
            letterSpacing = 0.18f
            setTextColor(Color.rgb(166, 210, 190))
            gravity = Gravity.CENTER
            setPadding(8, 0, 8, 22)
        }
        val connectionHeading = sectionHeading("СОСТОЯНИЕ СОЕДИНЕНИЯ")
        val helpHeading = sectionHeading("СПРАВКА")
        status = TextView(this).apply {
            text = "MATRIX GLASS  •  0.8.2\nVPN-туннель: не подключён\nOpenVPN 3 Core + AmneziaWG готовы к работе"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(16, 12, 16, 12)
        }
        networkStatus = TextView(this).apply {
            text = "Сеть: проверка…"
            textSize = 14f
            setTextColor(Color.rgb(180, 210, 185))
            gravity = Gravity.CENTER
            setPadding(8, 8, 8, 12)
        }
        val modeHeading = sectionHeading("РЕЖИМ ПОДКЛЮЧЕНИЯ")
        warpModeButton = Button(this).apply {
            text = "WARP  •  ВКЛЮЧИТЬ"
            isAllCaps = false
            textSize = 17f
            minHeight = (76 * resources.displayMetrics.density).toInt()
            setOnClickListener { onWarpModeClick() }
        }
        val importButton = Button(this).apply {
            text = "2. Импорт файла с телефона"
            setOnClickListener { chooseVpnProfile() }
        }
        val findWireGuardButton = Button(this).apply {
            text = "Получить новые WARP-профили"
            setOnClickListener { fetchFreeWarpProfile() }
        }
        val profilesButton = Button(this).apply {
            text = "Мои профили • умный выбор"
            setOnClickListener { showProfilePoolDialog() }
        }
        val recoveryButton = Button(this).apply {
            text = "Резервное восстановление"
            setOnClickListener { showRecoveryStatus() }
        }
        garlicStatus = TextView(this).apply {
            text = "MATRIX AI • Garlic: модель не установлена"
            textSize = 11.5f
            setTextColor(Color.rgb(180, 225, 195))
            gravity = Gravity.CENTER
            setPadding(8, 8, 8, 8)
        }
        val installGarlicButton = Button(this).apply {
            text = "MATRIX AI • УСТАНОВИТЬ МОДЕЛЬ"
            setOnClickListener { chooseGarlicModelPack() }
        }
        emergencyStopButton = Button(this).apply {
            text = "АВАРИЙНО ОСТАНОВИТЬ AI + VPN"
            isAllCaps = false
            setOnClickListener { emergencyStopAiAndVpn() }
        }
        val findVpnButton = Button(this).apply {
            text = "Сеть добровольцев MATRIX"
            setOnClickListener { searchVpnServices() }
        }
        val httpProxyButton = Button(this).apply {
            text = "HTTP CONNECT • TCP-прокси"
            setOnClickListener { configureHttpConnectProxy() }
        }
        val pasteButton = Button(this).apply {
            text = "1. Импорт из буфера телефона"
            setOnClickListener { importFromClipboard() }
        }
        connectButton = Button(this).apply {
            text = "VPN  •  НАЙТИ / ВКЛЮЧИТЬ"
            isEnabled = true
            isAllCaps = false
            textSize = 17f
            minHeight = (76 * resources.displayMetrics.density).toInt()
            setOnClickListener { onVpnModeClick() }
        }
        val help = TextView(this).apply {
            text = "WARP и VPN имеют отдельные кнопки управления. WARP использует встроенный AmneziaWG, а OpenVPN — встроенный OpenVPN 3 Core с Android VpnService. Профили зашифрованы Android Keystore. VPN Gate управляется добровольцами; не используйте такие узлы для конфиденциальных данных."
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(8, 12, 8, 12)
        }
        val modePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 12, 18, 18)
            background = glassSurface(Color.argb(62, 160, 255, 220), Color.argb(145, 235, 202, 128), 30f)
            elevation = 12f * resources.displayMetrics.density
            addView(modeHeading)
            addView(warpModeButton, menuButtonParams())
            addView(connectButton, menuButtonParams())
            addView(emergencyStopButton, menuButtonParams())
        }
        val statusPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 12, 18, 16)
            background = glassSurface(Color.argb(48, 125, 225, 190), Color.argb(112, 150, 205, 180), 26f)
            elevation = 8f * resources.displayMetrics.density
            addView(connectionHeading)
            addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(networkStatus, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val toolsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 14, 14, 14)
            background = glassSurface(Color.argb(30, 130, 205, 175), Color.argb(78, 135, 175, 145), 26f)
            addView(pasteButton, menuButtonParams())
            addView(importButton, menuButtonParams())
            addView(findVpnButton, menuButtonParams())
            addView(findWireGuardButton, menuButtonParams())
            addView(profilesButton, menuButtonParams())
            addView(httpProxyButton, menuButtonParams())
            addView(recoveryButton, menuButtonParams())
            addView(garlicStatus, menuButtonParams())
        }
        val menuContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(16, 16, 16, 34)
            // Keep the glass cards, but let the animated globe remain visible behind them.
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.argb(42, 3, 15, 13), Color.argb(48, 5, 10, 15), Color.argb(42, 3, 17, 12))
            )
            addView(title)
            addView(menuSubtitle)
            addView(modePanel, menuButtonParams())
            addView(statusPanel, menuButtonParams())
            addView(toolsPanel, menuButtonParams())
            addView(helpHeading)
            addView(help, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val mainScreen = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.TRANSPARENT)
            clipToPadding = false
            addView(menuContent, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        listOf(importButton, findWireGuardButton, findVpnButton, pasteButton, profilesButton, httpProxyButton, recoveryButton, installGarlicButton).forEachIndexed { index, button ->
            button.background = glassSurface(
                if (index % 2 == 0) Color.argb(58, 125, 210, 180) else Color.argb(48, 160, 190, 220),
                Color.argb(92, 170, 205, 175), 20f
            )
            button.backgroundTintList = null
            button.setTextColor(Color.rgb(222, 239, 228))
            button.textSize = 12.5f
            button.minHeight = (44 * resources.displayMetrics.density).toInt()
            button.minWidth = 0
            button.setPadding(10, 2, 10, 2)
            button.isAllCaps = false
            button.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            button.elevation = 5f * resources.displayMetrics.density
            button.stateListAnimator = null
            button.setShadowLayer(3f, 0f, 1f, Color.argb(95, 95, 235, 175))
        }
        emergencyStopButton.background = glassSurface(Color.argb(145, 120, 25, 32), Color.argb(220, 230, 105, 105), 18f)
        emergencyStopButton.backgroundTintList = null
        emergencyStopButton.setTextColor(Color.rgb(255, 225, 220))
        emergencyStopButton.textSize = 12f
        emergencyStopButton.minHeight = (42 * resources.displayMetrics.density).toInt()
        emergencyStopButton.elevation = 5f * resources.displayMetrics.density
        emergencyStopButton.stateListAnimator = null
        styleGlassModeButton(warpModeButton, false)
        styleGlassModeButton(connectButton, false)
        help.setTextColor(Color.rgb(165, 198, 184))
        help.textSize = 12f
        val density = resources.displayMetrics.density
        val screenRoot = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(2, 9, 8))
            addView(MatrixGlobeView(this@MainActivity), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(mainScreen, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(
                TrafficMeterView(this@MainActivity),
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                    topMargin = (9 * density).toInt()
                    marginEnd = (10 * density).toInt()
                }
            )
        }
        val splashIcon = ImageView(this).apply {
            contentDescription = "MATRIX VPN — кулак пробивает бетонную стену"
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(R.drawable.matrix_fist_loop)
            layoutParams = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
            splashFistIconAnimation = drawable as? Animatable
        }
        val splashRoot = FrameLayout(this).apply {
            addView(
                MatrixRainView(this@MainActivity),
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            addView(splashIcon, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))
        }
        setContentView(splashRoot)
        window.decorView.postDelayed({
            if (!isFinishing && !isDestroyed) {
                mainScreenShown = true
                setContentView(screenRoot)
                splashFistIconAnimation?.stop()
                if (activityStarted) fistIconAnimation?.start()
            }
        }, 1800L)
        networkWatcher = NetworkWatcher(this) { state ->
            runOnUiThread { networkStatus.text = state.summary() }
        }
        openVpnState = MatrixVpnService.currentState(this)
        openVpnRunning = openVpnState == MatrixVpnService.STATE_CONNECTING ||
            openVpnState == MatrixVpnService.STATE_CONNECTED ||
            openVpnState == MatrixVpnService.STATE_DISCONNECTING
        restoreSavedProfile()
        restoreGarlicAutopilotState()
        initializeInstalledGarlicModel()
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        if (mainScreenShown) fistIconAnimation?.start() else splashFistIconAnimation?.start()
        MatrixWireGuardSession.attach(this)
        tunnelUp = isWireGuardActive()
        networkWatcher.start()
        qualityHandler.removeCallbacks(qualityMonitorRunnable)
        qualityHandler.postDelayed(qualityMonitorRunnable, QUALITY_FIRST_PROBE_DELAY_MS)
        openVpnState = MatrixVpnService.currentState(this)
        openVpnRunning = openVpnState == MatrixVpnService.STATE_CONNECTING ||
            openVpnState == MatrixVpnService.STATE_CONNECTED ||
            openVpnState == MatrixVpnService.STATE_DISCONNECTING
        if (!openVpnReceiverRegistered) {
            ContextCompat.registerReceiver(
                this, openVpnStatusReceiver, IntentFilter(MatrixVpnService.ACTION_STATUS),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            openVpnReceiverRegistered = true
        }
        refreshModeButtons()
    }

    override fun onStop() {
        activityStarted = false
        fistIconAnimation?.stop()
        splashFistIconAnimation?.stop()
        networkWatcher.stop()
        if (openVpnReceiverRegistered) {
            try { unregisterReceiver(openVpnStatusReceiver) } catch (_: Exception) { }
            openVpnReceiverRegistered = false
        }
        super.onStop()
    }


    override fun onDestroy() {
        qualityHandler.removeCallbacks(qualityMonitorRunnable)
        MatrixWireGuardSession.detach(this)
        garlicProfileSelector.close()
        super.onDestroy()
    }

    private fun roundedPanel(fill: Int, stroke: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(fill, Color.argb(165, 7, 13, 12))).apply {
            cornerRadius = radiusDp * resources.displayMetrics.density
            setStroke((1.6f * resources.displayMetrics.density).toInt().coerceAtLeast(1), stroke)
        }

    private fun glassSurface(tint: Int, stroke: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.argb(148, 224, 255, 246),
                tint,
                Color.argb(88, 70, 112, 100)
            )
        ).apply {
            cornerRadius = radiusDp * resources.displayMetrics.density
            setStroke((1.2f * resources.displayMetrics.density).toInt().coerceAtLeast(1), stroke)
        }

    private fun styleGlassModeButton(button: Button, active: Boolean) {
        val gold = if (active) Color.argb(255, 255, 224, 145) else Color.argb(210, 190, 205, 184)
        val tint = if (active) Color.argb(132, 205, 164, 76) else Color.argb(86, 105, 170, 150)
        button.background = glassSurface(tint, gold, 30f)
        button.backgroundTintList = null
        button.setTextColor(if (active) Color.rgb(255, 242, 205) else Color.rgb(232, 239, 222))
        button.textSize = 16f
        button.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        button.minHeight = (64 * resources.displayMetrics.density).toInt()
        button.minWidth = 0
        button.setPadding(12, 2, 12, 2)
        button.elevation = if (active) 22f * resources.displayMetrics.density else 12f * resources.displayMetrics.density
        button.stateListAnimator = null
        button.setShadowLayer(if (active) 22f else 9f, 0f, 0f, if (active) Color.rgb(255, 204, 105) else Color.rgb(108, 210, 175))
    }

    private fun refreshModeButtons() {
        if (!::warpModeButton.isInitialized || !::connectButton.isInitialized) return
        val openVpnActive = isOpenVpnActive()
        val wireGuardActive = isWireGuardActive()
        // A single active engine owns the Android VPN slot. Never paint both modes as active.
        val warpActive = !openVpnActive && wireGuardActive && activeProfileProvider.equals("WARP", true)
        val vpnActive = openVpnActive || (wireGuardActive && !activeProfileProvider.equals("WARP", true))
        styleGlassModeButton(warpModeButton, warpActive)
        styleGlassModeButton(connectButton, vpnActive)
        listOf(warpModeButton, connectButton).forEach { button ->
            button.animate().cancel()
            val selected = (button === warpModeButton && warpActive) || (button === connectButton && vpnActive)
            button.animate().scaleX(if (selected) 1.015f else 1f)
                .scaleY(if (selected) 1.015f else 1f).setDuration(220L).start()
        }
        warpModeButton.text = if (warpActive) "WARP  •  ОТКЛЮЧИТЬ" else "WARP  •  ВКЛЮЧИТЬ"
        connectButton.text = when {
            openVpnActive && openVpnState == MatrixVpnService.STATE_DISCONNECTING -> "VPN  •  ОТКЛЮЧЕНИЕ…"
            openVpnActive -> "VPN  •  ОТКЛЮЧИТЬ"
            wireGuardActive && !activeProfileProvider.equals("WARP", true) -> "VPN  •  ОТКЛЮЧИТЬ"
            else -> "VPN  •  ПОИСК / ВКЛЮЧИТЬ"
        }
    }

        private fun onWarpModeClick() {
        if (engineSwitchInProgress || wireGuardStartupInProgress || autoRecoveryInProgress) return
        if (isOpenVpnActive()) {
            showError("VPN уже активен", "Сначала отключите OpenVPN 3, затем включите WARP.")
            return
        }
        if (isWireGuardActive()) {
            if (activeProfileProvider.equals("WARP", true)) connectOrDisconnect()
            else showError("VPN уже активен", "Сначала отключите текущий VPN, затем включите WARP.")
            return
        }
        beginGarlicAutopilotSession()
        val profiles = SecureProfileVault.list(this).filter { it.provider.equals("WARP", true) }
        if (profiles.isEmpty()) fetchFreeWarpProfile() else chooseSavedProfilesWithGarlic(profiles, "WARP")
    }

        private fun onVpnModeClick() {
        if (engineSwitchInProgress || wireGuardStartupInProgress || autoRecoveryInProgress) return
        if (isOpenVpnActive()) {
            connectOrDisconnect()
            return
        }
        if (isWireGuardActive()) {
            if (!activeProfileProvider.equals("WARP", true)) connectOrDisconnect()
            else showError("WARP уже активен", "Сначала отключите WARP, затем включите VPN.")
            return
        }
        beginGarlicAutopilotSession()
        val profiles = SecureProfileVault.list(this).filter { !it.provider.equals("WARP", true) }
        if (profiles.isEmpty()) {
            searchVpnServices()
            return
        }
        chooseSavedProfilesWithGarlic(profiles, "VPN")
    }

        private fun showSavedModeProfiles(profiles: List<org.matrixvpn.profile.SavedVpnProfile>, mode: String) {
        AlertDialog.Builder(this)
            .setTitle(if (mode == "WARP") "ВЫБОР WARP-ПРОФИЛЯ" else "ВЫБОР VPN-ПРОФИЛЯ")
            .setItems(profiles.map { it.name }.toTypedArray()) { _, which ->
                try {
                    val chosen = profiles[which]
                    val raw = SecureProfileVault.load(this, chosen.id)
                        ?: throw IllegalStateException("Не удалось прочитать сохранённый профиль.")
                    val inspected = VpnProfileInspector.inspect(raw)
                    when (inspected.format) {
                        ImportedVpnProfile.Format.OPENVPN -> {
                            openVpnProfileText = raw
                            wireGuardConfig = null
                            pendingOpenVpnStart = true
                        }
                        ImportedVpnProfile.Format.WIREGUARD, ImportedVpnProfile.Format.AMNEZIAWG -> {
                            openVpnProfileText = null
                            wireGuardConfig = Config.parse(ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)))
                            pendingOpenVpnStart = false
                        }
                        else -> throw IllegalArgumentException("Формат профиля не поддерживается.")
                    }
                    activeProfileId = chosen.id
                    activeProfileName = chosen.name
                    activeProfileProvider = chosen.provider
                    SecureProfileVault.setActive(this, chosen.id)
                    connectButton.isEnabled = true
                    status.text = "$mode • выбран профиль: ${chosen.name}\nЗапрашиваю разрешение Android…"
                    requestSelectedProfileVpnPermission()
                } catch (e: Exception) {
                    showError("Не удалось выбрать профиль", e.message ?: "Проверьте конфигурацию и попробуйте снова.")
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /**
     * Tie Android's asynchronous VPN consent result to the exact autopilot generation
     * that selected this profile. A later emergency stop invalidates the token, so a
     * delayed "Allow" result cannot resurrect a tunnel after the user stopped MATRIX.
     */
    private fun requestSelectedProfileVpnPermission() {
        if (!garlicProfileSelector.isActive(garlicRunToken)) beginGarlicAutopilotSession()
        val token = garlicRunToken
        pendingGarlicPermissionToken = token
        val permission = VpnService.prepare(this)
        if (permission != null) {
            startActivityForResult(permission, vpnPermissionRequest)
        } else {
            pendingGarlicPermissionToken = null
            if (garlicProfileSelector.isActive(token)) startSelectedVpnTunnel(token)
        }
    }

    private fun restoreGarlicAutopilotState() {
        garlicRunToken = garlicProfileSelector.restoreAutopilot()
        garlicStatus.text = if (garlicRunToken != 0L) "MATRIX AI • автопилот разрешён"
            else "MATRIX AI • ожидание команды"
    }

    private fun beginGarlicAutopilotSession() {
        garlicRunToken = garlicProfileSelector.beginAutopilot()
        garlicStatus.text = if (garlicProfileSelector.isReady) {
            "MATRIX AI • Garlic анализирует профили…"
        } else {
            "MATRIX AI • модель не установлена; ручной режим доступен"
        }
    }

    private fun initializeInstalledGarlicModel() {
        // Garlic's complete Qwen3.5 model is bundled in the APK. The native
        // loader copies the verified build assets into app-private storage.
        val directory = File(filesDir, "models/garlic")
        garlicStatus.text = "MATRIX AI • загрузка встроенной модели Garlic…"
        garlicProfileSelector.initialize(directory).whenComplete { result, error ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                garlicStatus.text = if (error == null && result?.ready == true) {
                    "MATRIX AI • GARLIC ONLINE • " + result.deviceAbi
                } else {
                    "MATRIX AI • Garlic недоступен на этом устройстве"
                }
            }
        }
    }

    private fun chooseGarlicModelPack() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivityForResult(intent, garlicModelPackRequest)
        } catch (_: android.content.ActivityNotFoundException) {
            val fallback = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivityForResult(
                    Intent.createChooser(fallback, "Выберите пакет модели Garlic Qwen3.5"),
                    garlicModelPackRequest
                )
            } catch (error: Exception) {
                showError("Выбор модели недоступен", error.message ?: "Android не открыл выбор файлов.")
            }
        }
    }

    private fun installGarlicModelPack(uri: Uri) {
        garlicStatus.text = "MATRIX AI • проверка и установка модели…"
        garlicModelPackInstaller.install(uri).whenComplete { result, error ->
            runOnUiThread {
                if (error != null) {
                    garlicStatus.text = "MATRIX AI • пакет модели не установлен"
                    showError(
                        "Не удалось установить Garlic",
                        error.cause?.message ?: error.message ?: "Архив модели не прошёл проверку."
                    )
                } else {
                    garlicStatus.text = "MATRIX AI • " + result.modelName + " проверена • запуск…"
                    garlicProfileSelector.initialize(File(result.directory)).whenComplete { initialized, initError ->
                        runOnUiThread {
                            if (initError != null || initialized?.ready != true) {
                                garlicStatus.text = "MATRIX AI • Garlic не запустился на этом устройстве"
                                showError(
                                    "Garlic не запустился",
                                    initError?.cause?.message ?: initError?.message ?: "Проверьте Vulkan/OpenCL."
                                )
                            } else {
                                garlicStatus.text = "MATRIX AI • GARLIC ONLINE • " + initialized.deviceAbi
                            }
                        }
                    }
                }
            }
        }
    }

    private fun chooseSavedProfilesWithGarlic(
        profiles: List<org.matrixvpn.profile.SavedVpnProfile>,
        mode: String
    ) {
        val token = garlicRunToken
        if (!garlicProfileSelector.isReady || !garlicProfileSelector.isActive(token)) {
            showSavedModeProfiles(profiles, mode)
            return
        }
        garlicStatus.text = "MATRIX AI • Garlic оценивает " + profiles.size + " профилей режима " + mode + "…"
        garlicProfileSelector.recommend(
            profiles,
            tunnelActive = false,
            currentProfileId = null,
            excludedProfileIds = emptySet(),
            token = token
        ).whenComplete { chosen, error ->
            runOnUiThread {
                if (!garlicProfileSelector.isActive(token)) return@runOnUiThread
                if (error != null || chosen == null) {
                    garlicStatus.text = "MATRIX AI • решение не прошло проверку; ручной выбор сохранён"
                    showSavedModeProfiles(profiles, mode)
                } else {
                    garlicStatus.text = "MATRIX AI • выбран " + chosen.provider + " / " + chosen.format
                    activateAiSelectedProfile(chosen, mode)
                }
            }
        }
    }

    private fun activateAiSelectedProfile(chosen: org.matrixvpn.profile.SavedVpnProfile, mode: String) {
        try {
            val raw = SecureProfileVault.load(this, chosen.id)
                ?: throw IllegalStateException("Не удалось прочитать сохранённый профиль.")
            val format = VpnProfileInspector.inspect(raw).format
            when (format) {
                ImportedVpnProfile.Format.OPENVPN -> {
                    openVpnProfileText = raw
                    wireGuardConfig = null
                    pendingOpenVpnStart = true
                }
                ImportedVpnProfile.Format.WIREGUARD, ImportedVpnProfile.Format.AMNEZIAWG -> {
                    openVpnProfileText = null
                    wireGuardConfig = Config.parse(ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)))
                    pendingOpenVpnStart = false
                }
                else -> throw IllegalArgumentException("Формат профиля не поддерживается.")
            }
            activeProfileId = chosen.id
            activeProfileName = chosen.name
            activeProfileProvider = chosen.provider
            SecureProfileVault.setActive(this, chosen.id)
            connectButton.isEnabled = true
            status.text = mode + " • Garlic выбрал: " + chosen.name + "\nЗапрашиваю разрешение Android…"
            requestSelectedProfileVpnPermission()
        } catch (error: Exception) {
            showError("Garlic не смог активировать профиль", error.message ?: "Профиль не прошёл проверку.")
        }
    }

    private fun emergencyStopAiAndVpn() {
        garlicRunToken = garlicProfileSelector.emergencyStop()
        qualityHandler.removeCallbacks(qualityMonitorRunnable)
        manualDisconnectInProgress = true
        engineSwitchInProgress = true
        connectButton.isEnabled = false
        warpModeButton.isEnabled = false
        emergencyStopButton.isEnabled = false
        emergencyStopButton.text = "ОСТАНОВКА AI + VPN…"
        garlicStatus.text = "MATRIX AI • аварийная остановка активирована"
        status.text = "Останавливаю оба VPN-движка…"
        runCatching { MatrixVpnService.stop(this) }
        Thread {
            val openStopped = stopOpenVpnAndWait(12_000L)
            val wireStopped = stopWireGuardAndWait(7_000L)
            runOnUiThread {
                manualDisconnectInProgress = false
                engineSwitchInProgress = false
                openVpnState = MatrixVpnService.currentState(this)
                openVpnRunning = isOpenVpnActive()
                tunnelUp = isWireGuardActive()
                emergencyStopButton.isEnabled = true
                emergencyStopButton.text = "АВАРИЙНО ОСТАНОВИТЬ AI + VPN"
                connectButton.isEnabled = true
                warpModeButton.isEnabled = true
                if (openStopped && wireStopped && !openVpnRunning && !tunnelUp) {
                    openVpnProfileText = null
                    wireGuardConfig = null
                    status.text = "VPN отключён. Автопилот заблокирован до нового нажатия режима."
                    garlicStatus.text = "MATRIX AI • ОСТАНОВЛЕН • ручной запуск разрешён"
                } else {
                    status.text = "Не удалось подтвердить отключение обоих VPN-движков."
                    garlicStatus.text = "MATRIX AI • остановлен; один движок не подтвердил отключение"
                    showError("Аварийная остановка не подтверждена", "Откройте настройки Android → VPN и проверьте статус MATRIX.")
                }
                refreshModeButtons()
            }
        }.start()
    }

    private fun isOpenVpnActive(): Boolean {
        val state = MatrixVpnService.currentState(this)
        return openVpnRunning || state == MatrixVpnService.STATE_CONNECTING ||
            state == MatrixVpnService.STATE_CONNECTED || state == MatrixVpnService.STATE_DISCONNECTING
    }

    private fun isWireGuardActive(): Boolean {
        if (tunnelUp) return true
        val backend = MatrixWireGuardSession.backend ?: return false
        return try {
            backend.getState(matrixTunnel) == State.UP
        } catch (_: Exception) {
            false
        }
    }

    /** Stop and verify both engines before starting either one. Android has one VPN slot per user. */
    private fun startSelectedVpnTunnel(token: Long = garlicRunToken) {
        if (!garlicProfileSelector.isActive(token)) {
            status.text = "Подключение отменено: автопилот остановлен."
            return
        }
        if (engineSwitchInProgress || autoRecoveryInProgress) return
        engineSwitchInProgress = true
        manualDisconnectInProgress = false
        connectButton.isEnabled = false
        warpModeButton.isEnabled = false
        status.text = "Подготавливаю выбранный режим…\nОтключаю предыдущий VPN-движок."

        Thread {
            val openVpnStopped = stopOpenVpnAndWait(12_000L)
            val wireGuardStopped = openVpnStopped && stopWireGuardAndWait(5_000L)
            if (!garlicProfileSelector.isActive(token)) {
                stopOpenVpnAndWait()
                stopWireGuardAndWait()
                return@Thread
            }
            runOnUiThread {
                if (!garlicProfileSelector.isActive(token)) return@runOnUiThread
                warpModeButton.isEnabled = true
                connectButton.isEnabled = true
                openVpnState = MatrixVpnService.currentState(this)
                openVpnRunning = isOpenVpnActive()
                if (!openVpnStopped || !wireGuardStopped || isWireGuardActive() || isOpenVpnActive()) {
                    engineSwitchInProgress = false
                    refreshModeButtons()
                    showError(
                        "Не удалось освободить VPN-подключение",
                        "Предыдущий VPN-движок не подтвердил отключение. Новый режим не запущен, чтобы два туннеля не конфликтовали. Отключите активный VPN и повторите попытку."
                    )
                    return@runOnUiThread
                }
                qualityTracker.reset()
                if (openVpnProfileText != null) startEmbeddedOpenVpnTunnel(token)
                else startWireGuardTunnel(token)
            }
        }.start()
    }

    private fun stopOpenVpnAndWait(timeoutMs: Long = 12_000L): Boolean {
        val state = MatrixVpnService.currentState(this)
        val active = openVpnRunning || state == MatrixVpnService.STATE_CONNECTING ||
            state == MatrixVpnService.STATE_CONNECTED || state == MatrixVpnService.STATE_DISCONNECTING
        if (!active) return true
        if (state != MatrixVpnService.STATE_DISCONNECTING) MatrixVpnService.stop(this)
        return waitForOpenVpnStop(timeoutMs)
    }

    private fun stopWireGuardAndWait(timeoutMs: Long = 5_000L): Boolean {
        return try {
            if (isWireGuardActive()) {
                wireGuardBackend.setState(matrixTunnel, State.DOWN, null)
            }
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (SystemClock.elapsedRealtime() < deadline) {
                if (!isWireGuardActive()) return true
                Thread.sleep(50L)
            }
            !isWireGuardActive()
        } catch (_: Exception) {
            false
        }
    }

    private fun startEmbeddedOpenVpnTunnel(token: Long) {
        if (!garlicProfileSelector.isActive(token)) return
        val profile = openVpnProfileText
        if (profile.isNullOrBlank()) {
            engineSwitchInProgress = false
            connectButton.isEnabled = true
            warpModeButton.isEnabled = true
            showError("Нет профиля OpenVPN", "Выберите или импортируйте профиль OpenVPN.")
            return
        }
        val needsCredentials = profile.lineSequence().any { it.trim().startsWith("auth-user-pass", true) } &&
            !profile.contains("<auth-user-pass>", true)
        if (needsCredentials && !activeProfileProvider.equals("VPN Gate", true)) {
            val username = EditText(this).apply { hint = "Имя пользователя" }
            val password = EditText(this).apply {
                hint = "Пароль"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            val fields = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 8, 24, 8)
                addView(username)
                addView(password)
            }
            AlertDialog.Builder(this)
                .setTitle("Учётные данные OpenVPN")
                .setMessage("Этот профиль требует имя пользователя и пароль. Данные используются только для текущего подключения.")
                .setView(fields)
                .setOnCancelListener {
                    if (garlicProfileSelector.isActive(token)) {
                        engineSwitchInProgress = false
                        connectButton.isEnabled = true
                        warpModeButton.isEnabled = true
                    }
                }
                .setNegativeButton("Отмена") { _, _ ->
                    if (garlicProfileSelector.isActive(token)) {
                        engineSwitchInProgress = false
                        connectButton.isEnabled = true
                        warpModeButton.isEnabled = true
                    }
                }
                .setPositiveButton("Подключить") { _, _ ->
                    launchEmbeddedOpenVpn(profile, username.text.toString(), password.text.toString(), token)
                }
                .show()
            return
        }
        val user = if (activeProfileProvider.equals("VPN Gate", true)) "vpn" else ""
        val pass = if (activeProfileProvider.equals("VPN Gate", true)) "vpn" else ""
        launchEmbeddedOpenVpn(profile, user, pass, token)
    }

    private fun launchEmbeddedOpenVpn(profile: String, username: String, password: String, token: Long) {
        if (!garlicProfileSelector.isActive(token)) return
        try {
            pendingOpenVpnStart = false
            status.text = "Запуск встроенного OpenVPN 3…\nСервер: $activeProfileName"
            connectButton.isEnabled = false
            MatrixVpnService.start(this, profile, username, password)
            if (!garlicProfileSelector.isActive(token)) {
                MatrixVpnService.stop(this)
                return
            }
            openVpnRunning = true
            openVpnState = MatrixVpnService.STATE_CONNECTING
            engineSwitchInProgress = false
            warpModeButton.isEnabled = true
            refreshModeButtons()
        } catch (error: Exception) {
            engineSwitchInProgress = false
            connectButton.isEnabled = true
            warpModeButton.isEnabled = true
            showError("Не удалось запустить OpenVPN 3", error.message ?: "Android не смог запустить VPN-службу.")
        }
    }

        private fun sectionHeading(label: String): TextView = TextView(this).apply {
        text = label
        textSize = 12f
        setTextColor(Color.rgb(0, 255, 70))
        gravity = Gravity.START
        setPadding(4, 18, 4, 6)
    }

    private fun menuButtonParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply {
        val side = (8 * resources.displayMetrics.density).toInt()
        marginStart = side
        marginEnd = side
        bottomMargin = (6 * resources.displayMetrics.density).toInt()
    }

    /** Fetches a fresh free Cloudflare WARP-compatible WireGuard profile from the public generator API. */
    private fun fetchFreeWarpProfile() {
        val previousVaultProfile = SecureProfileVault.activeId(this)
        status.text = "Получаю мобильный пул WARP AWG 2.0 • CPS5…"
        connectButton.isEnabled = false
        Thread {
            val savedProfiles = mutableListOf<org.matrixvpn.profile.SavedVpnProfile>()
            val errors = mutableListOf<String>()
            try {
                // Request recently checked WARP endpoints first; use hostname mode if Lab is unavailable.
                for (endpointMode in listOf("lab", "hostname")) {
                    var connection: java.net.HttpURLConnection? = null
                    try {
                        val url = java.net.URL(
                            "https://awgconfig.com/api/warp?mode=awg2&count=3&dns=cloudflare&mobile=1&cps5=1&cps=auto&cpsCompat=all&endpointMode=$endpointMode"
                        )
                        connection = (url.openConnection() as java.net.HttpURLConnection).apply {
                            requestMethod = "GET"
                            connectTimeout = 20000
                            readTimeout = 30000
                            setRequestProperty("Accept", "application/json")
                            setRequestProperty("User-Agent", "MATRIX-VPN-Android/0.6.1")
                        }
                        val code = connection.responseCode
                        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                        val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                            ?: throw IllegalStateException("Генератор не вернул ответ (HTTP $code).")
                        require(response.length <= 4_000_000) { "Ответ генератора слишком большой." }
                        if (code !in 200..299) {
                            errors.add("$endpointMode: HTTP $code")
                            if (endpointMode == "lab" && code >= 500) continue
                            throw IllegalStateException("Генератор WARP ответил HTTP $code.")
                        }
                        val json = org.json.JSONObject(response)
                        if (!json.optBoolean("success", true)) {
                            errors.add(json.optString("error").ifBlank { "Генератор отклонил запрос." })
                            if (endpointMode == "lab") continue
                            throw IllegalStateException(errors.last())
                        }
                        val appliedExtras = json.optJSONObject("appliedExtras")
                            ?: throw IllegalStateException("Генератор не подтвердил применённые параметры профиля.")
                        require(appliedExtras.optBoolean("mobile", false)) {
                            "Генератор не применил мобильные параметры MATRIX."
                        }
                        require(appliedExtras.optBoolean("cps5", false)) {
                            "Генератор не подтвердил дополнительные CPS-пакеты I2–I5."
                        }
                        val cpsStability = json.optString("cpsStability", "stable")
                        require(cpsStability.equals("stable", true)) {
                            "Генератор выбрал экспериментальный CPS-режим: $cpsStability."
                        }

                        val configs = json.optJSONArray("configs")
                        val encodedConfigs = mutableListOf<String>()
                        if (configs != null && configs.length() > 0) {
                            for (i in 0 until configs.length()) {
                                val encoded = configs.optJSONObject(i)?.optString("content").orEmpty()
                                if (encoded.isNotBlank()) encodedConfigs.add(encoded)
                            }
                        } else {
                            json.optString("content").takeIf { it.isNotBlank() }?.let(encodedConfigs::add)
                        }

                        for (encoded in encodedConfigs) {
                            if (savedProfiles.size >= 3) break
                            try {
                                val text = try {
                                    String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT), Charsets.UTF_8)
                                } catch (_: IllegalArgumentException) { encoded }
                                require(text.contains("[Interface]", true) && text.contains("[Peer]", true)) {
                                    "Конфигурация не похожа на WireGuard."
                                }
                                val inspected = VpnProfileInspector.inspect(text)
                                require(inspected.format == ImportedVpnProfile.Format.WIREGUARD ||
                                    inspected.format == ImportedVpnProfile.Format.AMNEZIAWG) {
                                    "Формат не поддерживается встроенным движком."
                                }
                                Config.parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
                                val number = savedProfiles.size + 1
                                val endpoint = inspected.endpointHints.firstOrNull()?.take(42)
                                val name = if (endpoint.isNullOrBlank()) "WARP AWG2 Mobile CPS5 • $number"
                                    else "WARP AWG2 • $number • $endpoint"
                                savedProfiles.add(SecureProfileVault.save(this, name, "WARP", text))
                            } catch (e: Exception) {
                                errors.add("Профиль ${savedProfiles.size + 1}: ${e.message ?: "ошибка проверки"}")
                            }
                        }
                        if (savedProfiles.isNotEmpty()) break
                        errors.add("$endpointMode: совместимых конфигураций нет")
                    } finally {
                        connection?.disconnect()
                    }
                }
                require(savedProfiles.isNotEmpty()) {
                    errors.joinToString("\n").ifBlank { "Генератор не выдал совместимых профилей." }
                }
                // New candidates do not replace the selected profile until the user chooses one.
                SecureProfileVault.setActive(this, previousVaultProfile)
                runOnUiThread {
                    status.text = "Получено WARP-профилей AWG 2.0: ${savedProfiles.size}\nВыберите узел."
                    connectButton.isEnabled = wireGuardConfig != null
                    showGeneratedWarpProfiles(savedProfiles)
                }
            } catch (e: Exception) {
                SecureProfileVault.setActive(this, previousVaultProfile)
                runOnUiThread {
                    connectButton.isEnabled = wireGuardConfig != null
                    status.text = "Не удалось обновить пул WARP"
                    showError("Генерация WARP не удалась",
                        (e.message ?: "Сервис временно недоступен.") +
                            "\n\nAWGConfig — сторонний генератор, а не официальный клиент Cloudflare.")
                }
            }
        }.start()
    }

    private fun showGeneratedWarpProfiles(profiles: List<org.matrixvpn.profile.SavedVpnProfile>) {
        if (garlicProfileSelector.isReady && garlicProfileSelector.isActive(garlicRunToken)) {
            chooseSavedProfilesWithGarlic(profiles, "WARP")
            return
        }
        val labels = profiles.map { profile ->
            val text = SecureProfileVault.load(this, profile.id)
            val endpoint = if (text == null) "адрес не определён"
                else VpnProfileInspector.inspect(text).endpointHints.firstOrNull() ?: "адрес не определён"
            "${profile.name}\n$endpoint\nAWG 2.0 Mobile + CPS5 • ещё не проверен на этом устройстве"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("ПУЛ WARP • AWG 2.0")
            .setMessage("Созданы отдельные WARP-регистрации с разными ключами. Выберите профиль для подключения.")
            .setItems(labels) { _, which ->
                val chosen = profiles[which]
                try {
                    val text = SecureProfileVault.load(this, chosen.id)
                        ?: throw IllegalStateException("Не удалось расшифровать профиль.")
                    wireGuardConfig = Config.parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
                    openVpnProfileText = null
                    pendingOpenVpnStart = false
                    activeProfileId = chosen.id
                    activeProfileName = chosen.name
                    activeProfileProvider = "WARP"
                    SecureProfileVault.setActive(this, chosen.id)
                    connectButton.isEnabled = true
                    status.text = "Выбран отдельный режим WARP\nУзел: ${chosen.name}\nЗапрашиваю разрешение Android…"
                    requestSelectedProfileVpnPermission()
                } catch (e: Exception) {
                    showError("Не удалось выбрать WARP-профиль", e.message ?: "Выберите другой узел.")
                }
            }
            .setNegativeButton("Позже", null)
            .show()
    }

    /**
     * Loads the public VPN Gate relay directory. These are volunteer-run OpenVPN servers,
     * not WARP profiles. Selected profiles are validated, encrypted in MATRIX's profile
     * vault, and connected with the embedded OpenVPN 3 Core through MatrixVpnService.
     */
    private fun searchVpnServices() {
        beginGarlicAutopilotSession()
        status.text = "Поиск работающих добровольческих узлов MATRIX…"
        connectButton.isEnabled = false
        val loading = AlertDialog.Builder(this)
            .setTitle("Сеть добровольцев MATRIX")
            .setMessage("Загружаю актуальные узлы добровольцев из официального каталога VPN Gate. Адреса меняются: MATRIX получает их заново при каждом поиске. Узлы принадлежат независимым операторам, а не MATRIX.")
            .setNegativeButton("Отмена", null)
            .create()
        loading.show()
        Thread {
            try {
                val catalog = fetchVpnGateCatalog()
                val rawServers = catalog.first
                val scoutResults = OpenVpnRouteScout(this).scout(
                    rawServers.map { RelayProbeTarget("${it.ip}|${it.host}", it.openVpnConfigBase64) }
                )
                val scoutById = scoutResults.associateBy { it.id }
                val servers = rawServers.sortedBy { server ->
                    when (scoutById["${server.ip}|${server.host}"]?.status) {
                        RelayProbeStatus.TCP_REACHABLE -> 0
                        RelayProbeStatus.UDP_OR_UNKNOWN -> 1
                        RelayProbeStatus.NO_PHYSICAL_NETWORK -> 2
                        RelayProbeStatus.PROBE_ERROR -> 3
                        RelayProbeStatus.TCP_UNREACHABLE -> 4
                        null -> 2
                    }
                }
                val tcpReachable = scoutResults.count { it.status == RelayProbeStatus.TCP_REACHABLE }
                val tcpTested = scoutResults.count { it.status == RelayProbeStatus.TCP_REACHABLE || it.status == RelayProbeStatus.TCP_UNREACHABLE }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    loading.dismiss()
                    connectButton.isEnabled = true
                    if (servers.isEmpty()) {
                        status.text = "Список бесплатных VPN-серверов пуст"
                        showError("Серверы не найдены", "Каталог не вернул доступных конфигураций. Попробуйте позже.")
                    } else {
                        status.text = "Найдено узлов добровольческой сети: " + servers.size + "\nИсточник: " + catalog.second +
                            "\nTCP-доступно: $tcpReachable из $tcpTested (предпроверка без OpenVPN handshake)."
                        val labels = servers.map { server ->
                            val pingLabel = if (server.ping >= 0) server.ping.toString() + " мс" else "ping неизвестен"
                            val speedLabel = if (server.speed > 0) " • " + (server.speed / 1_000_000) + " Мбит/с" else ""
                            val routeLabel = when (scoutById["${server.ip}|${server.host}"]?.status) {
                                RelayProbeStatus.TCP_REACHABLE -> " • TCP доступен"
                                RelayProbeStatus.TCP_UNREACHABLE -> " • TCP недоступен"
                                RelayProbeStatus.UDP_OR_UNKNOWN -> " • UDP/неизвестный транспорт"
                                else -> " • транспорт не проверен"
                            }
                            server.country + " • " + server.host + "\nIP: " + server.ip + " • " + pingLabel +
                                " • сессий: " + server.sessions + speedLabel + routeLabel
                        }.toTypedArray()
                        AlertDialog.Builder(this)
                            .setTitle("MATRIX • узлы добровольцев")
                            .setItems(labels) { _, which -> offerVpnGateProfile(servers[which]) }
                            .setPositiveButton("Автоподбор рабочего узла") { _, _ -> prepareVpnGateAutoQueue(servers) }
                            .setNegativeButton("Закрыть", null)
                            .setNeutralButton("Источник узлов") { _, _ ->
                                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.vpngate.net/"))) }
                                catch (_: Exception) { showError("Не удалось открыть сайт", "Откройте https://www.vpngate.net/ вручную.") }
                            }
                            .show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    loading.dismiss()
                    connectButton.isEnabled = true
                    status.text = "Поиск бесплатных VPN-серверов не выполнен"
                    showError("Ошибка поиска серверов", (e.message ?: "Каталоги VPN Gate сейчас недоступны.") +
                        "\n\nWARP и ручной импорт работают отдельно.")
                }
            }
        }.start()
    }

    private fun fetchVpnGateCatalog(): Pair<List<VpnGateServer>, String> {
        // Prefer the independently mirrored, hash-verified snapshot. Fall back to the official
        // CSV API if GitHub/CDN endpoints are unavailable on the user's mobile network.
        val mirrorFailure: Exception
        try {
            val mirror = fetchVpnGateMirror()
            return Pair(mirror.first, "проверенное зеркало VPN Gate • " + mirror.second)
        } catch (error: Exception) {
            mirrorFailure = error
        }

        try {
            val csvBytes = readHttpsBytes("https://www.vpngate.net/api/iphone/", 8 * 1024 * 1024)
            val csv = String(csvBytes, Charsets.UTF_8).removePrefix("\uFEFF")
            val lines = csv.lineSequence().map { it.trimEnd('\r') }
                .filter { it.isNotBlank() && it.trim() != "*" && !it.startsWith("*") }.toList()
            val header = lines.indexOfFirst { it.trimStart('#').startsWith("HostName,", ignoreCase = true) }
            require(header >= 0) { "Официальный CSV VPN Gate не содержит строки заголовков." }
            val columns = parseCsvLine(lines[header].trimStart('#')).map { it.trim() }
            val hostIndex = columns.indexOf("HostName")
            val ipIndex = columns.indexOf("IP")
            val scoreIndex = columns.indexOf("Score")
            val pingIndex = columns.indexOf("Ping")
            val speedIndex = columns.indexOf("Speed")
            val countryIndex = columns.indexOf("CountryLong")
            val countryShortIndex = columns.indexOf("CountryShort")
            val sessionsIndex = columns.indexOf("NumVpnSessions")
            val configIndex = columns.indexOf("OpenVPN_ConfigData_Base64")
            require(listOf(hostIndex, ipIndex, scoreIndex, pingIndex, speedIndex, countryIndex,
                countryShortIndex, sessionsIndex, configIndex).all { it >= 0 }) {
                "Формат CSV VPN Gate изменился: обязательные поля не найдены."
            }
            val servers = lines.drop(header + 1).mapNotNull { line ->
                if (line.startsWith("#")) return@mapNotNull null
                val fields = parseCsvLine(line)
                if (fields.size != columns.size) return@mapNotNull null
                val host = fields[hostIndex].trim()
                val ip = fields[ipIndex].trim()
                val score = fields[scoreIndex].toLongOrNull() ?: 0L
                val ping = fields[pingIndex].toLongOrNull() ?: -1L
                val speed = fields[speedIndex].toLongOrNull() ?: 0L
                val country = fields[countryIndex].trim().ifBlank {
                    fields[countryShortIndex].trim().ifBlank { "Неизвестная страна" }
                }
                val sessions = fields[sessionsIndex].toIntOrNull() ?: 0
                val config = fields[configIndex].trim()
                if (host.isBlank() || !isValidIpv4(ip) || config.isBlank()) null
                else VpnGateServer(host, ip, country, ping, speed, sessions, score, config)
            }.distinctBy { it.host + "|" + it.ip }
                .sortedWith(compareByDescending<VpnGateServer> { it.score }
                    .thenBy { if (it.ping < 0) Long.MAX_VALUE else it.ping }).take(30)
            require(servers.isNotEmpty()) { "Официальный каталог не содержит пригодных OpenVPN-конфигураций." }
            return Pair(servers, "официальный VPN Gate API")
        } catch (officialFailure: Exception) {
            throw IllegalStateException(
                "Зеркало VPN Gate: " + (mirrorFailure.message ?: "ошибка") +
                    "\nОфициальный API: " + (officialFailure.message ?: "ошибка")
            )
        }
    }

    private fun fetchVpnGateMirror(): Pair<List<VpnGateServer>, String> {
        val indexUrls = listOf(
            "https://raw.githubusercontent.com/GeorgeXie2333/vpngate-list-mirror/main/latest.json",
            "https://cdn.jsdelivr.net/gh/GeorgeXie2333/vpngate-list-mirror@main/latest.json",
            "https://fastly.jsdelivr.net/gh/GeorgeXie2333/vpngate-list-mirror@main/latest.json"
        )
        var index: org.json.JSONObject? = null
        val indexErrors = mutableListOf<String>()
        for (url in indexUrls) {
            try {
                val bytes = readHttpsBytes(url, 128 * 1024)
                val candidate = org.json.JSONObject(String(bytes, Charsets.UTF_8))
                require(candidate.optInt("schema_version", -1) == 1) {
                    "Неизвестная версия каталога VPN Gate."
                }
                index = candidate
                break
            } catch (error: Exception) {
                indexErrors.add(error.message ?: "ошибка загрузки индекса")
            }
        }
        val snapshotIndex = index ?: throw IllegalStateException(
            indexErrors.joinToString("; ").ifBlank { "Не удалось загрузить индекс VPN Gate." }
        )
        require(snapshotIndex.optInt("schema_version", -1) == 1) { "Версия схемы зеркала VPN Gate не поддерживается." }
        val commit = snapshotIndex.optString("data_commit")
        require(commit.matches(Regex("[0-9a-fA-F]{40}"))) { "Неверный идентификатор снимка VPN Gate." }
        val metadata = snapshotIndex.getJSONObject("files").getJSONObject("data/servers.json")
        val expectedBytes = metadata.optInt("bytes", -1)
        val expectedHash = metadata.optString("sha256").lowercase()
        require(expectedBytes in 1..(16 * 1024 * 1024)) { "Размер снимка недопустим." }
        require(expectedHash.matches(Regex("[0-9a-f]{64}"))) { "Хеш снимка недопустим." }

        val urls = listOf(
            "https://cdn.jsdelivr.net/gh/GeorgeXie2333/vpngate-list-mirror@" + commit + "/data/servers.json",
            "https://fastly.jsdelivr.net/gh/GeorgeXie2333/vpngate-list-mirror@" + commit + "/data/servers.json",
            "https://gcore.jsdelivr.net/gh/GeorgeXie2333/vpngate-list-mirror@" + commit + "/data/servers.json",
            "https://raw.githubusercontent.com/GeorgeXie2333/vpngate-list-mirror/" + commit + "/data/servers.json"
        )
        var verified: ByteArray? = null
        val errors = mutableListOf<String>()
        for (url in urls) {
            try {
                val candidate = readHttpsBytes(url, 16 * 1024 * 1024)
                require(candidate.size == expectedBytes) { "Размер снимка не совпал с индексом." }
                require(sha256(candidate) == expectedHash) { "SHA-256 снимка не совпал с индексом." }
                verified = candidate
                break
            } catch (error: Exception) {
                errors.add(error.message ?: "ошибка загрузки")
            }
        }
        val bytes = verified ?: throw IllegalStateException(errors.joinToString("; ").ifBlank {
            "Не удалось получить проверенный снимок VPN Gate."
        })
        val root = org.json.JSONObject(String(bytes, Charsets.UTF_8))
        require(root.optInt("schema_version", -1) == 1) { "Снимок VPN Gate имеет неизвестную схему." }
        val rows = root.getJSONArray("servers")
        require(rows.length() in 1..5000) { "Снимок VPN Gate пуст или слишком велик." }
        require(root.optInt("server_count", -1) == rows.length()) {
            "Количество серверов в снимке не совпадает с его заголовком."
        }
        require(snapshotIndex.optInt("server_count", -1) == rows.length() &&
            metadata.optInt("server_count", -1) == rows.length()) {
            "Количество серверов не совпадает с проверенным индексом."
        }

        val servers = mutableListOf<VpnGateServer>()
        for (i in 0 until rows.length()) {
            val item = rows.optJSONObject(i) ?: continue
            val host = item.optString("hostname")
            val ip = item.optString("ip")
            val encoded = item.optString("openvpn_config_base64")
            if (!host.matches(Regex("[A-Za-z0-9._-]{1,253}")) || !isValidIpv4(ip) || encoded.isBlank()) continue
            try {
                val config = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
                val configSize = item.optInt("openvpn_config_bytes", -1)
                val configHash = item.optString("openvpn_config_sha256").lowercase()
                if (config.isEmpty() || config.size > 1024 * 1024) continue
                if (configSize > 0 && config.size != configSize) continue
                if (configHash.isNotBlank() && sha256(config) != configHash) continue
                val text = String(config, Charsets.UTF_8)
                if (!text.contains("remote ", true) || !text.contains("client", true)) continue
                servers.add(VpnGateServer(
                    host = host,
                    ip = ip,
                    country = item.optString("country_name", "Неизвестная страна"),
                    ping = if (item.isNull("ping_ms")) -1L else item.optLong("ping_ms", -1L),
                    speed = item.optLong("speed_bps", 0L).coerceAtLeast(0L),
                    sessions = item.optInt("num_vpn_sessions", 0).coerceAtLeast(0),
                    score = item.optLong("score", 0L).coerceAtLeast(0L),
                    openVpnConfigBase64 = encoded
                ))
            } catch (_: Exception) {
                // Ignore a malformed row rather than discarding the verified snapshot.
            }
        }
        require(servers.isNotEmpty()) { "Проверенный снимок не содержит корректных OpenVPN-конфигураций." }
        return Pair(
            servers.sortedWith(compareByDescending<VpnGateServer> { it.score }
                .thenBy { if (it.ping < 0) Long.MAX_VALUE else it.ping }).take(30),
            snapshotIndex.optString("fetched_at").replace("T", " ").take(16)
        )
    }

    private fun readHttpsBytes(url: String, maxBytes: Int): ByteArray {
        val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 25000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MATRIX-VPN-Android/0.6.2")
            setRequestProperty("Accept", "application/json,text/csv,text/plain,*/*")
            setRequestProperty("Accept-Encoding", "identity")
        }
        try {
            val code = connection.responseCode
            require(code in 200..299) { "HTTP " + code + " при загрузке каталога." }
            val output = java.io.ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= maxBytes) { "Ответ каталога превысил лимит размера." }
                    output.write(buffer, 0, count)
                }
            }
            return output.toByteArray()
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { String.format("%02x", it) }

    private fun isValidIpv4(value: String): Boolean {
        val parts = value.split(".")
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all { it.isDigit() } &&
                (part.toIntOrNull()?.let { it in 0..255 } == true)
        }
    }

    private data class VpnGateServer(
        val host: String,
        val ip: String,
        val country: String,
        val ping: Long,
        val speed: Long,
        val sessions: Int,
        val score: Long,
        val openVpnConfigBase64: String
    )

    private fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
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
        fields.add(field.toString())
        return fields
    }

    private fun offerVpnGateProfile(server: VpnGateServer) {
        AlertDialog.Builder(this)
            .setTitle("Узел добровольца: ${server.country}")
            .setMessage(
                "Адрес: ${server.host} (${server.ip})\n\n" +
                "Это компьютер независимого добровольца, доступный через сеть VPN Gate. MATRIX подключается к нему встроенным OpenVPN 3 Core. " +
                "Оператор узла может видеть метаданные вашего трафика и ваш выходной IP. Не используйте такие узлы для банковских операций или конфиденциальных данных."
            )
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить и подключить") { _, _ -> connectVpnGateProfile(server) }
            .show()
    }

    private fun connectVpnGateProfile(server: VpnGateServer) {
        try {
            val decoded = android.util.Base64.decode(server.openVpnConfigBase64, android.util.Base64.DEFAULT)
            val profileText = String(decoded, Charsets.UTF_8)
            val inspected = VpnProfileInspector.inspect(profileText)
            require(inspected.format == ImportedVpnProfile.Format.OPENVPN) {
                "VPN Gate вернул конфигурацию, которая не распознана как OpenVPN."
            }
            val saved = SecureProfileVault.save(this, "MATRIX Community • ${server.country} • ${server.host}", "VPN Gate", profileText)
            openVpnProfileText = profileText
            wireGuardConfig = null
            activeProfileId = saved.id
            activeProfileName = saved.name
            activeProfileProvider = "VPN Gate"
            pendingOpenVpnStart = true
            SecureProfileVault.setActive(this, saved.id)
            requestSelectedProfileVpnPermission()
        } catch (error: Exception) {
            showError("Не удалось подготовить профиль OpenVPN", error.message ?: "Профиль сервера не удалось проверить.")
        }
    }

        private fun showProfilePoolDialog() {
        beginGarlicAutopilotSession()
        val profiles = SecureProfileVault.list(this)
            .sortedWith(compareByDescending<org.matrixvpn.profile.SavedVpnProfile> { NodeHealthStore.score(this, it.id) }
                .thenByDescending { it.createdAt })
        if (profiles.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Пул MATRIX пока пуст")
                .setMessage("Добавьте WARP, WireGuard/AmneziaWG или OpenVPN. Конфигурации хранятся в зашифрованном виде.")
                .setPositiveButton("Импортировать") { _, _ -> chooseVpnProfile() }
                .setNegativeButton("Закрыть", null).show()
            return
        }
        val labels = profiles.map { profile ->
            val marker = if (profile.provider.equals("WARP", true)) "WARP" else profile.format
            "${profile.name}\n$marker • оценка ${NodeHealthStore.score(this, profile.id)}/100 • ${NodeHealthStore.describe(this, profile.id)}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("ПУЛ ПРОФИЛЕЙ MATRIX")
            .setItems(labels) { _, which ->
                val chosen = profiles[which]
                try {
                    val text = SecureProfileVault.load(this, chosen.id)
                        ?: throw IllegalStateException("Не удалось расшифровать профиль. Импортируйте его заново.")
                    val inspected = VpnProfileInspector.inspect(text)
                    when (inspected.format) {
                        ImportedVpnProfile.Format.OPENVPN -> {
                            openVpnProfileText = text
                            wireGuardConfig = null
                        }
                        ImportedVpnProfile.Format.WIREGUARD, ImportedVpnProfile.Format.AMNEZIAWG -> {
                            openVpnProfileText = null
                            wireGuardConfig = Config.parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
                        }
                        else -> throw IllegalArgumentException("Формат профиля не поддерживается.")
                    }
                    activeProfileId = chosen.id
                    activeProfileName = chosen.name
                    activeProfileProvider = chosen.provider
                    SecureProfileVault.setActive(this, chosen.id)
                    connectButton.isEnabled = true
                    status.text = "Выбран профиль: ${chosen.name}\nТип: ${chosen.format}\nVPN-туннель: ${if (tunnelUp || openVpnRunning) "подключён" else "отключён"}"
                } catch (e: Exception) {
                    showError("Не удалось выбрать профиль", e.message ?: "Проверьте файл конфигурации.")
                }
            }
            .setPositiveButton("Добавить") { _, _ -> chooseVpnProfile() }
            .setNeutralButton("Удалить") { _, _ -> showDeleteProfileDialog(profiles) }
            .setNegativeButton("Закрыть", null)
            .show()
    }

        private fun showDeleteProfileDialog(profiles: List<org.matrixvpn.profile.SavedVpnProfile>) {
        val labels = profiles.map { "${it.name} • ${it.provider}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Удалить сохранённый профиль")
            .setItems(labels) { _, which ->
                val item = profiles[which]
                AlertDialog.Builder(this).setTitle("Удалить ${item.name}?")
                    .setMessage("Будут удалены зашифрованная конфигурация и история проверок.")
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Удалить") { _, _ ->
                        SecureProfileVault.delete(this, item.id)
                        if (activeProfileId == item.id) {
                            activeProfileId = null
                            SecureProfileVault.setActive(this, null)
                            wireGuardConfig = null
                            openVpnProfileText = null
                            pendingOpenVpnStart = false
                            connectButton.isEnabled = false
                            connectButton.text = "Подключить VPN"
                            status.text = "Активный профиль удалён. Импортируйте или выберите другой профиль."
                        }
                    }.show()
            }.setNegativeButton("Отмена", null).show()
    }

    private fun isMatrixLockdownEnabled(): Boolean {
        return try {
            val alwaysOnPackage = Settings.Secure.getString(contentResolver, "always_on_vpn_app")
            val lockdown = Settings.Secure.getInt(contentResolver, "always_on_vpn_lockdown", 0) == 1
            alwaysOnPackage == packageName && lockdown
        } catch (_: Exception) { false }
    }

    private fun showRecoveryStatus() {
        val enabled = isMatrixLockdownEnabled()
        val message = if (enabled) {
            "Системная блокировка Android включена для MATRIX. При трёх последовательных неудачных HTTPS-проверках приложение автоматически переберёт сохранённые профили WireGuard/AmneziaWG и OpenVPN, отключая старый движок до запуска следующего. Качество измеряется, пока приложение открыто. HTTPS-проверка не подтверждает криптографическое рукопожатие."
        } else {
            "Для безопасного автоматического переключения между OpenVPN и WireGuard/AmneziaWG откройте настройки Android → VPN → MATRIX, включите «Постоянная VPN» (Always-on VPN) и «Блокировать подключения без VPN» (Block connections without VPN). Без системной блокировки MATRIX не отключит текущий туннель ради другого."
        }
        AlertDialog.Builder(this)
            .setTitle(if (enabled) "Защита переключения активна" else "Нужно включить системную защиту")
            .setMessage(message)
            .setPositiveButton("Открыть настройки VPN") { _, _ ->
                try { startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
                catch (e: Exception) { showError("Настройки недоступны", e.message ?: "Откройте настройки Android → VPN вручную.") }
            }
            .setNegativeButton("Закрыть", null).show()
    }

    private fun importFromClipboard() {
        try {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            val text = if (clipboard.hasPrimaryClip() && clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).coerceToText(this)?.toString().orEmpty()
            } else ""
            if (text.isBlank()) {
                showPasteProfileDialog()
                return
            }
            processProfileText(text)
        } catch (e: Exception) {
            showError("Не удалось прочитать буфер телефона", e.message ?: "Скопируйте полный профиль и повторите попытку.")
        }
    }

    private fun chooseVpnProfile() {
        // Storage Access Framework works across Android 13–16 without broad storage permissions.
        // Keep the picker MIME filter unrestricted: some OEM document providers hide .conf files
        // when EXTRA_MIME_TYPES contains non-standard WireGuard/OpenVPN MIME types.
        val documentIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivityForResult(documentIntent, pickProfileRequest)
        } catch (_: android.content.ActivityNotFoundException) {
            // Compatibility fallback for devices without a working ACTION_OPEN_DOCUMENT provider.
            val contentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivityForResult(Intent.createChooser(contentIntent, "Выберите файл конфигурации VPN"), pickProfileRequest)
            } catch (e: Exception) {
                showError("Выбор файлов недоступен", "На телефоне недоступен выбор файлов. Вставьте полный текст через пункт «Вставить конфигурацию WARP / VPN». Подробности: ${e.message ?: "неизвестная ошибка"}")
            }
        } catch (e: Exception) {
            showError("Не удалось открыть файлы", "Попробуйте вставить полный текст конфигурации WARP/WireGuard. Подробности: ${e.message ?: "неизвестная ошибка"}")
        }
    }

    private fun showPasteProfileDialog() {
        val input = EditText(this).apply {
            hint = "[Interface]\\nPrivateKey = …\\nAddress = …\\n\\n[Peer]\\nPublicKey = …\\nEndpoint = host:port"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 8
            maxLines = 16
            gravity = Gravity.TOP or Gravity.START
            setPadding(24, 20, 24, 20)
        }
        AlertDialog.Builder(this)
            .setTitle("Вставить конфигурацию WARP / VPN")
            .setMessage("Откройте генератор WARP, скопируйте полную конфигурацию WireGuard/AmneziaWG и вставьте её сюда. Не вставляйте путь к папке или только адрес сервера.")
            .setView(input)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Проверить") { _, _ -> processProfileText(input.text.toString()) }
            .show()
    }

    private fun connectOrDisconnect() {
        if (engineSwitchInProgress || autoRecoveryInProgress) return
        if (isOpenVpnActive() || isWireGuardActive()) {
            emergencyStopAiAndVpn()
            return
        }
        if (openVpnProfileText == null && wireGuardConfig == null) {
            showError("Нет профиля VPN", "Сначала выберите WARP, найдите VPN или импортируйте профиль.")
            return
        }
        beginGarlicAutopilotSession()
        val token = garlicRunToken
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            pendingGarlicPermissionToken = token
            startActivityForResult(prepareIntent, vpnPermissionRequest)
        } else {
            startSelectedVpnTunnel(token)
        }
    }

    private fun startWireGuardTunnel(token: Long) {
        if (!garlicProfileSelector.isActive(token)) return
        val config = wireGuardConfig ?: run {
            engineSwitchInProgress = false
            warpModeButton.isEnabled = true
            connectButton.isEnabled = true
            return
        }
        manualDisconnectInProgress = false
        wireGuardStartupInProgress = true
        val startingProfileId = activeProfileId
        val startingProfileName = activeProfileName
        val startingProvider = activeProfileProvider
        status.text = "Запуск VPN-туннеля: $startingProfileName…"
        connectButton.isEnabled = false
        Thread {
            var finalText = ""
            var connected = false
            try {
                if (!garlicProfileSelector.isActive(token)) return@Thread
                wireGuardBackend.setState(matrixTunnel, State.UP, config)
                if (!garlicProfileSelector.isActive(token)) {
                    wireGuardBackend.setState(matrixTunnel, State.DOWN, null)
                    return@Thread
                }
                var probeStartedAt = SystemClock.elapsedRealtime()
                var probe = TunnelConnectivityProbe.check()
                var latency = (SystemClock.elapsedRealtime() - probeStartedAt).coerceAtLeast(1L)
                if (startingProfileId != null) NodeHealthStore.record(this, startingProfileId, probe.reachable, latency)
                if (!garlicProfileSelector.isActive(token)) {
                    wireGuardBackend.setState(matrixTunnel, State.DOWN, null)
                    return@Thread
                }

                if (probe.reachable) {
                    connected = true
                    finalText = "Подключено: $startingProfileName\n${probe.message}\nОценка основана на HTTPS-доступе через туннель; peer-handshake отдельно не подтверждён."
                } else if (isMatrixLockdownEnabled()) {
                    val backups = SecureProfileVault.list(this)
                        .filter {
                            it.id != startingProfileId &&
                                (if (startingProvider.equals("WARP", true)) it.provider.equals("WARP", true) else !it.provider.equals("WARP", true)) &&
                                it.format in setOf("WIREGUARD", "AMNEZIAWG")
                        }
                        .sortedByDescending { NodeHealthStore.score(this, it.id) }
                    val attempts = mutableListOf<String>()
                    for (backup in backups) {
                        if (!garlicProfileSelector.isActive(token)) {
                            wireGuardBackend.setState(matrixTunnel, State.DOWN, null)
                            break
                        }
                        try {
                            val text = SecureProfileVault.load(this, backup.id) ?: continue
                            val backupConfig = Config.parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
                            wireGuardBackend.setState(matrixTunnel, State.DOWN, null)
                            wireGuardBackend.setState(matrixTunnel, State.UP, backupConfig)
                            probeStartedAt = SystemClock.elapsedRealtime()
                            probe = TunnelConnectivityProbe.check()
                            latency = (SystemClock.elapsedRealtime() - probeStartedAt).coerceAtLeast(1L)
                            NodeHealthStore.record(this, backup.id, probe.reachable, latency)
                            if (!garlicProfileSelector.isActive(token)) {
                                wireGuardBackend.setState(matrixTunnel, State.DOWN, null)
                                connected = false
                                break
                            }
                            attempts.add(backup.name)
                            if (probe.reachable) {
                                activeProfileId = backup.id
                                activeProfileName = backup.name
                                activeProfileProvider = backup.provider
                                SecureProfileVault.setActive(this, backup.id)
                                wireGuardConfig = backupConfig
                                connected = true
                                finalText = "Резервное переключение выполнено\nАктивный профиль: ${backup.name}\n${probe.message}\nРезультат подтверждён HTTPS-проверкой; peer-handshake отдельно не подтверждён."
                                break
                            }
                            wireGuardBackend.setState(matrixTunnel, State.DOWN, null)
                        } catch (_: Exception) {
                            attempts.add("${backup.name} (ошибка запуска)")
                            try { wireGuardBackend.setState(matrixTunnel, State.DOWN, null) } catch (_: Exception) { }
                        }
                    }
                    if (!connected) {
                        try { wireGuardBackend.setState(matrixTunnel, State.DOWN, null) } catch (_: Exception) { }
                        finalText = "Основной профиль и резервные профили не прошли проверку.\nПроверено резервных профилей: ${attempts.size}.\nСистемная блокировка Android остаётся включённой."
                    }
                } else {
                    connected = true
                    finalText = "Туннель запущен, но HTTPS-проверка не пройдена.\n${probe.message}\nАвтопереключение не запускалось: включите Android Always-on VPN и «Блокировать подключения без VPN»."
                }
            } catch (e: Exception) {
                finalText = "Ошибка запуска VPN: ${e.message ?: e.javaClass.simpleName}"
                try { wireGuardBackend.setState(matrixTunnel, State.DOWN, null) } catch (_: Exception) { }
            }
            if (!garlicProfileSelector.isActive(token)) {
                try { wireGuardBackend.setState(matrixTunnel, State.DOWN, null) } catch (_: Exception) { }
                return@Thread
            }
            runOnUiThread {
                if (!garlicProfileSelector.isActive(token)) return@runOnUiThread
                wireGuardStartupInProgress = false
                engineSwitchInProgress = false
                warpModeButton.isEnabled = true
                status.text = finalText
                connectButton.isEnabled = true
                connectButton.text = if (connected && tunnelUp) "Отключить VPN" else "Подключить VPN"
                if (!connected && finalText.startsWith("Ошибка")) showError("Не удалось подключить VPN", finalText)
                if (!connected && isMatrixLockdownEnabled() && !autoRecoveryInProgress) {
                    startCrossEngineRecovery(startingProfileId, finalText)
                }
            }
        }.start()
    }



    /**
     * Saves a bounded VPN Gate candidate set and runs a one-tap sequential attempt
     * under Android lockdown. After the volunteer relay queue is exhausted,
     * previously saved WARP/WireGuard profiles remain eligible as a fallback.
     */

    private fun prepareVpnGateAutoQueue(servers: List<VpnGateServer>) {
        beginGarlicAutopilotSession()
        if (!isMatrixLockdownEnabled()) {
            showRecoveryStatus()
            return
        }
        val previousActiveId = SecureProfileVault.activeId(this)
        connectButton.isEnabled = false
        status.text = "Подготавливаю профили узлов VPN Gate для последовательной проверки…"
        Thread {
            val savedProfiles = mutableListOf<org.matrixvpn.profile.SavedVpnProfile>()
            val failures = mutableListOf<String>()
            for (server in servers.take(MAX_VPN_GATE_AUTO_CANDIDATES)) {
                try {
                    val decoded = android.util.Base64.decode(server.openVpnConfigBase64, android.util.Base64.DEFAULT)
                    val profile = String(decoded, Charsets.UTF_8)
                    require(VpnProfileInspector.inspect(profile).format == ImportedVpnProfile.Format.OPENVPN) {
                        "Каталог вернул некорректный OpenVPN-профиль."
                    }
                    val saved = SecureProfileVault.save(
                        this,
                        "MATRIX Auto • ${server.country} • ${server.host}",
                        "VPN Gate",
                        profile
                    )
                    savedProfiles.add(saved)
                    // SecureProfileVault.save selects every newly added profile.
                    // Restore the original active ID after each save so vault trimming
                    // does not evict the user's current profile during a batch.
                    SecureProfileVault.setActive(this, previousActiveId)
                } catch (error: Exception) {
                    failures.add("${server.host}: ${error.message ?: "ошибка профиля"}")
                }
            }
            SecureProfileVault.setActive(this, previousActiveId)
            runOnUiThread {
                connectButton.isEnabled = true
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (savedProfiles.isEmpty()) {
                    showError("Автоподбор не подготовлен", failures.take(5).joinToString("\n").ifBlank {
                        "Не удалось сохранить ни одного подходящего узла."
                    })
                    return@runOnUiThread
                }
                startCrossEngineRecovery(
                    previousActiveId,
                    "Автоподбор: проверяю ${savedProfiles.size} узлов VPN Gate; после них доступны сохранённые резервные WARP/WireGuard-профили.",
                    savedProfiles.map { it.id }
                )
                if (failures.isNotEmpty()) {
                    networkStatus.text = "Подготовлено ${savedProfiles.size} узлов; пропущено ${failures.size} некорректных профилей."
                }
            }
        }.start()
    }

    private fun isVpnSessionConnected(): Boolean =
        tunnelUp || openVpnState == MatrixVpnService.STATE_CONNECTED ||
            MatrixVpnService.currentState(this) == MatrixVpnService.STATE_CONNECTED

    private fun scheduleQualityMonitor(delayMs: Long = QUALITY_INTERVAL_MS) {
        if (isFinishing || isDestroyed) return
        qualityHandler.removeCallbacks(qualityMonitorRunnable)
        qualityHandler.postDelayed(qualityMonitorRunnable, delayMs)
    }

    /** Foreground quality monitor: three consecutive failed probes are needed before a switch. */
    private fun runQualityProbe() {
        if (qualityProbeInProgress || autoRecoveryInProgress || manualDisconnectInProgress || !isVpnSessionConnected()) return
        qualityProbeInProgress = true
        val checkedProfileId = activeProfileId
        Thread {
            val started = SystemClock.elapsedRealtime()
            val result = TunnelConnectivityProbe.check()
            val latency = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1L)
            if (checkedProfileId != null) NodeHealthStore.record(this, checkedProfileId, result.reachable, latency)
            val snapshot = qualityTracker.record(result.reachable, latency)
            runOnUiThread {
                qualityProbeInProgress = false
                if (manualDisconnectInProgress || !isVpnSessionConnected() || isFinishing || isDestroyed) return@runOnUiThread
                val label = when (snapshot.quality) {
                    TunnelQuality.HEALTHY -> "ХОРОШЕЕ"
                    TunnelQuality.DEGRADED -> "НЕСТАБИЛЬНО"
                    TunnelQuality.UNREACHABLE -> "НЕТ ДОСТУПА"
                    TunnelQuality.UNKNOWN -> "ПРОВЕРКА"
                }
                networkStatus.text = "VPN • $label • ${if (result.reachable) "${latency} мс" else "ошибка проверки"}"
                if (snapshot.shouldFailover) {
                    if (isMatrixLockdownEnabled()) {
                        startCrossEngineRecovery(checkedProfileId, "Три последовательные проверки внешней сети завершились ошибкой.")
                    } else {
                        status.text = "VPN-туннель запущен, но внешний доступ не подтверждён.\nДля автоматического переключения включите Always-on VPN и блокировку подключений без VPN."
                    }
                } else if (!result.reachable) {
                    status.text = "Проверка качества VPN: неудача ${snapshot.consecutiveFailures}/3.\n${result.message}"
                }
            }
        }.start()
    }

    /**
     * Switches sequentially between both embedded engines. Android lockdown
     * is required so a handover failure cannot expose traffic outside the VPN.
     */
    private fun startCrossEngineRecovery(
        excludedProfileId: String?,
        reason: String,
        preferredProfileIds: List<String> = emptyList(),
        garlicRankingComplete: Boolean = false,
        recoveryIsWarpOverride: Boolean? = null
    ) {
        if (!garlicProfileSelector.isActive(garlicRunToken)) {
            status.text = "$reason\nАвтопереключение остановлено пользователем."
            return
        }
        if (autoRecoveryInProgress || pendingRecoveryReason != null) return
        if (!isMatrixLockdownEnabled()) {
            status.text = "$reason\nАвтоматический переход заблокирован: включите Android Always-on VPN и блокировку подключений без VPN."
            return
        }
        val vpnConsent = VpnService.prepare(this)
        if (vpnConsent != null) {
            pendingRecoveryReason = reason
            pendingRecoveryExcludedId = excludedProfileId
            pendingRecoveryPreferredIds = preferredProfileIds
            pendingRecoveryGarlicToken = garlicRunToken
            startActivityForResult(vpnConsent, vpnPermissionRequest)
            return
        }

        autoRecoveryInProgress = true
        qualityHandler.removeCallbacks(qualityMonitorRunnable)
        connectButton.isEnabled = false
        status.text = "$reason\nЗапускаю безопасное переключение между VPN-движками…"

        val preferredOrder = preferredProfileIds.withIndex().associate { it.value to it.index }
        // WARP and the ordinary VPN mode are separate products in the UI; recovery must never cross between them.
        // A VPN Gate queue is always ordinary VPN, even if the previously selected profile happened to be WARP.
        val recoveryIsWarp = recoveryIsWarpOverride
            ?: (preferredProfileIds.isEmpty() && activeProfileProvider.equals("WARP", true))
        val candidates = SecureProfileVault.list(this)
            .filter { it.id != excludedProfileId && it.provider.equals("WARP", true) == recoveryIsWarp }
            .sortedWith(
                compareBy<org.matrixvpn.profile.SavedVpnProfile> { preferredOrder[it.id] ?: Int.MAX_VALUE }
                    .thenByDescending { NodeHealthStore.score(this, it.id) }
                    .thenByDescending { it.createdAt }
            )
            .take(MAX_CROSS_ENGINE_CANDIDATES)

        if (!garlicRankingComplete && garlicProfileSelector.isReady &&
            garlicProfileSelector.isActive(garlicRunToken) && candidates.size > 1) {
            val rankingToken = garlicRunToken
            garlicStatus.text = "MATRIX AI • Garlic выбирает резервный маршрут…"
            garlicProfileSelector.recommend(
                candidates, true, activeProfileId, setOfNotNull(excludedProfileId), rankingToken
            ).whenComplete { recommendation, error ->
                runOnUiThread {
                    if (!garlicProfileSelector.isActive(rankingToken)) return@runOnUiThread
                    val rankedIds = if (error == null && recommendation != null) {
                        listOf(recommendation.id) + preferredProfileIds.filterNot { it == recommendation.id }
                    } else preferredProfileIds
                    garlicStatus.text = if (error == null && recommendation != null) {
                        "MATRIX AI • резерв выбран: " + recommendation.provider + " / " + recommendation.format
                    } else {
                        "MATRIX AI • резерв по измеренной истории узлов"
                    }
                    startCrossEngineRecovery(
                        excludedProfileId,
                        reason,
                        rankedIds,
                        garlicRankingComplete = true,
                        recoveryIsWarpOverride = recoveryIsWarp
                    )
                }
            }
            return
        }

        val recoveryToken = garlicRunToken
        Thread {
            var selectedProfile: org.matrixvpn.profile.SavedVpnProfile? = null
            var selectedRaw: String? = null
            var selectedConfig: Config? = null
            var selectedEngine = ""
            val attempts = mutableListOf<String>()

            fun stopOpenVpnAndWait(): Boolean = this@MainActivity.stopOpenVpnAndWait(12_000L)
            fun stopWireGuardAndWait(): Boolean = this@MainActivity.stopWireGuardAndWait(5_000L)

            try {
                if (!stopOpenVpnAndWait()) {
                    attempts.add("OpenVPN не подтвердил отключение; переключение остановлено для безопасности.")
                } else if (!stopWireGuardAndWait()) {
                    attempts.add("WireGuard/AmneziaWG не подтвердил отключение; переключение остановлено для безопасности.")
                }

                for (candidate in candidates) {
                    if (!garlicProfileSelector.isActive(recoveryToken)) {
                        attempts.add("Автоматическое восстановление остановлено пользователем.")
                        break
                    }
                    if (attempts.any { it.contains("переключение остановлено") }) break
                    if (selectedProfile != null) break
                    try {
                        val raw = SecureProfileVault.load(this, candidate.id) ?: continue
                        val inspected = VpnProfileInspector.inspect(raw)
                        when (inspected.format) {
                            ImportedVpnProfile.Format.WIREGUARD,
                            ImportedVpnProfile.Format.AMNEZIAWG -> {
                                val config = Config.parse(ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)))
                                wireGuardBackend.setState(matrixTunnel, State.UP, config)
                                Thread.sleep(1_500L)
                                val started = SystemClock.elapsedRealtime()
                                val probe = TunnelConnectivityProbe.check()
                                val latency = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1L)
                                NodeHealthStore.record(this, candidate.id, probe.reachable, latency)
                                if (!garlicProfileSelector.isActive(recoveryToken)) {
                                    attempts.add("Аварийная остановка нажата во время проверки WireGuard; завершаю остановку.")
                                    stopWireGuardAndWait()
                                    break
                                }
                                attempts.add("${candidate.name} • AmneziaWG/WireGuard")
                                if (probe.reachable) {
                                    selectedProfile = candidate
                                    selectedRaw = raw
                                    selectedConfig = config
                                    selectedEngine = "WireGuard/AmneziaWG"
                                } else if (!stopWireGuardAndWait()) {
                                    attempts.add("Туннель WireGuard не подтвердил остановку; перебор прерван.")
                                    break
                                }
                            }
                            ImportedVpnProfile.Format.OPENVPN -> {
                                val needsCredentials = raw.lineSequence().any {
                                    it.trim().startsWith("auth-user-pass", true)
                                } && !raw.contains("<auth-user-pass>", true)
                                if (needsCredentials && !candidate.provider.equals("VPN Gate", true)) {
                                    attempts.add("${candidate.name} • пропущен: нужны учётные данные")
                                    continue
                                }
                                val user = if (candidate.provider.equals("VPN Gate", true)) "vpn" else ""
                                val pass = if (candidate.provider.equals("VPN Gate", true)) "vpn" else ""
                                MatrixVpnService.start(this, raw, user, pass)
                                val connected = waitForOpenVpnConnected(45_000L)
                                if (!garlicProfileSelector.isActive(recoveryToken)) {
                                    attempts.add("Аварийная остановка нажата во время запуска OpenVPN; завершаю остановку.")
                                    stopOpenVpnAndWait()
                                    stopWireGuardAndWait()
                                    break
                                }
                                if (connected) {
                                    Thread.sleep(1_000L)
                                    val started = SystemClock.elapsedRealtime()
                                    val probe = TunnelConnectivityProbe.check()
                                    val latency = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1L)
                                    NodeHealthStore.record(this, candidate.id, probe.reachable, latency)
                                    attempts.add("${candidate.name} • OpenVPN 3")
                                    if (probe.reachable) {
                                        selectedProfile = candidate
                                        selectedRaw = raw
                                        selectedEngine = "OpenVPN 3"
                                    }
                                } else {
                                    attempts.add("${candidate.name} • OpenVPN 3 не подключился")
                                }
                                if (selectedProfile == null && !stopOpenVpnAndWait()) {
                                    attempts.add("OpenVPN не подтвердил отключение; дальнейший перебор остановлен.")
                                    break
                                }
                            }
                            else -> attempts.add("${candidate.name} • неподдерживаемый формат")
                        }
                    } catch (error: Exception) {
                        attempts.add("${candidate.name} • ошибка: ${error.message ?: error.javaClass.simpleName}")
                        val openVpnStopped = stopOpenVpnAndWait()
                        val wireGuardStopped = stopWireGuardAndWait()
                        if (!openVpnStopped || !wireGuardStopped) {
                            attempts.add("Активный VPN-движок не подтвердил остановку; дальнейшее переключение прекращено.")
                            break
                        }
                    }
                }
            } catch (error: Exception) {
                attempts.add("Ошибка восстановления: ${error.message ?: error.javaClass.simpleName}")
            }

            if (!garlicProfileSelector.isActive(recoveryToken)) {
                // A stop can arrive while a backend connect/probe is already in flight.
                // Reassert DOWN after that operation returns and discard its candidate.
                stopOpenVpnAndWait()
                stopWireGuardAndWait()
                selectedProfile = null
                selectedRaw = null
                selectedConfig = null
                selectedEngine = ""
            }
            val recovered = selectedProfile != null
            if (!recovered) {
                stopOpenVpnAndWait()
                stopWireGuardAndWait()
            }
            runOnUiThread {
                autoRecoveryInProgress = false
                connectButton.isEnabled = true
                if (!garlicProfileSelector.isActive(recoveryToken)) {
                    status.text = "AI и VPN остановлены пользователем."
                    refreshModeButtons()
                    return@runOnUiThread
                }
                if (recovered) {
                    val chosen = selectedProfile!!
                    activeProfileId = chosen.id
                    activeProfileName = chosen.name
                    activeProfileProvider = chosen.provider
                    SecureProfileVault.setActive(this, chosen.id)
                    if (selectedEngine == "OpenVPN 3") {
                        openVpnProfileText = selectedRaw
                        wireGuardConfig = null
                        openVpnState = MatrixVpnService.currentState(this)
                        openVpnRunning = openVpnState == MatrixVpnService.STATE_CONNECTED
                    } else {
                        openVpnProfileText = null
                        wireGuardConfig = selectedConfig
                        openVpnState = MatrixVpnService.STATE_DISCONNECTED
                        openVpnRunning = false
                    }
                    qualityTracker.reset()
                    status.text = "Автоматическое переключение выполнено.\nДвижок: $selectedEngine\nПрофиль: ${chosen.name}\nПроверка внешнего HTTPS успешна."
                    refreshModeButtons()
                    scheduleQualityMonitor(QUALITY_FIRST_PROBE_DELAY_MS)
                } else {
                    openVpnState = MatrixVpnService.currentState(this)
                    openVpnRunning = openVpnState == MatrixVpnService.STATE_CONNECTED ||
                        openVpnState == MatrixVpnService.STATE_CONNECTING || openVpnState == MatrixVpnService.STATE_DISCONNECTING
                    tunnelUp = false
                    status.text = "Автоматическое восстановление не нашло работающий профиль.\nПопыток: ${attempts.size}.\nСистемная блокировка Android остаётся включённой: обычный трафик не должен выходить напрямую.\n" +
                        attempts.take(8).joinToString("\n")
                    refreshModeButtons()
                }
            }
        }.start()
    }

    private fun waitForOpenVpnConnected(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            when (MatrixVpnService.currentState(this)) {
                MatrixVpnService.STATE_CONNECTED -> return true
                MatrixVpnService.STATE_ERROR, MatrixVpnService.STATE_DISCONNECTED -> return false
            }
            Thread.sleep(300L)
        }
        return false
    }

    private fun waitForOpenVpnStop(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            when (MatrixVpnService.currentState(this)) {
                MatrixVpnService.STATE_DISCONNECTED, MatrixVpnService.STATE_ERROR -> return true
            }
            Thread.sleep(250L)
        }
        return false
    }

    private fun configureHttpConnectProxy() {
        var profile = openVpnProfileText
        if (profile.isNullOrBlank() && activeProfileId != null) {
            val stored = SecureProfileVault.load(this, activeProfileId!!)
            if (stored != null && VpnProfileInspector.inspect(stored).format == ImportedVpnProfile.Format.OPENVPN) {
                profile = stored
            }
        }
        if (profile.isNullOrBlank()) {
            showError("Нужен OpenVPN TCP-профиль", "Сначала выберите или импортируйте OpenVPN-профиль с TCP remote. Для UDP-профилей HTTP CONNECT не подходит.")
            return
        }

        val hostInput = EditText(this).apply {
            hint = "Адрес HTTP-прокси (например proxy.example)"
            setSingleLine(true)
            setText("")
        }
        val portInput = EditText(this).apply {
            hint = "Порт (например 8080)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
        }
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
            addView(hostInput)
            addView(portInput)
        }
        AlertDialog.Builder(this)
            .setTitle("HTTP CONNECT • TCP")
            .setMessage("Укажите свой доверенный HTTP-прокси. MATRIX сохранит отдельную копию профиля с http-proxy. Это прокси-транспорт, а не гарантия обхода DPI. В этой версии прокси с авторизацией не поддерживаются.")
            .setView(fields)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить копию") { _, _ ->
                try {
                    val updated = OpenVpnHttpProxy.apply(profile, hostInput.text.toString(), portInput.text.toString().toInt())
                    val baseName = "$activeProfileName • HTTP CONNECT".take(72)
                    val saved = SecureProfileVault.save(this, baseName, activeProfileProvider, updated)
                    openVpnProfileText = updated
                    wireGuardConfig = null
                    activeProfileId = saved.id
                    activeProfileName = saved.name
                    activeProfileProvider = saved.provider
                    pendingOpenVpnStart = false
                    SecureProfileVault.setActive(this, saved.id)
                    connectButton.isEnabled = true
                    status.text = "Создан отдельный TCP-прокси профиль.\n${saved.name}\nИсходная конфигурация сохранена без изменений."
                    refreshModeButtons()
                } catch (error: Exception) {
                    showError("Не удалось настроить HTTP CONNECT", error.message ?: "Проверьте TCP-профиль и адрес прокси.")
                }
            }
            .show()
    }

    /**
     * Restores the last supported profile after process/app restart.
     * The tunnel is deliberately not started automatically: Android VPN consent remains explicit.
     */
    private fun restoreSavedProfile() {
        val activeId = SecureProfileVault.activeId(this)
        if (activeId != null) {
            val metadata = SecureProfileVault.list(this).firstOrNull { it.id == activeId }
            val stored = SecureProfileVault.load(this, activeId)
            if (metadata != null && stored != null) {
                try {
                    val inspected = VpnProfileInspector.inspect(stored)
                    when (inspected.format) {
                        ImportedVpnProfile.Format.OPENVPN -> {
                            openVpnProfileText = stored
                            wireGuardConfig = null
                        }
                        ImportedVpnProfile.Format.WIREGUARD, ImportedVpnProfile.Format.AMNEZIAWG -> {
                            openVpnProfileText = null
                            wireGuardConfig = Config.parse(ByteArrayInputStream(stored.toByteArray(Charsets.UTF_8)))
                        }
                        else -> throw IllegalArgumentException("Сохранённый профиль имеет неподдерживаемый формат.")
                    }
                    activeProfileId = metadata.id
                    activeProfileName = metadata.name
                    activeProfileProvider = metadata.provider
                    connectButton.isEnabled = wireGuardConfig != null || openVpnProfileText != null
                    status.text = "Пул MATRIX восстановлен\nАктивный профиль: ${metadata.name}\nVPN-туннель: ${if (openVpnRunning) "работает" else "отключён"}"
                    return
                } catch (_: Exception) {
                    SecureProfileVault.delete(this, activeId)
                }
            }
        }
        val legacy = SecureProfileStore.load(this) ?: return
        try {
            val inspected = VpnProfileInspector.inspect(legacy)
            require(inspected.format == ImportedVpnProfile.Format.WIREGUARD ||
                inspected.format == ImportedVpnProfile.Format.AMNEZIAWG) {
                "Сохранённый профиль больше не поддерживается."
            }
            wireGuardConfig = Config.parse(ByteArrayInputStream(legacy.toByteArray(Charsets.UTF_8)))
            openVpnProfileText = null
            val saved = SecureProfileVault.save(this, "Мигрированный профиль", "Импорт", legacy)
            activeProfileId = saved.id
            activeProfileName = saved.name
            activeProfileProvider = saved.provider
            connectButton.isEnabled = true
            status.text = "Старый профиль перенесён в пул MATRIX\nVPN-туннель: отключён"
        } catch (e: Exception) {
            wireGuardConfig = null
            openVpnProfileText = null
            connectButton.isEnabled = false
            status.text = "Сохранённый профиль повреждён или больше не поддерживается."
            showError("Не удалось восстановить профиль", e.message ?: "Импортируйте конфигурацию заново.")
        }
    }

    private fun showError(title: String, message: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("Понятно", null).show()
    }

    @Deprecated("Используется системный выбор файлов для совместимости с разными версиями Android.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == garlicModelPackRequest) {
            if (resultCode == RESULT_OK) data?.data?.let { installGarlicModelPack(it) }
            return
        }
        if (requestCode == vpnPermissionRequest) {
            val recoveryReason = pendingRecoveryReason
            val recoveryToken = pendingRecoveryGarlicToken
            pendingRecoveryGarlicToken = null
            val selectedProfileToken = pendingGarlicPermissionToken
            pendingGarlicPermissionToken = null
            if (selectedProfileToken != null && !garlicProfileSelector.isActive(selectedProfileToken)) {
                pendingOpenVpnStart = false
                status.text = "AI и VPN остановлены пользователем. Отложенное подключение отменено."
                garlicStatus.text = "MATRIX AI • ОСТАНОВЛЕН • запрос подключения отменён"
                refreshModeButtons()
                return
            }
            if (recoveryReason != null &&
                (recoveryToken == null || !garlicProfileSelector.isActive(recoveryToken))) {
                pendingRecoveryReason = null
                pendingRecoveryExcludedId = null
                pendingRecoveryPreferredIds = emptyList()
                status.text = "Автоматическое переключение отменено аварийной остановкой."
                return
            }
            if (resultCode == RESULT_OK && recoveryReason != null) {
                val excludedId = pendingRecoveryExcludedId
                val preferredIds = pendingRecoveryPreferredIds
                pendingRecoveryReason = null
                pendingRecoveryExcludedId = null
                pendingRecoveryPreferredIds = emptyList()
                startCrossEngineRecovery(excludedId, recoveryReason, preferredIds)
            } else if (resultCode == RESULT_OK) {
                if (selectedProfileToken != null) startSelectedVpnTunnel(selectedProfileToken)
                else {
                    status.text = "Подключение отменено: отсутствует действующий запрос MATRIX."
                    refreshModeButtons()
                }
            } else {
                pendingRecoveryReason = null
                pendingRecoveryExcludedId = null
                pendingRecoveryPreferredIds = emptyList()
                pendingRecoveryGarlicToken = null
                showError("Разрешение VPN не предоставлено", "Android не разрешил приложению MATRIX создать VPN-туннель.")
                scheduleQualityMonitor(QUALITY_FIRST_PROBE_DELAY_MS)
            }
            return
        }
        if (requestCode != pickProfileRequest || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        try {
            val profileText = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: throw IllegalArgumentException("Не удалось прочитать выбранный файл.")
            processProfileText(profileText)
        } catch (e: Exception) {
            showError("Не удалось прочитать профиль VPN", e.message ?: "Не удалось проверить выбранный файл.")
        }
    }

    private fun processProfileText(
        profileText: String,
        suggestedName: String? = null,
        providerLabel: String = "Импорт"
    ) {
        try {
            require(profileText.isNotBlank()) { "Конфигурация пустая. Вставьте текст или выберите полный файл .conf." }
            require(profileText.length <= 2_000_000) { "Файл слишком большой для безопасного импорта." }
            val profile = VpnProfileInspector.inspect(profileText)
            val formatLabel = when (profile.format) {
                ImportedVpnProfile.Format.WIREGUARD -> "WireGuard"
                ImportedVpnProfile.Format.AMNEZIAWG -> "AmneziaWG (AWG)"
                ImportedVpnProfile.Format.OPENVPN -> "OpenVPN"
                ImportedVpnProfile.Format.UNKNOWN -> "Неизвестный формат профиля"
            }
            wireGuardConfig = null
            openVpnProfileText = if (profile.format == ImportedVpnProfile.Format.OPENVPN) profileText else null
            if (profile.format == ImportedVpnProfile.Format.WIREGUARD || profile.format == ImportedVpnProfile.Format.AMNEZIAWG) {
                try {
                    wireGuardConfig = Config.parse(ByteArrayInputStream(profileText.toByteArray(Charsets.UTF_8)))
                } catch (e: Exception) {
                    showError("Неверный профиль WireGuard", e.message ?: "Проверка WireGuard отклонила эту конфигурацию.")
                }
            }
            connectButton.isEnabled = wireGuardConfig != null || openVpnProfileText != null
            if (wireGuardConfig != null || openVpnProfileText != null) {
                try {
                    val defaultName = suggestedName
                        ?: profile.endpointHints.firstOrNull()?.substringBeforeLast(':')?.takeIf { it.isNotBlank() }
                        ?: "Профиль ${System.currentTimeMillis()}"
                    activeProfileId = null
                    activeProfileName = defaultName
                    activeProfileProvider = providerLabel
                    val saved = SecureProfileVault.save(this, defaultName, providerLabel, profileText)
                    activeProfileId = saved.id
                    activeProfileName = saved.name
                    activeProfileProvider = saved.provider
                    SecureProfileVault.setActive(this, saved.id)
                    if (wireGuardConfig != null) SecureProfileStore.save(this, profileText)
                } catch (e: Exception) {
                    activeProfileId = null
                    showError("Не удалось сохранить профиль", e.message ?: "Профиль проверен, но не добавлен в зашифрованный пул. Подключение останется доступно в памяти до закрытия приложения.")
                }
            } else if (profile.format != ImportedVpnProfile.Format.OPENVPN) {
                SecureProfileStore.clear(this)
            }
            val endpointText = if (profile.endpointHints.isEmpty()) "Адрес сервера не найден" else profile.endpointHints.joinToString("\n")
            val details = buildString {
                append("Определённый формат: ").append(formatLabel).append("\n")
                if (profile.peerCount > 0) append("Количество узлов: ").append(profile.peerCount).append("\n")
                append("Обнаруженные адреса серверов:\n").append(endpointText).append("\n\n")
                if (profile.hasInlineCredentials) append("Профиль содержит приватные ключи или учётные данные. Не передавайте его посторонним.\n\n")
                if (wireGuardConfig != null && profile.format == ImportedVpnProfile.Format.AMNEZIAWG) append("Профиль AmneziaWG принят встроенным AWG-модулем. Можно подключаться.")
                else if (wireGuardConfig != null) append("Конфигурация WireGuard проверена встроенным модулем. Можно подключаться.")
                else if (profile.format == ImportedVpnProfile.Format.OPENVPN) append("Профиль OpenVPN проверен и сохранён. Подключение выполняется встроенным OpenVPN 3 Core прямо внутри MATRIX.")
                else append("Подходящая конфигурация VPN не загружена.")
                if (wireGuardConfig != null) {
                    append("\n\nПрофиль WireGuard/AmneziaWG добавлен в зашифрованный пул MATRIX (Android Keystore) и выбран активным. Откройте «Мои профили» для управления несколькими конфигурациями. Автоподключение при запуске приложения не выполняется.")
                } else if (profile.format == ImportedVpnProfile.Format.OPENVPN) {
                    append("\n\nOpenVPN-профиль сохранён в зашифрованном хранилище MATRIX. Для VPN Gate используются стандартные учётные данные vpn/vpn; для частных серверов приложение запросит логин и пароль при подключении.")
                } else {
                    append("\n\nПрофиль не сохранён и не может быть подключён встроенным движком MATRIX.")
                }
            }
            status.text = if (wireGuardConfig != null || openVpnProfileText != null) "Профиль $formatLabel готов: $activeProfileName\nVPN-туннель: ${if (tunnelUp || openVpnRunning) "подключён" else "отключён"}"
            else "Профиль проверен: $formatLabel\nVPN-туннель: отключён"
            AlertDialog.Builder(this).setTitle("Профиль VPN обработан").setMessage(details).setPositiveButton("Понятно", null).show()
        } catch (e: Exception) {
            showError("Не удалось обработать профиль VPN", e.message ?: "Не удалось проверить конфигурацию.")
        }
    }
}