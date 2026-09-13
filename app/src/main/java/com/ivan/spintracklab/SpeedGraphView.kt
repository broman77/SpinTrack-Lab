package com.ivan.spintracklab

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max

class SpeedGraphView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF62B5FF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x334D5967
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val zeroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x667D8792
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val samples = ArrayDeque<Float>()
    private val maxSamples = 90

    fun addSample(value: Float) {
        if (!value.isFinite()) return
        samples.addLast(value.coerceIn(-55f, 55f))
        while (samples.size > maxSamples) samples.removeFirst()
        postInvalidateOnAnimation()
    }

    fun clear() {
        samples.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.TRANSPARENT)
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val pad = 6f * density
        val left = pad
        val right = w - pad
        val top = pad
        val bottom = h - pad
        val mid = (top + bottom) * 0.5f

        for (i in 1..3) {
            val y = top + (bottom - top) * i / 4f
            canvas.drawLine(left, y, right, y, gridPaint)
        }
        canvas.drawLine(left, mid, right, mid, zeroPaint)

        if (samples.size < 2) return
        val values = samples.toList()
        var scale = 2f
        values.forEach { scale = max(scale, abs(it)) }
        scale = (scale * 1.12f).coerceAtMost(55f)

        val path = Path()
        values.forEachIndexed { index, value ->
            val x = left + (right - left) * index / max(1, values.size - 1).toFloat()
            val normalized = (value / scale).coerceIn(-1f, 1f)
            val y = mid - normalized * (bottom - top) * 0.43f
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, linePaint)
    }
}
