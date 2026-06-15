package com.aircontrol.accessibility

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View

/**
 * Custom View that renders the cursor dot for the accessibility overlay.
 *
 * Visual design:
 * - 24dp accent-colored (ElectricBlue #2F81F7) dot
 * - Soft radial gradient shadow
 * - Subtle idle pulse animation when not moving
 * - Small ring around cursor when state machine is ARMED
 */
class CursorDotView(
    context: Context,
    private val dotSizePx: Int,
    private val ringSizePx: Int,
) : View(context) {

    private val accentColor = android.graphics.Color.parseColor("#2F81F7")

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        style = Paint.Style.FILL
    }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        alpha = 60
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
        alpha = 180
    }

    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        style = Paint.Style.FILL
        alpha = 30
    }

    // Pulse animation
    private var pulseRadius = 0f
    private var pulseAlpha = 30

    // M-12: Only animate pulse when cursor is idle
    private var isMoving = false
    private val moveResetRunnable = Runnable {
        isMoving = false
        if (isAttachedToWindow) pulseAnimator.resume()
    }

    private val pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = PULSE_DURATION_MS
        repeatMode = ValueAnimator.RESTART
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { animation ->
            val fraction = animation.animatedValue as Float
            pulseRadius = dotSizePx * 0.5f + dotSizePx * fraction * 0.5f
            pulseAlpha = (30 * (1f - fraction)).toInt()
            invalidate()
        }
    }

    var isArmed: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Notifies the view that the cursor is moving.
     * Pauses the idle pulse animation and schedules a resume after 1 second of no movement.
     */
    fun notifyMoving() {
        if (!isMoving) {
            isMoving = true
            pulseAnimator.pause()
        }
        removeCallbacks(moveResetRunnable)
        postDelayed(moveResetRunnable, IDLE_TIMEOUT_MS)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        pulseAnimator.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(moveResetRunnable)
        pulseAnimator.cancel()
    }

    // M-13: Cached RadialGradient — only recreate when view size changes
    private var cachedGradient: RadialGradient? = null
    private var lastGradientWidth = 0
    private var lastGradientHeight = 0

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerX = width / 2f
        val centerY = height / 2f

        // Draw idle pulse (only when not moving — M-12)
        if (!isMoving && pulseAlpha > 0) {
            pulsePaint.alpha = pulseAlpha
            canvas.drawCircle(centerX, centerY, pulseRadius, pulsePaint)
        }

        // Draw soft shadow — M-13: cache gradient, only recreate when size changes
        val shadowRadius = dotSizePx * 0.7f
        if (width != lastGradientWidth || height != lastGradientHeight) {
            cachedGradient = RadialGradient(
                centerX, centerY, shadowRadius,
                accentColor,
                android.graphics.Color.TRANSPARENT,
                Shader.TileMode.CLAMP,
            )
            lastGradientWidth = width
            lastGradientHeight = height
        }
        shadowPaint.shader = cachedGradient
        shadowPaint.alpha = 40
        canvas.drawCircle(centerX, centerY, shadowRadius, shadowPaint)

        // Draw armed ring
        if (isArmed) {
            canvas.drawCircle(centerX, centerY, ringSizePx * 0.8f, ringPaint)
        }

        // Draw cursor dot
        val dotRadius = dotSizePx * 0.4f
        canvas.drawCircle(centerX, centerY, dotRadius, dotPaint)
    }

    companion object {
        private const val PULSE_DURATION_MS = 2000L
        private const val IDLE_TIMEOUT_MS = 1000L
    }
}
