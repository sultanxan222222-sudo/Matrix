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
import android.os.SystemClock
import android.provider.Settings
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
import org.matrixvpn.profile.ImportedVpnProfile
import org.matrixvpn.profile.VpnProfileInspector
import org.matrixvpn.profile.SecureProfileVault
import org.matrixvpn.recovery.NodeHealthStore
import java.io.ByteArrayInputStream

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var networkStatus: TextView
    private lateinit var connectButton: Button
    private lateinit var networkWatcher: NetworkWatcher
    private val pickProfileRequest = 4102
    private val vpnPermissionRequest = 4103
    private val tunnelActions = object : TunnelActionHandler {
        override fun runPreUp(scripts: MutableCollection<String>) {}
        override fun runPostUp(scripts: MutableCollection<String>) {}
        override fun runPreDown(scripts: MutableCollection<String>) {}
        override fun runPostDown(scripts: MutableCollection<String>) {}
    }
    private val wireGuardBackend by lazy { GoBackend(applicationContext, tunnelActions) }
    private var wireGuardConfig: Config? = null
    private var tunnelUp = false
    private var activeProfileId: String? = null
    private var activeProfileName: String = "Нет профиля"
    private var activeProfileProvider: String = "Импорт"

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
            text = "HYBRID VPN CONTROL • ВЕРСИЯ 0.6.0"
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
        val importButton = Button(this).apply {
            text = "2. Импорт файла с телефона"
            setOnClickListener { chooseVpnProfile() }
        }
        val findWireGuardButton = Button(this).apply {
            text = "WARP — отдельный режим"
            setOnClickListener { fetchFreeWarpProfile() }
        }
        val profilesButton = Button(this).apply {
            text = "Мои профили • умный выбор"
            setOnClickListener { showProfilePoolDialog() }
        }
        val recoveryButton = Button(this).apply {
            text = "Безопасное авто-переключение"
            setOnClickListener { showRecoveryStatus() }
        }
        val findVpnButton = Button(this).apply {
            text = "Каталог VPN Gate (OpenVPN)"
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
            text = "WARP — отдельный режим получения WARP-совместимого WireGuard/AWG-профиля через сторонний генератор (это не официальный API Cloudflare). MATRIX хранит несколько профилей в зашифрованном Android Keystore, оценивает их по HTTPS-проверкам и может переключаться на резервный профиль только при включённом системном Always-on VPN и блокировке соединений без VPN. VPN Gate даёт OpenVPN-профили для внешнего OpenVPN-клиента; встроенный движок MATRIX подключает WireGuard/AmneziaWG."
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
            addView(profilesButton, menuButtonParams())
            addView(recoveryButton, menuButtonParams())
            addView(connectButton, menuButtonParams())
            addView(helpHeading)
            addView(help, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val mainScreen = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.rgb(4, 12, 7))
            addView(menuContent, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        listOf(importButton, findWireGuardButton, findVpnButton, pasteButton, profilesButton, recoveryButton, connectButton).forEach { button ->
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
        restoreSavedProfile()
    }

    override fun onStart() {
        super.onStart()
        networkWatcher.start()
    }

    override fun onStop() {
        networkWatcher.stop()
        super.onStop()
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
        status.text = "Поиск бесплатного подключения WARP…"
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
                    processProfileText(profileText, "Cloudflare WARP", "WARP")
                    if (wireGuardConfig != null) {
                        status.text = "Отдельный профиль WARP сохранён. Запрашиваю разрешение Android…"
                        val prepareIntent = VpnService.prepare(this)
                        if (prepareIntent != null) startActivityForResult(prepareIntent, vpnPermissionRequest)
                        else startWireGuardTunnel()
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

    private fun showProfilePoolDialog() {
        val profiles = SecureProfileVault.list(this)
            .sortedWith(compareByDescending<org.matrixvpn.profile.SavedVpnProfile> { NodeHealthStore.score(this, it.id) }
                .thenByDescending { it.createdAt })
        if (profiles.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Пул MATRIX пока пуст")
                .setMessage("Добавьте WARP-профиль, импортируйте WireGuard/AmneziaWG или вставьте конфигурацию. Профили хранятся отдельно и в зашифрованном виде.")
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
                    require(inspected.format == ImportedVpnProfile.Format.WIREGUARD ||
                        inspected.format == ImportedVpnProfile.Format.AMNEZIAWG) {
                        "Формат профиля не поддерживается встроенным движком."
                    }
                    wireGuardConfig = Config.parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
                    activeProfileId = chosen.id
                    activeProfileName = chosen.name
                    activeProfileProvider = chosen.provider
                    SecureProfileVault.setActive(this, chosen.id)
                    connectButton.isEnabled = true
                    status.text = "Выбран профиль: ${chosen.name}\nТип: ${chosen.format}\nVPN-туннель: ${if (tunnelUp) "подключён" else "отключён"}"
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
            "Системная блокировка Android включена для MATRIX. При неудачной HTTPS-проверке во время подключения MATRIX может проверить сохранённые резервные профили. Это проверка интернет-доступа через туннель, а не криптографического рукопожатия."
        } else {
            "Для автоматического переключения без утечки трафика откройте настройки Android → VPN → MATRIX, включите «Постоянная VPN» (Always-on VPN) и «Блокировать подключения без VPN» (Block connections without VPN). MATRIX не будет автоматически отключать один туннель и запускать другой, пока системная блокировка не подтверждена."
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
        if (tunnelUp) {
            connectButton.isEnabled = false
            Thread {
                try {
                    wireGuardBackend.setState(matrixTunnel, State.DOWN, null)
                    runOnUiThread {
                        status.text = "VPN-туннель: отключён"
                        connectButton.isEnabled = true
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
        if (wireGuardConfig == null) {
            showError("Нет профиля VPN", "Сначала импортируйте и проверьте файл WireGuard или AmneziaWG .conf.")
            return
        }
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) startActivityForResult(prepareIntent, vpnPermissionRequest)
        else startWireGuardTunnel()
    }

    private fun startWireGuardTunnel() {
        val config = wireGuardConfig ?: return
        val startingProfileId = activeProfileId
        val startingProfileName = activeProfileName
        val startingProvider = activeProfileProvider
        status.text = "Запуск VPN-туннеля: $startingProfileName…"
        connectButton.isEnabled = false
        Thread {
            var finalText = ""
            var connected = false
            try {
                wireGuardBackend.setState(matrixTunnel, State.UP, config)
                var probeStartedAt = SystemClock.elapsedRealtime()
                var probe = TunnelConnectivityProbe.check()
                var latency = (SystemClock.elapsedRealtime() - probeStartedAt).coerceAtLeast(1L)
                if (startingProfileId != null) NodeHealthStore.record(this, startingProfileId, probe.reachable, latency)

                if (probe.reachable) {
                    connected = true
                    finalText = "Подключено: $startingProfileName\n${probe.message}\nОценка основана на HTTPS-доступе через туннель; peer-handshake отдельно не подтверждён."
                } else if (startingProvider != "WARP" && isMatrixLockdownEnabled()) {
                    val backups = SecureProfileVault.list(this)
                        .filter { it.id != startingProfileId && it.format in setOf("WIREGUARD", "AMNEZIAWG") }
                        .sortedByDescending { NodeHealthStore.score(this, it.id) }
                    val attempts = mutableListOf<String>()
                    for (backup in backups) {
                        try {
                            val text = SecureProfileVault.load(this, backup.id) ?: continue
                            val backupConfig = Config.parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
                            wireGuardBackend.setState(matrixTunnel, State.DOWN, null)
                            wireGuardBackend.setState(matrixTunnel, State.UP, backupConfig)
                            probeStartedAt = SystemClock.elapsedRealtime()
                            probe = TunnelConnectivityProbe.check()
                            latency = (SystemClock.elapsedRealtime() - probeStartedAt).coerceAtLeast(1L)
                            NodeHealthStore.record(this, backup.id, probe.reachable, latency)
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
            runOnUiThread {
                status.text = finalText
                connectButton.isEnabled = true
                connectButton.text = if (connected && tunnelUp) "Отключить VPN" else "Подключить VPN"
                if (!connected && finalText.startsWith("Ошибка")) showError("Не удалось подключить VPN", finalText)
            }
        }.start()
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
                    wireGuardConfig = Config.parse(ByteArrayInputStream(stored.toByteArray(Charsets.UTF_8)))
                    activeProfileId = metadata.id
                    activeProfileName = metadata.name
                    activeProfileProvider = metadata.provider
                    connectButton.isEnabled = true
                    status.text = "Пул MATRIX восстановлен\nАктивный профиль: ${metadata.name}\nVPN-туннель: отключён"
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
            val saved = SecureProfileVault.save(this, "Мигрированный профиль", "Импорт", legacy)
            activeProfileId = saved.id
            activeProfileName = saved.name
            activeProfileProvider = saved.provider
            connectButton.isEnabled = true
            status.text = "Старый профиль перенесён в пул MATRIX\nVPN-туннель: отключён"
        } catch (e: Exception) {
            wireGuardConfig = null
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
        if (requestCode == vpnPermissionRequest) {
            if (resultCode == RESULT_OK) startWireGuardTunnel()
            else showError("Разрешение VPN не предоставлено", "Android не разрешил приложению MATRIX создать VPN-туннель.")
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
            if (profile.format == ImportedVpnProfile.Format.WIREGUARD || profile.format == ImportedVpnProfile.Format.AMNEZIAWG) {
                try {
                    wireGuardConfig = Config.parse(ByteArrayInputStream(profileText.toByteArray(Charsets.UTF_8)))
                } catch (e: Exception) {
                    showError("Неверный профиль WireGuard", e.message ?: "Проверка WireGuard отклонила эту конфигурацию.")
                }
            }
            connectButton.isEnabled = wireGuardConfig != null
            if (wireGuardConfig != null) {
                try {
                    val defaultName = suggestedName
                        ?: profile.endpointHints.firstOrNull()?.substringBeforeLast(':')?.takeIf { it.isNotBlank() }
                        ?: "Профиль ${System.currentTimeMillis()}"
                    val saved = SecureProfileVault.save(this, defaultName, providerLabel, profileText)
                    activeProfileId = saved.id
                    activeProfileName = saved.name
                    activeProfileProvider = saved.provider
                    SecureProfileVault.setActive(this, saved.id)
                    SecureProfileStore.save(this, profileText)
                } catch (e: Exception) {
                    showError("Не удалось сохранить профиль", e.message ?: "Профиль проверен, но не добавлен в зашифрованный пул.")
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
                if (wireGuardConfig != null && profile.format == ImportedVpnProfile.Format.AMNEZIAWG) append("Профиль AmneziaWG принят встроенным модулем AWG. Можно запросить разрешение Android и подключиться.")
                else if (wireGuardConfig != null) append("Конфигурация WireGuard проверена. Теперь можно запросить разрешение Android и подключиться.")
                else if (profile.format == ImportedVpnProfile.Format.OPENVPN) append("Профиль OpenVPN распознан, но модуль для подключения OpenVPN пока не встроен.")
                else append("Подходящая конфигурация WireGuard/AmneziaWG не загружена.")
                append("\n\nПоддерживаемый профиль WireGuard/AmneziaWG добавлен в зашифрованный пул MATRIX (Android Keystore) и выбран активным. Откройте «Мои профили» для управления несколькими конфигурациями. Автоподключение при запуске приложения не выполняется.")
            }
            status.text = if (wireGuardConfig != null) "Профиль $formatLabel готов: $activeProfileName\nVPN-туннель: ${if (tunnelUp) "подключён" else "отключён"}"
            else "Профиль проверен: $formatLabel\nVPN-туннель: отключён"
            AlertDialog.Builder(this).setTitle("Профиль VPN обработан").setMessage(details).setPositiveButton("Понятно", null).show()
        } catch (e: Exception) {
            showError("Не удалось обработать профиль VPN", e.message ?: "Не удалось проверить конфигурацию.")
        }
    }
}