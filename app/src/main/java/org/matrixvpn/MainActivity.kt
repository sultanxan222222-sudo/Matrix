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
                    "VPN tunnel: connected\nChecking external HTTPS…\nRecovery engine: prototype"
                } else {
                    "VPN tunnel: disconnected\nRecovery engine: prototype"
                }
                connectButton.text = if (tunnelUp) "Disconnect VPN" else "Connect VPN"
            }
        }
        override fun isIpv4ResolutionPreferred(): Boolean = true
        override fun isMetered(): Boolean = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val title = TextView(this).apply {
            text = "MATRIX VPN"
            textSize = 28f
            gravity = Gravity.CENTER
        }
        status = TextView(this).apply {
            text = "Development build\nVPN tunnel: not connected\nRecovery engine: prototype"
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
            text = "Connect VPN"
            isEnabled = false
            setOnClickListener { connectOrDisconnect() }
        }
        endpointList = TextView(this).apply {
            textSize = 14f
            setPadding(12, 12, 12, 12)
        }
        val help = TextView(this).apply {
            text = "WireGuard and AmneziaWG profiles, including compatible configs from the WARP generator, can be connected with Android VPN permission. OpenVPN files can be previewed but require a separate OpenVPN engine. Server addresses are candidate hints only; an address by itself is not enough to connect. After starting a tunnel, MATRIX checks whether an external HTTPS request succeeds."
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
        endpointList.text = if (entries.isEmpty()) "Saved server candidates: none"
        else "Saved server candidates (tap and hold to remove):\n" + entries.joinToString("\n")
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
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "application/octet-stream", "application/x-wireguard-profile", "application/x-openvpn-profile"))
        }
        startActivityForResult(intent, pickProfileRequest)
    }

    private fun connectOrDisconnect() {
        if (tunnelUp) {
            connectButton.isEnabled = false
            Thread {
                try {
                    wireGuardBackend.setState(matrixTunnel, State.DOWN, null)
                    runOnUiThread {
                        status.text = "VPN tunnel: disconnected"
                        connectButton.isEnabled = true
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        connectButton.isEnabled = true
                        showError("Disconnect failed", e.message ?: "Could not stop the tunnel.")
                    }
                }
            }.start()
            return
        }
        if (wireGuardConfig == null) {
            showError("No VPN profile", "Import and validate a WireGuard or AmneziaWG .conf file first.")
            return
        }
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) startActivityForResult(prepareIntent, vpnPermissionRequest)
        else startWireGuardTunnel()
    }

    private fun startWireGuardTunnel() {
        val config = wireGuardConfig ?: return
        status.text = "Starting VPN tunnel…"
        connectButton.isEnabled = false
        Thread {
            try {
                wireGuardBackend.setState(matrixTunnel, State.UP, config)
                val probe = TunnelConnectivityProbe.check()
                runOnUiThread {
                    status.text = if (probe.reachable) {
                        "VPN tunnel: active\n${probe.message}\nPeer handshake is not independently confirmed."
                    } else {
                        "VPN tunnel: started, but internet check failed.\n${probe.message}\nThe server may be unreachable or the profile may not route internet traffic."
                    }
                    connectButton.isEnabled = true
                    connectButton.text = if (tunnelUp) "Disconnect VPN" else "Connect VPN"
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
            val profileText = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: throw IllegalArgumentException("Could not read the selected file.")
            require(profileText.length <= 2_000_000) { "The selected profile is too large to import safely." }
            val profile = VpnProfileInspector.inspect(profileText)
            val formatLabel = when (profile.format) {
                ImportedVpnProfile.Format.WIREGUARD -> "WireGuard"
                ImportedVpnProfile.Format.AMNEZIAWG -> "AmneziaWG (AWG)"
                ImportedVpnProfile.Format.OPENVPN -> "OpenVPN"
                ImportedVpnProfile.Format.UNKNOWN -> "Unknown profile format"
            }
            wireGuardConfig = null
            if (profile.format == ImportedVpnProfile.Format.WIREGUARD || profile.format == ImportedVpnProfile.Format.AMNEZIAWG) {
                try {
                    wireGuardConfig = Config.parse(ByteArrayInputStream(profileText.toByteArray(Charsets.UTF_8)))
                } catch (e: Exception) {
                    showError("Invalid WireGuard profile", e.message ?: "The official WireGuard parser rejected this configuration.")
                }
            }
            connectButton.isEnabled = wireGuardConfig != null
            val endpointText = if (profile.endpointHints.isEmpty()) "No endpoint address detected" else profile.endpointHints.joinToString("\n")
            val details = buildString {
                append("Detected format: ").append(formatLabel).append("\n")
                if (profile.peerCount > 0) append("Peers: ").append(profile.peerCount).append("\n")
                append("Endpoint hints:\n").append(endpointText).append("\n\n")
                if (profile.hasInlineCredentials) append("This profile contains private keys or credentials. Keep it private.\n\n")
                if (wireGuardConfig != null && profile.format == ImportedVpnProfile.Format.AMNEZIAWG) append("AmneziaWG profile accepted by the integrated AWG-capable tunnel library. You can request Android VPN permission and connect.")
                else if (wireGuardConfig != null) append("WireGuard configuration validated. You can now request Android VPN permission and connect.")
                else if (profile.format == ImportedVpnProfile.Format.OPENVPN) append("OpenVPN profile recognized, but the OpenVPN engine is not integrated yet.")
                else append("No supported, valid WireGuard/AmneziaWG configuration was loaded.")
                append("\n\nProfile contents are kept in memory only by this build; they are not saved by MATRIX.")
            }
            status.text = if (wireGuardConfig != null) "$formatLabel profile ready\nVPN tunnel: disconnected"
            else "Profile inspected: $formatLabel\nVPN tunnel: disconnected"
            AlertDialog.Builder(this).setTitle("VPN profile processed").setMessage(details).setPositiveButton("OK", null).show()
        } catch (e: Exception) {
            showError("Could not read VPN profile", e.message ?: "The selected file could not be inspected.")
        }
    }
}