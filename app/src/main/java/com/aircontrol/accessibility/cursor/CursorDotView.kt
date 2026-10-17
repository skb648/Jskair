package com.aircontrol.accessibility.cursor

import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Precision HUD + Desktop-Class Cursor Renderer (accessibility overlay).
 *
 * Combines a razor-sharp, desktop-grade pointer (with explicit hotspot alignment
 * at [CursorGeometry.HOTSPOT_DP]) with an Iron Man / JARVIS tactical HUD reticle:
 *  - EXPLICIT HOTSPOT: every glyph is built around (0,0) = its semantic point
 *    (arrow tip / index fingertip / beam centre / resize centre) and drawn
 *    translated to the view's anchor ([CursorGeometry.HOTSPOT_DP]).
 *  - PINCH TENSION GAUGE: as thumb and index fingertips converge ([pinchProximity]
 *    0..1), a segmented cyan/gold tactical ring contracts smoothly toward the
 *    hotspot, giving instant visual feedback before the click fires.
 *  - TARGET LOCK & CLICK SHOCKWAVE: hovering interactive elements illuminates a
 *    subtle magnetic lock halo; clicking triggers a crisp dual-ring shockwave.
 *  - ZERO ALLOCATION ON DRAW: all [Path], [Paint], and [RectF] instances are
 *    pre-allocated and reused across frames.
 */
