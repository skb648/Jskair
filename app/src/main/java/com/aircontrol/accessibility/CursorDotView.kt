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
        canvas.save()
        canvas.scale(currentScale, currentScale, cx, cy)
        val path = buildPointerPath(cx, cy)
        canvas.save()
        canvas.translate(1.5f * density, 1.5f * density)
        canvas.drawPath(path, shadowPaint)
        canvas.restore()
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, outlinePaint)

        if (isArmed) canvas.drawCircle(cx, cy, ringSizePx * 0.72f, ringPaint)
        if (dwellProgress > 0f) {
            val r = ringSizePx * 0.82f
            dwellRect.set(cx - r, cy - r, cx + r, cy + r)
            canvas.drawArc(dwellRect, -90f, 360f * dwellProgress, false, ringPaint)
        }
        if (rippleAlpha > 0) {
            ripplePaint.alpha = rippleAlpha
            canvas.drawCircle(cx, cy, ringSizePx * (0.7f + rippleProgress), ripplePaint)
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
