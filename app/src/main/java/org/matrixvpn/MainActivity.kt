package org.matrixvpn

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
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
import org.matrixvpn.profile.VpnEndpoint
import org.matrixvpn.profile.VpnProfileInspector
import java.io.ByteArrayInputStream

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var networkStatus: TextView
    private lateinit var endpointList: TextView
    private lateinit var connectButton: Button
    private lateinit var networkWatcher: NetworkWatcher
    private val pickProfileRequest = 4102
    private val vpnPermissionRequest = 4103
    private val preferences by lazy { getSharedPreferences("matrix_endpoints", MODE_PRIVATE) }
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
            text = "УПРАВЛЕНИЕ VPN • ВЕРСИЯ 0.3.0"
            textSize = 12f
            setTextColor(Color.rgb(130, 220, 150))
            gravity = Gravity.CENTER
            setPadding(8, 0, 8, 18)
        }
        val connectionHeading = sectionHeading("СОСТОЯНИЕ СОЕДИНЕНИЯ")
        val profilesHeading = sectionHeading("СЕРВЕРЫ И ПРОФИЛИ")
        val helpHeading = sectionHeading("СПРАВКА")
        status = TextView(this).apply {
            text = "Сборка 0.3.0\nVPN-туннель: не подключён\nАвтоматическое восстановление: в разработке"
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
        val addEndpointButton = Button(this).apply {
            text = "Добавить адрес сервера VPN"
            setOnClickListener { showAddEndpointDialog() }
        }
        val importButton = Button(this).apply {
            text = "Импортировать профиль VPN"
            setOnClickListener { chooseVpnProfile() }
        }
        val findWireGuardButton = Button(this).apply {
            text = "Найти и настроить бесплатный WARP"
            setOnClickListener { fetchFreeWarpProfile() }
        }
        val findVpnButton = Button(this).apply {
            text = "Получить новый профиль WARP"
            setOnClickListener { fetchFreeWarpProfile() }
        }
        val pasteButton = Button(this).apply {
            text = "Вставить конфигурацию WARP / VPN"
            setOnClickListener { showPasteProfileDialog() }
        }
        connectButton = Button(this).apply {
            text = "Подключить VPN"
            isEnabled = false
            setOnClickListener { connectOrDisconnect() }
        }
        endpointList = TextView(this).apply {
            textSize = 14f
            setPadding(12, 12, 12, 12)
        }
        val help = TextView(this).apply {
            text = "Здесь можно подключать профили WireGuard и AmneziaWG (AWG), в том числе совместимые конфигурации из генератора WARP. Для подключения Android запросит разрешение. Файлы OpenVPN можно распознать, но для подключения нужен отдельный модуль OpenVPN. Один адрес сервера не заменяет полный профиль. После запуска проверяется доступ в интернет."
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
            addView(profilesHeading)
            addView(addEndpointButton, menuButtonParams())
            addView(endpointList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
        listOf(addEndpointButton, importButton, findWireGuardButton, findVpnButton, pasteButton, connectButton).forEach { button ->
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

    private fun showAddEndpointDialog() {
        val input = EditText(this).apply {
            hint = "vpn.example.net:51820"
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(32, 24, 32, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("Добавить адрес сервера VPN")
            .setMessage("Введите адрес в формате хост:порт. Адрес будет только сохранён в списке — соединение не проверяется и не запускается.")
            .setView(input)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ ->
                try {
                    val endpoint = VpnEndpoint.parse(input.text.toString())
                    val entries = preferences.getStringSet("addresses", emptySet()).orEmpty().toMutableSet()
                    entries.add(endpoint.displayAddress)
                    preferences.edit().putStringSet("addresses", entries).apply()
                    refreshEndpointList()
                } catch (e: IllegalArgumentException) {
                    AlertDialog.Builder(this)
                        .setTitle("Неверный адрес")
                        .setMessage(e.message ?: "Проверьте адрес и порт.")
                        .setPositiveButton("Понятно", null)
                        .show()
                }
            }
            .show()
    }

    private fun refreshEndpointList() {
        val entries = preferences.getStringSet("addresses", emptySet()).orEmpty().toList().sorted()
        endpointList.text = if (entries.isEmpty()) "Сохранённых адресов серверов нет"
        else "Сохранённые адреса серверов (удерживайте адрес, чтобы удалить):\n" + entries.joinToString("\n")
        endpointList.setOnLongClickListener {
            if (entries.isEmpty()) return@setOnLongClickListener false
            val labels = entries.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Удалить сохранённый адрес")
                .setItems(labels) { _, which ->
                    val updated = preferences.getStringSet("addresses", emptySet()).orEmpty().toMutableSet()
                    updated.remove(labels[which])
                    preferences.edit().putStringSet("addresses", updated).apply()
                    refreshEndpointList()
                }
                .setNegativeButton("Отмена", null)
                .show()
            true
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