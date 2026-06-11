package com.aircontrol.accessibility

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
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
    private var posX: Int = prefs.getInt(KEY_POS_X, DEFAULT_POS_X)
    private var posY: Int = prefs.getInt(KEY_POS_Y, DEFAULT_POS_Y)

    // Drag state
    private var isDragging = false
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragViewStartX = 0
    private var dragViewStartY = 0

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

        view.setBackgroundColor(bgColor)
        view.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = dpToPx(12).toFloat()
        }
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

                if (!isDragging && (kotlin.math.abs(dx) > TOUCH_SLOP || kotlin.math.abs(dy) > TOUCH_SLOP)) {
                    isDragging = true
                }

                if (isDragging) {
                    params.x = dragViewStartX + dx.toInt()
                    params.y = dragViewStartY + dy.toInt()
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
                    // Persist position
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

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val PREFS_NAME = "aircontrol_status_overlay"
        private const val KEY_POS_X = "overlay_pos_x"
        private const val KEY_POS_Y = "overlay_pos_y"
        private const val DEFAULT_POS_X = 50
        private const val DEFAULT_POS_Y = 100
        private const val TOUCH_SLOP = 10
    }
}
