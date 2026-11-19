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
import org.matrixvpn.network.NetworkWatcher
import org.matrixvpn.network.TunnelConnectivityProbe
import org.matrixvpn.ai.GarlicMatrixPolicy
import org.matrixvpn.ai.GarlicModelPackInstaller
import org.matrixvpn.ai.GarlicPolicyOutputGate
import org.matrixvpn.ai.AutopilotSafetyLatch
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import org.matrixvpn.profile.ImportedVpnProfile
import org.matrixvpn.profile.VpnProfileInspector
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var networkStatus: TextView
    private lateinit var connectButton: Button
    private lateinit var autoVpnButton: Button
    private lateinit var emergencyStopButton: Button
    private lateinit var networkWatcher: NetworkWatcher
    private lateinit var aiStatus: TextView
    private data class VpnCandidate(val id: String, val label: String, val config: Config)
    private data class CandidateHealth(
        val reachable: Boolean,
        val latencyMillis: Long?,
        val jitterMillis: Long?,
        val failurePercent: Int,
        val sampleCount: Int,
        val successfulSamples: Int,
        val httpCode: Int?,
        val checkedAtMillis: Long
    )
    private val vpnCandidates = ConcurrentHashMap<String, VpnCandidate>()
    private val candidateHealth = ConcurrentHashMap<String, CandidateHealth>()
    private val autopilotLatch = AutopilotSafetyLatch()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var autoHealthCheck: Runnable? = null
    private val manualConnectGeneration = AtomicLong(0L)
    private val vpnExecutor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "matrix-vpn-autopilot").apply { isDaemon = true }
    }
    private val probeExecutor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "matrix-vpn-health-probe").apply { isDaemon = true }
    }
    @Volatile private var activeConfig: Config? = null
    @Volatile private var activeCandidateId: String? = null
    @Volatile private var garlicReady = false
    @Volatile private var pendingAutoConnect = false
    @Volatile private var pendingAutoPermissionPrompt = false
    @Volatile private var pendingManualPermissionPrompt = false
    @Volatile private var autoConnectToken = 0L
    private val pickProfileRequest = 4102
    private val modelPackRequest = 4110
    private val modelPackInstaller by lazy { GarlicModelPackInstaller(applicationContext) }
    private var garlicPolicy: GarlicMatrixPolicy? = null
    private val vpnPermissionRequest = 4103
    private val tunnelActions = object : TunnelActionHandler {
        override fun runPreUp(scripts: MutableCollection<String>) {}
        override fun runPostUp(scripts: MutableCollection<String>) {}
        override fun runPreDown(scripts: MutableCollection<String>) {}
        override fun runPostDown(scripts: MutableCollection<String>) {}
    }
    private val wireGuardBackend by lazy { GoBackend(applicationContext, tunnelActions) }
    private var wireGuardConfig: Config? = null
    @Volatile private var tunnelUp = false

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
            text = "УПРАВЛЕНИЕ VPN • ВЕРСИЯ 0.5.1"
            textSize = 12f
            setTextColor(Color.rgb(130, 220, 150))
            gravity = Gravity.CENTER
            setPadding(8, 0, 8, 18)
        }
        val connectionHeading = sectionHeading("СОСТОЯНИЕ СОЕДИНЕНИЯ")
        val helpHeading = sectionHeading("СПРАВКА")
        status = TextView(this).apply {
            text = "Сборка 0.5.1\nVPN-туннель: не подключён\nАвтоматическое восстановление: прототип"
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
        aiStatus = TextView(this).apply {
            text = "MATRIX AI: модель не установлена"
            textSize = 13f
            setTextColor(Color.rgb(130, 220, 150))
            gravity = Gravity.CENTER
            setPadding(8, 8, 8, 12)
        }
        val installAiButton = Button(this).apply {
            text = "Установить MATRIX AI (Garlic)"
            setOnClickListener { chooseGarlicModelPack() }
        }
        val importButton = Button(this).apply {
            text = "2. Импорт файла с телефона"
            setOnClickListener { chooseVpnProfile() }
        }
        val findWireGuardButton = Button(this).apply {
            text = "Получить до 3 вариантов Cloudflare WARP"
            setOnClickListener { fetchFreeWarpProfile() }
        }
        autoVpnButton = Button(this).apply {
            text = "MATRIX AI — выбрать и подключить VPN"
            setOnClickListener { startAiAutopilot() }
        }
        emergencyStopButton = Button(this).apply {
            text = "АВАРИЙНО ОТКЛЮЧИТЬ VPN"
            setOnClickListener { emergencyStopVpn() }
        }
        val findVpnButton = Button(this).apply {
            text = "3. Найти бесплатные VPN-серверы"
            setOnClickListener { searchVpnServices() }
        }
        val pasteButton = Button(this).apply {
            text = "1. Импорт из буфера телефона"
            setOnClickListener { importFromClipboard() }
        }
        connectButton = Button(this).apply {
            text = "Подключить VPN"
            isEnabled = false
            setOnClickListener { connectOrDisconnect() }
        }
        val help = TextView(this).apply {
            text = "MATRIX AI может самостоятельно выбрать, проверить и подключить один из разрешённых профилей, а при сбое перейти к другому. Кнопка аварийного отключения останавливает туннель и блокирует повторное подключение до нового нажатия запуска. Генератор WARP создаёт до трёх отдельных профилей одного провайдера Cloudflare; это не три независимые VPN-компании. VPN Gate пока предоставляет OpenVPN-профили для внешнего клиента — встроенному MATRIX ещё нужен OpenVPN-модуль."
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
            addView(aiStatus, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(installAiButton, menuButtonParams())
            addView(autoVpnButton, menuButtonParams())
            addView(emergencyStopButton, menuButtonParams())
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
        listOf(importButton, findWireGuardButton, findVpnButton, pasteButton, connectButton, installAiButton, autoVpnButton).forEach { button ->
            button.backgroundTintList = ColorStateList.valueOf(Color.rgb(0, 110, 40))
            button.setTextColor(Color.WHITE)
            button.textSize = 14f
            button.minHeight = (48 * resources.displayMetrics.density).toInt()
        }
        emergencyStopButton.backgroundTintList = ColorStateList.valueOf(Color.rgb(180, 24, 35))
        emergencyStopButton.setTextColor(Color.WHITE)
        emergencyStopButton.textSize = 14f
        emergencyStopButton.minHeight = (54 * resources.displayMetrics.density).toInt()
        help.setTextColor(Color.rgb(170, 220, 180))
        setContentView(MatrixRainView(this))
        window.decorView.postDelayed({
            setContentView(mainScreen)
        }, 1800L)
        networkWatcher = NetworkWatcher(this) { state ->
            runOnUiThread { networkStatus.text = state.summary() }
        }

        // Reuse a previously imported private model pack after an app restart.
        val installedModel = File(filesDir, "models/garlic")
        val hasInstalledPack = File(installedModel, "model.idx").isFile &&
            File(installedModel, "tokenizer.bin").isFile &&
            File(installedModel, "qwen3.5-0.8b-q4i.bin").isFile
        if (hasInstalledPack) {
            aiStatus.text = "MATRIX AI: загружена модель, запуск Garlic…"
            window.decorView.postDelayed({
                if (!isFinishing && !isDestroyed && garlicPolicy == null) {
                    initializeGarlicPolicy(installedModel)
                }
            }, 2_200L)
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
        autoHealthCheck?.let { mainHandler.removeCallbacks(it) }
        autoHealthCheck = null
        pendingAutoConnect = false
        pendingAutoPermissionPrompt = false
        pendingManualPermissionPrompt = false
        autoConnectToken = autopilotLatch.emergencyStop()
        manualConnectGeneration.incrementAndGet()
        garlicPolicy?.close()
        garlicPolicy = null
        vpnExecutor.shutdown()
        probeExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun chooseGarlicModelPack() {
        // OEM document providers sometimes mislabel downloaded ZIP archives.
        // Use the unrestricted SAF picker; the installer validates the archive contents.
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivityForResult(intent, modelPackRequest)
        } catch (_: android.content.ActivityNotFoundException) {
            val fallback = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivityForResult(Intent.createChooser(fallback, "Выберите архив модели MATRIX AI"), modelPackRequest)
            } catch (e: Exception) {
                showError("Выбор архива недоступен", e.message ?: "Android не смог открыть выбор файлов.")
            }
        } catch (e: Exception) {
            showError("Не удалось открыть выбор файлов", e.message ?: "Попробуйте ещё раз.")
        }
    }

    private fun installGarlicModelPack(uri: Uri) {
        aiStatus.text = "MATRIX AI: проверка архива и установка модели…"
        modelPackInstaller.install(uri).whenComplete { result, error ->
            runOnUiThread {
                if (error != null) {
                    aiStatus.text = "MATRIX AI: установка не завершена"
                    showError("Не удалось установить модель", error.cause?.message ?: error.message ?: "Проверка модели не пройдена.")
                } else {
                    aiStatus.text = "MATRIX AI: ${result.modelName}, SHA-256 проверен"
                    initializeGarlicPolicy(File(result.directory))
                }
            }
        }
    }

    private fun initializeGarlicPolicy(modelDirectory: File) {
        garlicPolicy?.close()
        val policy = GarlicMatrixPolicy(applicationContext, modelDirectory)
        garlicPolicy = policy
        aiStatus.text = "MATRIX AI: запуск Garlic на GPU…"
        policy.initialize().whenComplete { initialized, error ->
            runOnUiThread {
                if (error != null) {
                    garlicReady = false
                    aiStatus.text = "MATRIX AI: движок пока не готов"
                    showError(
                        "Garlic не запустился",
                        error.cause?.message ?: error.message ?: "Не удалось запустить внутренний GPU-модуль."
                    )
                } else {
                    garlicReady = true
                    aiStatus.text = "MATRIX AI: Garlic готов • ${initialized.deviceAbi}"
                }
            }
        }
    }

    /**
     * Starts host-authorized automatic VPN selection. The user explicitly enables
     * this mode; the separate emergency-stop control always has priority.
     */
    private fun startAiAutopilot() {
        if (!garlicReady || garlicPolicy == null) {
            showError(
                "MATRIX AI ещё не готов",
                "Сначала установите модель Garlic и дождитесь статуса «Garlic готов». Без работающей модели автоподключение не запускается."
            )
            return
        }
        manualConnectGeneration.incrementAndGet()
        val token = autopilotLatch.enable()
        autoConnectToken = token
        autoVpnButton.isEnabled = false
        autoVpnButton.text = "MATRIX AI: подбирает варианты…"
        aiStatus.text = "MATRIX AI: автоматический подбор разрешён"
        emergencyStopButton.text = "АВАРИЙНО ОТКЛЮЧИТЬ VPN"
        if (vpnCandidates.isEmpty()) {
            fetchWarpCandidates(autoStart = true, token = token)
        } else {
            requestAutoVpnPermission(token)
        }
    }

    private fun requestAutoVpnPermission(token: Long) {
        if (!autopilotLatch.isActive(token)) return
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent != null) {
            pendingAutoConnect = true
            pendingAutoPermissionPrompt = true
            pendingManualPermissionPrompt = false
            autoConnectToken = token
            try {
                startActivityForResult(permissionIntent, vpnPermissionRequest)
            } catch (error: Exception) {
                pendingAutoConnect = false
                pendingAutoPermissionPrompt = false
                autopilotLatch.emergencyStop()
                autoVpnButton.isEnabled = true
                showError("Не удалось запросить разрешение VPN", error.message ?: "Android не открыл системное окно VPN.")
            }
        } else {
            pendingAutoConnect = false
            pendingAutoPermissionPrompt = false
            requestAutomaticCandidateSelection(emptySet(), null, token)
        }
    }

    private fun requestAutomaticCandidateSelection(
        excludedIds: Set<String>,
        lastProbe: org.matrixvpn.network.TunnelProbeResult?,
        token: Long
    ) {
        if (!autopilotLatch.isActive(token)) return
        val policy = garlicPolicy
        if (!garlicReady || policy == null) {
            runOnUiThread { aiStatus.text = "MATRIX AI: модель недоступна — автоподбор остановлен" }
            autopilotLatch.emergencyStop()
            return
        }
        val currentId = activeCandidateId
        val remaining = vpnCandidates.values.filter {
            it.id !in excludedIds && (!tunnelUp || it.id != currentId)
        }
        if (remaining.isEmpty()) {
            val preservingActive = tunnelUp && currentId != null && currentId !in excludedIds
            runOnUiThread {
                aiStatus.text = if (preservingActive) {
                    "MATRIX AI: альтернатив нет; текущий VPN оставлен"
                } else {
                    "MATRIX AI: все разрешённые варианты исчерпаны"
                }
                status.text = if (preservingActive) {
                    "Текущий VPN остаётся подключённым. Других проверенных профилей нет."
                } else if (tunnelUp) {
                    "Альтернативный маршрут не найден. Текущий VPN оставлен включённым для защиты трафика."
                } else {
                    "VPN не подключён: доступных профилей для выбора нет."
                }
                autoVpnButton.isEnabled = true
                autoVpnButton.text = "MATRIX AI — повторить подбор"
            }
            autopilotLatch.emergencyStop()
            return
        }

        val allowedIds = remaining.map { it.id }.toSet()
        val candidateJson = JSONArray().apply { remaining.forEach { put(it.id) } }
        val excludedJson = JSONArray().apply { excludedIds.forEach { put(it) } }
        val now = System.currentTimeMillis()
        val healthJson = JSONArray().apply {
            remaining.forEach { candidate ->
                val health = candidateHealth[candidate.id] ?: return@forEach
                put(
                    JSONObject()
                        .put("profile_id", candidate.id)
                        .put("reachable", health.reachable)
                        .put("https_median_ms", health.latencyMillis ?: JSONObject.NULL)
                        .put("https_variation_ms", health.jitterMillis ?: JSONObject.NULL)
                        .put("probe_failure_percent", health.failurePercent)
                        .put("sample_count", health.sampleCount)
                        .put("successful_samples", health.successfulSamples)
                        .put("http_status", health.httpCode ?: JSONObject.NULL)
                        .put("age_seconds", ((now - health.checkedAtMillis).coerceAtLeast(0L) / 1000L))
                )
            }
        }
        val descriptorJson = JSONArray().apply {
            remaining.forEach { candidate ->
                put(
                    JSONObject()
                        .put("profile_id", candidate.id)
                        .put("label", candidate.label.take(160))
                )
            }
        }
        val observation = JSONObject()
            .put("policy_scope", "vpn_candidate_selection")
            .put("automatic_connection_authorized", true)
            .put("selection_objective", "Prefer recently verified reachable candidates with lower RTT; avoid previously failed candidates; preserve the current route when healthy.")
            .put("tunnel_active", tunnelUp)
            .put("current_profile_id", activeCandidateId ?: JSONObject.NULL)
            .put("connectivity_known", lastProbe != null)
            .put("external_https_reachable", lastProbe?.reachable ?: false)
            .put("external_https_median_ms", lastProbe?.latencyMillis ?: JSONObject.NULL)
            .put("external_https_variation_ms", lastProbe?.jitterMillis ?: JSONObject.NULL)
            .put("external_probe_failure_percent", lastProbe?.probeFailurePercent ?: JSONObject.NULL)
            .put("external_probe_samples", lastProbe?.sampleCount ?: JSONObject.NULL)
            .put("external_probe_successes", lastProbe?.successfulSamples ?: JSONObject.NULL)
            .put("external_http_status", lastProbe?.httpCode ?: JSONObject.NULL)
            .put("candidate_count", remaining.size)
            .put("candidate_profile_ids", candidateJson)
            .put("candidate_descriptors", descriptorJson)
            .put("candidate_health", healthJson)
            .put("failed_profile_ids", excludedJson)
            .toString()

        runOnUiThread { aiStatus.text = "MATRIX AI: Garlic оценивает ${remaining.size} разрешённых вариантов…" }
        policy.assess(observation).whenComplete { raw, error ->
            if (!autopilotLatch.isActive(token)) return@whenComplete
            if (error != null) {
                runOnUiThread {
                    aiStatus.text = "MATRIX AI: не удалось оценить VPN-кандидатов"
                    autoVpnButton.isEnabled = true
                    autoVpnButton.text = "MATRIX AI — повторить подбор"
                }
                autopilotLatch.emergencyStop()
                return@whenComplete
            }
            try {
                val advice = GarlicPolicyOutputGate.validateForTunnelState(
                    raw = raw,
                    allowedCandidateIds = allowedIds,
                    tunnelActive = tunnelUp,
                    currentProfileId = activeCandidateId
                )
                if (advice.confidence < 0.60) {
                    throw IllegalArgumentException("Garlic не набрал достаточную уверенность для подключения.")
                }
                when (advice.action) {
                    GarlicPolicyOutputGate.Action.KEEP_CURRENT -> {
                        if (!tunnelUp) {
                            throw IllegalArgumentException("Garlic предложил оставить маршрут, хотя активного туннеля нет.")
                        }
                        runOnUiThread {
                            aiStatus.text = "MATRIX AI: текущий VPN оставлен • ${advice.reasonCode}"
                            autoVpnButton.isEnabled = true
                            autoVpnButton.text = "MATRIX AI — автоподбор включён"
                        }
                        scheduleAutoHealthCheck(token)
                    }
                    GarlicPolicyOutputGate.Action.CONNECT_PROFILE,
                    GarlicPolicyOutputGate.Action.PROBE_PROFILE,
                    GarlicPolicyOutputGate.Action.PROPOSE_FAILOVER -> {
                        val id = advice.profileId ?: throw IllegalArgumentException("Garlic не указал VPN-кандидата.")
                        val candidate = remaining.firstOrNull { it.id == id }
                            ?: throw IllegalArgumentException("MATRIX не нашёл разрешённый профиль-кандидат.")
                        activateAutomaticCandidate(candidate, excludedIds, token)
                    }
                }
            } catch (validationError: Exception) {
                runOnUiThread {
                    aiStatus.text = "MATRIX AI: решение не прошло проверку безопасности"
                    autoVpnButton.isEnabled = true
                    autoVpnButton.text = "MATRIX AI — повторить подбор"
                }
                autopilotLatch.emergencyStop()
            }
        }
    }

    private fun activateAutomaticCandidate(
        candidate: VpnCandidate,
        excludedIds: Set<String>,
        token: Long
    ) {
        vpnExecutor.execute connectTask@{
            if (!autopilotLatch.isActive(token)) return@connectTask
            val previousConfig = activeConfig
            val previousId = activeCandidateId
            try {
                if (!autopilotLatch.isActive(token)) return@connectTask

                // GoBackend handles UP->UP reconfiguration itself. It tears down the
                // current interface, starts the candidate, and restores the old config
                // if candidate activation throws. Avoid a separate manual DOWN/UP pair.
                wireGuardBackend.setState(matrixTunnel, State.UP, candidate.config)
                activeConfig = candidate.config
                activeCandidateId = candidate.id
                wireGuardConfig = candidate.config
                tunnelUp = true

                if (!autopilotLatch.isActive(token)) {
                    wireGuardBackend.setState(matrixTunnel, State.DOWN, null)
                    tunnelUp = false
                    activeConfig = null
                    activeCandidateId = null
                    return@connectTask
                }

                // The HTTPS test is isolated from the serialized tunnel executor so the
                // user's emergency-stop action never waits behind a network timeout.
                probeExecutor.execute probeTask@{
                    val probe = TunnelConnectivityProbe.check()
                    if (!autopilotLatch.isActive(token)) return@probeTask

                    candidateHealth[candidate.id] = CandidateHealth(
                        reachable = probe.reachable,
                        latencyMillis = probe.latencyMillis,
                        jitterMillis = probe.jitterMillis,
                        failurePercent = probe.probeFailurePercent,
                        sampleCount = probe.sampleCount,
                        successfulSamples = probe.successfulSamples,
                        httpCode = probe.httpCode,
                        checkedAtMillis = System.currentTimeMillis()
                    )
                    if (probe.reachable) {
                        runOnUiThread {
                            if (!autopilotLatch.isActive(token)) return@runOnUiThread
                            status.text = "VPN подключён автоматически\n${candidate.label}\n${probe.message}"
                            aiStatus.text = "MATRIX AI: выбран маршрут • ${candidate.id}"
                            connectButton.isEnabled = true
                            connectButton.text = "Отключить VPN"
                            autoVpnButton.isEnabled = true
                            autoVpnButton.text = "MATRIX AI — автоподбор включён"
                        }
                        scheduleAutoHealthCheck(token)
                    } else {
                        val failed = excludedIds + candidate.id
                        vpnExecutor.execute failoverTask@{
                            if (!autopilotLatch.isActive(token)) return@failoverTask
                            try {
                                val previousRouteWasAlreadyFailed =
                                    previousId != null && previousId in excludedIds
                                val restoredPrevious = previousConfig != null &&
                                    !previousRouteWasAlreadyFailed && autopilotLatch.isActive(token)

                                if (restoredPrevious) {
                                    // Reconfigure directly to the last known-good profile;
                                    // GoBackend performs its own rollback if this fails.
                                    wireGuardBackend.setState(matrixTunnel, State.UP, previousConfig)
                                    activeConfig = previousConfig
                                    activeCandidateId = previousId
                                    tunnelUp = true
                                    if (autopilotLatch.isActive(token)) {
                                        runOnUiThread {
                                            aiStatus.text = "MATRIX AI: тест неудачен; прежний VPN восстановлен"
                                        }
                                        scheduleAutoHealthCheck(token)
                                    }
                                } else {
                                    wireGuardBackend.setState(matrixTunnel, State.DOWN, null)
                                    tunnelUp = false
                                    activeConfig = null
                                    activeCandidateId = null
                                    if (autopilotLatch.isActive(token)) {
                                        runOnUiThread {
                                            aiStatus.text = "MATRIX AI: ${candidate.id} не прошёл проверку; выбирается следующий"
                                        }
                                        requestAutomaticCandidateSelection(failed, probe, token)
                                    }
                                }
                            } catch (restoreError: Exception) {
                                // If restoring the last known-good profile fails, leave no
                                // ambiguous tunnel state and continue only while the latch allows it.
                                try {
                                    wireGuardBackend.setState(matrixTunnel, State.DOWN, null)
                                } catch (_: Exception) {
                                }
                                tunnelUp = false
                                activeConfig = null
                                activeCandidateId = null
                                if (autopilotLatch.isActive(token)) {
                                    requestAutomaticCandidateSelection(failed, probe, token)
                                }
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                // GoBackend attempts to restore its previous tunnel when an UP->UP
                // configuration change fails. Preserve that route when it was not marked bad.
                val previousRouteWasAlreadyFailed =
                    previousId != null && previousId in excludedIds
                val restoredPrevious = previousConfig != null &&
                    tunnelUp && !previousRouteWasAlreadyFailed && autopilotLatch.isActive(token)

                if (restoredPrevious) {
                    activeConfig = previousConfig
                    activeCandidateId = previousId
                    runOnUiThread {
                        aiStatus.text = "MATRIX AI: новый вариант не запустился; прежний VPN сохранён"
                    }
                    scheduleAutoHealthCheck(token)
                } else {
                    try {
                        // Also send DOWN when our local flag is false: setState(UP) may have
                        // failed after partially opening the backend interface.
                        wireGuardBackend.setState(matrixTunnel, State.DOWN, null)
                    } catch (_: Exception) {
                    }
                    tunnelUp = false
                    activeConfig = null
                    activeCandidateId = null
                    val failed = excludedIds + candidate.id
                    if (autopilotLatch.isActive(token)) {
                        runOnUiThread {
                            aiStatus.text = "MATRIX AI: кандидат не подключился; проверяется следующий"
                        }
                        requestAutomaticCandidateSelection(failed, null, token)
                    }
                }
            }
        }
    }

    /**
     * Re-check the active route every two minutes while the user has enabled autopilot.
     * When a route fails, Garlic receives the failed opaque candidate ID and can choose
     * another entry from MATRIX's currently validated candidate pool.
     */
    private fun scheduleAutoHealthCheck(token: Long) {
        autoHealthCheck?.let { mainHandler.removeCallbacks(it) }
        val task = object : Runnable {
            override fun run() {
                if (!autopilotLatch.isActive(token) || !tunnelUp) return
                probeExecutor.execute healthProbeTask@{
                    if (!autopilotLatch.isActive(token) || !tunnelUp) return@healthProbeTask
                    val probe = TunnelConnectivityProbe.check()
                    if (!autopilotLatch.isActive(token)) return@healthProbeTask
                    val currentId = activeCandidateId
                    if (currentId != null) {
                        candidateHealth[currentId] = CandidateHealth(
                            reachable = probe.reachable,
                            latencyMillis = probe.latencyMillis,
                        jitterMillis = probe.jitterMillis,
                        failurePercent = probe.probeFailurePercent,
                        sampleCount = probe.sampleCount,
                        successfulSamples = probe.successfulSamples,
                            httpCode = probe.httpCode,
                            checkedAtMillis = System.currentTimeMillis()
                        )
                    }
                    val failed = if (!probe.reachable && currentId != null) {
                        setOf(currentId)
                    } else {
                        emptySet()
                    }
                    requestAutomaticCandidateSelection(failed, probe, token)
                }
                mainHandler.postDelayed(this, 120_000L)
            }
        }
        autoHealthCheck = task
        mainHandler.postDelayed(task, 120_000L)
    }

    /**
     * Human-owned kill control. It invalidates all pending Garlic decisions first,
     * then serially stops the tunnel after any in-flight backend call completes.
     */
    private fun emergencyStopVpn() {
        autoHealthCheck?.let { mainHandler.removeCallbacks(it) }
        autoHealthCheck = null
        manualConnectGeneration.incrementAndGet()
        pendingAutoConnect = false
        pendingManualPermissionPrompt = false
        // Keep pendingAutoPermissionPrompt until Android returns from its consent dialog.
        autoConnectToken = autopilotLatch.emergencyStop()
        autoVpnButton.isEnabled = true
        autoVpnButton.text = "MATRIX AI — запуск по команде"
        aiStatus.text = "MATRIX AI: автоподключение остановлено пользователем"
        status.text = "Аварийная остановка VPN…"
        vpnExecutor.execute {
            try {
                wireGuardBackend.setState(matrixTunnel, State.DOWN, null)
                tunnelUp = false
                activeConfig = null
                activeCandidateId = null
                runOnUiThread {
                    status.text = "VPN полностью отключён. Автоподключение заблокировано."
                    connectButton.text = "Подключить выбранный VPN"
                    connectButton.isEnabled = wireGuardConfig != null
                    emergencyStopButton.text = "АВАРИЙНО ОТКЛЮЧИТЬ VPN"
                }
            } catch (error: Exception) {
                runOnUiThread {
                    status.text = "Не удалось подтвердить отключение VPN"
                    showError("Аварийное отключение не подтверждено", error.message ?: "Проверьте состояние VPN в Android.")
                }
            }
        }
    }

    private fun requestGarlicNetworkAssessment(probe: org.matrixvpn.network.TunnelProbeResult) {
        val policy = garlicPolicy ?: return
        val observation = JSONObject()
            .put("tunnel_active", tunnelUp)
            .put("external_https_reachable", probe.reachable)
            .put("external_https_rtt_ms", probe.latencyMillis ?: JSONObject.NULL)
            .put("external_http_status", probe.httpCode ?: JSONObject.NULL)
            .put("warp_status", probe.warpStatus ?: JSONObject.NULL)
            .put("candidate_profile_ids", JSONArray())
            .put("policy_scope", "network_health_only")
            .toString()

        policy.assess(observation).whenComplete { raw, error ->
            if (error != null) {
                runOnUiThread { aiStatus.text = "MATRIX AI: проверка сети не завершилась" }
                return@whenComplete
            }
            try {
                // No alternate profiles are configured in this check. The gate therefore
                // permits only KEEP_CURRENT and rejects any invented failover candidate.
                val advice = GarlicPolicyOutputGate.validate(raw, emptySet())
                runOnUiThread {
                    aiStatus.text = if (advice.action == GarlicPolicyOutputGate.Action.KEEP_CURRENT) {
                        "MATRIX AI: оставить текущий маршрут • ${advice.reasonCode}"
                    } else {
                        "MATRIX AI: запрос отклонён защитным контроллером"
                    }
                }
            } catch (_: Exception) {
                runOnUiThread { aiStatus.text = "MATRIX AI: ответ отклонён защитным контроллером" }
            }
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
        bottomMargin = (6 * resources.displayMetrics.density).toInt()
    }

    /** Fetches up to three independently registered WARP candidate profiles. */
    private fun fetchFreeWarpProfile() {
        fetchWarpCandidates(autoStart = false, token = 0L)
    }

    private fun fetchWarpCandidates(autoStart: Boolean, token: Long) {
        runOnUiThread {
            status.text = if (autoStart) "MATRIX AI: поиск вариантов VPN…" else "Получение вариантов Cloudflare WARP…"
            connectButton.isEnabled = false
            autoVpnButton.isEnabled = false
        }
        Thread {
            var connection: java.net.HttpURLConnection? = null
            var usedEndpointLab = false
            try {
                var responseJson: JSONObject? = null
                var lastServiceError = "Сервис WARP не ответил."
                // Prefer distinct, recently checked endpoints. If Lab has no fresh pool,
                // explicitly retry the generator's documented hostname mode.
                for (endpointMode in listOf("lab", "hostname")) {
                    connection?.disconnect()
                    val url = java.net.URL(
                        "https://awgconfig.com/api/warp?mode=legacy&count=3&dns=cloudflare&endpointMode=$endpointMode"
                    )
                    connection = (url.openConnection() as java.net.HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 15000
                        readTimeout = 25000
                        setRequestProperty("Accept", "application/json")
                        setRequestProperty("User-Agent", "MATRIX-VPN-Android/0.7.0")
                        instanceFollowRedirects = true
                    }
                    val code = connection.responseCode
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                    val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    require(response.length <= 1_000_000) { "Ответ сервиса слишком большой." }
                    if (code in 200..299) {
                        responseJson = JSONObject(response)
                        usedEndpointLab = endpointMode == "lab"
                        break
                    }
                    lastServiceError = "Сервис WARP ответил с кодом HTTP $code."
                    if (endpointMode != "lab" || code != 503) {
                        throw IllegalStateException(lastServiceError)
                    }
                }
                val json = responseJson ?: throw IllegalStateException(lastServiceError)
                if (!json.optBoolean("success", true)) {
                    throw IllegalStateException(json.optString("error").ifBlank { "Сервис не смог создать профили WARP." })
                }

                val encodedProfiles = mutableListOf<String>()
                val configs = json.optJSONArray("configs")
                if (configs != null) {
                    for (index in 0 until minOf(configs.length(), 3)) {
                        val item = configs.optJSONObject(index) ?: continue
                        val encoded = item.optString("content")
                        if (encoded.isNotBlank()) encodedProfiles += encoded
                    }
                }
                if (encodedProfiles.isEmpty()) {
                    val encoded = json.optString("content")
                    if (encoded.isNotBlank()) encodedProfiles += encoded
                }
                require(encodedProfiles.isNotEmpty()) { "Сервис не вернул ни одного VPN-профиля." }

                val parsed = mutableListOf<VpnCandidate>()
                val seenEndpoints = mutableSetOf<String>()
                encodedProfiles.take(3).forEachIndexed { index, encoded ->
                    val profileText = decodeGeneratedProfile(encoded)
                    val summary = VpnProfileInspector.inspect(profileText)
                    require(summary.format == ImportedVpnProfile.Format.WIREGUARD ||
                        summary.format == ImportedVpnProfile.Format.AMNEZIAWG) {
                        "Профиль №${index + 1} не совместим со встроенным движком WireGuard/AmneziaWG."
                    }
                    val endpointRaw = summary.endpointHints.firstOrNull()
                    require(!endpointRaw.isNullOrBlank()) {
                        "У профиля №${index + 1} не найден адрес VPN endpoint."
                    }
                    val endpointKey = endpointRaw.trim().lowercase()
                    // The generator's hostname fallback creates separate WARP registrations
                    // but routes them to the same endpoint. Do not pretend those are
                    // independent failover servers; keep only genuinely distinct endpoints.
                    if (!seenEndpoints.add(endpointKey)) return@forEachIndexed

                    val config = Config.parse(ByteArrayInputStream(profileText.toByteArray(Charsets.UTF_8)))
                    val endpoint = endpointRaw.take(96)
                    val id = "warp-${index + 1}-${UUID.randomUUID().toString().take(8)}"
                    parsed += VpnCandidate(id, "Cloudflare WARP ${parsed.size + 1} • $endpoint", config)
                }
                require(parsed.isNotEmpty()) {
                    "Не найдено ни одного нового уникального endpoint. Повторная регистрация WARP на одном hostname не считается новым VPN-маршрутом."
                }

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (autoStart && !autopilotLatch.isActive(token)) {
                        autoVpnButton.isEnabled = true
                        status.text = "Автоподбор отменён пользователем."
                        return@runOnUiThread
                    }
                    // Refresh generated WARP alternatives without deleting user-imported
                    // profiles or the profile that is currently carrying the tunnel.
                    vpnCandidates.keys.removeIf { it.startsWith("warp-") && it != activeCandidateId }
                    candidateHealth.keys.removeIf { it.startsWith("warp-") && it != activeCandidateId }
                    parsed.forEach { vpnCandidates[it.id] = it }
                    if (wireGuardConfig == null) wireGuardConfig = parsed.first().config
                    connectButton.isEnabled = true
                    autoVpnButton.isEnabled = true
                    status.text = "MATRIX получил и проверил уникальные маршруты: ${parsed.size}\nИсточник: awgconfig.com / Cloudflare WARP • ${if (usedEndpointLab) "разные проверенные endpoint" else "один hostname endpoint"}"
                    aiStatus.text = "MATRIX AI: доступно кандидатов VPN — ${parsed.size}"
                    if (autoStart) requestAutoVpnPermission(token)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    // A delayed result from an earlier run must not cancel a newer run.
                    if (autoStart && !autopilotLatch.isActive(token)) return@runOnUiThread
                    autoVpnButton.isEnabled = true
                    connectButton.isEnabled = wireGuardConfig != null
                    status.text = "Не удалось получить варианты VPN"
                    showError(
                        "Автоматический поиск не завершён",
                        (e.message ?: "Сервис WARP сейчас недоступен.") +
                            "\n\nГенератор сторонний и может временно не отвечать. MATRIX не подключает непроверенные профили."
                    )
                    if (autoStart && autopilotLatch.isActive(token)) autopilotLatch.emergencyStop()
                }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    private fun decodeGeneratedProfile(encoded: String): String {
        val decoded = try {
            String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            encoded
        }
        val profileText = if (decoded.contains("[Interface]", ignoreCase = true)) decoded else encoded
        require(profileText.contains("[Interface]", ignoreCase = true) &&
            profileText.contains("[Peer]", ignoreCase = true)) {
            "Сервис вернул данные не в формате WireGuard/AmneziaWG."
        }
        require(profileText.length <= 2_000_000) { "VPN-профиль слишком большой." }
        return profileText
    }


    /**
     * Loads the public VPN Gate relay directory. These are real volunteer-run VPN servers,
     * not WARP profiles. VPN Gate publishes OpenVPN client configs; MATRIX currently uses
     * WireGuard/AWG internally, so selected OpenVPN profiles are handed to a compatible
     * external VPN app instead of being falsely treated as WireGuard configs.
     */
    private fun searchVpnServices() {
        status.text = "Поиск бесплатных VPN-серверов…"
        connectButton.isEnabled = wireGuardConfig != null
        val loading = AlertDialog.Builder(this)
            .setTitle("Бесплатные VPN-серверы")
            .setMessage("Загружаю список VPN Gate. Серверы предоставлены добровольцами; доступность и доверие к ним не гарантируются.")
            .setNegativeButton("Отмена", null)
            .create()
        loading.show()
        Thread {
            var connection: java.net.HttpURLConnection? = null
            try {
                connection = (java.net.URL("https://www.vpngate.net/api/iphone/").openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 20000
                    setRequestProperty("User-Agent", "MATRIX-VPN-Android/0.5.1")
                    setRequestProperty("Accept", "text/csv,text/plain,*/*")
                    instanceFollowRedirects = true
                }
                val code = connection.responseCode
                require(code in 200..299) { "Каталог VPN Gate ответил HTTP $code." }
                val csv = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                require(csv.length <= 20_000_000) { "Ответ каталога слишком большой." }
                val lines = csv.lineSequence().filter { it.isNotBlank() && !it.startsWith("*") }.toList()
                val headerIndex = lines.indexOfFirst { it.trimStart('#').startsWith("HostName,IP,Score,Ping,Speed,CountryLong", ignoreCase = true) }
                require(headerIndex >= 0) { "Каталог вернул неизвестный формат." }
                val servers = lines.drop(headerIndex + 1).mapNotNull { line ->
                    val fields = parseCsvLine(line)
                    if (fields.size < 15) return@mapNotNull null
                    val host = fields[0].trim()
                    val ip = fields[1].trim()
                    val score = fields[2].toLongOrNull() ?: 0L
                    val ping = fields[3].toLongOrNull() ?: -1L
                    val speed = fields[4].toLongOrNull() ?: 0L
                    val country = fields[5].trim().ifBlank { fields[6].trim().ifBlank { "Неизвестная страна" } }
                    val sessions = fields[7].toIntOrNull() ?: 0
                    val config = fields[14].trim()
                    if (host.isBlank() || ip.isBlank() || config.isBlank()) null
                    else VpnGateServer(host, ip, country, ping, speed, sessions, score, config)
                }.sortedWith(compareByDescending<VpnGateServer> { it.score }.thenBy { if (it.ping < 0) Long.MAX_VALUE else it.ping }).take(30)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    loading.dismiss()
                    if (servers.isEmpty()) {
                        status.text = "Список бесплатных VPN-серверов пуст"
                        showError("Серверы не найдены", "VPN Gate не вернул доступных конфигураций. Попробуйте позже.")
                    } else {
                        status.text = "Найдено бесплатных VPN-серверов: ${servers.size}\nИсточник: VPN Gate (добровольческие серверы)"
                        val labels = servers.map { server ->
                            val pingLabel = if (server.ping >= 0) "${server.ping} мс" else "ping неизвестен"
                            val speedLabel = if (server.speed > 0) " • ${server.speed / 1_000_000} Мбит/с" else ""
                            "${server.country} • ${server.host}\n$pingLabel • сессий: ${server.sessions}$speedLabel"
                        }.toTypedArray()
                        AlertDialog.Builder(this)
                            .setTitle("Бесплатные VPN-серверы (VPN Gate)")
                            .setItems(labels) { _, which -> offerVpnGateProfile(servers[which]) }
                            .setNegativeButton("Закрыть", null)
                            .setNeutralButton("О VPN Gate") { _, _ ->
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
                    status.text = "Поиск бесплатных VPN-серверов не выполнен"
                    showError("Ошибка поиска серверов", (e.message ?: "VPN Gate сейчас недоступен.") + "\n\nПопробуйте позже. WARP и ручной импорт работают отдельно.")
                }
            } finally {
                connection?.disconnect()
            }
        }.start()
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
            .setTitle("Бесплатный сервер: ${server.country}")
            .setMessage(
                "Сервер: ${server.host} (${server.ip})\n\n" +
                "Это реальный бесплатный VPN-сервер VPN Gate, не WARP. Каталог предоставляет профиль OpenVPN. " +
                "Встроенный движок MATRIX сейчас поддерживает WireGuard/AmneziaWG, поэтому для подключения этого сервера нужен совместимый внешний OpenVPN-клиент. " +
                "Профиль будет передан через системное меню Android. Бесплатные серверы управляются добровольцами; не используйте их для конфиденциальных данных."
            )
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Получить профиль") { _, _ -> shareVpnGateProfile(server) }
            .show()
    }

    private fun shareVpnGateProfile(server: VpnGateServer) {
        try {
            val decoded = android.util.Base64.decode(server.openVpnConfigBase64, android.util.Base64.DEFAULT)
            val profileText = String(decoded, Charsets.UTF_8)
            require(profileText.contains("client", ignoreCase = true) && profileText.contains("remote", ignoreCase = true)) {
                "Каталог вернул недопустимый OpenVPN-профиль."
            }
            val safeHost = server.host.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val outDir = java.io.File(cacheDir, "vpn-gate").apply { mkdirs() }
            val file = java.io.File(outDir, "vpngate-$safeHost.ovpn")
            file.writeText(profileText, Charsets.UTF_8)
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/x-openvpn-profile"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "VPN Gate profile - ${server.country}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = android.content.ClipData.newUri(contentResolver, file.name, uri)
            }
            startActivity(Intent.createChooser(shareIntent, "Открыть профиль в OpenVPN-клиенте"))
        } catch (e: Exception) {
            showError("Не удалось подготовить профиль", e.message ?: "Профиль сервера не удалось прочитать.")
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
        if (tunnelUp) {
            emergencyStopVpn()
            return
        }
        // A manual connection always ends the previous automatic selection session.
        pendingAutoConnect = false
        autopilotLatch.emergencyStop()
        if (wireGuardConfig == null) {
            showError("Нет профиля VPN", "Сначала импортируйте и проверьте файл WireGuard или AmneziaWG .conf.")
            return
        }
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            pendingManualPermissionPrompt = true
            pendingAutoPermissionPrompt = false
            startActivityForResult(prepareIntent, vpnPermissionRequest)
        } else {
            pendingManualPermissionPrompt = false
            startWireGuardTunnel()
        }
    }

    private fun startWireGuardTunnel() {
        val config = wireGuardConfig ?: return
        val generation = manualConnectGeneration.incrementAndGet()
        status.text = "Запуск VPN-туннеля…"
        connectButton.isEnabled = false
        vpnExecutor.execute manualConnectTask@{
            try {
                if (manualConnectGeneration.get() != generation) return@manualConnectTask
                wireGuardBackend.setState(matrixTunnel, State.UP, config)
                activeConfig = config
                activeCandidateId = vpnCandidates.values.firstOrNull { it.config === config }?.id
                tunnelUp = true
                if (manualConnectGeneration.get() != generation) {
                    wireGuardBackend.setState(matrixTunnel, State.DOWN, null)
                    tunnelUp = false
                    activeConfig = null
                    return@manualConnectTask
                }
                probeExecutor.execute manualProbeTask@{
                    val probe = TunnelConnectivityProbe.check()
                    if (manualConnectGeneration.get() != generation) return@manualProbeTask
                    activeCandidateId?.let { id ->
                        candidateHealth[id] = CandidateHealth(
                            reachable = probe.reachable,
                            latencyMillis = probe.latencyMillis,
                        jitterMillis = probe.jitterMillis,
                        failurePercent = probe.probeFailurePercent,
                        sampleCount = probe.sampleCount,
                        successfulSamples = probe.successfulSamples,
                            httpCode = probe.httpCode,
                            checkedAtMillis = System.currentTimeMillis()
                        )
                    }
                    runOnUiThread {
                        if (manualConnectGeneration.get() != generation) return@runOnUiThread
                        requestGarlicNetworkAssessment(probe)
                        status.text = if (probe.reachable) {
                            "VPN-туннель запущен\n${probe.message}\nПодтверждение рукопожатия с сервером отдельно не проверено."
                        } else {
                            "VPN-туннель запущен, но проверка интернета не пройдена.\n${probe.message}\nСервер может быть недоступен или профиль может не направлять интернет-трафик через туннель."
                        }
                        connectButton.isEnabled = true
                        connectButton.text = if (tunnelUp) "Отключить VPN" else "Подключить VPN"
                    }
                }
            } catch (e: Exception) {
                // A failed native start may have partially initialized the backend even
                // though tunnelUp has not yet been updated. Always request an explicit stop.
                try {
                    wireGuardBackend.setState(matrixTunnel, State.DOWN, null)
                } catch (_: Exception) {
                }
                tunnelUp = false
                activeConfig = null
                activeCandidateId = null
                runOnUiThread {
                    connectButton.isEnabled = true
                    connectButton.text = "Подключить VPN"
                    if (manualConnectGeneration.get() == generation) {
                        showError("Ошибка подключения", e.message ?: "Не удалось запустить WireGuard. Проверьте профиль и разрешение Android на VPN.")
                    }
                }
            }
        }
    }

    private fun showError(title: String, message: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("Понятно", null).show()
    }

    @Deprecated("Используется системный выбор файлов для совместимости с разными версиями Android.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == modelPackRequest) {
            if (resultCode == RESULT_OK) {
                val uri = data?.data
                if (uri != null) installGarlicModelPack(uri)
            }
            return
        }
        if (requestCode == vpnPermissionRequest) {
            if (pendingAutoPermissionPrompt) {
                // An emergency stop may be pressed while Android's consent dialog is open.
                // Never reinterpret a late "Allow" as a manual connection.
                pendingAutoPermissionPrompt = false
                val token = autoConnectToken
                if (resultCode == RESULT_OK && pendingAutoConnect && autopilotLatch.isActive(token)) {
                    pendingAutoConnect = false
                    requestAutomaticCandidateSelection(emptySet(), null, token)
                } else {
                    pendingAutoConnect = false
                    if (autopilotLatch.isActive(token)) autopilotLatch.emergencyStop()
                    autoVpnButton.isEnabled = true
                }
            } else if (pendingManualPermissionPrompt && resultCode == RESULT_OK) {
                pendingManualPermissionPrompt = false
                startWireGuardTunnel()
            } else {
                pendingManualPermissionPrompt = false
                if (resultCode != RESULT_OK) {
                    showError("Разрешение VPN не предоставлено", "Android не разрешил приложению MATRIX создать VPN-туннель.")
                }
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
            if (profile.format == ImportedVpnProfile.Format.WIREGUARD || profile.format == ImportedVpnProfile.Format.AMNEZIAWG) {
                try {
                    wireGuardConfig = Config.parse(ByteArrayInputStream(profileText.toByteArray(Charsets.UTF_8)))
                } catch (e: Exception) {
                    showError("Неверный профиль WireGuard", e.message ?: "Проверка WireGuard отклонила эту конфигурацию.")
                }
            }
            connectButton.isEnabled = wireGuardConfig != null
            if (wireGuardConfig != null) {
                val manualId = "import-${UUID.randomUUID().toString().take(8)}"
                val endpointHint = profile.endpointHints.firstOrNull()?.take(96) ?: "endpoint не указан"
                vpnCandidates[manualId] = VpnCandidate(
                    manualId,
                    "Импортированный $formatLabel • $endpointHint",
                    wireGuardConfig!!
                )
                aiStatus.text = "MATRIX AI: добавлен новый разрешённый VPN-кандидат • $endpointHint"
            }
            val endpointText = if (profile.endpointHints.isEmpty()) "Адрес сервера не найден" else profile.endpointHints.joinToString("\n")
            val details = buildString {
                append("Определённый формат: ").append(formatLabel).append("\n")
                if (profile.peerCount > 0) append("Количество узлов: ").append(profile.peerCount).append("\n")
                append("Обнаруженные адреса серверов:\n").append(endpointText).append("\n\n")
                if (profile.hasInlineCredentials) append("Профиль содержит приватные ключи или учётные данные. Не передавайте его посторонним.\n\n")
                if (wireGuardConfig != null && profile.format == ImportedVpnProfile.Format.AMNEZIAWG) append("Профиль AmneziaWG принят встроенным модулем AWG. Можно запросить разрешение Android и подключиться.")
                else if (wireGuardConfig != null) append("Конфигурация WireGuard проверена. Теперь можно запросить разрешение Android и подключиться.")
                else if (profile.format == ImportedVpnProfile.Format.OPENVPN) append("Профиль OpenVPN распознан, но модуль для подключения OpenVPN пока не встроен.")
                else append("Подходящая конфигурация WireGuard/AmneziaWG не загружена.")
                append("\n\nВ этой версии профиль хранится только в памяти и не сохраняется приложением MATRIX.")
            }
            status.text = if (wireGuardConfig != null) "Профиль $formatLabel готов\nVPN-туннель: отключён"
            else "Профиль проверен: $formatLabel\nVPN-туннель: отключён"
            AlertDialog.Builder(this).setTitle("Профиль VPN обработан").setMessage(details).setPositiveButton("Понятно", null).show()
        } catch (e: Exception) {
            showError("Не удалось обработать профиль VPN", e.message ?: "Не удалось проверить конфигурацию.")
        }
    }
}