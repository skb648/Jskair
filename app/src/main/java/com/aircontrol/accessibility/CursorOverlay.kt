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
 * - Dead-zone to prevent drift at small movements
 * - Exponential smoothing on top of One Euro output
 * - 10% edge margin expansion for corner reachability
 * - Front camera mirroring applied via ActionDispatcher coordinate mapping
 */
class CursorOverlay(
    private val context: Context,
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

    // Smoothed position (exponential smoothing)
    private var smoothedX = 0f
    private var smoothedY = 0f
    private var hasInitialized = false

    // Dead-zone radius in pixels
    private val deadZonePx = dpToPx(DEAD_ZONE_DP)

    // Smoothing factor (lower = smoother, higher = more responsive)
    // 0.3 = fairly smooth, still responsive
    private val smoothingAlpha = 0.3f

    // Cursor size in pixels
    private val cursorSizePx = dpToPx(CURSOR_SIZE_DP)
    private val ringSizePx = dpToPx(RING_SIZE_DP)

    // Armed state — shows ring around cursor
    private var isArmed = false

    // Hide animation tracking
    private var hideRunnable: Runnable? = null
    private val hideDelayMs = 200L

    /**
     * Updates the cursor position from normalized hand coordinates.
     * Applies front camera mirroring, edge margin expansion,
     * exponential smoothing, and dead-zone filtering.
     */
    fun updatePosition(normX: Float, normY: Float, screenW: Int, screenHeight: Int) {
        if (!isAdded) return

        // Cancel pending hide
        cancelPendingHide()

        // Map normalized coords to screen pixels (with mirroring and margin expansion)
        val targetX = ActionDispatcher.normalizeToScreenX(normX, screenW)
        val targetY = ActionDispatcher.normalizeToScreenY(normY, screenHeight)

        // Apply dead-zone: skip update if movement is too small
        if (hasInitialized) {
            val dx = targetX - smoothedX
            val dy = targetY - smoothedY
            val distance = kotlin.math.sqrt(dx * dx + dy * dy)
            if (distance < deadZonePx) {
                // Within dead-zone — don't update
                if (!isVisible) show()
                return
            }
        }

        // Apply exponential smoothing
        if (!hasInitialized) {
            smoothedX = targetX
            smoothedY = targetY
            hasInitialized = true
        } else {
            smoothedX = smoothedX + smoothingAlpha * (targetX - smoothedX)
            smoothedY = smoothedY + smoothingAlpha * (targetY - smoothedY)
        }

        currentScreenX = smoothedX
        currentScreenY = smoothedY

        // Update layout
        updateViewLayout()

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

        // Create a simple colored circle view for the cursor
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
        // Use a ComposeView for the cursor rendering
        // For simplicity and minimal dependency, we use a native View
        // with custom drawing
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
            // Center cursor on the initial position
            x = currentScreenX.toInt() - size / 2
            y = currentScreenY.toInt() - size / 2
        }
    }

    private fun updateViewLayout() {
        val view = cursorView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return

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
        hideRunnable?.let {
            cursorView?.removeCallbacks(it)
            hideRunnable = null
        }
        cursorView?.animate()?.cancel()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val CURSOR_SIZE_DP = 24
        private const val RING_SIZE_DP = 18
        private const val DEAD_ZONE_DP = 3
    }
}
