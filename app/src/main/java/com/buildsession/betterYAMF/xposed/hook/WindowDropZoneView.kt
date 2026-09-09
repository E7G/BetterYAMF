package com.buildsession.betterYAMF.xposed.hook

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

internal class WindowDropZoneView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        pathEffect = DashPathEffect(floatArrayOf(8 * density, 6 * density), 0f)
    }
    private val path = Path()
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        strokeCap = Paint.Cap.ROUND
    }
    var highlighted = false
        set(value) { if (field != value) { field = value; invalidate() } }

    override fun onDraw(canvas: Canvas) {
        val inset = 4 * density
        val radius = minOf(width, height).toFloat() - inset
        val oval = RectF(width - radius, -radius, width + radius, radius)
        fill.color = if (highlighted) Color.argb(122, 63, 180, 255) else Color.argb(45, 255, 255, 255)
        stroke.color = if (highlighted) Color.argb(245, 190, 236, 255) else Color.argb(180, 255, 255, 255)
        path.reset()
        path.moveTo(width.toFloat(), 0f)
        path.lineTo(width.toFloat(), radius)
        path.arcTo(oval, 90f, 90f)
        path.close()
        canvas.drawPath(path, fill)
        canvas.drawArc(oval, 90f, 90f, false, stroke)

        // Small overlapping-window glyph; readable without turning the corner into a panel.
        val cx = width - radius * .38f
        val cy = radius * .38f
        val size = 13 * density
        canvas.drawRoundRect(cx - size * .55f, cy - size * .45f,
            cx + size * .25f, cy + size * .35f, 2 * density, 2 * density, icon)
        canvas.drawRoundRect(cx - size * .15f, cy - size * .10f,
            cx + size * .65f, cy + size * .70f, 2 * density, 2 * density, icon)
    }
}
