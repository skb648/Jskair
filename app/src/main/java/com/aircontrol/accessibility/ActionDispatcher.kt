package com.aircontrol.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.media.AudioManager
import android.os.Build
import com.aircontrol.data.model.UserPreferences
import com.aircontrol.data.repository.SettingsRepository
import com.aircontrol.gesture.model.GestureEngineState
import com.aircontrol.gesture.model.GestureEvent
import com.aircontrol.gesture.model.PinchPhase
import com.aircontrol.gesture.model.Pose
import com.aircontrol.gesture.model.SwipeDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gesture action mapping configuration.
 * Maps gesture types to their system actions.
 * Persisted per-user so gesture assignments are customizable.
 */
enum class GestureAction {
    NONE,
    SCROLL_UP,
    SCROLL_DOWN,
    SCROLL_LEFT,
    SCROLL_RIGHT,
    BACK,
    HOME,
    RECENTS,
    NOTIFICATIONS,
    QUICK_SETTINGS,
    VOLUME_UP,
    VOLUME_DOWN,
    MEDIA_PLAY_PAUSE,
    SCREENSHOT,
    LOCK_SCREEN,
    TAP,
    LONG_PRESS,
    DRAG,
}

/**
 * Maps GestureEvent → system actions using the user's gesture configuration.
 *
 * Supported actions:
 * - **Scroll**: dispatchGesture with smooth Path, 250ms duration, continuation strokes for long scrolls
 * - **Global actions**: BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS
 * - **Volume**: AudioManager ADJUST_RAISE/LOWER with ADJUST_SAME UI flag
 * - **Media**: dispatchMediaKeyEvent via AudioManager
 * - **Screenshot**: GLOBAL_ACTION_TAKE_SCREENSHOT (API 28+)
 * - **Lock screen**: GLOBAL_ACTION_LOCK_SCREEN (API 28+)
 *
 * Every action includes a haptic tick (if enabled in settings).
 * dispatchGesture cancellation is handled with one retry.
 */
