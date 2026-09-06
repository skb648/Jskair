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
 * Native-like cursor renderer (accessibility overlay).
 *
 * This is NOT the OS-native pointer — it is a clean, desktop-like pointer drawn
 * by the accessibility overlay (see NativeLikeCursor.md). Design rules:
 *  - CLEAN/SHARP/DESKTOP-LIKE: white glyph + crisp black outline + soft shadow,
 *    standard pointer scale (~11×20dp arrow), no glow, no permanent ring,
 *    no crescent, no always-visible decoration.
 *  - EXPLICIT HOTSPOT: every glyph is built around (0,0) = its semantic point
 *    (arrow tip / index fingertip / beam centre / resize centre) and drawn
 *    translated to the view's anchor ([CursorGeometry.HOTSPOT_DP]). The
 *    overlay window is positioned so the anchor sits exactly on the logical
 *    cursor position — the click point is always the visible arrow tip.
 *  - CONTEXT ICONS: [icon] switches ARROW/HAND/IBEAM/resize; a switch is a
 *    plain redraw at the same anchor (no movement, no flicker animation).
 *  - Subtle click press + small ripple only on click; dwell progress is a thin
 *    arc UNDER the glyph, centred on the hotspot, drawn only while a dwell is
 *    actually progressing. Reduced motion disables press/ripple.
 */
class CursorDotView(
    context: Context,
    private val density: Float,
) : View(context) {

    /** Current pointer glyph; changing it never moves the hotspot. */
    var icon: CursorIcon = CursorIcon.ARROW
        set(value) {
            if (field != value) { field = value; invalidate() }
        }

    /** Kept for API compat; the armed ring was removed (user-test noise). */
    var isArmed: Boolean = false
        set(value) { field = value }

    /** Fix (user test): tint the glyph while a pinch-drag is in progress. */
    var isDragging: Boolean = false
        set(value) { if (field != value) { field = value; invalidate() } }

    // --- reused rendering objects (no per-frame allocation) ---
    private val glyphPath = Path()
    private val dwellRect = RectF()
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 0, 0, 0) }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = OUTLINE_DP * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val dwellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(215, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = DWELL_STROKE_DP * density
        strokeCap = Paint.Cap.ROUND
    }
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = outlinePaint.strokeWidth
    }

    /** Windows-style drag feedback tint. */
    private val dragTint = Color.parseColor("#4DA3FF")

    private var dwellProgress = 0f
    private var reducedMotion = false
    private var pressScale = 1f
    private var pressAnimator: ValueAnimator? = null
    private var rippleAnimator: ValueAnimator? = null
    private var rippleProgress = 0f
    private var rippleAlpha = 0

    // Hover/moving "breathing" scale was removed (spec §3: normal movement
    // shows only the pointer; hover feedback now comes from the ICON change).
    fun notifyMoving() = Unit
    fun notifyHover() = Unit
    fun resetHover() = Unit

    /** Subtle short press feedback (spec §12) — small, quick, hotspot-pivoted. */
    fun notifyTap() = press()

    fun pulse() = ripple()

    fun ripple() {
        if (reducedMotion) return
        rippleAnimator?.cancel()
        rippleProgress = 0f
        rippleAlpha = 120
        rippleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                rippleProgress = it.animatedValue as Float
                rippleAlpha = (120f * (1f - rippleProgress)).toInt()
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    rippleAlpha = 0
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

    private fun press() {
        if (reducedMotion || !isAttachedToWindow) return
        pressAnimator?.cancel()
        pressAnimator = ValueAnimator.ofFloat(1f, 0.92f, 1f).apply {
            duration = 120L
            interpolator = DecelerateInterpolator()
            addUpdateListener { pressScale = it.animatedValue as Float; invalidate() }
        }.also { it.start() }
    }

    // ---------------- glyph geometry (dp units around hotspot 0,0) ----------------

    private fun buildArrowPath() {
        // Classic desktop arrow, tip at (0,0): ~11.6 × 20.4 dp.
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
        // Pointing hand, index fingertip at (0,0): ~11 × 16.4 dp.
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
        // Classic I-beam, beam centre at (0,0): ~4.8 × 18 dp.
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
        // Horizontal ↔, centre at (0,0): ~19 × 5.6 dp. V/diagonals are this
        // path rotated about the hotspot.
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
            CursorIcon.RESIZE_HORIZONTAL -> buildDoubleArrowPath()
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

        // Dwell progress FIRST (under the glyph): a thin arc centred on the
        // hotspot — it never changes the pointer's shape, and the hotspot
        // point itself stays uncovered (the arc ring passes around it).
        if (dwellProgress > 0f) {
            val r = DWELL_RADIUS_DP * density
            dwellRect.set(anchor - r, anchor - r, anchor + r, anchor + r)
            canvas.drawArc(dwellRect, -90f, 360f * dwellProgress, false, dwellPaint)
        }

        buildGlyph()
        fillPaint.color = if (isDragging) dragTint else Color.WHITE

        canvas.save()
        canvas.scale(pressScale, pressScale, anchor, anchor)
        canvas.translate(anchor, anchor)
        val rotation = glyphRotationDegrees()
        if (rotation != 0f) canvas.rotate(rotation, 0f, 0f)

        // Soft shadow (offset copy), then white fill, then crisp outline —
        // readable on both light and dark backgrounds.
        canvas.save()
        canvas.translate(SHADOW_OFFSET_DP * density, SHADOW_OFFSET_DP * density)
        canvas.drawPath(glyphPath, shadowPaint)
        canvas.restore()
        canvas.drawPath(glyphPath, fillPaint)
        canvas.drawPath(glyphPath, outlinePaint)
        canvas.restore()

        // Click feedback: small, short, centred on the hotspot — never large
        // enough to obscure it.
        if (rippleAlpha > 0) {
            ripplePaint.alpha = rippleAlpha
            canvas.drawCircle(
                anchor,
                anchor,
                (RIPPLE_MIN_DP + (RIPPLE_MAX_DP - RIPPLE_MIN_DP) * rippleProgress) * density,
                ripplePaint,
            )
        }
    }

    override fun onDetachedFromWindow() {
        pressAnimator?.cancel()
        rippleAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val OUTLINE_DP = 1.1f
        private const val SHADOW_OFFSET_DP = 0.75f
        private const val DWELL_RADIUS_DP = 15f
        private const val DWELL_STROKE_DP = 2f
        private const val RIPPLE_MIN_DP = 8f
        private const val RIPPLE_MAX_DP = 13f
    }
}
