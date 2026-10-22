package org.matrixvpn

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.matrixvpn.profile.ImportedVpnProfile
import org.matrixvpn.profile.VpnProfileInspector

class MainActivity : Activity() {
    private lateinit var status: TextView
    private val pickProfileRequest = 4102

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = TextView(this).apply {
            text = "MATRIX VPN"
            textSize = 28f
            gravity = Gravity.CENTER
        }
        status = TextView(this).apply {
            text = "Development build\n\nRecovery engine: prototype\nVPN tunnel: not connected\n\nTraffic is not routed through a VPN in this build."
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
        }
        val importButton = Button(this).apply {
            text = "Import existing VPN profile"
            setOnClickListener { chooseVpnProfile() }
        }
        val help = TextView(this).apply {
            text = "Supports profile preview for WireGuard (.conf) and OpenVPN (.ovpn). A server address alone is not a complete VPN profile."
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(8, 12, 8, 12)
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
            addView(title)
            addView(status)
            addView(importButton)
            addView(help)
        })
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
