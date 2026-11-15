package org.matrixvpn

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Decorative-only Matrix-style globe; the node and trail never represent real location or VPN routing. */
class MatrixGlobeView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val spherePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.8f * density
        color = Color.argb(72, 35, 255, 126)
    }
    private val landPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(48, 40, 255, 112)
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        color = Color.argb(150, 55, 255, 130)
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(105, 91, 255, 142)
        textSize = 9f * density
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private var rotation = 0.0
    private var travel = 0.0
    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f
    private val landShapes = listOf(
        listOf(-168 to 70, -145 to 60, -128 to 52, -124 to 38, -110 to 28, -96 to 18, -82 to 25, -78 to 42, -62 to 50, -80 to 60, -105 to 72, -135 to 73),
        listOf(-82 to 12, -70 to 8, -58 to -5, -52 to -22, -63 to -54, -72 to -42, -78 to -18),
        listOf(-12 to 36, 5 to 44, 24 to 58, 42 to 52, 58 to 42, 42 to 34, 30 to 28, 18 to 35, 4 to 34),
        listOf(-17 to 34, 8 to 36, 32 to 30, 42 to 12, 34 to -10, 22 to -35, 8 to -34, -2 to -18, -16 to 2),
        listOf(38 to 58, 65 to 68, 100 to 62, 132 to 50, 150 to 38, 128 to 24, 110 to 8, 90 to 12, 75 to 25, 55 to 28),
        listOf(112 to -12, 135 to -10, 154 to -24, 145 to -42, 122 to -40, 112 to -25)
    )

    init { isFocusable = false }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h * 0.52f
        radius = minOf(w * 0.43f, h * 0.235f).coerceAtLeast(82f * density)
    }

    private data class ProjectedPoint(val x: Float, val y: Float, val depth: Float)

    private fun project(longitude: Double, latitude: Double, spin: Double): ProjectedPoint {
        val lat = latitude * PI / 180.0
        val lon = longitude * PI / 180.0 + spin
        val cosLat = cos(lat)
        return ProjectedPoint(
            centerX + radius * cosLat.toFloat() * sin(lon).toFloat(),
            centerY - radius * sin(lat).toFloat(),
            (cosLat * cos(lon)).toFloat()
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (radius <= 0f) return
        glowPaint.shader = RadialGradient(
            centerX, centerY, radius * 1.55f,
            intArrayOf(Color.argb(32, 0, 255, 92), Color.argb(10, 0, 180, 90), Color.TRANSPARENT),
            floatArrayOf(0f, 0.68f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(centerX, centerY, radius * 1.55f, glowPaint)
        glowPaint.shader = null

        spherePaint.shader = RadialGradient(
            centerX - radius * 0.28f, centerY - radius * 0.34f, radius * 1.45f,
            intArrayOf(Color.rgb(8, 62, 39), Color.rgb(3, 28, 24), Color.rgb(1, 12, 17)),
            floatArrayOf(0f, 0.58f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(centerX, centerY, radius, spherePaint)
        spherePaint.shader = null

        val clip = Path().apply { addCircle(centerX, centerY, radius - 1f, Path.Direction.CW) }
        val save = canvas.save()
        canvas.clipPath(clip)
        for (i in 0 until 12) {
            val angle = rotation + i * PI / 12.0
            val halfWidth = radius * abs(cos(angle)).toFloat().coerceAtLeast(0.045f)
            canvas.drawOval(RectF(centerX - halfWidth, centerY - radius, centerX + halfWidth, centerY + radius), gridPaint)
        }
        for (latitude in listOf(-66f, -45f, -22f, 0f, 22f, 45f, 66f)) {
            val y = centerY - radius * sin(latitude * PI / 180.0).toFloat()
            val cosLatitude = cos(latitude * PI / 180.0).toFloat()
            val h = (radius * cosLatitude * 0.12f).coerceAtLeast(2f)
            canvas.drawOval(RectF(centerX - radius, y - h, centerX + radius, y + h), gridPaint)
        }
        for (shape in landShapes) {
            val points = shape.map { (lon, lat) -> project(lon.toDouble(), lat.toDouble(), rotation) }
            if (points.all { it.depth > 0.08f }) {
                val path = Path().apply {
                    moveTo(points.first().x, points.first().y)
                    points.drop(1).forEach { lineTo(it.x, it.y) }
                    close()
                }
                canvas.drawPath(path, landPaint)
                canvas.drawPath(path, outlinePaint)
            }
        }

        val currentLon = travel * 360.0 - 180.0
        val currentLat = sin(travel * PI * 4.0) * 25.0
        val trail = mutableListOf<ProjectedPoint>()
        for (i in 46 downTo 0) {
            val t = travel - i * 0.0048
            trail.add(project(t * 360.0 - 180.0, sin(t * PI * 4.0) * 25.0, rotation))
        }
        for (i in 1 until trail.size) {
            val a = trail[i - 1]
            val b = trail[i]
            if (a.depth > 0f && b.depth > 0f) {
                trailPaint.color = Color.argb((28 + i * 4).coerceAtMost(220), 45, 255, 105)
                trailPaint.strokeWidth = (1.2f + i / 22f) * density
                canvas.drawLine(a.x, a.y, b.x, b.y, trailPaint)
            }
        }
        val node = project(currentLon, currentLat, rotation)
        if (node.depth > 0f) {
            glowPaint.color = Color.argb(100, 80, 255, 75)
            glowPaint.setShadowLayer(15f * density, 0f, 0f, Color.rgb(20, 255, 70))
            canvas.drawCircle(node.x, node.y, 7f * density, glowPaint)
            glowPaint.clearShadowLayer()
            dotPaint.color = Color.rgb(175, 255, 115)
            canvas.drawCircle(node.x, node.y, 3.2f * density, dotPaint)
        }
        canvas.restoreToCount(save)
        outlinePaint.color = Color.argb(165, 48, 255, 126)
        outlinePaint.strokeWidth = 1.1f * density
        canvas.drawCircle(centerX, centerY, radius, outlinePaint)
        labelPaint.textSize = 9f * density
        canvas.drawText("MATRIX  /  GLOBAL ROUTE", centerX - radius * 0.72f, centerY + radius + 18f * density, labelPaint)
        rotation += 0.0038
        travel = (travel + 0.00165) % 1.0
        postInvalidateDelayed(33L)
    }
}
