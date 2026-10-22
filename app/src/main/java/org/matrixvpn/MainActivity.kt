package org.matrixvpn

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val title = TextView(this).apply { text = "MATRIX VPN"; textSize = 28f; gravity = Gravity.CENTER }
        val status = TextView(this).apply {
            text = "Development build\n\nRecovery engine: prototype\nVPN tunnel: not connected\n\nTraffic is not routed through a VPN in this build."
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
            addView(title)
            addView(status)
        })
    }
}
