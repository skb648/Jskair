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
 * - Subtle idle pulse animation when not moving
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

    // Throttle overlay updates to ~30fps to reduce IPC overhead
    private var lastUpdateTimeMs = 0L
    private val updateThrottleMs = 33L

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
     * Sets the armed state on the cursor view (m-12).
     */
    fun setArmed(armed: Boolean) {
        isArmed = armed
        (cursorView as? CursorDotView)?.isArmed = armed
    }

    /**
     * Updates the cursor position from normalized hand coordinates.
     * Applies front camera mirroring, full screen mapping,
     * and minimal dead-zone filtering.
     *
     * The One Euro Filter in HandTracker already provides smooth output,
     * so we do NOT apply additional exponential smoothing here to minimize latency.
     */
    fun updatePosition(normX: Float, normY: Float, screenW: Int, screenHeight: Int) {
        if (!isAdded) return

        // Cancel pending hide
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
                // Within dead-zone — don't update
                if (!isVisible) show()
                return
            }
        }

        // Direct position update — One Euro Filter in HandTracker handles smoothing
        currentScreenX = targetX
        currentScreenY = targetY
        hasInitialized = true

        // Update layout immediately for minimal latency
        updateViewLayout()

        // M-12: Notify the cursor dot view that movement is happening
        (cursorView as? CursorDotView)?.notifyMoving()

        if (!isVisible) show()
    }

    /**
     * Shows the cursor overlay.
     */
    fun show() {
        if (!isAdded) {
            addView()
        }
        cursorView?.apply {
            alpha = 1f
            visibility = View.VISIBLE
        }
        isVisible = true
    }

    /**
     * Hides the cursor with a 200ms fade-out.
     */
    fun hide() {
        if (!isVisible) return
        val view = cursorView ?: return

        // Animate alpha to 0 over 200ms
        view.animate()
            .alpha(0f)
            .setDuration(hideDelayMs)
            .withEndAction {
                view.visibility = View.INVISIBLE
                isVisible = false
            }
            .start()
    }

    /**
     * Updates the screen size after rotation.
     */
    fun updateScreenSize(width: Int, height: Int) {
        screenWidth = width
        screenHeight = height
    }

    /**
     * Removes the overlay from the window manager.
     */
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

    // ========== Private implementation ==========

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

        val size = ringSizePx * 2 + dpToPx(4) // Extra padding for ring
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
            return // Throttle overlay updates to ~30fps
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
        private const val CURSOR_SIZE_DP = 24
        private const val RING_SIZE_DP = 18
        // (Bug #6 & #7 Fix): Increased from 1dp to 3dp to eliminate the remaining
        // hand tremor that survives the (now consolidated) One Euro Filter in
        // CursorSmoother. With the landmark-level filter removed from HandTracker,
        // this overlay-side dead zone is the second of two anti-jitter stages;
        // 3dp (~3px on mdpi, ~9px on xxxhdpi) is small enough to be invisible
        // during intentional motion but large enough to suppress micro-tremor.
        private const val DEAD_ZONE_DP = 3
    }
}
