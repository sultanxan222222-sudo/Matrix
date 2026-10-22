package org.matrixvpn

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.matrixvpn.network.NetworkWatcher
import org.matrixvpn.profile.ImportedVpnProfile
import org.matrixvpn.profile.VpnEndpoint
import org.matrixvpn.profile.VpnProfileInspector

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var networkStatus: TextView
    private lateinit var endpointList: TextView
    private lateinit var networkWatcher: NetworkWatcher
    private val pickProfileRequest = 4102
    private val preferences by lazy { getSharedPreferences("matrix_endpoints", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = TextView(this).apply {
            text = "MATRIX VPN"
            textSize = 28f
            gravity = Gravity.CENTER
        }
        status = TextView(this).apply {
            text = "Development build\nRecovery engine: prototype\nVPN tunnel: not connected"
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
            text = "Import existing VPN profile"
            setOnClickListener { chooseVpnProfile() }
        }
        endpointList = TextView(this).apply {
            textSize = 14f
            setPadding(12, 12, 12, 12)
        }
        val help = TextView(this).apply {
            text = "Server addresses are saved locally as candidate hints. An address alone cannot authenticate or establish a VPN connection. WireGuard/OpenVPN profile import is preview-only until a tunnel engine is integrated."
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
            singleLine = true
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
            "Saved server candidates (tap and hold one to remove):\n" + entries.joinToString("\n")
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
                "application/x-openvpn-profile"
            ))
        }
        startActivityForResult(intent, pickProfileRequest)
    }

    @Deprecated("Uses the platform document picker result API for broad Android compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickProfileRequest || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        try {
            val profileText = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
                it.readText()
            } ?: throw IllegalArgumentException("Could not read the selected file.")

            val profile = VpnProfileInspector.inspect(profileText)
            val formatLabel = when (profile.format) {
                ImportedVpnProfile.Format.WIREGUARD -> "WireGuard"
                ImportedVpnProfile.Format.OPENVPN -> "OpenVPN"
                ImportedVpnProfile.Format.UNKNOWN -> "Unknown profile format"
            }
            val endpointText = if (profile.endpointHints.isEmpty()) {
                "No endpoint address detected"
            } else {
                profile.endpointHints.joinToString("\n")
            }
            val details = buildString {
                append("Detected format: ").append(formatLabel).append("\n")
                if (profile.peerCount > 0) append("Peers: ").append(profile.peerCount).append("\n")
                append("Endpoint hints:\n").append(endpointText).append("\n\n")
                if (profile.hasInlineCredentials) {
                    append("This profile appears to contain embedded keys or credentials. Keep it private.\n\n")
                }
                append("The file was inspected in memory and was not saved by MATRIX. ")
                append("This build cannot connect through the imported profile yet.")
            }
            status.text = "Profile inspected: $formatLabel\nVPN tunnel: not connected"
            AlertDialog.Builder(this)
                .setTitle("VPN profile imported for preview")
                .setMessage(details)
                .setPositiveButton("OK", null)
                .show()
        } catch (e: Exception) {
            AlertDialog.Builder(this)
                .setTitle("Could not read VPN profile")
                .setMessage(e.message ?: "The selected file could not be inspected.")
                .setPositiveButton("OK", null)
                .show()
        }
    }
}
