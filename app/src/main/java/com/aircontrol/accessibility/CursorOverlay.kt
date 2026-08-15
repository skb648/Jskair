package com.aircontrol.accessibility

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import timber.log.Timber

/**
 * Accessibility overlay that renders the cursor dot on screen.
 * Uses TYPE_ACCESSIBILITY_OVERLAY so it works without SYSTEM_ALERT_WINDOW
 * when the accessibility service is enabled.
 *
 * Cursor rendering:
 * - 24dp accent-colored dot with soft shadow
 * - State-aware scale/glow feedback for movement and interaction
 * - Small ring around cursor when state machine is ARMED
 * - 200ms fade-out when hand is lost
 * - Minimal dead-zone to prevent drift at tiny movements
 * - Low-latency direct position update (no exponential smoothing on top of One Euro)
 * - Full screen coverage with proper coordinate mapping for all aspect ratios
 *   including Android 17 edge-to-edge and cutout handling
 * - Front camera mirroring applied via ActionDispatcher coordinate mapping
 */
class CursorOverlay(
    private val context: Context = context.applicationContext,
    private var screenWidth: Int,
    private var screenHeight: Int,
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var cursorView: View? = null
    private var isAdded = false
    private var isVisible = false

    // Cursor position in screen pixels
    private var currentScreenX = 0f
    private var currentScreenY = 0f

    // Cursor updates are capped near display refresh timing.
    private val updateThrottleMs = 16L
    private var lastUpdateTimeMs = 0L

    // Whether we've received the first position update
    private var hasInitialized = false

    // Very small dead-zone in pixels (1dp) — just enough to prevent sub-pixel jitter
    private val deadZonePx = dpToPx(DEAD_ZONE_DP)

    // Cursor size in pixels
    private val cursorSizePx = dpToPx(CURSOR_SIZE_DP)
    private val ringSizePx = dpToPx(RING_SIZE_DP)

    // Armed state — shows ring around cursor
    private var isArmed = false

    // Hide animation tracking
    private val hideDelayMs = 200L

    /**
     * Sets the armed state on the cursor view.
     */
    fun setArmed(armed: Boolean) {
        isArmed = armed
        (cursorView as? CursorDotView)?.isArmed = armed
    }

    /**
     * Updates the cursor position from normalized hand coordinates.
     * Applies front camera mirroring, full screen mapping, and minimal dead-zone filtering.
     */
    fun updatePosition(normX: Float, normY: Float, screenW: Int, screenHeight: Int) {
        if (!isAdded) return

        cancelPendingHide()

        // Map normalized coords to screen pixels (with mirroring and full coverage)
        val targetX = ActionDispatcher.normalizeToScreenX(normX, screenW)
        val targetY = ActionDispatcher.normalizeToScreenY(normY, screenHeight)

        // Apply minimal dead-zone: skip update if movement is too small
        if (hasInitialized) {
            val dx = targetX - currentScreenX
            val dy = targetY - currentScreenY
            val distance = kotlin.math.sqrt(dx * dx + dy * dy)
            if (distance < deadZonePx) {
                if (!isVisible) show()
                return
            }
        }

        // Sub-pixel interpolation keeps motion visually smooth without a second
        // heavyweight filter on top of CursorSmoother.
        val interpolationFactor = 0.3f
        currentScreenX += (targetX - currentScreenX) * interpolationFactor
        currentScreenY += (targetY - currentScreenY) * interpolationFactor
        hasInitialized = true

        updateViewLayout()
        (cursorView as? CursorDotView)?.notifyMoving()

        if (!isVisible) show()
    }

    fun show() {
        if (!isAdded) {
            addView()
        }
        cursorView?.apply {
            alpha = 0f
            visibility = View.VISIBLE
            animate()
                .alpha(1f)
                .setDuration(hideDelayMs)
                .start()
        }
        isVisible = true
    }

    fun hide() {
        if (!isVisible) return
        val view = cursorView ?: return

        view.animate()
            .alpha(0f)
            .setDuration(hideDelayMs)
            .withEndAction {
                view.visibility = View.INVISIBLE
                isVisible = false
            }
            .start()
    }

    fun updateScreenSize(width: Int, height: Int) {
        screenWidth = width
        screenHeight = height
    }

    /** Visual confirmation that a gesture was successfully dispatched. */
    fun pulse() {
        (cursorView as? CursorDotView)?.pulse()
    }

    /** Notify that the cursor is over an interactive target. */
    fun notifyHover() {
        (cursorView as? CursorDotView)?.notifyHover()
    }

    /** Clear interactive-target hover feedback. */
    fun resetHover() {
        (cursorView as? CursorDotView)?.resetHover()
    }

    /** Notify that a click/tap action was accepted. */
    fun notifyTap() {
        (cursorView as? CursorDotView)?.notifyTap()
    }

    fun remove() {
        try {
            cursorView?.let { windowManager.removeView(it) }
        } catch (_: Exception) {
            // View not attached
        }
        cursorView = null
        isAdded = false
        isVisible = false
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addView() {
        if (isAdded) return

        cursorView = createCursorView()
        val params = createLayoutParams()

        try {
            windowManager.addView(cursorView, params)
            isAdded = true
        } catch (e: Exception) {
            Timber.e("Failed to add cursor overlay: %s", e.message)
        }
    }

    private fun createCursorView(): View {
        val view = CursorDotView(context, cursorSizePx, ringSizePx)
        val size = ringSizePx * 2 + dpToPx(4)
        view.layoutParams = FrameLayout.LayoutParams(size, size)
        return view
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        val size = ringSizePx * 2 + dpToPx(4)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        return WindowManager.LayoutParams(
            size,
            size,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = currentScreenX.toInt() - size / 2
            y = currentScreenY.toInt() - size / 2
        }
    }

    private fun updateViewLayout() {
        val view = cursorView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return

        val now = System.currentTimeMillis()
        if (now - lastUpdateTimeMs < updateThrottleMs) {
            return
        }
        lastUpdateTimeMs = now

        val size = ringSizePx * 2 + dpToPx(4)
        params.x = currentScreenX.toInt() - size / 2
        params.y = currentScreenY.toInt() - size / 2

        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
            // View not attached
        }
    }

    private fun cancelPendingHide() {
        cursorView?.animate()?.cancel()
        cursorView?.alpha = 1f
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val CURSOR_SIZE_DP = 28
        private const val RING_SIZE_DP = 20
        private const val DEAD_ZONE_DP = 1
    }
}
