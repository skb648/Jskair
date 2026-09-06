package com.aircontrol.accessibility

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import timber.log.Timber
import com.aircontrol.accessibility.cursor.CursorDotView
import com.aircontrol.accessibility.cursor.CursorGeometry
import com.aircontrol.accessibility.cursor.CursorIcon

/**
 * Accessibility overlay that renders the NATIVE-LIKE cursor (a clean,
 * desktop-style pointer — NOT the OS-native pointer; see NativeLikeCursor.md).
 *
 * Window/hotspot contract (spec §4):
 *  - The logical cursor position (this class's currentScreenX/Y, screen px) is
 *    THE coordinate used for clicks, hover and accessibility lookups.
 *  - The overlay window is positioned so [CursorGeometry]'s hotspot pixel —
 *    where every glyph draws its semantic point (arrow tip / fingertip /
 *    beam centre) — sits exactly on the logical position. The visible arrow
 *    tip therefore always covers the point that receives the click.
 *
 * Latency: direct position updates at up to 60 Hz with frame coalescing (a
 * throttled frame is deferred, never dropped); NO added smoothing — the One
 * Euro filter upstream already smooths hand coordinates exactly once.
 */
class CursorOverlay(
    private val context: Context,
    private var screenWidth: Int,
    private var screenHeight: Int,
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = context.resources.displayMetrics.density

    private var cursorView: View? = null
    private var isAdded = false
    private var isVisible = false

    // Logical cursor position in screen pixels (kept as floats; rounding to
    // window ints happens once per applied frame, never accumulated).
    private var currentScreenX = 0f
    private var currentScreenY = 0f

    /**
     * Notified with the APPLIED logical position (screen px) on every layout
     * pass (≤60 Hz). The accessibility service feeds this into the hover
     * resolver. Invoked on the main thread.
     */
    var onPositionApplied: ((x: Float, y: Float) -> Unit)? = null

    // 60fps cursor movement; throttled frames are coalesced (never dropped).
    private val updateThrottleMs = 16L
    private var lastUpdateTimeMs = 0L

    private var hasInitialized = false

    /** Deferred paint scheduled when two updates arrive closer than the throttle. */
    private var pendingLayout: Runnable? = null

    /** Current pointer glyph (rendered by CursorDotView at the same hotspot). */
    private var icon: CursorIcon = CursorIcon.ARROW

    private val hideDelayMs = 200L

    /**
     * Armed state retained for feedback consumers; the ring visual was removed
     * (user-test noise) — armed is conveyed by the status pill.
     */
    fun setArmed(armed: Boolean) {
        (cursorView as? CursorDotView)?.isArmed = armed
    }

    /** Fix (user test): tint the pointer while a pinch-drag is in progress. */
    fun setDragging(dragging: Boolean) {
        (cursorView as? CursorDotView)?.isDragging = dragging
    }

    /** Switches the pointer glyph (ARROW/HAND/IBEAM/…). Never moves the hotspot. */
    fun setCursorIcon(icon: CursorIcon) {
        if (icon == this.icon) return
        this.icon = icon
        (cursorView as? CursorDotView)?.icon = icon
    }

    /**
     * Updates the logical cursor position from normalized hand/gaze
     * coordinates (mirroring + full-screen mapping via ActionDispatcher),
     * clamps it to the usable screen, and re-positions the overlay window so
     * the hotspot covers it. [directMapping] skips the hand dead-zone mapping
     * for gaze/eye coordinates that are already screen-normalized.
     */
    fun updatePosition(normX: Float, normY: Float, screenW: Int, screenHeight: Int, directMapping: Boolean = false) {
        if (!isAdded) return

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

        // NaN/∞ must never move the window (spec §27: invalid coordinates).
        if (!CursorGeometry.isUsable(targetX, targetY)) return

        currentScreenX = CursorGeometry.clampToScreen(targetX, screenWidth)
        currentScreenY = CursorGeometry.clampToScreen(targetY, screenHeight)
        hasInitialized = true

        updateViewLayout()

        if (!isVisible) show()
    }

    /** Shows the cursor overlay (200ms fade-in, never restarted while visible). */
    fun show() {
        if (!isAdded) {
            addView()
        }
        if (isVisible && cursorView?.visibility == View.VISIBLE && cursorView?.alpha == 1f) return
        cancelPendingHide()
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

    /** Hides the cursor with a 200ms fade-out. */
    fun hide() {
        cancelPendingLayout()
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

    /** Updates the screen size after rotation/display changes. */
    fun updateScreenSize(width: Int, height: Int) {
        screenWidth = width
        screenHeight = height
    }

    /** Click visual confirmation (small ripple, reduced-motion aware). */
    fun pulse() {
        (cursorView as? CursorDotView)?.pulse()
    }

    /** Hover state notifications — the scale visual was removed; kept for API compat. */
    fun notifyHover() {
        (cursorView as? CursorDotView)?.notifyHover()
    }

    fun resetHover() {
        (cursorView as? CursorDotView)?.resetHover()
    }

    fun notifyTap() {
        (cursorView as? CursorDotView)?.notifyTap()
    }

    fun ripple() {
        (cursorView as? CursorDotView)?.ripple()
    }

    /** Dwell progress (0..1) — thin arc under the glyph, centred on the hotspot. */
    fun setDwellProgress(progress: Float) {
        (cursorView as? CursorDotView)?.setDwellProgress(progress)
    }

    /** Reduced motion — disables press/ripple animations. */
    fun setReducedMotion(reduced: Boolean) {
        (cursorView as? CursorDotView)?.setReducedMotion(reduced)
    }

    /** Removes the overlay from the window manager. */
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
        val view = CursorDotView(context, density)
        view.icon = icon
        val size = CursorGeometry.viewSizePx(density)
        view.layoutParams = android.widget.FrameLayout.LayoutParams(size, size)
        return view
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        return WindowManager.LayoutParams(
            CursorGeometry.viewSizePx(density),
            CursorGeometry.viewSizePx(density),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Anchor the window so the HOTSPOT pixel (not the window centre)
            // sits on the logical cursor position.
            x = CursorGeometry.windowLeft(currentScreenX, density)
            y = CursorGeometry.windowTop(currentScreenY, density)
        }
    }

    private fun updateViewLayout() {
        val view = cursorView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return

        val now = SystemClock.elapsedRealtime()
        if (now - lastUpdateTimeMs < updateThrottleMs) {
            // Coalesce throttled frames into ONE deferred paint — never drop.
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
        params.x = CursorGeometry.windowLeft(currentScreenX, density)
        params.y = CursorGeometry.windowTop(currentScreenY, density)

        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
            // View not attached
        }
        onPositionApplied?.invoke(currentScreenX, currentScreenY)
    }

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
}
