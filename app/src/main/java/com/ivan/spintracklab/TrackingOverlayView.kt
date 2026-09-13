package com.ivan.spintracklab

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

class TrackingOverlayView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB3FFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE6E9ED.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC80CBC4.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val radialPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x9980CBC4.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 13f * resources.displayMetrics.scaledDensity
    }

    private var state = TrackingState()
    var onCenterSelected: ((Float, Float) -> Unit)? = null

    fun submitState(value: TrackingState) {
        state = value
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val content = contentRect(state.frameAspectRatioPortrait)
        val centre = normalizedToView(state.centerX, state.centerY, content)
        val radiusPx = min(content.width(), content.height()) * state.outerRadiusFraction

        canvas.drawCircle(centre.first, centre.second, radiusPx, guidePaint)
        canvas.drawCircle(centre.first, centre.second, radiusPx * state.innerRadiusRatio, guidePaint)

        val cross = 10f * density
        canvas.drawLine(centre.first - cross, centre.second, centre.first + cross, centre.second, centerPaint)
        canvas.drawLine(centre.first, centre.second - cross, centre.first, centre.second + cross, centerPaint)

        val points = state.trail
        if (points.size >= 2) {
            val path = Path()
            points.forEachIndexed { index, p ->
                val mapped = normalizedToView(p.x, p.y, content)
                if (index == 0) path.moveTo(mapped.first, mapped.second) else path.lineTo(mapped.first, mapped.second)
            }
            canvas.drawPath(path, trailPaint)
        }

        if (state.detected) {
            val p = normalizedToView(state.x, state.y, content)
            canvas.drawLine(centre.first, centre.second, p.first, p.second, radialPaint)
            canvas.drawCircle(p.first, p.second, 7f * density, ballPaint)
            canvas.drawCircle(p.first, p.second, 15f * density, guidePaint)
        }

        canvas.drawText("Tap disc centre to calibrate", content.left + 12f * density, content.top + 24f * density, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val content = contentRect(state.frameAspectRatioPortrait)
        if (!content.contains(event.x, event.y)) return true
        val nx = ((event.x - content.left) / content.width()).coerceIn(0f, 1f)
        val ny = ((event.y - content.top) / content.height()).coerceIn(0f, 1f)
        onCenterSelected?.invoke(nx, ny)
        performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun contentRect(frameAspect: Float): RectF {
        val safeAspect = frameAspect.coerceIn(0.25f, 4f)
        val viewW = width.toFloat().coerceAtLeast(1f)
        val viewH = height.toFloat().coerceAtLeast(1f)
        val viewAspect = viewW / viewH
        return if (viewAspect > safeAspect) {
            val contentW = viewH * safeAspect
            val left = (viewW - contentW) * 0.5f
            RectF(left, 0f, left + contentW, viewH)
        } else {
            val contentH = viewW / safeAspect
            val top = (viewH - contentH) * 0.5f
            RectF(0f, top, viewW, top + contentH)
        }
    }

    private fun normalizedToView(x: Float, y: Float, content: RectF): Pair<Float, Float> = Pair(
        content.left + x * content.width(),
        content.top + y * content.height()
    )
}
