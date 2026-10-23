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
        val importButton = Button(this).apply {
            text = "2. Импорт файла с телефона"
            setOnClickListener { chooseVpnProfile() }
        }
        val findWireGuardButton = Button(this).apply {
            text = "WARP — отдельный режим"
            setOnClickListener { fetchFreeWarpProfile() }
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
            text = "Импортируйте конфигурацию из буфера или файл с телефона. WARP работает отдельно. Пункт бесплатных серверов получает актуальный каталог VPN Gate и передаёт OpenVPN-профиль совместимому приложению; встроенное подключение MATRIX пока поддерживает WireGuard/AmneziaWG. Бесплатные серверы добровольцев не гарантируют доступность и конфиденциальность."
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
            addView(importButton, menuButtonParams())
            addView(findWireGuardButton, menuButtonParams())
            addView(findVpnButton, menuButtonParams())
            addView(pasteButton, menuButtonParams())
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
        endpointList.setTextColor(Color.rgb(170, 220, 180))
        help.setTextColor(Color.rgb(170, 220, 180))
        setContentView(MatrixRainView(this))
        window.decorView.postDelayed({
            setContentView(mainScreen)
        }, 1800L)
        networkWatcher = NetworkWatcher(this) { state ->
            runOnUiThread { networkStatus.text = state.summary() }
        }
        refreshEndpointList()
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