@Singleton
class ActionDispatcher @Inject constructor(
    private val settingsRepository: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var accessibilityService: AccessibilityService? = null
    private var audioManager: AudioManager? = null
    private var currentPreferences = UserPreferences()

    /** Current gesture-to-action mapping. Defaults are sensible. */
    private val gestureMap = mutableMapOf<String, GestureAction>(
        KEY_SWIPE_LEFT to GestureAction.SCROLL_LEFT,
        KEY_SWIPE_RIGHT to GestureAction.SCROLL_RIGHT,
        KEY_SWIPE_UP to GestureAction.SCROLL_UP,
        KEY_SWIPE_DOWN to GestureAction.SCROLL_DOWN,
        KEY_POSE_PINCH to GestureAction.TAP,
        KEY_POSE_POINTING to GestureAction.NONE,
        KEY_POSE_VICTORY to GestureAction.MEDIA_PLAY_PAUSE,
        KEY_POSE_THUMB_UP to GestureAction.VOLUME_UP,
        KEY_POSE_THUMB_DOWN to GestureAction.VOLUME_DOWN,
    )

    // Gesture dispatch retry tracking
    private var lastDispatchRetryCount = 0
    private val MAX_RETRIES = 1

    init {
        scope.launch {
            settingsRepository.userPreferences.collect { prefs ->
                currentPreferences = prefs
            }
        }
        scope.launch {
            settingsRepository.gestureMapConfig.collect { config ->
                gestureMap.clear()
                config.entries.forEach { entry ->
                    gestureMap[entry.key] = entry.action
                }
                Timber.d("Loaded %d gesture mappings from settings", gestureMap.size)
            }
        }
    }

    /**
     * Binds this dispatcher to the accessibility service instance.
     * Called when the service connects.
     */
    fun attachService(service: AccessibilityService) {
        accessibilityService = service
        audioManager = service.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        Timber.i("ActionDispatcher attached to accessibility service")
    }

    /**
     * Detaches from the accessibility service.
     * Called when the service is destroyed.
     */
    fun detachService() {
        accessibilityService = null
        audioManager = null
        Timber.i("ActionDispatcher detached from accessibility service")
    }

    /**
     * Returns the current gesture-to-action mapping.
     */
    fun getGestureMap(): Map<String, GestureAction> = gestureMap.toMap()

    /**
     * Updates the action for a specific gesture key.
     */
    fun updateGestureAction(key: String, action: GestureAction) {
        gestureMap[key] = action
        Timber.d("Updated gesture mapping: %s → %s", key, action)
    }

    /**
     * Dispatches the appropriate system action for a given gesture event.
     * Only dispatches when the engine is in ARMED or EXECUTING state.
     *
     * @param event The gesture event to handle
     * @param engineState The current state machine state
     * @param cursorX Normalized cursor X position [0,1] for tap/drag actions
     * @param cursorY Normalized cursor Y position [0,1] for tap/drag actions
     * @param screenWidth Screen width in pixels for coordinate conversion
     * @param screenHeight Screen height in pixels for coordinate conversion
     * @return true if an action was dispatched
     */
    fun dispatch(
        event: GestureEvent,
        engineState: GestureEngineState,
        cursorX: Float,
        cursorY: Float,
        screenWidth: Int,
        screenHeight: Int,
    ): Boolean {
        // Only dispatch when armed or executing
        if (engineState != GestureEngineState.ARMED && engineState != GestureEngineState.EXECUTING) {
            return false
        }

        // Check if gestures are enabled in settings
        if (!currentPreferences.gesturesEnabled) {
            Timber.v("Gestures disabled in settings, ignoring event")
            return false
        }

        val dispatched = when (event) {
            is GestureEvent.Swipe -> dispatchSwipe(event, screenWidth, screenHeight)
            is GestureEvent.Pinch -> dispatchPinch(event, cursorX, cursorY, screenWidth, screenHeight)
            is GestureEvent.PoseTriggered -> dispatchPose(event, cursorX, cursorY, screenWidth, screenHeight)
            is GestureEvent.Armed,
            is GestureEvent.Disarmed,
            is GestureEvent.CursorMoved -> false
        }

        if (dispatched) {
            performHapticFeedback()
        }

        return dispatched
    }

    // ========== Swipe dispatching ==========

    private fun dispatchSwipe(event: GestureEvent.Swipe, screenWidth: Int, screenHeight: Int): Boolean {
        val action = when (event.direction) {
            SwipeDirection.LEFT -> gestureMap[KEY_SWIPE_LEFT] ?: GestureAction.NONE
            SwipeDirection.RIGHT -> gestureMap[KEY_SWIPE_RIGHT] ?: GestureAction.NONE
            SwipeDirection.UP -> gestureMap[KEY_SWIPE_UP] ?: GestureAction.NONE
            SwipeDirection.DOWN -> gestureMap[KEY_SWIPE_DOWN] ?: GestureAction.NONE
        }

        return when (action) {
            GestureAction.SCROLL_UP -> dispatchScrollGesture(screenWidth, screenHeight, scrollUp = true)
            GestureAction.SCROLL_DOWN -> dispatchScrollGesture(screenWidth, screenHeight, scrollUp = false)
            GestureAction.SCROLL_LEFT -> dispatchHorizontalScroll(screenWidth, screenHeight, scrollLeft = true)
            GestureAction.SCROLL_RIGHT -> dispatchHorizontalScroll(screenWidth, screenHeight, scrollLeft = false)
            GestureAction.BACK -> performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            GestureAction.HOME -> performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            GestureAction.RECENTS -> performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            GestureAction.NOTIFICATIONS -> performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            GestureAction.QUICK_SETTINGS -> performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            else -> {
                Timber.v("No action mapped for swipe %s", event.direction)
                false
            }
        }
    }

    /**
     * Dispatches a vertical scroll gesture using dispatchGesture.
     * Uses a smooth Path with 250ms duration and continuation strokes.
     */
    private fun dispatchScrollGesture(screenWidth: Int, screenHeight: Int, scrollUp: Boolean): Boolean {
        val centerX = screenWidth / 2f
        val startY = if (scrollUp) screenHeight * 0.7f else screenHeight * 0.3f
        val endY = if (scrollUp) screenHeight * 0.3f else screenHeight * 0.7f

        val path = Path().apply {
            moveTo(centerX, startY)
            // Add intermediate points for smooth scrolling
            val steps = 10
            for (i in 1..steps) {
                val t = i.toFloat() / steps
                // Ease-out curve for natural feel
                val easedT = 1f - (1f - t) * (1f - t)
                lineTo(centerX, startY + (endY - startY) * easedT)
            }
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, SCROLL_DURATION_MS))
            .build()

        return dispatchGestureWithRetry(gesture, "scroll_${if (scrollUp) "up" else "down"}")
    }

    /**
     * Dispatches a horizontal scroll gesture.
     */
    private fun dispatchHorizontalScroll(screenWidth: Int, screenHeight: Int, scrollLeft: Boolean): Boolean {
        val centerY = screenHeight / 2f
        val startX = if (scrollLeft) screenWidth * 0.7f else screenWidth * 0.3f
        val endX = if (scrollLeft) screenWidth * 0.3f else screenWidth * 0.7f

        val path = Path().apply {
            moveTo(startX, centerY)
            val steps = 10
            for (i in 1..steps) {
                val t = i.toFloat() / steps
                val easedT = 1f - (1f - t) * (1f - t)
                val x = startX + (endX - startX) * easedT
                lineTo(x, centerY)
            }
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, SCROLL_DURATION_MS))
            .build()

        return dispatchGestureWithRetry(gesture, "scroll_${if (scrollLeft) "left" else "right"}")
    }

    // ========== Pinch dispatching ==========

    private fun dispatchPinch(
        event: GestureEvent.Pinch,
        cursorX: Float,
        cursorY: Float,
        screenWidth: Int,
        screenHeight: Int,
    ): Boolean {
        val action = gestureMap[KEY_POSE_PINCH] ?: GestureAction.TAP

        return when (event.phase) {
            PinchPhase.START -> {
                // Record pinch start — we'll decide tap/long-press/drag on END
                pinchStartTimeMs = System.currentTimeMillis()
                pinchStartX = cursorX
                pinchStartY = cursorY

                // Initialize drag state in screen coordinates. If the mapped action
                // is not DRAG this state is harmless and will be ignored.
                dragStartX = normalizeToScreenX(cursorX, screenWidth)
                dragStartY = normalizeToScreenY(cursorY, screenHeight)
                dragCurrentX = dragStartX
                dragCurrentY = dragStartY
                isDragging = false

                Timber.v("Pinch START at (%.2f, %.2f)", cursorX, cursorY)
                true // Acknowledge start
            }
            PinchPhase.MOVE -> {
                // If action is DRAG, dispatch continuous stroke
                if (action == GestureAction.DRAG) {
                    dispatchDragStroke(cursorX, cursorY, screenWidth, screenHeight)
                } else {
                    false
                }
            }
            PinchPhase.END -> {
                val holdDurationMs = System.currentTimeMillis() - pinchStartTimeMs
                when {
                    action == GestureAction.DRAG -> dispatchDragEnd(cursorX, cursorY, screenWidth, screenHeight)
                    holdDurationMs >= LONG_PRESS_THRESHOLD_MS -> dispatchLongPress(pinchStartX, pinchStartY, screenWidth, screenHeight)
                    else -> dispatchTap(pinchStartX, pinchStartY, screenWidth, screenHeight)
                }
            }
        }
    }

    /**
     * Dispatches a tap gesture at the cursor position.
     */
    private fun dispatchTap(normX: Float, normY: Float, screenWidth: Int, screenHeight: Int): Boolean {
        val x = normalizeToScreenX(normX, screenWidth)
        val y = normalizeToScreenY(normY, screenHeight)

        val path = Path().apply {
            moveTo(x, y)
            // Tiny movement to ensure tap registers
            lineTo(x + 1f, y)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS))
            .build()

        return dispatchGestureWithRetry(gesture, "tap")
    }

    /**
     * Dispatches a long-press gesture at the cursor position.
     */
    private fun dispatchLongPress(normX: Float, normY: Float, screenWidth: Int, screenHeight: Int): Boolean {
        val x = normalizeToScreenX(normX, screenWidth)
        val y = normalizeToScreenY(normY, screenHeight)

        val path = Path().apply { moveTo(x, y) }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, LONG_PRESS_DURATION_MS))
            .build()

        return dispatchGestureWithRetry(gesture, "long_press")
    }

    /**
     * Dispatches a drag stroke (continuous movement during pinch-hold).
     */
    private fun dispatchDragStroke(normX: Float, normY: Float, screenWidth: Int, screenHeight: Int): Boolean {
        val x = normalizeToScreenX(normX, screenWidth)
        val y = normalizeToScreenY(normY, screenHeight)
        val fromX = dragCurrentX
        val fromY = dragCurrentY

        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(x, y)
        }

        val label = if (!isDragging) "drag_start" else "drag_continue"
        isDragging = true
        dragCurrentX = x
        dragCurrentY = y

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, DRAG_STEP_DURATION_MS))
            .build()
        return dispatchGestureWithRetry(gesture, label)
    }

    /**
     * Ends a drag gesture at the specified position.
     */
    private fun dispatchDragEnd(normX: Float, normY: Float, screenWidth: Int, screenHeight: Int): Boolean {
        isDragging = false
        val x = normalizeToScreenX(normX, screenWidth)
        val y = normalizeToScreenY(normY, screenHeight)

        val path = Path().apply {
            moveTo(dragCurrentX, dragCurrentY)
            lineTo(x, y)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 32L))
            .build()

        return dispatchGestureWithRetry(gesture, "drag_end")
    }

    // ========== Pose dispatching ==========

    private fun dispatchPose(
        event: GestureEvent.PoseTriggered,
        cursorX: Float,
        cursorY: Float,
        screenWidth: Int,
        screenHeight: Int,
    ): Boolean {
        val key = when (event.pose) {
            Pose.PINCH -> KEY_POSE_PINCH
            Pose.POINTING -> KEY_POSE_POINTING
            Pose.VICTORY -> KEY_POSE_VICTORY
            Pose.THUMB_UP -> KEY_POSE_THUMB_UP
            Pose.THUMB_DOWN -> KEY_POSE_THUMB_DOWN
            Pose.OPEN_PALM, Pose.FIST, Pose.NONE -> return false
        }

        val action = gestureMap[key] ?: GestureAction.NONE

        return when (action) {
            GestureAction.BACK -> performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            GestureAction.HOME -> performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            GestureAction.RECENTS -> performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            GestureAction.NOTIFICATIONS -> performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            GestureAction.QUICK_SETTINGS -> performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            GestureAction.VOLUME_UP -> adjustVolume(up = true)
            GestureAction.VOLUME_DOWN -> adjustVolume(up = false)
            GestureAction.MEDIA_PLAY_PAUSE -> toggleMediaPlayback()
            GestureAction.SCREENSHOT -> takeScreenshot()
            GestureAction.LOCK_SCREEN -> lockScreen()
            GestureAction.TAP -> dispatchTap(cursorX, cursorY, screenWidth, screenHeight)
            GestureAction.LONG_PRESS -> dispatchLongPress(cursorX, cursorY, screenWidth, screenHeight)
            GestureAction.SCROLL_UP -> dispatchScrollGesture(screenWidth, screenHeight, scrollUp = true)
            GestureAction.SCROLL_DOWN -> dispatchScrollGesture(screenWidth, screenHeight, scrollUp = false)
            GestureAction.SCROLL_LEFT -> dispatchHorizontalScroll(screenWidth, screenHeight, scrollLeft = true)
            GestureAction.SCROLL_RIGHT -> dispatchHorizontalScroll(screenWidth, screenHeight, scrollLeft = false)
            GestureAction.DRAG -> false // Drag is pinch-only
            GestureAction.NONE -> {
                Timber.v("No action mapped for pose %s", event.pose)
                false
            }
        }
    }

    // ========== Global actions ==========

    private fun performGlobalAction(action: Int): Boolean {
        val service = accessibilityService ?: run {
            Timber.w("Cannot perform global action: service not attached")
            return false
        }

        // Block gesture injection when keyguard is locked (except unlock-irrelevant actions)
        if (isKeyguardLocked()) {
            val allowedWhileLocked = action == AccessibilityService.GLOBAL_ACTION_HOME ||
                action == AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS ||
                action == AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            if (!allowedWhileLocked) {
                Timber.v("Keyguard locked, blocking global action %d", action)
                return false
            }
        }

        val result = service.performGlobalAction(action)
        val actionName = globalActionName(action)
        Timber.i("Global action %s: %s", actionName, if (result) "success" else "failed")
        return result
    }

    // ========== Volume & Media ==========

    private fun adjustVolume(up: Boolean): Boolean {
        val am = audioManager ?: return false
        val direction = if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        try {
            am.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                direction,
                AudioManager.FLAG_SHOW_UI,
            )
            Timber.i("Volume %s dispatched", if (up) "up" else "down")
            return true
        } catch (e: SecurityException) {
            Timber.e(e, "Cannot adjust volume — permission denied")
            return false
        }
    }

    private fun toggleMediaPlayback(): Boolean {
        val am = audioManager ?: return false
        try {
            val downEvent = android.view.KeyEvent(
                android.view.KeyEvent.ACTION_DOWN,
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            )
            val upEvent = android.view.KeyEvent(
                android.view.KeyEvent.ACTION_UP,
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            )
            am.dispatchMediaKeyEvent(downEvent)
            am.dispatchMediaKeyEvent(upEvent)
            Timber.i("Media play/pause dispatched")
            return true
        } catch (e: SecurityException) {
            Timber.e(e, "Cannot dispatch media key event")
            return false
        }
    }

    private fun takeScreenshot(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Timber.w("Screenshot not available below API 28")
            return false
        }
        return performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
    }

    private fun lockScreen(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Timber.w("Lock screen not available below API 28")
            return false
        }
        return performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
    }

    // ========== Gesture dispatch with retry ==========

    private fun dispatchGestureWithRetry(gesture: GestureDescription, label: String): Boolean {
        val service = accessibilityService ?: run {
            Timber.w("Cannot dispatch gesture '%s': service not attached", label)
            return false
        }

        if (isKeyguardLocked()) {
            Timber.v("Keyguard locked, blocking gesture '%s'", label)
            return false
        }

        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Timber.v("Gesture '%s' completed", label)
                lastDispatchRetryCount = 0
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (lastDispatchRetryCount < MAX_RETRIES) {
                    lastDispatchRetryCount++
                    Timber.w("Gesture '%s' cancelled, retrying (%d/%d)", label, lastDispatchRetryCount, MAX_RETRIES)
                    service.dispatchGesture(gesture, null, null)
                } else {
                    Timber.w("Gesture '%s' cancelled, max retries reached", label)
                    lastDispatchRetryCount = 0
                }
            }
        }

        val result = service.dispatchGesture(gesture, callback, null)
        Timber.v("Gesture '%s' dispatch result: %s", label, result)
        return result
    }

    // ========== Coordinate mapping ==========

    // normalizeToScreenX and normalizeToScreenY are in companion object for static access

    // ========== Haptic feedback ==========

    @Suppress("DEPRECATION")
    private fun performHapticFeedback() {
        if (!currentPreferences.hapticFeedback) return
        val service = accessibilityService ?: return
        val vibrator = service.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        vibrator?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                it.vibrate(
                    android.os.VibrationEffect.createOneShot(
                        HAPTIC_TICK_MS,
                        android.os.VibrationEffect.DEFAULT_AMPLITUDE,
                    ),
                )
            } else {
                @Suppress("DEPRECATION")
                it.vibrate(HAPTIC_TICK_MS)
            }
        }
    }

    // ========== Utilities ==========

    private fun isKeyguardLocked(): Boolean {
        val service = accessibilityService ?: return true
        val km = service.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        return km?.isDeviceLocked ?: true
    }

    private fun globalActionName(action: Int): String = when (action) {
        AccessibilityService.GLOBAL_ACTION_BACK -> "BACK"
        AccessibilityService.GLOBAL_ACTION_HOME -> "HOME"
        AccessibilityService.GLOBAL_ACTION_RECENTS -> "RECENTS"
        AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS -> "NOTIFICATIONS"
        AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS -> "QUICK_SETTINGS"
        AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT -> "SCREENSHOT"
        AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN -> "LOCK_SCREEN"
        else -> "UNKNOWN($action)"
    }

    companion object {
        private const val SCROLL_DURATION_MS = 250L
        private const val TAP_DURATION_MS = 50L
        private const val LONG_PRESS_DURATION_MS = 500L
        private const val LONG_PRESS_THRESHOLD_MS = 600L
        private const val DRAG_STEP_DURATION_MS = 16L
        private const val HAPTIC_TICK_MS = 15L
        private const val EDGE_MARGIN_FRACTION = 0.1f

        // Gesture map keys
        const val KEY_SWIPE_LEFT = "swipe_left"
        const val KEY_SWIPE_RIGHT = "swipe_right"
        const val KEY_SWIPE_UP = "swipe_up"
        const val KEY_SWIPE_DOWN = "swipe_down"
        const val KEY_POSE_PINCH = "pose_pinch"
        const val KEY_POSE_POINTING = "pose_pointing"
        const val KEY_POSE_VICTORY = "pose_victory"
        const val KEY_POSE_THUMB_UP = "pose_thumb_up"
        const val KEY_POSE_THUMB_DOWN = "pose_thumb_down"

        /**
         * Maps normalized X coordinate [0,1] to screen pixel with edge margin expansion.
         * The front camera mirror is applied (1 - x) for selfie-view mapping.
         * 10% edge margin expansion ensures corners are reachable.
         */
        fun normalizeToScreenX(normX: Float, screenWidth: Int): Float {
            // Mirror for front camera (selfie view)
            val mirrored = 1f - normX
            // Expand 10% margins so corners are reachable
            val marginFraction = EDGE_MARGIN_FRACTION
            val expanded = marginFraction + mirrored * (1f - 2f * marginFraction)
            return (expanded * screenWidth).coerceIn(0f, screenWidth.toFloat())
        }

        /**
         * Maps normalized Y coordinate [0,1] to screen pixel with edge margin expansion.
         * 10% edge margin expansion ensures corners are reachable.
         */
        fun normalizeToScreenY(normY: Float, screenHeight: Int): Float {
            val marginFraction = EDGE_MARGIN_FRACTION
            val expanded = marginFraction + normY * (1f - 2f * marginFraction)
            return (expanded * screenHeight).coerceIn(0f, screenHeight.toFloat())
        }
    }

    // Pinch state tracking
    private var pinchStartTimeMs = 0L
    private var pinchStartX = 0f
    private var pinchStartY = 0f

    // Drag state tracking
    private var isDragging = false
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragCurrentX = 0f
    private var dragCurrentY = 0f
}
