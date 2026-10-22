package org.matrixvpn

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.VpnService
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.backend.Tunnel.State
import com.wireguard.config.Config
import org.matrixvpn.network.NetworkWatcher
import org.matrixvpn.profile.ImportedVpnProfile
import org.matrixvpn.profile.VpnEndpoint
import org.matrixvpn.profile.VpnProfileInspector
import java.io.BufferedReader
import java.io.StringReader

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var networkStatus: TextView
    private lateinit var endpointList: TextView
    private lateinit var connectButton: Button
    private lateinit var networkWatcher: NetworkWatcher
    private val pickProfileRequest = 4102
    private val vpnPermissionRequest = 4103
    private val preferences by lazy { getSharedPreferences("matrix_endpoints", MODE_PRIVATE) }
    private val wireGuardBackend by lazy { GoBackend(applicationContext) }
    private var wireGuardConfig: Config? = null
    private var tunnelUp = false

    private val matrixTunnel = object : Tunnel {
        override fun getName(): String = "matrix"
        override fun onStateChange(newState: State) {
            runOnUiThread {
                tunnelUp = newState == State.UP
                status.text = if (tunnelUp) {
                    "WireGuard tunnel: connected\nRecovery engine: prototype"
                } else {
                    "WireGuard tunnel: disconnected\nRecovery engine: prototype"
                }
                connectButton.text = if (tunnelUp) "Disconnect VPN" else "Connect WireGuard VPN"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = TextView(this).apply {
            text = "MATRIX VPN"
            textSize = 28f
            gravity = Gravity.CENTER
        }
        status = TextView(this).apply {
            text = "Development build\nWireGuard tunnel: not connected\nRecovery engine: prototype"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(16, 16, 16, 16)
        }
        networkStatus = TextView(this).apply {
            text = "Network: checking…"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(8, 8, 8, 8)
        }
        val addEndpointButton = Button(this).apply {
            text = "Add VPN server address"
            setOnClickListener { showAddEndpointDialog() }
        }
        val importButton = Button(this).apply {
            text = "Import VPN profile"
            setOnClickListener { chooseVpnProfile() }
        }
        connectButton = Button(this).apply {
            text = "Connect WireGuard VPN"
            isEnabled = false
            setOnClickListener { connectOrDisconnect() }
        }
        endpointList = TextView(this).apply {
            textSize = 14f
            setPadding(12, 12, 12, 12)
        }
        val help = TextView(this).apply {
            text = "WireGuard profiles can be connected with Android VPN permission. OpenVPN files can be previewed but require a separate OpenVPN engine. Server addresses are candidate hints only; an address by itself is not enough to connect."
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(8, 12, 8, 12)
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(20, 20, 20, 20)
            addView(title)
            addView(status)
            addView(networkStatus)
            addView(addEndpointButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(endpointList)
            addView(importButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(connectButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(help)
        })
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

    private fun showAddEndpointDialog() {
        val input = EditText(this).apply {
            hint = "vpn.example.net:51820"
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(32, 24, 32, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("Add VPN server address")
            .setMessage("Enter host:port. This saves the address as a candidate only; it does not connect or test the server.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                try {
                    val endpoint = VpnEndpoint.parse(input.text.toString())
                    val entries = preferences.getStringSet("addresses", emptySet()).orEmpty().toMutableSet()
                    entries.add(endpoint.displayAddress)
                    preferences.edit().putStringSet("addresses", entries).apply()
                    refreshEndpointList()
                } catch (e: IllegalArgumentException) {
                    AlertDialog.Builder(this)
                        .setTitle("Address not accepted")
                        .setMessage(e.message ?: "Check the host and port.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            .show()
    }

    private fun refreshEndpointList() {
        val entries = preferences.getStringSet("addresses", emptySet()).orEmpty().toList().sorted()
        endpointList.text = if (entries.isEmpty()) {
            "Saved server candidates: none"
        } else {
            "Saved server candidates (tap and hold to remove):\n" + entries.joinToString("\n")
        }
        endpointList.setOnLongClickListener {
            if (entries.isEmpty()) return@setOnLongClickListener false
            val labels = entries.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Remove saved address")
                .setItems(labels) { _, which ->
                    val updated = preferences.getStringSet("addresses", emptySet()).orEmpty().toMutableSet()
                    updated.remove(labels[which])
                    preferences.edit().putStringSet("addresses", updated).apply()
                    refreshEndpointList()
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }
    }

    private fun chooseVpnProfile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "text/plain",
                "application/octet-stream",
                "application/x-wireguard-profile",
                "application/x-openvpn-profile"
            ))
        }
        startActivityForResult(intent, pickProfileRequest)
    }

    private fun connectOrDisconnect() {
        if (tunnelUp) {
            Thread {
                try {
                    wireGuardBackend.setState(matrixTunnel, State.DOWN, null)
                    runOnUiThread { status.text = "WireGuard tunnel: disconnected" }
                } catch (e: Exception) {
                    runOnUiThread { showError("Disconnect failed", e.message ?: "Could not stop the tunnel.") }
                }
            }.start()
            return
        }
        if (wireGuardConfig == null) {
            showError("No WireGuard profile", "Import and validate a WireGuard .conf file first.")
            return
        }
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            startActivityForResult(prepareIntent, vpnPermissionRequest)
        } else {
            startWireGuardTunnel()
        }
    }

    private fun startWireGuardTunnel() {
        val config = wireGuardConfig ?: return
        status.text = "Starting WireGuard tunnel…"
        connectButton.isEnabled = false
        Thread {
            try {
                wireGuardBackend.setState(matrixTunnel, State.UP, config)
                runOnUiThread {
                    status.text = "WireGuard tunnel started. Waiting for peer traffic/handshake."
                    connectButton.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    connectButton.isEnabled = true
                    showError("Connection failed", e.message ?: "WireGuard could not start. Check the profile and Android VPN permission.")
                }
            }
        }.start()
    }

    private fun showError(title: String, message: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show()
    }

    @Deprecated("Uses the platform document picker result API for broad Android compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == vpnPermissionRequest) {
            if (resultCode == RESULT_OK) startWireGuardTunnel()
            else showError("VPN permission denied", "Android did not authorize MATRIX to create a VPN tunnel.")
            return
        }
        if (requestCode != pickProfileRequest || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        try {
            val profileText = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
                it.readText()
            } ?: throw IllegalArgumentException("Could not read the selected file.")
            require(profileText.length <= 2_000_000) { "The selected profile is too large to import safely." }

            val profile = VpnProfileInspector.inspect(profileText)
            val formatLabel = when (profile.format) {
                ImportedVpnProfile.Format.WIREGUARD -> "WireGuard"
                ImportedVpnProfile.Format.OPENVPN -> "OpenVPN"
                ImportedVpnProfile.Format.UNKNOWN -> "Unknown profile format"
            }
            wireGuardConfig = null
            if (profile.format == ImportedVpnProfile.Format.WIREGUARD) {
                try {
                    wireGuardConfig = Config.parse(BufferedReader(StringReader(profileText)))
                } catch (e: Exception) {
                    showError("Invalid WireGuard profile", e.message ?: "The official WireGuard parser rejected this configuration.")
                }
            }
            connectButton.isEnabled = wireGuardConfig != null
            val endpointText = if (profile.endpointHints.isEmpty()) "No endpoint address detected"
                else profile.endpointHints.joinToString("\n")
            val details = buildString {
                append("Detected format: ").append(formatLabel).append("\n")
                if (profile.peerCount > 0) append("Peers: ").append(profile.peerCount).append("\n")
                append("Endpoint hints:\n").append(endpointText).append("\n\n")
                if (profile.hasInlineCredentials) append("This profile contains private keys or credentials. Keep it private.\n\n")
                if (wireGuardConfig != null) {
                    append("WireGuard configuration validated. You can now request Android VPN permission and connect.")
                } else if (profile.format == ImportedVpnProfile.Format.OPENVPN) {
                    append("OpenVPN profile recognized, but the OpenVPN engine is not integrated yet.")
                } else {
                    append("No supported, valid WireGuard configuration was loaded.")
                }
                append("\n\nProfile contents are kept in memory only by this build; they are not saved by MATRIX.")
            }
            status.text = if (wireGuardConfig != null) "WireGuard profile ready\nVPN tunnel: disconnected"
                else "Profile inspected: $formatLabel\nVPN tunnel: disconnected"
            AlertDialog.Builder(this)
                .setTitle("VPN profile processed")
                .setMessage(details)
                .setPositiveButton("OK", null)
                .show()
        } catch (e: Exception) {
            showError("Could not read VPN profile", e.message ?: "The selected file could not be inspected.")
        }
    }
}