class CursorDotView(
    context: Context,
    private val density: Float,
) : View(context) {

    /** Current pointer glyph; changing it never moves the hotspot. */
    var icon: CursorIcon = CursorIcon.ARROW
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** True when the gesture engine is ARMED and actively listening. */
    var isArmed: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** Tint the glyph and reticle while a pinch-drag is in progress. */
    var isDragging: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** Normalized pinch tension (0.0 = fingers open, 1.0 = fingertips touching). */
    var pinchProximity: Float = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (kotlin.math.abs(field - clamped) >= 0.015f || (clamped == 0f && field != 0f)) {
                field = clamped
                invalidate()
            }
        }

    // --- reused rendering objects (no per-frame allocation) ---
    private val glyphPath = Path()
    private val dwellRect = RectF()
    private val hudRect = RectF()

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 0, 0, 0)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0A0E17")
        style = Paint.Style.STROKE
        strokeWidth = OUTLINE_DP * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val hudRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = HUD_RING_STROKE_DP * density
        strokeCap = Paint.Cap.ROUND
    }
    private val hudGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.FILL
    }
    private val dwellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = DWELL_STROKE_DP * density
        strokeCap = Paint.Cap.ROUND
    }
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
    }

    private val dragTint = Color.parseColor("#00E5FF")
    private val hoverLockTint = Color.parseColor("#38BDF8")
    private val pinchReadyTint = Color.parseColor("#FFCA28")

    private var dwellProgress = 0f
    private var reducedMotion = false
    private var isHoveringTarget = false
    private var pressScale = 1f
    private var pressAnimator: ValueAnimator? = null
    private var rippleAnimator: ValueAnimator? = null
    private var rippleProgress = 0f
    private var rippleAlpha = 0

    fun notifyMoving() = Unit

    fun notifyHover() {
        if (!isHoveringTarget) {
            isHoveringTarget = true
            invalidate()
        }
    }

    fun resetHover() {
        if (isHoveringTarget) {
            isHoveringTarget = false
            invalidate()
        }
    }

    fun notifyTap() = ripple()

    fun pulse() = ripple()

    fun ripple() {
        if (reducedMotion) return
        rippleAnimator?.cancel()
        pressAnimator?.cancel()
        rippleProgress = 0f
        rippleAlpha = 210
        pressScale = 0.88f

        pressAnimator = ValueAnimator.ofFloat(0.88f, 1.0f).apply {
            duration = 180L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                pressScale = it.animatedValue as Float
            }
            start()
        }

        rippleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                rippleProgress = it.animatedValue as Float
                rippleAlpha = (210f * (1f - rippleProgress)).toInt()
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    rippleAlpha = 0
                    pressScale = 1f
                    invalidate()
                }
            })
        }.also { it.start() }
    }

    fun setDwellProgress(progress: Float) {
        val clamped = progress.coerceIn(0f, 1f)
        if (clamped == dwellProgress) return
        dwellProgress = clamped
        invalidate()
    }

    fun setReducedMotion(reduced: Boolean) {
        reducedMotion = reduced
        if (reduced) {
            pressAnimator?.cancel()
            rippleAnimator?.cancel()
            pressScale = 1f
            rippleAlpha = 0
        }
        invalidate()
    }

    // ---------------- glyph geometry (dp units around hotspot 0,0) ----------------

    private fun buildArrowPath() {
        glyphPath.reset()
        glyphPath.moveTo(0f, 0f)
        glyphPath.lineTo(0f, 18.4f * density)
        glyphPath.lineTo(3.9f * density, 14.6f * density)
        glyphPath.lineTo(6.3f * density, 20.4f * density)
        glyphPath.lineTo(8.6f * density, 19.3f * density)
        glyphPath.lineTo(6.2f * density, 13.5f * density)
        glyphPath.lineTo(11.6f * density, 13.5f * density)
        glyphPath.close()
    }

    private fun buildHandPath() {
        val d = density
        glyphPath.reset()
        glyphPath.moveTo(0f, 0f)
        glyphPath.lineTo(1.8f * d, 0f)
        glyphPath.lineTo(1.8f * d, 6.2f * d)
        glyphPath.lineTo(3.0f * d, 6.2f * d)
        glyphPath.lineTo(3.0f * d, 3.4f * d)
        glyphPath.lineTo(4.6f * d, 3.4f * d)
        glyphPath.lineTo(4.6f * d, 6.6f * d)
        glyphPath.lineTo(5.8f * d, 6.6f * d)
        glyphPath.lineTo(5.8f * d, 4.6f * d)
        glyphPath.lineTo(7.4f * d, 4.6f * d)
        glyphPath.lineTo(7.4f * d, 7.0f * d)
        glyphPath.lineTo(8.6f * d, 7.0f * d)
        glyphPath.lineTo(8.6f * d, 9.0f * d)
        glyphPath.lineTo(11.0f * d, 9.0f * d)
        glyphPath.lineTo(11.0f * d, 13.6f * d)
        glyphPath.lineTo(8.4f * d, 16.4f * d)
        glyphPath.lineTo(3.4f * d, 16.4f * d)
        glyphPath.lineTo(1.2f * d, 13.8f * d)
        glyphPath.lineTo(0f, 10.6f * d)
        glyphPath.close()
    }

    private fun buildIBeamPath() {
        val d = density
        glyphPath.reset()
        glyphPath.moveTo(-2.4f * d, -9.0f * d)
        glyphPath.lineTo(2.4f * d, -9.0f * d)
        glyphPath.lineTo(2.4f * d, -7.6f * d)
        glyphPath.lineTo(0.6f * d, -7.6f * d)
        glyphPath.lineTo(0.6f * d, 7.6f * d)
        glyphPath.lineTo(2.4f * d, 7.6f * d)
        glyphPath.lineTo(2.4f * d, 9.0f * d)
        glyphPath.lineTo(-2.4f * d, 9.0f * d)
        glyphPath.lineTo(-2.4f * d, 7.6f * d)
        glyphPath.lineTo(-0.6f * d, 7.6f * d)
        glyphPath.lineTo(-0.6f * d, -7.6f * d)
        glyphPath.lineTo(-2.4f * d, -7.6f * d)
        glyphPath.close()
    }

    private fun buildDoubleArrowPath() {
        val d = density
        glyphPath.reset()
        glyphPath.moveTo(-9.5f * d, 0f)
        glyphPath.lineTo(-5.2f * d, -2.8f * d)
        glyphPath.lineTo(-5.2f * d, -1.0f * d)
        glyphPath.lineTo(5.2f * d, -1.0f * d)
        glyphPath.lineTo(5.2f * d, -2.8f * d)
        glyphPath.lineTo(9.5f * d, 0f)
        glyphPath.lineTo(5.2f * d, 2.8f * d)
        glyphPath.lineTo(5.2f * d, 1.0f * d)
        glyphPath.lineTo(-5.2f * d, 1.0f * d)
        glyphPath.lineTo(-5.2f * d, 2.8f * d)
        glyphPath.close()
    }

    private fun buildGlyph() {
        when (icon) {
            CursorIcon.ARROW -> buildArrowPath()
            CursorIcon.HAND -> buildHandPath()
            CursorIcon.IBEAM -> buildIBeamPath()
            CursorIcon.RESIZE_HORIZONTAL,
            CursorIcon.RESIZE_VERTICAL,
            CursorIcon.RESIZE_DIAGONAL_1,
            CursorIcon.RESIZE_DIAGONAL_2,
            -> buildDoubleArrowPath()
        }
    }

    private fun glyphRotationDegrees(): Float = when (icon) {
        CursorIcon.RESIZE_VERTICAL -> 90f
        CursorIcon.RESIZE_DIAGONAL_1 -> 45f
        CursorIcon.RESIZE_DIAGONAL_2 -> -45f
        else -> 0f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val anchor = CursorGeometry.hotspotPx(density)

        // 1. Tactical HUD Reticle & Pinch Tension Ring (centered on hotspot)
        val showHudRing = isArmed || pinchProximity > 0.05f || isHoveringTarget || icon == CursorIcon.HAND
        if (showHudRing) {
            val baseRadius = HUD_BASE_RADIUS_DP * density
            // Contract the HUD ring smoothly as thumb & index finger converge
            val contractedRadius = baseRadius * (1f - 0.42f * pinchProximity)
            hudRect.set(
                anchor - contractedRadius,
                anchor - contractedRadius,
                anchor + contractedRadius,
                anchor + contractedRadius,
            )

            // Subtle core halo when hovering a clickable element or pinching
            if (isHoveringTarget || icon == CursorIcon.HAND || pinchProximity > 0.2f) {
                hudGlowPaint.color = if (pinchProximity > 0.75f) pinchReadyTint else hoverLockTint
                hudGlowPaint.alpha = (28 + (52 * pinchProximity).toInt()).coerceIn(20, 85)
                canvas.drawCircle(anchor, anchor, contractedRadius * 0.85f, hudGlowPaint)
            }

            // Segmented Iron Man arc-reactor brackets
            hudRingPaint.color = when {
                isDragging -> dragTint
                pinchProximity > 0.8f -> pinchReadyTint
                else -> hoverLockTint
            }
            val baseAlpha = if (isHoveringTarget || icon == CursorIcon.HAND || pinchProximity > 0.1f) 210 else 115
            hudRingPaint.alpha = baseAlpha

            val sweepAngle = 52f + 34f * pinchProximity
            val rotOffset = pinchProximity * 45f
            for (q in 0 until 4) {
                val startAngle = q * 90f + (45f - sweepAngle * 0.5f) + rotOffset
                canvas.drawArc(hudRect, startAngle, sweepAngle, false, hudRingPaint)
            }
        }

        // 2. Dwell progress arc (centered on hotspot)
        if (dwellProgress > 0f) {
            val r = DWELL_RADIUS_DP * density
            dwellRect.set(anchor - r, anchor - r, anchor + r, anchor + r)
            canvas.drawArc(dwellRect, -90f, 360f * dwellProgress, false, dwellPaint)
        }

        // 3. Crisp pointer glyph at exact hotspot (0,0) -> (anchor, anchor)
        buildGlyph()
        fillPaint.color = when {
            isDragging -> dragTint
            pinchProximity > 0.85f -> pinchReadyTint
            else -> Color.WHITE
        }

        canvas.save()
        canvas.scale(pressScale, pressScale, anchor, anchor)
        canvas.translate(anchor, anchor)
        val rotation = glyphRotationDegrees()
        if (rotation != 0f) canvas.rotate(rotation, 0f, 0f)

        canvas.save()
        canvas.translate(SHADOW_OFFSET_DP * density, SHADOW_OFFSET_DP * density)
        canvas.drawPath(glyphPath, shadowPaint)
        canvas.restore()
        canvas.drawPath(glyphPath, fillPaint)
        canvas.drawPath(glyphPath, outlinePaint)
        canvas.restore()

        // 4. Dual-ring shockwave click feedback centered on hotspot
        if (rippleAlpha > 0) {
            val outerRadius = (RIPPLE_MIN_DP + (RIPPLE_MAX_DP - RIPPLE_MIN_DP) * rippleProgress) * density
            ripplePaint.color = dragTint
            ripplePaint.alpha = rippleAlpha
            canvas.drawCircle(anchor, anchor, outerRadius, ripplePaint)

            val innerRadius = outerRadius * 0.62f
            ripplePaint.color = Color.WHITE
            ripplePaint.alpha = (rippleAlpha * 0.75f).toInt()
            canvas.drawCircle(anchor, anchor, innerRadius, ripplePaint)
        }
    }

    override fun onDetachedFromWindow() {
        pressAnimator?.cancel()
        rippleAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val OUTLINE_DP = 1.15f
        private const val SHADOW_OFFSET_DP = 0.85f
        private const val HUD_BASE_RADIUS_DP = 13.5f
        private const val HUD_RING_STROKE_DP = 1.5f
        private const val DWELL_RADIUS_DP = 16.5f
        private const val DWELL_STROKE_DP = 2.2f
        private const val RIPPLE_MIN_DP = 6f
        private const val RIPPLE_MAX_DP = 16f
    }
}
