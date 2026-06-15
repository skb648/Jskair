package com.aircontrol.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.media.AudioManager
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.aircontrol.data.model.UserPreferences
import com.aircontrol.data.model.CustomGesture
import com.aircontrol.data.model.CustomGestureDirection
import com.aircontrol.data.model.CustomGesturePose
import com.aircontrol.data.model.CustomGestureTrigger
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
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
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

    private var accessibilityServiceRef = WeakReference<AccessibilityService>(null)
    private var audioManager: AudioManager? = null
    private var currentPreferences = UserPreferences()

    /** Current gesture-to-action mapping. Defaults are sensible. */
    private val gestureMap = ConcurrentHashMap<String, GestureAction>()

    private val MAX_RETRIES = 1

    // Custom gestures from user configuration
    private var customGesturesList: List<CustomGesture> = emptyList()

    // Current detected pose, updated from gesture event pipeline for custom gesture matching
    @Volatile
    private var currentPose: Pose = Pose.NONE

    // Drag stroke continuation tracking (M-04: use continueStroke for drag gestures)
    private var lastDragStroke: GestureDescription.StrokeDescription? = null

    // Keyguard state caching to avoid IPC on every check (m-04)
    private var cachedKeyguardLocked = false
    private var keyguardReceiver: BroadcastReceiver? = null
    private var keyguardReceiverContext: android.content.Context? = null

    init {
        // Populate default gesture mappings
        gestureMap[KEY_SWIPE_LEFT] = GestureAction.SCROLL_LEFT
        gestureMap[KEY_SWIPE_RIGHT] = GestureAction.SCROLL_RIGHT
        gestureMap[KEY_SWIPE_UP] = GestureAction.SCROLL_UP
        gestureMap[KEY_SWIPE_DOWN] = GestureAction.SCROLL_DOWN
        gestureMap[KEY_POSE_PINCH] = GestureAction.TAP
        gestureMap[KEY_POSE_POINTING] = GestureAction.NONE
        gestureMap[KEY_POSE_VICTORY] = GestureAction.MEDIA_PLAY_PAUSE
        gestureMap[KEY_POSE_THUMB_UP] = GestureAction.VOLUME_UP
        gestureMap[KEY_POSE_THUMB_DOWN] = GestureAction.VOLUME_DOWN
        gestureMap[KEY_POSE_PINCH_HOLD] = GestureAction.DRAG

        scope.launch {
            settingsRepository.userPreferences.collect { prefs ->
                currentPreferences = prefs
            }
        }
        scope.launch {
            settingsRepository.gestureMapConfig.collect { config ->
                val newMap = ConcurrentHashMap<String, GestureAction>()
                config.entries.forEach { entry ->
                    newMap[entry.key] = entry.action
                }
                gestureMap.clear()
                gestureMap.putAll(newMap)
                Timber.d("Loaded %d gesture mappings from settings", gestureMap.size)
            }
        }
        scope.launch {
            settingsRepository.customGestures.collect { gestures ->
                customGesturesList = gestures.filter { it.isEnabled }
                Timber.d("Loaded %d custom gestures", customGesturesList.size)
            }
        }
    }

    /**
     * Binds this dispatcher to the accessibility service instance.
     * Called when the service connects.
     */
    fun attachService(service: AccessibilityService) {
        accessibilityServiceRef = WeakReference(service)
        audioManager = service.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        // Initialize keyguard cache
        val km = service.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        cachedKeyguardLocked = km?.isDeviceLocked ?: true
        // Register keyguard state receiver
        registerKeyguardReceiver(service)
        Timber.i("ActionDispatcher attached to accessibility service")
    }

    /**
     * Detaches from the accessibility service.
     * Called when the service is destroyed.
     */
    fun detachService() {
        unregisterKeyguardReceiver()
        accessibilityServiceRef.clear()
        audioManager = null
        lastDragStroke = null
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
        // Only dispatch when armed, executing, or in cooldown
        if (engineState != GestureEngineState.ARMED &&
            engineState != GestureEngineState.EXECUTING &&
            engineState != GestureEngineState.COOLDOWN) {
            return false
        }

        // Check if gestures are enabled in settings
        if (!currentPreferences.gesturesEnabled) {
            Timber.v("Gestures disabled in settings, ignoring event")
            return false
        }

        // Track current pose for custom gesture matching in swipe dispatch
        if (event is GestureEvent.PoseTriggered) {
            currentPose = event.pose
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
        // Check custom gestures with direction first
        val customDirection = when (event.direction) {
            SwipeDirection.LEFT -> CustomGestureDirection.LEFT
            SwipeDirection.RIGHT -> CustomGestureDirection.RIGHT
            SwipeDirection.UP -> CustomGestureDirection.UP
            SwipeDirection.DOWN -> CustomGestureDirection.DOWN
        }
        val customPose = when (currentPose) {
            Pose.OPEN_PALM -> CustomGesturePose.OPEN_PALM
            Pose.FIST -> CustomGesturePose.FIST
            Pose.PINCH -> CustomGesturePose.PINCH
            Pose.POINTING -> CustomGesturePose.POINTING
            Pose.VICTORY -> CustomGesturePose.VICTORY
            Pose.THUMB_UP -> CustomGesturePose.THUMB_UP
            Pose.THUMB_DOWN -> CustomGesturePose.THUMB_DOWN
            Pose.THREE_FINGERS -> CustomGesturePose.THREE_FINGERS
            Pose.FOUR_FINGERS -> CustomGesturePose.FOUR_FINGERS
            else -> null
        }
        // TODO: D-24 — Full pose+direction matching requires passing current pose from GestureDetector.
        //  Currently we check trigger.pose against the last detected pose. If no pose was detected
        //  (customPose == null), we only match on direction for backward compatibility.
        val customAction = customGesturesList.find { gesture ->
            val trigger = gesture.triggerPose as? CustomGestureTrigger.PoseWithDirection
            trigger != null && trigger.direction == customDirection &&
                (customPose == null || trigger.pose == customPose)
        }?.action
        if (customAction != null && customAction != GestureAction.NONE) {
            return executeAction(customAction, 0.5f, 0.5f, screenWidth, screenHeight)
        }

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
        return when (event.phase) {
            PinchPhase.START -> {
                pinchStartTimeMs = System.currentTimeMillis()
                pinchStartX = cursorX
                pinchStartY = cursorY
                dragStartX = normalizeToScreenX(cursorX, screenWidth)
                dragStartY = normalizeToScreenY(cursorY, screenHeight)
                dragCurrentX = dragStartX
                dragCurrentY = dragStartY
                isDragging = false
                Timber.v("Pinch START at (%.2f, %.2f)", cursorX, cursorY)
                true
            }
            PinchPhase.MOVE -> {
                // Determine action based on hold duration
                val holdDurationMs = System.currentTimeMillis() - pinchStartTimeMs
                val effectiveAction = if (holdDurationMs >= LONG_PRESS_THRESHOLD_MS) {
                    gestureMap[KEY_POSE_PINCH_HOLD] ?: gestureMap[KEY_POSE_PINCH] ?: GestureAction.TAP
                } else {
                    gestureMap[KEY_POSE_PINCH] ?: GestureAction.TAP
                }
                if (effectiveAction == GestureAction.DRAG) {
                    dispatchDragStroke(cursorX, cursorY, screenWidth, screenHeight)
                } else {
                    false
                }
            }
            PinchPhase.END -> {
                val holdDurationMs = System.currentTimeMillis() - pinchStartTimeMs

                // Check custom PINCH gestures (PINCH + NONE direction) first
                val customPinchAction = matchCustomGesture(Pose.PINCH)

                val effectiveAction = if (holdDurationMs >= LONG_PRESS_THRESHOLD_MS) {
                    gestureMap[KEY_POSE_PINCH_HOLD] ?: gestureMap[KEY_POSE_PINCH] ?: GestureAction.TAP
                } else {
                    gestureMap[KEY_POSE_PINCH] ?: GestureAction.TAP
                }

                // Custom gesture action takes priority if present
                val finalAction = customPinchAction ?: effectiveAction

                when {
                    finalAction == GestureAction.DRAG -> dispatchDragEnd(cursorX, cursorY, screenWidth, screenHeight)
                    holdDurationMs >= LONG_PRESS_THRESHOLD_MS && finalAction == effectiveAction -> dispatchLongPress(pinchStartX, pinchStartY, screenWidth, screenHeight)
                    finalAction != GestureAction.DRAG && finalAction != GestureAction.NONE -> executeAction(finalAction, pinchStartX, pinchStartY, screenWidth, screenHeight)
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

        val path = Path().apply { moveTo(x, y); lineTo(x + 1f, y) }

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

        // M-04: Use continueStroke for continuous drag gesture
        val stroke = if (lastDragStroke == null) {
            // First drag step — start a new continuous gesture
            GestureDescription.StrokeDescription(path, 0L, DRAG_STEP_DURATION_MS, true)
        } else {
            // Subsequent steps — continue the previous stroke
            lastDragStroke!!.continueStroke(path, 0L, DRAG_STEP_DURATION_MS, true)
        }
        lastDragStroke = stroke

        val gesture = GestureDescription.Builder().addStroke(stroke).build()
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

        // M-04: Use continueStroke to finalize the drag gesture
        val stroke = if (lastDragStroke != null) {
            // Continue and finalize the drag stroke
            lastDragStroke!!.continueStroke(path, 0L, 32L, false)
        } else {
            // No prior stroke — just dispatch a single stroke
            GestureDescription.StrokeDescription(path, 0L, 32L)
        }
        lastDragStroke = null

        val gesture = GestureDescription.Builder().addStroke(stroke).build()
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
        // Check custom gestures first (higher priority)
        val customAction = matchCustomGesture(event.pose)
        if (customAction != null) {
            return executeAction(customAction, cursorX, cursorY, screenWidth, screenHeight)
        }

        val key = when (event.pose) {
            Pose.PINCH -> KEY_POSE_PINCH
            Pose.POINTING -> KEY_POSE_POINTING
            Pose.VICTORY -> KEY_POSE_VICTORY
            Pose.THUMB_UP -> KEY_POSE_THUMB_UP
            Pose.THUMB_DOWN -> KEY_POSE_THUMB_DOWN
            Pose.OPEN_PALM, Pose.FIST, Pose.NONE -> return false
        }

        val action = gestureMap[key] ?: GestureAction.NONE

        return executeAction(action, cursorX, cursorY, screenWidth, screenHeight)
    }

    /**
     * Matches a pose against custom gestures.
     * Returns the GestureAction if a match is found, null otherwise.
     */
    private fun matchCustomGesture(pose: Pose): GestureAction? {
        val customPose = when (pose) {
            Pose.OPEN_PALM -> CustomGesturePose.OPEN_PALM
            Pose.FIST -> CustomGesturePose.FIST
            Pose.PINCH -> CustomGesturePose.PINCH
            Pose.POINTING -> CustomGesturePose.POINTING
            Pose.VICTORY -> CustomGesturePose.VICTORY
            Pose.THUMB_UP -> CustomGesturePose.THUMB_UP
            Pose.THUMB_DOWN -> CustomGesturePose.THUMB_DOWN
            Pose.THREE_FINGERS -> CustomGesturePose.THREE_FINGERS
            Pose.FOUR_FINGERS -> CustomGesturePose.FOUR_FINGERS
            else -> return null
        }

        // Find a custom gesture that matches this pose with no direction requirement
        // TODO: D-23 — OPEN_PALM and FIST custom gestures can never fire because
        //  GestureEngine excludes them from PoseTriggered events (used for arm/disarm).
        //  This requires changes in GestureEngine to allow these poses through when
        //  custom gestures are configured for them.
        return customGesturesList.find { gesture ->
            when (val trigger = gesture.triggerPose) {
                is CustomGestureTrigger.PoseWithDirection ->
                    trigger.pose == customPose && trigger.direction == CustomGestureDirection.NONE
                is CustomGestureTrigger.FingerCount -> {
                    // FingerCount matching: match if the current pose's extended finger count
                    // matches the trigger's expected count. This is a simplified matching
                    // that checks total extended finger count.
                    val expectedCount = trigger.extendedFingers
                    val actualCount = when (pose) {
                        Pose.POINTING -> 1
                        Pose.VICTORY -> 2
                        Pose.THREE_FINGERS -> 3
                        Pose.FOUR_FINGERS -> 4
                        Pose.OPEN_PALM -> 5
                        else -> 0
                    }
                    actualCount == expectedCount
                }
            }
        }?.action
    }

    /**
     * Executes a GestureAction regardless of whether it came from standard or custom mapping.
     */
    private fun executeAction(
        action: GestureAction,
        cursorX: Float,
        cursorY: Float,
        screenWidth: Int,
        screenHeight: Int,
    ): Boolean {
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
                Timber.v("No action mapped for this gesture")
                false
            }
        }
    }

    // ========== Global actions ==========

    private fun performGlobalAction(action: Int): Boolean {
        val service = accessibilityServiceRef.get() ?: run {
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

    // TODO: m-06 — Replace adjustStreamVolume with VolumeProvider or AudioAttributes-based API for API 26+
    @Suppress("DEPRECATION")
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

    // TODO: m-05 — Replace dispatchMediaKeyEvent with MediaSessionManager approach for API 33+
    @Suppress("DEPRECATION")
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
        val service = accessibilityServiceRef.get() ?: run {
            Timber.w("Cannot dispatch gesture '%s': service not attached", label)
            return false
        }

        if (isKeyguardLocked()) {
            Timber.v("Keyguard locked, blocking gesture '%s'", label)
            return false
        }

        var retryCount = 0
        lateinit var callback: AccessibilityService.GestureResultCallback
        callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Timber.v("Gesture '%s' completed", label)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (retryCount < MAX_RETRIES) {
                    retryCount++
                    Timber.w("Gesture '%s' cancelled, retrying (%d/%d)", label, retryCount, MAX_RETRIES)
                    service.dispatchGesture(gesture, callback, null)
                } else {
                    Timber.w("Gesture '%s' cancelled, max retries reached", label)
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
        val service = accessibilityServiceRef.get() ?: return
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

    private fun isKeyguardLocked(): Boolean = cachedKeyguardLocked

    private fun registerKeyguardReceiver(ctx: Context) {
        val appCtx = ctx.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        cachedKeyguardLocked = true
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        cachedKeyguardLocked = false
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        appCtx.registerReceiver(receiver, filter)
        keyguardReceiver = receiver
        keyguardReceiverContext = appCtx
    }

    private fun unregisterKeyguardReceiver() {
        keyguardReceiver?.let {
            try {
                keyguardReceiverContext?.unregisterReceiver(it)
            } catch (_: Exception) {
                // Not registered
            }
        }
        keyguardReceiver = null
        keyguardReceiverContext = null
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
        private const val EDGE_MARGIN_FRACTION = 0.005f
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
        const val KEY_POSE_PINCH_HOLD = "pose_pinch_hold"

        /**
         * Maps normalized X coordinate [0,1] to screen pixel with full screen coverage.
         *
         * Since the camera image is already mirrored in CameraService.imageProxyToMPImage
         * (selfie-view), MediaPipe landmarks are in selfie coordinates where the user's
         * right hand appears on the right side. No additional mirroring is needed here.
         *
         * Full screen coverage: coordinates are mapped to the entire screen area
         * including cutout and edge-to-edge regions (Android 17+ compatible).
         * A small 2% margin prevents the cursor from being partially clipped at edges.
         */
        fun normalizeToScreenX(normX: Float, screenWidth: Int): Float {
            // No mirror — camera already provides selfie-view coordinates
            // Small margin to prevent cursor clipping at screen edges
            val marginFraction = EDGE_MARGIN_FRACTION
            val expanded = marginFraction + normX * (1f - 2f * marginFraction)
            return (expanded * screenWidth).coerceIn(0f, screenWidth.toFloat())
        }

        /**
         * Maps normalized Y coordinate [0,1] to screen pixel with full screen coverage.
         *
         * Full screen coverage for all device aspect ratios including Android 17
         * edge-to-edge display, cutouts, and any screen ratio.
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
