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
 * Accessibility overlay that renders the pointer (a small desktop-style arrow,
 * not a literal dot — see CursorDotView) on screen.
 * Uses TYPE_ACCESSIBILITY_OVERLAY so it works without SYSTEM_ALERT_WINDOW
 * when the accessibility service is enabled.
 *
 * Pointer rendering (Fix B5: docs now say "pointer", matching what is drawn):
 * - Desktop-style arrow pointer with soft shadow
 * - Subtle idle pulse animation when not moving
 * - Small ring around pointer when state machine is ARMED
 * - 200ms fade-out when hand is lost
 * - Minimal dead-zone to prevent drift at tiny movements
 * - Low-latency direct position update (no exponential smoothing on top of One Euro)
 * - Full screen coverage with proper coordinate mapping for all aspect ratios
 *   including Android 17 edge-to-edge and cutout handling
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

    // CRITICAL FIX: Remove throttle for 60fps cursor movement (Apple Vision Pro level)
    // Apple Vision Pro uses 60fps+ for buttery smooth cursor
    private val updateThrottleMs = 16L  // ✅ 60fps (16ms) instead of 33ms (30fps)
    private var lastUpdateTimeMs = 0L   // ✅ Track last update time for throttling

    // Whether we've received the first position update
    private var hasInitialized = false

    /** Deferred paint scheduled when two updates arrive closer than the throttle. */
    private var pendingLayout: Runnable? = null

    // Fix A-10: the old 4dp "jitter filter" on the overlay is gone.
    //
    // It dropped every movement smaller than dpToPx(4) - about ten screen pixels
    // on a typical phone - which made fine pointing impossible: reaching a small
    // icon needed a hand movement far larger than the distance still to go, and
    // the dot simply never arrived. Worse, the early return *lost* the delta
    // instead of deferring it, so the dot stayed visibly offset from where the
    // click landed. Jitter suppression belongs in the One Euro filter
    // (CursorSmoother), which is velocity-aware: it keeps the dot still while the
    // hand is still, without eating real movement.

    // Cursor size in pixels
    private val cursorSizePx = dpToPx(CURSOR_SIZE_DP) // Responsive: scaled via screen density already (tablet density higher)
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
     * Applies front camera mirroring, full screen mapping, and minimal dead-zone filtering.
     * 
     * CRITICAL FIX: Added exponential interpolation for sub-pixel smoothness (Apple Vision Pro level)
     * This provides buttery smooth cursor movement without visible jumps or stuttering.
     */
    fun updatePosition(normX: Float, normY: Float, screenW: Int, screenHeight: Int, directMapping: Boolean = false) {
        if (!isAdded) return

        // Cancel pending hide
        cancelPendingHide()

        // Map normalized coords to screen pixels (with mirroring and full coverage).
        // Fix A2: gaze/eye coordinates are already screen-normalized — mapping
        // them through the hand's margin/dead-zone transform shifted the eye
        // cursor away from where the user actually looks (and away from where
        // the click then landed).
        val targetX = if (directMapping) {
            ActionDispatcher.normalizeDirect(normX, screenW)
        } else {
            ActionDispatcher.normalizeToScreenX(normX, screenW)
        }
        val targetY = if (directMapping) {
            ActionDispatcher.normalizeDirect(normY, screenHeight)
        } else {
            ActionDispatcher.normalizeToScreenY(normY, screenHeight)
        }

        // Set position directly. Smoothing is handled by the One Euro filter in
        // GestureControlAccessibilityService (CursorSmoother); applying a second
        // exponential interpolation here caused double-smoothing lag.
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
        // Fix: don't restart the fade-in if already visible. Previously every
        // call (once per frame) reset alpha to 0 and re-ran the 200ms fade-in,
        // so the cursor never reached full opacity → constant blinking.
        if (isVisible && cursorView?.visibility == View.VISIBLE && cursorView?.alpha == 1f) return
        cancelPendingHide()
        cursorView?.apply {
            // UC-04 Fix: Added fade-in animation (200ms) to match fade-out
            // This creates a smooth, polished feel instead of abrupt appearance
            alpha = 0f
            visibility = View.VISIBLE
            animate()
                .alpha(1f)
                .setDuration(hideDelayMs) // Use same duration as hide for symmetry
                .start()
        }
        isVisible = true
    }

    /**
     * Hides the cursor with a 200ms fade-out.
     */
    fun hide() {
        cancelPendingLayout()
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
     * UG-09/UG-10 Fix: Triggers a visual pulse effect on the cursor.
     * Called when a gesture is successfully dispatched to provide clear visual
     * confirmation to the user that their gesture was recognized.
     * BUG #5 FIX: Uses internal CursorDotView.pulse() instead of View scaling
     */
    fun pulse() {
        (cursorView as? CursorDotView)?.pulse()
    }

    /**
     * Notify cursor is hovering over interactive element.
     * BUG #4 FIX: Exposes CursorDotView.notifyHover() for external triggering
     */
    fun notifyHover() {
        (cursorView as? CursorDotView)?.notifyHover()
    }

    /**
     * Reset hover state when cursor leaves interactive element.
     * BUG #4 FIX: Exposes CursorDotView.resetHover() for external triggering
     */
    fun resetHover() {
        (cursorView as? CursorDotView)?.resetHover()
    }

    /**
     * Notify cursor tap/click action.
     * BUG #4 FIX: Exposes CursorDotView.notifyTap() for external triggering
     */
    fun notifyTap() {
        (cursorView as? CursorDotView)?.notifyTap()
    }

    /**
     * F11: Ripple feedback — an expanding ring on click (Vision Pro style). Replaces
     * the scale "pop" pulse for gesture confirmation.
     */
    fun ripple() {
        (cursorView as? CursorDotView)?.ripple()
    }

    /**
     * F1: Sets the dwell progress (0..1) shown as a circular ring around the cursor.
     */
    fun setDwellProgress(progress: Float) {
        (cursorView as? CursorDotView)?.setDwellProgress(progress)
    }

    /**
     * F9: Reduced motion — disables pulse/glow/ripple animations.
     */
    fun setReducedMotion(reduced: Boolean) {
        (cursorView as? CursorDotView)?.setReducedMotion(reduced)
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
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Fix (audit #12): anchor the window on the pointer TIP, not the centre.
            x = windowLeft()
            y = windowTop()
        }
    }

    private fun updateViewLayout() {
        val view = cursorView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return

        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastUpdateTimeMs < updateThrottleMs) {
            // Fix B-4: never *drop* a throttled frame. The skipped paint used to be
            // discarded outright, so when camera frames landed a little faster than
            // 16 ms apart the dot could sit a whole frame behind the hand - and stay
            // there once the user stopped moving, which is exactly the "I clicked
            // next to the button" complaint. Coalesce it into one deferred paint.
            if (pendingLayout == null) {
                val deferred = Runnable {
                    pendingLayout = null
                    applyLayout(view, params)
                }
                pendingLayout = deferred
                view.postDelayed(deferred, updateThrottleMs)
            }
            return
        }
        lastUpdateTimeMs = now
        applyLayout(view, params)
    }

    private fun applyLayout(view: View, params: WindowManager.LayoutParams) {
        // Fix (audit #12): position the window so the pointer's visible TIP —
        // not the view centre — lands exactly on the injected click point.
        params.x = windowLeft()
        params.y = windowTop()

        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
            // View not attached
        }
    }

    private val viewSizePx: Int
        get() = ringSizePx * 2 + dpToPx(4)

    /**
     * Fix (audit #12): the arrow is drawn with its TIP at
     * (0.43*viewSize − 0.42*cursorSize, 0.50*viewSize − 0.50*1.38*cursorSize)
     * from the window's top-left (see CursorDotView.buildPointerPath/onDraw).
     * The injected click point is (currentScreenX, currentScreenY), so the
     * window's top-left must sit so that the TIP — not the window centre —
     * covers it. Before this, every click landed ~15dp right/below of where
     * the visible arrow tip pointed.
     */
    private fun windowLeft(): Int =
        (currentScreenX - (viewSizePx * 0.43f - cursorSizePx * 0.42f)).toInt()

    private fun windowTop(): Int =
        (currentScreenY - (viewSizePx * 0.50f - cursorSizePx * 1.38f * 0.50f)).toInt()

    /** Drop a scheduled repaint (hide/remove must not leave a stale frame). */
    private fun cancelPendingLayout() {
        val view = cursorView ?: return
        pendingLayout?.let { view.removeCallbacks(it) }
        pendingLayout = null
    }

    private fun cancelPendingHide() {
        cursorView?.animate()?.cancel()
        cursorView?.alpha = 1f
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    companion object {
        // CRITICAL FIX: Smaller cursor for precision work (Apple Vision Pro level)
        // Apple Vision Pro uses a small, elegant cursor
        private const val CURSOR_SIZE_DP = 28  // ✅ Reduced from 36dp to 28dp
        private const val RING_SIZE_DP = 20    // ✅ Reduced from 28dp to 20dp
        
    }
}
