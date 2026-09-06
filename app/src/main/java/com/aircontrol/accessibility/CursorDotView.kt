package com.aircontrol.accessibility

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

/** Desktop-style pointer rendered by the accessibility overlay. */
class CursorDotView(
    context: Context,
    private val dotSizePx: Int,
    private val ringSizePx: Int,
) : View(context) {

    private val density = resources.displayMetrics.density
    private val pointerWidth = dotSizePx.toFloat()
    private val pointerHeight = dotSizePx * 1.38f
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(85, 0, 0, 0) }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = maxOf(1.5f * density, 2f)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = maxOf(1.5f * density, 2f)
        alpha = 210
    }
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = maxOf(1.5f * density, 2f)
    }

    /** Thin, unobtrusive dwell-progress arc (replaces the old ring reuse). */
    private val dwellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = maxOf(2f * density, 3f)
        strokeCap = Paint.Cap.ROUND
    }
    private val dwellRadius: Float
        get() = ringSizePx * 0.85f

    /** Pointer tint while dragging (Windows-style drag feedback). */
    private val dragTint = Color.parseColor("#4DA3FF")
    private val pointerPath = Path()
    private val dwellRect = RectF()
    private var isHovering = false
    private var dwellProgress = 0f
    private var reducedMotion = false
    private var currentScale = 1f
    private var targetScale = 1f
    private var scaleAnimator: ValueAnimator? = null
    private var rippleAnimator: ValueAnimator? = null
    private var rippleProgress = 0f
    private var rippleAlpha = 0

    var isArmed: Boolean = false
        set(value) { field = value; invalidate() }

    /** Fix (user test): tint the arrow while a pinch-drag is in progress. */
    var isDragging: Boolean = false
        set(value) { field = value; invalidate() }

    private val moveResetRunnable = Runnable {
        targetScale = if (isHovering) 1.04f else 1f
        animateScale()
    }

    fun notifyMoving() {
        removeCallbacks(moveResetRunnable)
        targetScale = if (isHovering) 1.04f else 1f
        animateScale()
        postDelayed(moveResetRunnable, 140L)
    }

    fun notifyHover() {
        if (!isHovering) {
            isHovering = true
            targetScale = 1.04f
            animateScale()
        }
    }

    fun resetHover() {
        if (isHovering) {
            isHovering = false
            targetScale = 1f
            animateScale()
        }
    }

    fun notifyTap() {
        targetScale = 0.94f
        animateScale()
        postDelayed({ targetScale = if (isHovering) 1.04f else 1f; animateScale() }, 90L)
    }

    fun pulse() = ripple()

    fun ripple() {
        if (reducedMotion) return
        rippleAnimator?.cancel()
        rippleProgress = 0f
        rippleAlpha = 130
        rippleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                rippleProgress = it.animatedValue as Float
                rippleAlpha = (130f * (1f - rippleProgress)).toInt()
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
        dwellProgress = progress.coerceIn(0f, 1f)
        invalidate()
    }

    fun setReducedMotion(reduced: Boolean) {
        reducedMotion = reduced
        if (reduced) {
            scaleAnimator?.cancel()
            rippleAnimator?.cancel()
            currentScale = 1f
            targetScale = 1f
            rippleAlpha = 0
        }
        invalidate()
    }

    private fun animateScale() {
        if (reducedMotion || !isAttachedToWindow) {
            currentScale = targetScale
            invalidate()
            return
        }
        scaleAnimator?.cancel()
        scaleAnimator = ValueAnimator.ofFloat(currentScale, targetScale).apply {
            duration = 70L
            interpolator = DecelerateInterpolator()
            addUpdateListener { currentScale = it.animatedValue as Float; invalidate() }
        }.also { it.start() }
    }

    private fun buildPointerPath(cx: Float, cy: Float): Path {
        val w = pointerWidth
        val h = pointerHeight
        val left = cx - w * 0.42f
        val top = cy - h * 0.50f
        pointerPath.reset()
        pointerPath.moveTo(left, top)
        pointerPath.lineTo(left, top + h * 0.78f)
        pointerPath.lineTo(left + w * 0.27f, top + h * 0.61f)
        pointerPath.lineTo(left + w * 0.43f, top + h * 0.98f)
        pointerPath.lineTo(left + w * 0.60f, top + h * 0.91f)
        pointerPath.lineTo(left + w * 0.45f, top + h * 0.54f)
        pointerPath.lineTo(left + w * 0.80f, top + h * 0.54f)
        pointerPath.close()
        return pointerPath
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width * 0.43f
        val cy = height * 0.50f
        // Fix (audit #12): every "where the pointer is" affordance — dwell arc,
        // ripple, scale pivot — must centre on the visible arrow TIP, because
        // the tip is the point clicks actually land on (the overlay window is
        // positioned so the tip covers the dispatch point).
        val tipX = cx - pointerWidth * 0.42f
        val tipY = cy - pointerHeight * 0.50f
        canvas.save()
        canvas.scale(currentScale, currentScale, tipX, tipY)
        // Fix (user test: "aadha chand"/ring noise): tint the pointer while a
        // drag is in progress so the drag state is obvious without any ring.
        fillPaint.color = if (isDragging) dragTint else Color.WHITE
        val path = buildPointerPath(cx, cy)
        canvas.save()
        canvas.translate(1.5f * density, 1.5f * density)
        canvas.drawPath(path, shadowPaint)
        canvas.restore()
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, outlinePaint)

        // Fix (user test): the always-on "armed" ring around the tip was visual
        // noise (the half-moon) — removed. Armed state is already conveyed by
        // the status pill; the dwell arc below only appears while a dwell is
        // actually progressing, and the ripple only on click.
        if (dwellProgress > 0f) {
            dwellRect.set(tipX - dwellRadius, tipY - dwellRadius, tipX + dwellRadius, tipY + dwellRadius)
            canvas.drawArc(dwellRect, -90f, 360f * dwellProgress, false, dwellPaint)
        }
        if (rippleAlpha > 0) {
            ripplePaint.alpha = rippleAlpha
            canvas.drawCircle(tipX, tipY, ringSizePx * (0.7f + rippleProgress), ripplePaint)
        }
        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        scaleAnimator?.cancel()
        rippleAnimator?.cancel()
        removeCallbacks(moveResetRunnable)
        super.onDetachedFromWindow()
    }
}
