package com.aircontrol.accessibility

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.aircontrol.accessibility.cursor.CursorDotView
import com.aircontrol.accessibility.cursor.CursorGeometry
import com.aircontrol.accessibility.cursor.CursorIcon
import com.aircontrol.accessibility.cursor.CursorVisibilityAction
import com.aircontrol.accessibility.cursor.CursorVisibilityStateMachine
import timber.log.Timber
import kotlin.math.abs
import kotlin.math.exp

/**
 * Zero-IPC, VSYNC-locked (60–120Hz) Accessibility Cursor Overlay.
 *
 * Architectural Upgrade (Butter-Smooth Iron Man HUD):
 *  - ZERO PER-FRAME BINDER IPC: Instead of calling [WindowManager.updateViewLayout]
 *    on every frame (which crosses Binder IPC into `system_server` and triggers
 *    `SurfaceFlinger` window relayouts, causing frame drops), the overlay attaches
 *    one full-screen touch-passthrough container and moves [CursorDotView] via
 *    GPU-accelerated sub-pixel [View.setTranslationX] / [View.setTranslationY].
 *  - 120Hz VSYNC INTERPOLATION: Camera frames arrive at 20–30 FPS; a lightweight
 *    [Choreographer.FrameCallback] smoothly interpolates display coordinates at the
 *    panel's native refresh rate and automatically unschedules itself the instant
 *    the pointer settles within 0.15 px of the target (zero idle CPU).
 *  - EXPLICIT HOTSPOT CONTRACT: Logical cursor position `(currentScreenX, currentScreenY)`
 *    is always aligned with [CursorGeometry.hotspotPx] so clicks, hover, and visual
 *    pointer tip match to the sub-pixel.
 */
