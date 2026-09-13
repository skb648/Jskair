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
import com.aircontrol.accessibility.cursor.CursorVisibilityAction
import com.aircontrol.accessibility.cursor.CursorVisibilityStateMachine

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

    // Issue 1: cursor visibility is a state machine, not a frame operation.
    // Repeated show() while visible/fading-in is a no-op; show() during a
    // fade-out cancels the fade-out and restores full visibility without
    // starting a new fade-in. Animations are transitions between explicit
    // states (HIDDEN/SHOWING/VISIBLE/HIDING), never per-frame restarts.
    private val visibilityMachine = CursorVisibilityStateMachine()

    // Monotonic token that lets stale animation end-callbacks know they belong
    // to an animation that was cancelled/removed — a cancelled fade must never
    // flip the machine (and a removed overlay must not inherit old callbacks).
    private var animationGeneration = 0L

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

    // High refresh rate (up to 240Hz) cursor movement; throttled frames are coalesced (never dropped).
    private val updateThrottleMs = 4L

    /** Whether a window position has ever been pushed to WindowManager (Rule 16 skip-guard). */
    private var lastLayoutApplied = false
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
            ActionDispatcher.normalizeToScreenY(normY, screenHeight, screenW)
        }

        // NaN/∞ must never move the window (spec §27: invalid coordinates).
        if (!CursorGeometry.isUsable(targetX, targetY)) return

        currentScreenX = CursorGeometry.clampToScreen(targetX, screenWidth)
        currentScreenY = CursorGeometry.clampToScreen(targetY, screenHeight)
        hasInitialized = true

        updateViewLayout()

        // Idempotent: no-op while visible or fading in; see CursorVisibilityStateMachine.
        if (!visibilityMachine.isEffectivelyVisible) show()
    }

    /**
     * Shows the cursor (200ms fade-in). IDEMPOTENT (Issue 1):
     *  - already fully visible  → nothing happens;
     *  - fade-in already running → not restarted;
     *  - fade-out running → cancelled and full visibility restored WITHOUT
     *    starting a new fade-in;
     *  - position changes elsewhere never restart the visibility animation.
     */
    fun show() {
        if (!isAdded) {
            addView()
        }
        when (val action = visibilityMachine.onShowRequest()) {
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
                // Cancel the running fade-out and restore full visibility
                // cleanly — no new fade-in (the machine already moved to VISIBLE).
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

    /** Hides the cursor with a 200ms fade-out (idempotent when already hidden). */
    fun hide() {
        cancelPendingLayout()
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

    /** Ghost mode for reading or background hover — reduces cursor alpha to prevent visual distraction. */
    fun setGhostMode(ghost: Boolean) {
        if (!visibilityMachine.isEffectivelyVisible) return
        cursorView?.animate()?.cancel()
        cursorView?.alpha = if (ghost) 0.32f else 1.0f
    }

    /** Reduced motion — disables press/ripple animations. */
    fun setReducedMotion(reduced: Boolean) {
        (cursorView as? CursorDotView)?.setReducedMotion(reduced)
    }

    /** Removes the overlay from the window manager (cancels all animations). */
    fun remove() {
        // Reset the machine to HIDDEN and cancel every running animation so a
        // stale end-callback can never flip a freshly recreated overlay.
        visibilityMachine.onRemoved()
        cancelViewAnimation()
        cancelPendingLayout()
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
            // Fresh LayoutParams: the no-op guard must compare against what is actually on screen.
            lastLayoutApplied = false
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

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        return WindowManager.LayoutParams(
            CursorGeometry.viewSizePx(density),
            CursorGeometry.viewSizePx(density),
            type,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
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
                    lastUpdateTimeMs = SystemClock.elapsedRealtime()
                    applyLayout(view, params)
                }
                pendingLayout = deferred
                view.post(deferred)
            }
            return
        }
        cancelPendingLayout()
        lastUpdateTimeMs = now
        applyLayout(view, params)
    }

    private fun applyLayout(view: View, params: WindowManager.LayoutParams) {
        val x = CursorGeometry.windowLeft(currentScreenX, density)
        val y = CursorGeometry.windowTop(currentScreenY, density)
        // Rule 16: a movement smaller than a pixel is not a movement. `updateViewLayout` is a
        // binder round trip plus a full re-composition of the overlay window, so skipping the
        // no-op is worth more than any smoothing knob — and it is exactly the case that used to
        // dominate while the user was looking still at a target.
        if (params.x == x && params.y == y && lastLayoutApplied) return
        params.x = x
        params.y = y
        lastLayoutApplied = true

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

    /** Keeps the legacy [isVisible] flag in lockstep with the state machine. */
    private fun syncVisibilityState() {
        isVisible = visibilityMachine.isEffectivelyVisible
    }

    /**
     * Cancels the currently running alpha animation. Increments the generation
     * token so any stale withEndAction that was already posted is ignored.
     */
    private fun cancelViewAnimation() {
        animationGeneration++
        cursorView?.animate()?.cancel()
    }

    /**
     * Runs a fade to [targetAlpha] over [fadeDurationMs]. A stale end-callback
     * (from an animation that was later cancelled or whose overlay was removed)
     * is dropped via the generation token, so animation callbacks can never
     * leak across overlay recreations (Issue 1 acceptance).
     */
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
}
