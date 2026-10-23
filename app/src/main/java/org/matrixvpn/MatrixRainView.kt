package org.matrixvpn

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.random.Random

/**
 * Short Matrix-style startup animation. It displays only the MATRIX wordmark
 * and falling code; the normal Russian-language app screen follows afterwards.
 */
class MatrixRainView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 255, 70)
        textSize = 22f
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 255, 70)
        textSize = 34f
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val symbols = "01アイウエオカキクケコサシスセソタチツテト"
    private var columns = 0
    private var drops = FloatArray(0)
    private var columnWidth = 0f

    init {
        setBackgroundColor(Color.BLACK)
        isFocusable = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        columnWidth = (paint.textSize * 1.15f).coerceAtLeast(14f)
        columns = (w / columnWidth).toInt().coerceAtLeast(1)
        drops = FloatArray(columns) { Random.nextInt(-h.coerceAtLeast(1), 0).toFloat() }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        for (i in 0 until columns) {
            val x = i * columnWidth
            val y = drops[i]
            paint.alpha = Random.nextInt(100, 256)
            canvas.drawText(symbols[Random.nextInt(symbols.length)].toString(), x, y, paint)
            drops[i] += paint.textSize * Random.nextFloat().coerceIn(0.7f, 1.8f)
            if (drops[i] > height + Random.nextInt(0, 250)) {
                drops[i] = Random.nextInt(-height.coerceAtLeast(1), 0).toFloat()
            }
        }
        titlePaint.textSize = (width * 0.12f).coerceIn(28f, 46f)
        canvas.drawText("MATRIX VPN", width / 2f, height / 2f, titlePaint)
        postInvalidateDelayed(55L)
    }
}