class CursorOverlay(
    private val context: Context,
    private var screenWidth: Int,
    private var screenHeight: Int,
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = context.resources.displayMetrics.density

    private var containerView: FrameLayout? = null
    private var cursorView: CursorDotView? = null
    private var isAdded = false
    private var isVisible = false

    private val visibilityMachine = CursorVisibilityStateMachine()
    private var animationGeneration = 0L

    // Logical target cursor position in screen pixels
    private var currentScreenX = 0f
    private var currentScreenY = 0f

    // VSYNC-interpolated visual position in screen pixels (for 120Hz smoothness)
    private var renderedScreenX = 0f
    private var renderedScreenY = 0f
    private var hasInitialized = false
    private var vsyncRunning = false
    private var lastFrameNanos = 0L

    /**
     * Notified with the APPLIED logical position (screen px) on position updates.
     * The accessibility service feeds this into the hover resolver. Invoked on the main thread.
     */
    var onPositionApplied: ((x: Float, y: Float) -> Unit)? = null

    private var lastHoverNotifyX = -100f
    private var lastHoverNotifyY = -100f

    /** Current pointer glyph (rendered by CursorDotView at the same hotspot). */
    private var icon: CursorIcon = CursorIcon.ARROW

    private val hideDelayMs = 180L
    private var reducedMotion = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!vsyncRunning || !isAdded) {
                vsyncRunning = false
                return
            }
            val dtSec = if (lastFrameNanos > 0L) {
                ((frameTimeNanos - lastFrameNanos) * 1e-9f).coerceIn(0.001f, 0.050f)
            } else {
                0.008f
            }
            lastFrameNanos = frameTimeNanos

            val dx = currentScreenX - renderedScreenX
            val dy = currentScreenY - renderedScreenY

            if (reducedMotion || (abs(dx) < SETTLE_EPSILON_PX && abs(dy) < SETTLE_EPSILON_PX)) {
                renderedScreenX = currentScreenX
                renderedScreenY = currentScreenY
                applyTranslation(renderedScreenX, renderedScreenY)
                vsyncRunning = false
                lastFrameNanos = 0L
                return
            }

            // Exponential frame-rate-independent smoothing (tau ≈ 18ms -> crisp 120Hz glide)
            val alpha = 1f - exp(-dtSec * INTERP_SPEED)
            renderedScreenX += dx * alpha
            renderedScreenY += dy * alpha
            applyTranslation(renderedScreenX, renderedScreenY)

            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun setArmed(armed: Boolean) {
        cursorView?.isArmed = armed
    }

    fun setDragging(dragging: Boolean) {
        cursorView?.isDragging = dragging
    }

    fun setPinchProximity(proximity: Float) {
        cursorView?.pinchProximity = proximity
    }

    fun setCursorIcon(icon: CursorIcon) {
        if (icon == this.icon) return
        this.icon = icon
        cursorView?.icon = icon
    }

    fun updatePosition(
        normX: Float,
        normY: Float,
        screenW: Int,
        screenH: Int,
        directMapping: Boolean = false,
    ) {
        if (!isAdded) {
            addView()
            if (!isAdded) return
        }

        if (screenW > 0 && screenH > 0 && (screenW != screenWidth || screenH != screenHeight)) {
            updateScreenSize(screenW, screenH)
        }

        val targetX = if (directMapping) {
            ActionDispatcher.normalizeDirect(normX, screenWidth)
        } else {
            ActionDispatcher.normalizeToScreenX(normX, screenWidth)
        }
        val targetY = if (directMapping) {
            ActionDispatcher.normalizeDirect(normY, screenHeight)
        } else {
            ActionDispatcher.normalizeToScreenY(normY, screenHeight, screenWidth)
        }

        if (!CursorGeometry.isUsable(targetX, targetY)) return

        currentScreenX = CursorGeometry.clampToScreen(targetX, screenWidth)
        currentScreenY = CursorGeometry.clampToScreen(targetY, screenHeight)

        if (!hasInitialized) {
            hasInitialized = true
            renderedScreenX = currentScreenX
            renderedScreenY = currentScreenY
            applyTranslation(renderedScreenX, renderedScreenY)
        } else if (reducedMotion) {
            renderedScreenX = currentScreenX
            renderedScreenY = currentScreenY
            applyTranslation(renderedScreenX, renderedScreenY)
        } else {
            startVsyncLoopIfNeeded()
        }

        if (abs(currentScreenX - lastHoverNotifyX) >= 0.5f || abs(currentScreenY - lastHoverNotifyY) >= 0.5f) {
            lastHoverNotifyX = currentScreenX
            lastHoverNotifyY = currentScreenY
            onPositionApplied?.invoke(currentScreenX, currentScreenY)
        }

        if (!visibilityMachine.isEffectivelyVisible) show()
    }

    fun show() {
        if (!isAdded) {
            addView()
        }
        when (visibilityMachine.onShowRequest()) {
            CursorVisibilityAction.START_FADE_IN -> {
                cursorView?.apply {
                    alpha = 0f
                    visibility = View.VISIBLE
                }
                animateAlpha(1f, hideDelayMs) {
                    visibilityMachine.onFadeInCompleted()
                    syncVisibilityState()
                }
            }
            CursorVisibilityAction.CANCEL_AND_RESTORE -> {
                cancelViewAnimation()
                cursorView?.apply {
                    alpha = 1f
                    visibility = View.VISIBLE
                }
            }
            else -> Unit
        }
        syncVisibilityState()
    }

    fun hide() {
        stopVsyncLoop()
        if (visibilityMachine.onHideRequest() == CursorVisibilityAction.START_FADE_OUT) {
            animateAlpha(0f, hideDelayMs) {
                if (visibilityMachine.onFadeOutCompleted() == CursorVisibilityAction.MAKE_INVISIBLE) {
                    cursorView?.visibility = View.INVISIBLE
                }
                syncVisibilityState()
            }
        }
        syncVisibilityState()
    }

    fun updateScreenSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        screenWidth = width
        screenHeight = height
    }

    fun pulse() {
        cursorView?.pulse()
    }

    fun notifyHover() {
        cursorView?.notifyHover()
    }

    fun resetHover() {
        cursorView?.resetHover()
    }

    fun notifyTap() {
        cursorView?.notifyTap()
    }

    fun ripple() {
        cursorView?.ripple()
    }

    fun setDwellProgress(progress: Float) {
        cursorView?.setDwellProgress(progress)
    }

    fun setReducedMotion(reduced: Boolean) {
        reducedMotion = reduced
        cursorView?.setReducedMotion(reduced)
    }

    fun remove() {
        visibilityMachine.onRemoved()
        stopVsyncLoop()
        cancelViewAnimation()
        try {
            containerView?.let { windowManager.removeView(it) }
        } catch (_: Exception) {
            // View not attached
        }
        containerView = null
        cursorView = null
        isAdded = false
        isVisible = false
        hasInitialized = false
    }

    // ========== Private implementation ==========

    @SuppressLint("ClickableViewAccessibility")
    private fun addView() {
        if (isAdded) return

        val dot = CursorDotView(context, density).apply {
            icon = this@CursorOverlay.icon
            setReducedMotion(reducedMotion)
        }
        val size = CursorGeometry.viewSizePx(density)
        val container = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            addView(dot, FrameLayout.LayoutParams(size, size))
        }

        val params = createLayoutParams()
        try {
            windowManager.addView(container, params)
            containerView = container
            cursorView = dot
            isAdded = true
            applyTranslation(renderedScreenX, renderedScreenY)
        } catch (e: Exception) {
            Timber.e("Failed to add cursor overlay: %s", e.message)
        }
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            x = 0
            y = 0
        }
    }

    private fun applyTranslation(screenX: Float, screenY: Float) {
        val view = cursorView ?: return
        val hotspot = CursorGeometry.hotspotPx(density)
        view.translationX = screenX - hotspot
        view.translationY = screenY - hotspot
    }

    private fun startVsyncLoopIfNeeded() {
        if (vsyncRunning) return
        vsyncRunning = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopVsyncLoop() {
        if (!vsyncRunning) return
        vsyncRunning = false
        lastFrameNanos = 0L
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    private fun syncVisibilityState() {
        isVisible = visibilityMachine.isEffectivelyVisible
    }

    private fun cancelViewAnimation() {
        animationGeneration++
        cursorView?.animate()?.cancel()
    }

    private fun animateAlpha(targetAlpha: Float, fadeDurationMs: Long, onFinished: () -> Unit) {
        val view = cursorView ?: return
        cancelViewAnimation()
        val generation = ++animationGeneration
        view.animate()
            .alpha(targetAlpha)
            .setDuration(fadeDurationMs)
            .withEndAction {
                if (generation == animationGeneration) onFinished()
            }
            .start()
    }

    companion object {
        private const val SETTLE_EPSILON_PX = 0.15f
        private const val INTERP_SPEED = 55f
    }
}
