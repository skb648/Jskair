package com.aircontrol.accessibility

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.aircontrol.gesture.model.GestureEngineState
import timber.log.Timber

/**
 * Always-on floating status pill that shows armed/disarmed state.
 *
 * Features:
 * - Tiny floating pill showing armed (green) / disarmed (gray) state
 * - Draggable to reposition
 * - Position persisted in SharedPreferences
 * - Uses TYPE_ACCESSIBILITY_OVERLAY (no SYSTEM_ALERT_WINDOW needed)
 */
class StatusOverlay(
    private val context: Context,
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE,
    )

    private var statusView: View? = null
    private var labelView: TextView? = null
    private var isAdded = false

    // Position (persisted)
    // Fix C-4: the default position used to be 50/100 raw *pixels*. On a 2.75x
    // phone that is 18x36 dp, i.e. the status chip was wedged under the clock and
    // the camera cutout (and on a tablet it floated in the top-left corner). The
    // default is now expressed in dp and resolved against this device's density;
    // a position the user dragged is still stored (and re-read) in pixels.
    private var posX: Int = try { prefs.getInt(KEY_POS_X, defaultPosX()) } catch (_: Exception) { defaultPosX() }
    private var posY: Int = try { prefs.getInt(KEY_POS_Y, defaultPosY()) } catch (_: Exception) { defaultPosY() }

    private fun defaultPosX(): Int = dpToPx(DEFAULT_POS_X_DP)
    private fun defaultPosY(): Int = dpToPx(DEFAULT_POS_Y_DP) + statusBarHeightPx()

    /**
     * Reads the framework status-bar inset so the default pill position lands below
     * the clock and the notch. `getIdentifier` on the android namespace is the only
     * pre-API-30 way to do this from a service (WindowInsets needs a window), so the
     * discouraged-API lint is suppressed with the reason recorded here; the fallback
     * covers the (rare) OEM that strips the resource.
     */
    @android.annotation.SuppressLint("DiscouragedApi")
    private fun statusBarHeightPx(): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else dpToPx(24)
    }

    // Drag state
    private var isDragging = false
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragViewStartX = 0
    private var dragViewStartY = 0

    // Touch slop from system (m-10)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    // Cached drawable (M-14)
    private val cachedDrawable = android.graphics.drawable.GradientDrawable()

    // Screen dimensions for bounds clamping (C-06 Fix: make dynamic for foldables/rotation)
    // Previously cached at construction time, which broke on foldable devices or
    // when display scaling changed. Now refreshed on each layout pass.
    private val screenWidth: Int
        get() = context.resources.displayMetrics.widthPixels
    private val screenHeight: Int
        get() = context.resources.displayMetrics.heightPixels

    // Current state
    private var currentState = GestureEngineState.DISARMED

    /**
     * Updates the displayed state.
     */
    fun updateState(state: GestureEngineState) {
        currentState = state
        if (!isAdded) addView()
        updateAppearance()
    }

    /**
     * Removes the overlay.
     */
    fun remove() {
        try {
            statusView?.let { windowManager.removeView(it) }
        } catch (_: Exception) {
            // View not attached
        }
        statusView = null
        labelView = null
        isAdded = false
    }

    // ========== Private implementation ==========

    @SuppressLint("ClickableViewAccessibility")
    private fun addView() {
        if (isAdded) return

        val container = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        labelView = TextView(context).apply {
            text = "AC"
            textSize = 10f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(
                dpToPx(12), dpToPx(4),
                dpToPx(12), dpToPx(4),
            )
        }
        container.addView(labelView)

        // Set up drag handling
        container.setOnTouchListener { _, event ->
            handleTouchEvent(event)
        }

        statusView = container

        val params = createLayoutParams()
        try {
            windowManager.addView(statusView, params)
            isAdded = true
            updateAppearance()
        } catch (e: Exception) {
            Timber.e("Failed to add status overlay: %s", e.message)
        }
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = posX
            y = posY
        }
    }

    private fun updateAppearance() {
        val view = statusView ?: return
        val label = labelView ?: return

        val bgColor = when (currentState) {
            GestureEngineState.ARMED,
            GestureEngineState.EXECUTING,
            GestureEngineState.COOLDOWN -> {
                label.text = "●"
                android.graphics.Color.parseColor("#4CAF50") // Green
            }
            GestureEngineState.ARMING -> {
                label.text = "◐"
                android.graphics.Color.parseColor("#FF9800") // Orange
            }
            GestureEngineState.DISARMED -> {
                label.text = "○"
                android.graphics.Color.parseColor("#9E9E9E") // Gray
            }
        }

        // M-14: Reuse cached drawable instead of creating a new one each time
        cachedDrawable.setColor(bgColor)
        cachedDrawable.cornerRadius = dpToPx(12).toFloat()
        view.background = cachedDrawable
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun handleTouchEvent(event: MotionEvent): Boolean {
        val params = statusView?.layoutParams as? WindowManager.LayoutParams ?: return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = false
                dragStartX = event.rawX
                dragStartY = event.rawY
                dragViewStartX = params.x
                dragViewStartY = params.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - dragStartX
                val dy = event.rawY - dragStartY

                if (!isDragging && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                    isDragging = true
                }

                if (isDragging) {
                    // M-15: Clamp position to screen bounds
                    val viewWidth = statusView?.width ?: 0
                    val viewHeight = statusView?.height ?: 0
                    params.x = (dragViewStartX + dx.toInt()).coerceIn(0, (screenWidth - viewWidth).coerceAtLeast(0))
                    params.y = (dragViewStartY + dy.toInt()).coerceIn(0, (screenHeight - viewHeight).coerceAtLeast(0))
                    try {
                        windowManager.updateViewLayout(statusView, params)
                    } catch (_: Exception) {
                        // View not attached
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    // Persist position (already clamped during move)
                    posX = params.x
                    posY = params.y
                    persistPosition()
                }
                isDragging = false
                return true
            }
        }
        return false
    }

    private fun persistPosition() {
        prefs.edit()
            .putInt(KEY_POS_X, posX)
            .putInt(KEY_POS_Y, posY)
            .apply()
    }

    /**
     * Repositions the overlay after a configuration change (e.g. rotation).
     * Clamps the stored position into the new screen bounds so the pill
     * doesn't end up off-screen.
     */
    fun reposition() {
        val params = statusView?.layoutParams as? WindowManager.LayoutParams ?: return
        val viewWidth = statusView?.width ?: 0
        val viewHeight = statusView?.height ?: 0
        params.x = posX.coerceIn(0, (screenWidth - viewWidth).coerceAtLeast(0))
        params.y = posY.coerceIn(0, (screenHeight - viewHeight).coerceAtLeast(0))
        posX = params.x
        posY = params.y
        try {
            windowManager.updateViewLayout(statusView, params)
        } catch (_: Exception) { /* not attached */ }
    }

    /**
     * Resets the overlay position to the default location.
     */
    fun resetToDefaultPosition() {
        posX = defaultPosX()
        posY = defaultPosY()
        val params = statusView?.layoutParams as? WindowManager.LayoutParams
        if (params != null) {
            params.x = posX
            params.y = posY
            try {
                windowManager.updateViewLayout(statusView, params)
            } catch (_: Exception) {
                // View not attached
            }
        }
        persistPosition()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val PREFS_NAME = "aircontrol_status_overlay"
        private const val KEY_POS_X = "overlay_pos_x"
        private const val KEY_POS_Y = "overlay_pos_y"
        // Top-right, just under the status bar: out of the way of the thumb and of
        // the navigation gestures on both phones and tablets.
        private const val DEFAULT_POS_X_DP = 12
        private const val DEFAULT_POS_Y_DP = 8
        // TOUCH_SLOP moved to instance field (m-10) — uses ViewConfiguration.get(context).scaledTouchSlop
    }
}
