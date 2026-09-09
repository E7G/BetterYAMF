package com.buildsession.betterYAMF.xposed.hook

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
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
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 15 * resources.configuration.fontScale * density
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT,
            android.graphics.Typeface.BOLD)
    }
    var highlighted = false
        set(value) { if (field != value) { field = value; invalidate() } }

    override fun onDraw(canvas: Canvas) {
        val inset = 10 * density
        val rect = RectF(inset, inset, width - inset, height - inset)
        val radius = 24 * density
        fill.color = if (highlighted) Color.argb(122, 63, 180, 255) else Color.argb(45, 255, 255, 255)
        stroke.color = if (highlighted) Color.argb(245, 190, 236, 255) else Color.argb(180, 255, 255, 255)
        canvas.drawRoundRect(rect, radius, radius, fill)
        canvas.drawRoundRect(rect, radius, radius, stroke)
        val y = rect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2
        canvas.drawText(if (highlighted) "松开进入小窗" else "小窗", rect.centerX(), y, textPaint)
    }
}
