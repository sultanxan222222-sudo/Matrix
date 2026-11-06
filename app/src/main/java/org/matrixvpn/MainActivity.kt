package org.matrixvpn

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.VpnService
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.backend.TunnelActionHandler
import org.amnezia.awg.backend.Tunnel.State
import org.amnezia.awg.config.Config
import org.matrixvpn.caravan.CaravanAction
import org.matrixvpn.caravan.CaravanAttemptQueue
import org.matrixvpn.caravan.CaravanFailure
import org.matrixvpn.caravan.CaravanRecoveryPolicy
import org.matrixvpn.caravan.CaravanRouteScout
import org.matrixvpn.caravan.RouteScoutStatus
import org.matrixvpn.caravan.VpnGateDirectoryRepository
import org.matrixvpn.caravan.VpnGateServer
import org.matrixvpn.network.NetworkWatcher
import org.matrixvpn.network.TunnelConnectivityProbe
import org.matrixvpn.profile.ImportedVpnProfile
import org.matrixvpn.profile.VpnProfileInspector
import org.matrixvpn.vpn.OpenVpnGateEngine
import java.io.ByteArrayInputStream

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var networkStatus: TextView
    private lateinit var connectButton: Button
    private lateinit var networkWatcher: NetworkWatcher
    private val pickProfileRequest = 4102
    private val vpnPermissionRequest = 4103
    private val mainHandler = Handler(Looper.getMainLooper())
    private val vpnGateRepository by lazy { VpnGateDirectoryRepository() }
    private var vpnGateServers: List<VpnGateServer> = emptyList()
    private var caravanQueue: CaravanAttemptQueue? = null
    private var preferredCaravanServerId: String? = null
    private var openVpnEngine: OpenVpnGateEngine? = null
    private var openVpnAttemptActive = false
    private var openVpnConnected = false
    private var caravanActive = false
    private var caravanSwitching = false
    private var pendingAutoConnect = false
    private var pendingOpenVpnProfile: String? = null
    private var attemptTimeout: Runnable? = null
    private var activeCaravanServer: VpnGateServer? = null
    private var lastScoutedRouteId: String? = null
    private var caravanScoutRunning = false
    private val tunnelActions = object : TunnelActionHandler {
        override fun runPreUp(scripts: MutableCollection<String>) {}
        override fun runPostUp(scripts: MutableCollection<String>) {}
        override fun runPreDown(scripts: MutableCollection<String>) {}
        override fun runPostDown(scripts: MutableCollection<String>) {}
    }
    private val wireGuardBackend by lazy { GoBackend(applicationContext, tunnelActions) }
    private var wireGuardConfig: Config? = null
    private var tunnelUp = false

    private val matrixTunnel = object : Tunnel {
        override fun getName(): String = "matrix"
        override fun onStateChange(newState: State) {
            runOnUiThread {
                tunnelUp = newState == State.UP
                status.text = if (tunnelUp) {
                    "VPN-туннель: подключён\nПроверка HTTPS-доступа…\nМеханизм восстановления: прототип"
                } else {
                    "VPN-туннель: отключён\nМеханизм восстановления: прототип"
                }
                connectButton.text = if (tunnelUp) "Отключить VPN" else "Подключить VPN"
            }
        }
        override fun isIpv4ResolutionPreferred(): Boolean = true
        override fun isMetered(): Boolean = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val title = TextView(this).apply {
            text = "MATRIX VPN"
            textSize = 30f
            setTextColor(Color.rgb(0, 255, 70))
            gravity = Gravity.CENTER
            setPadding(8, 18, 8, 8)
        }
        val menuSubtitle = TextView(this).apply {
            text = "УПРАВЛЕНИЕ VPN • ВЕРСИЯ 0.6.0"
            textSize = 12f
            setTextColor(Color.rgb(130, 220, 150))
            gravity = Gravity.CENTER
            setPadding(8, 0, 8, 18)
        }
        val connectionHeading = sectionHeading("СОСТОЯНИЕ СОЕДИНЕНИЯ")
        val helpHeading = sectionHeading("СПРАВКА")
        status = TextView(this).apply {
            text = "Сборка 0.6.0\nКараван: готов к разведке\nVPN-туннель: не подключён"
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
        val importButton = Button(this).apply {
            text = "2. Импорт файла с телефона"
            setOnClickListener { chooseVpnProfile() }
        }
        val findWireGuardButton = Button(this).apply {
            text = "WARP — отдельный режим"
            setOnClickListener { fetchFreeWarpProfile() }
        }
        val findVpnButton = Button(this).apply {
            text = "Каталог бесплатных VPN-серверов"
            setOnClickListener { searchVpnServices() }
        }
        val pasteButton = Button(this).apply {
            text = "1. Импорт из буфера телефона"
            setOnClickListener { importFromClipboard() }
        }
        connectButton = Button(this).apply {
            text = "Подключиться автоматически"
            isEnabled = true
            setOnClickListener { connectOrDisconnect() }
        }
        val help = TextView(this).apply {
            text = "MATRIX CARAVAN: автоматический поиск бесплатных маршрутов VPN Gate и подключение встроенным OpenVPN-движком. Также поддерживаются WARP, WireGuard, AmneziaWG и импорт профилей. Бесплатные ретрансляторы принадлежат добровольцам и не подходят для конфиденциальных данных."
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(8, 12, 8, 12)
        }
        val menuContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(18, 10, 18, 24)
            setBackgroundColor(Color.rgb(4, 12, 7))
            addView(title)
            addView(menuSubtitle)
            addView(connectionHeading)
            addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(networkStatus, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(pasteButton, menuButtonParams())
            addView(importButton, menuButtonParams())
            addView(findVpnButton, menuButtonParams())
            addView(findWireGuardButton, menuButtonParams())
            addView(connectButton, menuButtonParams())
            addView(helpHeading)
            addView(help, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val mainScreen = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.rgb(4, 12, 7))
            addView(menuContent, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        listOf(importButton, findWireGuardButton, findVpnButton, pasteButton, connectButton).forEach { button ->
            button.backgroundTintList = ColorStateList.valueOf(Color.rgb(0, 110, 40))
            button.setTextColor(Color.WHITE)
            button.textSize = 14f
            button.minHeight = (48 * resources.displayMetrics.density).toInt()
        }
        help.setTextColor(Color.rgb(170, 220, 180))
        setContentView(MatrixRainView(this))
        window.decorView.postDelayed({
            setContentView(mainScreen)
        }, 1800L)
        networkWatcher = NetworkWatcher(this) { state ->
            runOnUiThread { networkStatus.text = state.summary() }
        }
    }

    override fun onStart() {
        super.onStart()
        networkWatcher.start()
    }

    override fun onStop() {
        networkWatcher.stop()
        super.onStop()
    }

    override fun onDestroy() {
        cancelAttemptTimeout()
        openVpnEngine?.release()
        openVpnEngine = null
        super.onDestroy()
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
        bottomMargin = (6 * resources.displayMetrics.density).toInt()
    }

    /** Fetches a fresh free Cloudflare WARP-compatible WireGuard profile from the public generator API. */
    private fun fetchFreeWarpProfile() {
        status.text = "Получение бесплатного профиля WARP…"
        connectButton.isEnabled = false
        Thread {
            var connection: java.net.HttpURLConnection? = null
            try {
                val url = java.net.URL("https://awgconfig.com/api/warp?mode=legacy&count=1&dns=cloudflare")
                connection = (url.openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 20000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "MATRIX-VPN-Android/0.4.0")
                    instanceFollowRedirects = true
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: throw IllegalStateException("Сервис не вернул ответ (HTTP $code).")
                require(response.length <= 1_000_000) { "Ответ сервиса слишком большой." }
                if (code !in 200..299) throw IllegalStateException("Сервис WARP ответил с кодом HTTP $code.")
                val json = org.json.JSONObject(response)
                if (json.optBoolean("success", true).not()) {
                    throw IllegalStateException(json.optString("error").ifBlank { "Сервис не смог создать профиль WARP." })
                }
                var encoded = json.optString("content")
                if (encoded.isBlank()) {
                    val configs = json.optJSONArray("configs")
                    if (configs != null && configs.length() > 0) encoded = configs.getJSONObject(0).optString("content")
                }
                require(encoded.isNotBlank()) { "Сервис не вернул файл конфигурации." }
                val profileText = try {
                    String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT), Charsets.UTF_8)
                } catch (_: IllegalArgumentException) {
                    encoded
                }
                require(profileText.contains("[Interface]", ignoreCase = true) &&
                    profileText.contains("[Peer]", ignoreCase = true)) {
                    "Сервис вернул данные в неизвестном формате."
                }
                runOnUiThread {
                    processProfileText(profileText)
                    if (wireGuardConfig != null) {
                        status.text = "Бесплатный профиль WARP готов\nVPN-туннель: отключён\nНажмите «Подключить VPN»."
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    connectButton.isEnabled = wireGuardConfig != null
                    status.text = "Не удалось получить профиль WARP"
                    showError(
                        "Автоматическая настройка не удалась",
                        (e.message ?: "Сервис WARP сейчас недоступен.") +
                            "\n\nПроверьте интернет и попробуйте ещё раз. Генератор — сторонний бесплатный сервис, его доступность не гарантируется."
                    )
                }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }


    /**
     * Refresh the public VPN Gate directory. Selecting a relay launches it through
     * MATRIX's embedded OpenVPN engine; no external VPN app is required.
     */
    private fun searchVpnServices() {
        status.text = "Караван: разведка бесплатных серверов VPN Gate…"
        Thread {
            val result = runCatching { vpnGateRepository.fetchServers() }
            runOnUiThread {
                result.onSuccess { servers ->
                    vpnGateServers = servers
                    if (servers.isEmpty()) {
                        status.text = "VPN Gate не вернул пригодных маршрутов"
                        showError("Серверы не найдены", "Каталог ответил, но не вернул корректных OpenVPN-профилей. Попробуйте позже.")
                    } else {
                        status.text = "Разведка завершена: найдено маршрутов — ${servers.size}"
                        val labels = servers.take(40).map { server ->
                            val pingLabel = if (server.ping > 0) "${server.ping} мс" else "ping неизвестен"
                            val speedLabel = if (server.speed > 0) " • ${server.speed / 1_000_000} Мбит/с" else ""
                            "${server.country} • ${server.host}\n$pingLabel • сессий: ${server.sessions}$speedLabel"
                        }.toTypedArray()
                        AlertDialog.Builder(this)
                            .setTitle("Бесплатные маршруты VPN Gate")
                            .setItems(labels) { _, which -> offerVpnGateProfile(servers[which]) }
                            .setNegativeButton("Закрыть", null)
                            .setNeutralButton("О VPN Gate") { _, _ ->
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.vpngate.net/")))
                            }
                            .show()
                    }
                }.onFailure { error ->
                    status.text = "Разведка VPN Gate не удалась"
                    showError("Не удалось загрузить каталог", error.message ?: "VPN Gate сейчас недоступен.")
                }
            }
        }.start()
    }

    private fun offerVpnGateProfile(server: VpnGateServer) {
        AlertDialog.Builder(this)
            .setTitle("Маршрут: ${server.country}")
            .setMessage(
                "Сервер: ${server.host} (${server.ip})\n" +
                    "Задержка: ${if (server.ping > 0) "${server.ping} мс" else "не измерена"}\n\n" +
                    "MATRIX подключится встроенным OpenVPN-движком. При ошибке караван попробует следующий маршрут. " +
                    "Это добровольческий сервер VPN Gate: не передавайте через него пароли, банковские данные и другую чувствительную информацию."
            )
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Подключить и искать обход") { _, _ ->
                startCaravanAutoConnect(server.id)
            }
            .show()
    }

    private fun ensureOpenVpnEngine(): OpenVpnGateEngine {
        val existing = openVpnEngine
        if (existing != null) return existing
        return OpenVpnGateEngine(applicationContext).also { engine ->
            engine.onStateChanged = { state, message -> onOpenVpnState(state, message) }
            openVpnEngine = engine
        }
    }

    private fun startCaravanAutoConnect(preferredId: String? = null) {
        preferredCaravanServerId = preferredId
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            pendingAutoConnect = true
            startActivityForResult(prepareIntent, vpnPermissionRequest)
            return
        }
        loadCaravanDirectoryAndConnect()
    }

    private fun loadCaravanDirectoryAndConnect() {
        caravanActive = true
        caravanSwitching = false
        openVpnConnected = false
        openVpnAttemptActive = false
        cancelAttemptTimeout()
        status.text = "Караван: загружаю маршруты и выбираю лучший…"
        connectButton.text = "Остановить караван"
        connectButton.isEnabled = true

        Thread {
            val result = runCatching { vpnGateRepository.fetchServers() }
            runOnUiThread {
                result.onSuccess { servers ->
                    vpnGateServers = servers
                    if (servers.isEmpty()) {
                        caravanActive = false
                        connectButton.text = "Подключиться автоматически"
                        status.text = "Нет доступных маршрутов"
                        showError("Маршруты не найдены", "VPN Gate не вернул пригодные OpenVPN-профили. Попробуйте позже или импортируйте собственный профиль.")
                    } else {
                        caravanQueue = CaravanAttemptQueue(servers, preferredCaravanServerId)
                        connectNextCaravanServer()
                    }
                }.onFailure { error ->
                    caravanActive = false
                    connectButton.text = "Подключиться автоматически"
                    status.text = "Не удалось получить список маршрутов"
                    showError("Разведка не выполнена", error.message ?: "VPN Gate сейчас недоступен.")
                }
            }
        }.start()
    }

    private fun connectNextCaravanServer() {
        if (!caravanActive || caravanSwitching) return
        val server = caravanQueue?.next()
        if (server == null) {
            caravanActive = false
            openVpnAttemptActive = false
            openVpnConnected = false
            activeCaravanServer = null
            cancelAttemptTimeout()
            connectButton.text = "Повторить автоматический поиск"
            status.text = "Караван исчерпал доступные маршруты"
            showError("Нет рабочего маршрута", "Все найденные серверы были проверены или отклонили соединение. Каталог обновляется при следующей попытке.")
            return
        }

        activeCaravanServer = server
        openVpnAttemptActive = true
        openVpnConnected = false
        status.text = "Караван: подключаюсь к ${server.country} • ${server.host}…"
        connectButton.text = "Остановить караван"
        try {
            ensureOpenVpnEngine().connect(server.decodeOpenVpnProfile())
            scheduleAttemptTimeout(server.id)
        } catch (error: Exception) {
            failoverCaravan(
                CaravanFailure.AUTHENTICATION_REJECTED,
                error.message ?: "Профиль сервера не удалось запустить."
            )
        }
    }

    private fun scheduleAttemptTimeout(serverId: String) {
        cancelAttemptTimeout()
        val timeout = Runnable {
            if (caravanActive && activeCaravanServer?.id == serverId && !openVpnConnected && !caravanSwitching) {
                failoverCaravan(CaravanFailure.CONNECT_TIMEOUT, "сервер не установил соединение вовремя")
            }
        }
        attemptTimeout = timeout
        mainHandler.postDelayed(timeout, 25_000L)
    }

    private fun cancelAttemptTimeout() {
        attemptTimeout?.let(mainHandler::removeCallbacks)
        attemptTimeout = null
    }

    private fun onOpenVpnState(state: String, message: String) {
        runOnUiThread {
            when (state.uppercase()) {
                "CONNECTED" -> {
                    cancelAttemptTimeout()
                    openVpnAttemptActive = true
                    status.text = "OpenVPN-туннель установлен. Проверяю реальный доступ в интернет…"
                    Thread {
                        val probe = TunnelConnectivityProbe.check()
                        runOnUiThread {
                            if (probe.reachable) {
                                openVpnConnected = true
                                status.text = "VPN подключён через ${activeCaravanServer?.country ?: "OpenVPN-профиль"}\n${probe.message}\nКараван проверяет запасные маршруты."
                                connectButton.text = "Отключить VPN"
                                if (caravanActive) scoutAlternativeRoutesInBackground()
                            } else if (caravanActive) {
                                failoverCaravan(CaravanFailure.EGRESS_CHECK_FAILED, probe.message)
                            } else {
                                openVpnConnected = true
                                status.text = "OpenVPN-туннель запущен, но интернет-проверка не пройдена.\n${probe.message}"
                                connectButton.text = "Отключить VPN"
                            }
                        }
                    }.start()
                }
                "AUTH_FAILED" -> {
                    if (caravanActive) {
                        failoverCaravan(CaravanFailure.AUTHENTICATION_REJECTED, "сервер отклонил аутентификацию")
                    } else {
                        status.text = "OpenVPN: сервер отклонил аутентификацию"
                        connectButton.text = "Подключиться автоматически"
                    }
                }
                "EXITING" -> {
                    if (caravanActive && !caravanSwitching) {
                        failoverCaravan(CaravanFailure.TUNNEL_DROPPED, "сервер завершил соединение")
                    } else if (!caravanActive && !caravanSwitching) {
                        openVpnConnected = false
                        openVpnAttemptActive = false
                        connectButton.text = "Подключиться автоматически"
                        status.text = "VPN-туннель отключён"
                    }
                }
                "RECONNECTING" -> {
                    status.text = "Караван: основной маршрут нестабилен, проверяю восстановление…"
                    val serverId = activeCaravanServer?.id
                    if (caravanActive && serverId != null) scheduleAttemptTimeout(serverId)
                }
                "CONNECTING", "WAIT", "RESOLVE", "TCP_CONNECT" -> {
                    if (caravanActive) status.text = "Караван: устанавливаю защищённый маршрут…"
                }
            }
        }
    }

    /**
     * Tests alternate TCP endpoints over the physical network while the
     * active tunnel remains connected. UDP profiles are never classified
     * as blocked by this TCP-only preflight.
     */
    private fun scoutAlternativeRoutesInBackground() {
        val currentId = activeCaravanServer?.id ?: return
        if (caravanScoutRunning || lastScoutedRouteId == currentId) return
        val candidates = vpnGateServers.filterNot { it.id == currentId }.take(30)
        if (candidates.isEmpty()) return

        caravanScoutRunning = true
        lastScoutedRouteId = currentId
        Thread {
            val results = runCatching {
                CaravanRouteScout(applicationContext).scout(candidates)
            }.getOrElse { emptyList() }

            runOnUiThread {
                caravanScoutRunning = false
                if (!caravanActive || activeCaravanServer?.id != currentId) return@runOnUiThread

                val reachable = results
                    .filter { it.status == RouteScoutStatus.TCP_REACHABLE }
                    .sortedBy { it.latencyMillis ?: Long.MAX_VALUE }
                    .map { it.serverId }
                caravanQueue?.prioritize(reachable)

                val udpCount = results.count { it.status == RouteScoutStatus.UDP_OR_UNSPECIFIED }
                val reachableCount = reachable.size
                val message = when {
                    results.isEmpty() -> "Разведка запасных маршрутов недоступна."
                    reachableCount > 0 -> "Разведка: TCP доступны $reachableCount; UDP/непроверенных: $udpCount."
                    else -> "TCP-маршруты не подтверждены; UDP и непроверенные узлы остаются в очереди."
                }
                status.text = "VPN подключён через ${activeCaravanServer?.country ?: "OpenVPN"}\\n$message\\nОсновной туннель не переключался."
            }
        }.start()
    }

    private fun failoverCaravan(failure: CaravanFailure, reason: String) {
        if (!caravanActive || caravanSwitching) return
        val hasAlternative = caravanQueue?.hasNext() == true
        when (CaravanRecoveryPolicy.nextAction(failure, hasAlternative)) {
            CaravanAction.TRY_NEXT_ROUTE -> {
                caravanSwitching = true
                cancelAttemptTimeout()
                openVpnConnected = false
                status.text = "Обнаружено препятствие: $reason. Караван ищет обходной маршрут…"
                runCatching { openVpnEngine?.disconnect() }
                mainHandler.postDelayed({
                    caravanSwitching = false
                    openVpnAttemptActive = false
                    connectNextCaravanServer()
                }, 1_200L)
            }
            CaravanAction.WAIT_FOR_NETWORK -> {
                cancelAttemptTimeout()
                status.text = "Сеть недоступна. Караван ожидает восстановления соединения."
            }
            CaravanAction.STOP -> {
                caravanActive = false
                cancelAttemptTimeout()
                connectButton.text = "Подключиться автоматически"
                status.text = "Караван остановлен: $reason"
            }
        }
    }

    private fun connectImportedOpenVpnProfile(profileText: String) {
        caravanActive = false
        caravanSwitching = false
        cancelAttemptTimeout()
        try {
            ensureOpenVpnEngine().connect(profileText)
            openVpnAttemptActive = true
            openVpnConnected = false
            connectButton.text = "Отключить VPN"
            status.text = "Запускаю встроенный OpenVPN-движок…"
        } catch (error: Exception) {
            openVpnAttemptActive = false
            connectButton.text = "Подключиться автоматически"
            showError("Не удалось запустить OpenVPN", error.message ?: "Проверьте профиль.")
        }
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
        if (caravanActive || openVpnAttemptActive || openVpnConnected) {
            caravanActive = false
            caravanSwitching = false
            cancelAttemptTimeout()
            runCatching { openVpnEngine?.disconnect() }
            openVpnAttemptActive = false
            openVpnConnected = false
            connectButton.text = "Подключиться автоматически"
            status.text = "VPN отключается…"
            return
        }

        if (tunnelUp) {
            connectButton.isEnabled = false
            Thread {
                try {
                    wireGuardBackend.setState(matrixTunnel, State.DOWN, null)
                    runOnUiThread {
                        status.text = "VPN-туннель: отключён"
                        connectButton.isEnabled = true
                        connectButton.text = "Подключиться автоматически"
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        connectButton.isEnabled = true
                        showError("Не удалось отключить VPN", e.message ?: "Не удалось остановить туннель.")
                    }
                }
            }.start()
            return
        }

        val importedOpenVpn = pendingOpenVpnProfile
        if (importedOpenVpn != null) {
            val prepareIntent = VpnService.prepare(this)
            if (prepareIntent != null) startActivityForResult(prepareIntent, vpnPermissionRequest)
            else {
                pendingOpenVpnProfile = null
                connectImportedOpenVpnProfile(importedOpenVpn)
            }
            return
        }

        if (wireGuardConfig != null) {
            val prepareIntent = VpnService.prepare(this)
            if (prepareIntent != null) startActivityForResult(prepareIntent, vpnPermissionRequest)
            else startWireGuardTunnel()
            return
        }

        startCaravanAutoConnect()
    }

    private fun startWireGuardTunnel() {
        val config = wireGuardConfig ?: return
        status.text = "Запуск VPN-туннеля…"
        connectButton.isEnabled = false
        Thread {
            try {
                wireGuardBackend.setState(matrixTunnel, State.UP, config)
                val probe = TunnelConnectivityProbe.check()
                runOnUiThread {
                    status.text = if (probe.reachable) {
                        "VPN-туннель запущен\n${probe.message}\nПодтверждение рукопожатия с сервером отдельно не проверено."
                    } else {
                        "VPN-туннель запущен, но проверка интернета не пройдена.\n${probe.message}\nСервер может быть недоступен или профиль может не направлять интернет-трафик через туннель."
                    }
                    connectButton.isEnabled = true
                    connectButton.text = if (tunnelUp) "Отключить VPN" else "Подключить VPN"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    connectButton.isEnabled = true
                    showError("Ошибка подключения", e.message ?: "Не удалось запустить WireGuard. Проверьте профиль и разрешение Android на VPN.")
                }
            }
        }.start()
    }

    private fun showError(title: String, message: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("Понятно", null).show()
    }

    @Deprecated("Используется системный выбор файлов для совместимости с разными версиями Android.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == vpnPermissionRequest) {
            if (resultCode == RESULT_OK) {
                when {
                    pendingAutoConnect -> {
                        pendingAutoConnect = false
                        loadCaravanDirectoryAndConnect()
                    }
                    pendingOpenVpnProfile != null -> {
                        val profile = pendingOpenVpnProfile
                        pendingOpenVpnProfile = null
                        if (profile != null) connectImportedOpenVpnProfile(profile)
                    }
                    else -> startWireGuardTunnel()
                }
            } else {
                pendingAutoConnect = false
                showError("Разрешение VPN не предоставлено", "Android не разрешил приложению MATRIX создать VPN-туннель.")
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

    private fun processProfileText(profileText: String) {
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
            pendingOpenVpnProfile = null
            if (profile.format == ImportedVpnProfile.Format.WIREGUARD || profile.format == ImportedVpnProfile.Format.AMNEZIAWG) {
                try {
                    wireGuardConfig = Config.parse(ByteArrayInputStream(profileText.toByteArray(Charsets.UTF_8)))
                } catch (e: Exception) {
                    showError("Неверный профиль WireGuard", e.message ?: "Проверка WireGuard отклонила эту конфигурацию.")
                }
            } else if (profile.format == ImportedVpnProfile.Format.OPENVPN) {
                pendingOpenVpnProfile = profileText
            }
            connectButton.isEnabled = true
            val endpointText = if (profile.endpointHints.isEmpty()) "Адрес сервера не найден" else profile.endpointHints.joinToString("\n")
            val details = buildString {
                append("Определённый формат: ").append(formatLabel).append("\n")
                if (profile.peerCount > 0) append("Количество узлов: ").append(profile.peerCount).append("\n")
                append("Обнаруженные адреса серверов:\n").append(endpointText).append("\n\n")
                if (profile.hasInlineCredentials) append("Профиль содержит приватные ключи или учётные данные. Не передавайте его посторонним.\n\n")
                if (wireGuardConfig != null && profile.format == ImportedVpnProfile.Format.AMNEZIAWG) append("Профиль AmneziaWG принят встроенным модулем AWG. Можно подключаться.")
                else if (wireGuardConfig != null) append("Конфигурация WireGuard проверена встроенным движком. Можно подключаться.")
                else if (pendingOpenVpnProfile != null) append("Профиль OpenVPN принят. MATRIX подключит его собственным встроенным движком, без стороннего приложения.")
                else append("Не удалось подготовить подходящую конфигурацию VPN.")
                append("\n\nВ этой версии профиль хранится только в памяти и не сохраняется приложением MATRIX.")
            }
            status.text = if (wireGuardConfig != null || pendingOpenVpnProfile != null) "Профиль $formatLabel готов\nVPN-туннель: отключён"
            else "Профиль проверен: $formatLabel\nVPN-туннель: отключён"
            AlertDialog.Builder(this).setTitle("Профиль VPN обработан").setMessage(details).setPositiveButton("Понятно", null).show()
        } catch (e: Exception) {
            showError("Не удалось обработать профиль VPN", e.message ?: "Не удалось проверить конфигурацию.")
        }
    }
}