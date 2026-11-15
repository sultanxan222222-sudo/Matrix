package org.matrixvpn

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.TrafficStats
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.View
import kotlin.math.max

/** Compact live RX/TX rate meter for MATRIX's own Android UID. */
class TrafficMeterView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(208, 3, 19, 15) }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Color.argb(145, 205, 165, 83)
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(115, 255, 157)
        textSize = 8f * density
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 211, 119)
        textSize = 10f * density
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val handler = Handler(Looper.getMainLooper())
    private var previousRx = TrafficStats.getUidRxBytes(Process.myUid())
    private var previousTx = TrafficStats.getUidTxBytes(Process.myUid())
    private var previousAt = android.os.SystemClock.elapsedRealtime()
    private var rxRate = 0L
    private var txRate = 0L
    private val ticker = object : Runnable {
        override fun run() {
            updateRates()
            invalidate()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    private fun updateRates() {
        val now = android.os.SystemClock.elapsedRealtime()
        val rx = TrafficStats.getUidRxBytes(Process.myUid())
        val tx = TrafficStats.getUidTxBytes(Process.myUid())
        val elapsed = (now - previousAt).coerceAtLeast(1L)
        if (rx >= 0L && previousRx >= 0L) rxRate = max(0L, (rx - previousRx) * 1000L / elapsed)
        if (tx >= 0L && previousTx >= 0L) txRate = max(0L, (tx - previousTx) * 1000L / elapsed)
        previousRx = rx
        previousTx = tx
        previousAt = now
    }

    private fun rate(value: Long): String = when {
        value < 1024L -> "$value B/s"
        value < 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f KB/s", value / 1024.0)
        else -> String.format(java.util.Locale.US, "%.2f MB/s", value / (1024.0 * 1024.0))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((116f * density).toInt(), (51f * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect = RectF(0.5f * density, 0.5f * density, width - 0.5f * density, height - 0.5f * density)
        canvas.drawRoundRect(rect, 11f * density, 11f * density, backgroundPaint)
        canvas.drawRoundRect(rect, 11f * density, 11f * density, borderPaint)
        val left = 9f * density
        canvas.drawText("MATRIX LINK", left, 13f * density, titlePaint)
        canvas.drawText("↓ RX  ${rate(rxRate)}", left, 28f * density, valuePaint)
        canvas.drawText("↑ TX  ${rate(txRate)}", left, 42f * density, valuePaint)
    }
}
