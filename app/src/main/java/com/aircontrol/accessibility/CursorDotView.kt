package com.aircontrol.accessibility

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * Apple Vision Pro Level Cursor - Premium Spatial Computing Design
 * 
 * Design Principles (from Apple Vision Pro):
 * - No blinking/pulsing animations that cause visual fatigue
 * - Smooth, stable cursor that feels "solid" and predictable
 * - Minimal visual feedback only on interaction (tap/hover)
 * - Zero jitter through proper filtering
 * 
 * Visual States:
 * - IDLE: Solid dot, no animation (stable, predictable)
 * - MOVING: Solid dot, trail effect (smooth motion feedback)
 * - HOVER: Slight scale up (1.05x) + glow (pre-interaction certainty)
 * - TAP: Quick scale down (0.95x) + haptic (immediate feedback)
 * - ARMED: Subtle ring indicator (system state clarity)
 */
class CursorDotView(
    context: Context,
    private val dotSizePx: Int,
    private val ringSizePx: Int,
) : View(context) {

    private val accentColor = android.graphics.Color.parseColor("#2F81F7")

    // ========== Paints ==========
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        style = Paint.Style.FILL
    }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        alpha = 0 // Start invisible, only show on hover
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        alpha = 120 // Subtle, not dominant
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        style = Paint.Style.FILL
        alpha = 0 // Start invisible
    }

    // ========== State Management ==========
    private var isMoving = false
    private var isHovering = false
    private var isTapping = false
    
    // Smooth scale animation (Apple Vision Pro style)
    private var currentScale = 1.0f
    private var targetScale = 1.0f
    
    // Glow animation
    private var currentGlowAlpha = 0
    private var targetGlowAlpha = 0
    
    // BUG #1 FIX: Track animators to prevent stacking
    private var scaleAnimator: ValueAnimator? = null
    private var glowAnimator: ValueAnimator? = null

    var isArmed: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    // ========== Movement Tracking ==========
    private val moveResetRunnable = Runnable {
        isMoving = false
        if (isAttachedToWindow) {
            // Smooth return to idle state
            targetScale = 1.0f
            targetGlowAlpha = 0
            animateScaleAndGlow()
        }
    }

    /**
     * Notify cursor is moving - triggers motion trail effect
     * NO BLINKING - just smooth motion feedback
     */
    fun notifyMoving() {
        if (!isMoving) {
            isMoving = true
            // Subtle motion feedback - slight scale increase
            targetScale = 1.02f
            targetGlowAlpha = 20 // Very subtle glow during motion
            animateScaleAndGlow()
        }
        removeCallbacks(moveResetRunnable)
        postDelayed(moveResetRunnable, IDLE_TIMEOUT_MS)
    }

    /**
     * Notify cursor is hovering over interactive element
     * Apple Vision Pro: Pre-interaction certainty through visual feedback
     */
    fun notifyHover() {
        if (!isHovering) {
            isHovering = true
            // Apple Vision Pro hover: 1.05x scale + outer glow
            targetScale = 1.05f
            targetGlowAlpha = 40 // Noticeable but not distracting
            animateScaleAndGlow()
        }
    }

    /**
     * Reset hover state when cursor leaves interactive element
     * BUG #9 FIX: Allows hover to be triggered again
     */
    fun resetHover() {
        if (isHovering) {
            isHovering = false
            targetScale = 1.0f
            targetGlowAlpha = 0
            animateScaleAndGlow()
        }
    }

    /**
     * Notify cursor tap/click action
     * Apple Vision Pro: Immediate tactile feedback through visual compression
     */
    fun notifyTap() {
        isTapping = true
        // Quick compression then spring back (0.95x -> 1.0x)
        targetScale = 0.95f
        animateScaleAndGlow()
        
        // Spring back after 100ms
        postDelayed({
            targetScale = 1.0f
            animateScaleAndGlow()
            isTapping = false
        }, 100)
    }

    /**
     * Visual pulse effect for gesture feedback
     * BUG #5 FIX: Uses internal scale animation instead of View scaling
     * to avoid conflicts with other animations
     */
    fun pulse() {
        // Quick expansion then return (1.0x -> 1.15x -> 1.0x)
        targetScale = 1.15f
        animateScaleAndGlow()
        
        postDelayed({
            targetScale = 1.0f
            animateScaleAndGlow()
        }, 150)
    }

    /**
     * Smooth animation using damped spring physics (Apple Vision Pro Layer 4)
     * BUG #1 FIX: Cancel previous animators before starting new ones
     */
    private fun animateScaleAndGlow() {
        if (!isAttachedToWindow) return
        
        // Cancel previous animators to prevent stacking
        scaleAnimator?.cancel()
        glowAnimator?.cancel()
        
        // Animate scale with spring physics
        scaleAnimator = ValueAnimator.ofFloat(currentScale, targetScale).apply {
            duration = 150 // Quick but smooth
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animation ->
                currentScale = animation.animatedValue as Float
                invalidate()
            }
        }.also { it.start() }

        // Animate glow alpha
        glowAnimator = ValueAnimator.ofInt(currentGlowAlpha, targetGlowAlpha).apply {
            duration = 150
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animation ->
                currentGlowAlpha = animation.animatedValue as Int
                glowPaint.alpha = currentGlowAlpha
                invalidate()
            }
        }.also { it.start() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // NO pulse animation - stable, predictable cursor
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(moveResetRunnable)
    }

    // ========== Cached Rendering ==========
    private var cachedGradient: RadialGradient? = null
    private var lastGradientWidth = 0
    private var lastGradientHeight = 0

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerX = width / 2f
        val centerY = height / 2f

        // Apply smooth scale transformation (Apple Vision Pro style)
        canvas.save()
        canvas.scale(currentScale, currentScale, centerX, centerY)

        // Draw hover glow (only when hovering or moving)
        if (currentGlowAlpha > 0) {
            val glowRadius = dotSizePx * 1.2f
            canvas.drawCircle(centerX, centerY, glowRadius, glowPaint)
        }

        // Draw soft shadow (always present for depth)
        val shadowRadius = dotSizePx * 0.8f
        if (width != lastGradientWidth || height != lastGradientHeight) {
            cachedGradient = RadialGradient(
                centerX, centerY, shadowRadius,
                android.graphics.Color.BLACK,
                android.graphics.Color.TRANSPARENT,
                Shader.TileMode.CLAMP,
            )
            lastGradientWidth = width
            lastGradientHeight = height
        }
        shadowPaint.shader = cachedGradient
        shadowPaint.alpha = 30 // Subtle depth
        canvas.drawCircle(centerX, centerY + 2f, shadowRadius, shadowPaint) // Offset for depth

        // Draw armed ring (subtle system state indicator)
        if (isArmed) {
            canvas.drawCircle(centerX, centerY, ringSizePx * 0.9f, ringPaint)
        }

        // Draw cursor dot (solid, stable, no blinking)
        val dotRadius = dotSizePx * 0.5f
        canvas.drawCircle(centerX, centerY, dotRadius, dotPaint)

        canvas.restore()
    }

    companion object {
        // Apple Vision Pro: NO pulse duration - stable cursor
        private const val IDLE_TIMEOUT_MS = 150L // Quick return to idle
    }
}
