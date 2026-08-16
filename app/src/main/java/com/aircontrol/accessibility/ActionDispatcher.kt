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
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    DOUBLE_TAP,
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
 * 
 * UG-09/UG-10 Fix: Added onGestureDispatched callback for visual feedback
 */
@Singleton
class ActionDispatcher @Inject constructor(
    private val settingsRepository: SettingsRepository,
) {
    // UG-09/UG-10 Fix: Callback for visual feedback when gesture is successfully dispatched
    // The accessibility service will listen to this and trigger cursor pulse animation
    var onGestureDispatched: ((String) -> Unit)? = null

    // Emits an event every time a gesture action is successfully dispatched.
    // HomeViewModel collects this to drive the "Gestures" session counter
    // (previously incrementGestureCount() was never called, so the count was always 0).
    private val _dispatchedEvents = MutableSharedFlow<String>(
        extraBufferCapacity = 32,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val dispatchedEvents: SharedFlow<String> = _dispatchedEvents.asSharedFlow()
    // H-08 Fix: Use a scope that can be properly cancelled when the service is destroyed.
    // Previously this scope was never cancelled, which meant the three collect blocks
    // in init{} could never be cleaned up. As a @Singleton this is technically fine
    // (lives for app lifetime), but for testability and proper resource management,
    // we track the jobs so they can be cancelled in detachService().
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settingsJobs = mutableListOf<Job>()

    private var accessibilityServiceRef = WeakReference<AccessibilityService>(null)
    private var audioManager: AudioManager? = null
    private var currentPreferences = UserPreferences()

    /** 
     * Current gesture-to-action mapping. Uses AtomicReference for thread-safe
     * atomic swaps to prevent race conditions during settings updates.
     * 
     * Bug C-04 Fix: Previously used clear() + putAll() which created a brief
     * window where the map was empty, causing dropped gestures during settings
     * changes. Now uses atomic reference swap for zero-downtime updates.
     */
    private val gestureMapRef = java.util.concurrent.atomic.AtomicReference(
        ConcurrentHashMap<String, GestureAction>()
    )
    
    /** Convenience accessor for the current gesture map. */
    private val gestureMap: ConcurrentHashMap<String, GestureAction>
        get() = gestureMapRef.get()

    private val MAX_RETRIES = 1

    // Custom gestures from user configuration
    private var customGesturesList: List<CustomGesture> = emptyList()

    // Current detected pose, updated from gesture event pipeline for custom gesture matching
    @Volatile
    private var currentPose: Pose = Pose.NONE

    // Drag stroke continuation tracking (M-04: use continueStroke for drag gestures)
    private var lastDragStroke: GestureDescription.StrokeDescription? = null

    // Issue 7 Fix: Drag stability — state locking and timing mechanisms.
    // The original code dropped drag state too aggressively because:
    // 1. Each frame's pinch MOVE was treated independently (no state locking)
    // 2. A single frame of non-PINCH pose would break the drag
    // 3. No grace period for brief tracking losses
    //
    // Fixes:
    // - dragLockUntilMs: Once drag starts, keep it alive for at least GRACE_PERIOD_MS
    //   even if tracking briefly flickers. This prevents mid-drag drops.
    // - dragGraceFrames: Count consecutive non-drag frames before actually ending drag.
    //   A single bad frame won't break the drag.
    private var dragLockUntilMs: Long = 0L
    private var dragGraceFrameCount: Int = 0
    private var lastDragMoveMs: Long = 0L

    // F3 (Double-pinch): timestamp of the last dispatched tap, used to detect a
    // quick second tap within DOUBLE_TAP_WINDOW_MS and upgrade it to DOUBLE_TAP.
    @Volatile
    private var lastTapTimeMs: Long = 0L

    // Keyguard state caching to avoid IPC on every check (m-04)
    private var cachedKeyguardLocked = false
    private var keyguardReceiver: BroadcastReceiver? = null
    private var keyguardReceiverContext: android.content.Context? = null

    init {
        // Populate default gesture mappings atomically
        val defaultMap = ConcurrentHashMap<String, GestureAction>()
        defaultMap[KEY_SWIPE_LEFT] = GestureAction.SCROLL_LEFT
        defaultMap[KEY_SWIPE_RIGHT] = GestureAction.SCROLL_RIGHT
        defaultMap[KEY_SWIPE_UP] = GestureAction.SCROLL_UP
        defaultMap[KEY_SWIPE_DOWN] = GestureAction.SCROLL_DOWN
        defaultMap[KEY_POSE_PINCH] = GestureAction.TAP
        defaultMap[KEY_POSE_POINTING] = GestureAction.NONE
        defaultMap[KEY_POSE_VICTORY] = GestureAction.MEDIA_PLAY_PAUSE
        defaultMap[KEY_POSE_THUMB_UP] = GestureAction.VOLUME_UP
        defaultMap[KEY_POSE_THUMB_DOWN] = GestureAction.VOLUME_DOWN
        defaultMap[KEY_POSE_PINCH_HOLD] = GestureAction.DRAG
        defaultMap[KEY_PALM_HOME] = GestureAction.HOME
        gestureMapRef.set(defaultMap)

        // H-08 Fix: Track settings collection jobs so they can be cancelled in detachService()
        settingsJobs.add(scope.launch {
            settingsRepository.userPreferences.collect { prefs ->
                currentPreferences = prefs
            }
        })
        settingsJobs.add(scope.launch {
            settingsRepository.gestureMapConfig.collect { config ->
                // Bug C-04 Fix: Atomic swap — build the new map completely before
                // swapping the reference. This prevents the race condition where
                // dispatch() could read an empty map during clear()+putAll().
                val newMap = ConcurrentHashMap<String, GestureAction>()
                config.entries.forEach { entry ->
                    newMap[entry.key] = entry.action
                }
                gestureMapRef.set(newMap)
                Timber.d("Loaded %d gesture mappings from settings", newMap.size)
            }
        })
        settingsJobs.add(scope.launch {
            settingsRepository.customGestures.collect { gestures ->
                customGesturesList = gestures.filter { it.isEnabled }
                Timber.d("Loaded %d custom gestures", customGesturesList.size)
            }
        })
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
        dragGraceFrameCount = 0
        dragLockUntilMs = 0L
        // H-08 Fix: Cancel settings collection jobs to prevent leaks
        settingsJobs.forEach { job: Job -> job.cancel() }
        settingsJobs.clear()
        Timber.i("ActionDispatcher detached from accessibility service")
    }

    /**
     * Returns the current gesture-to-action mapping.
     */
    fun getGestureMap(): Map<String, GestureAction> = gestureMap.toMap()

    /**
     * F1 (Dwell-to-click): fires a tap at the given cursor position. Called by the
     * accessibility service when the cursor has been held still for the configured
     * dwell duration.
     */
    fun dispatchDwellTap(cursorX: Float, cursorY: Float, screenWidth: Int, screenHeight: Int): Boolean {
        if (!currentPreferences.dwellEnabled) return false
        return performFeedbackTap(cursorX, cursorY, screenWidth, screenHeight, "dwell_tap")
    }

    /**
     * F-blink (Blink-to-click): fires a tap at the given cursor position. Gated on
     * blinkClickEnabled (NOT dwellEnabled) — blink is an independent alternative
     * to dwell, so enabling "Blink to Click" alone must work.
     */
    fun dispatchBlinkTap(cursorX: Float, cursorY: Float, screenWidth: Int, screenHeight: Int): Boolean {
        if (!currentPreferences.blinkClickEnabled) return false
        return performFeedbackTap(cursorX, cursorY, screenWidth, screenHeight, "blink_tap")
    }

    /** Shared tap dispatcher + haptic + feedback for dwell/blink clicks. */
    private fun performFeedbackTap(
        cursorX: Float,
        cursorY: Float,
        screenWidth: Int,
        screenHeight: Int,
        label: String,
    ): Boolean {
        val ok = dispatchTap(cursorX, cursorY, screenWidth, screenHeight)
        if (ok) {
            performHapticFeedback()
            onGestureDispatched?.invoke(label)
            _dispatchedEvents.tryEmit(label)
        }
        return ok
    }

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
            is GestureEvent.Swipe -> dispatchSwipe(event, cursorX, cursorY, screenWidth, screenHeight)
            is GestureEvent.Pinch -> dispatchPinch(event, cursorX, cursorY, screenWidth, screenHeight)
            is GestureEvent.PoseTriggered -> dispatchPose(event, cursorX, cursorY, screenWidth, screenHeight)
            is GestureEvent.CustomGestureTriggered ->
                dispatchCustomGesture(event, cursorX, cursorY, screenWidth, screenHeight)
            is GestureEvent.PalmHome -> dispatchPalmHome(cursorX, cursorY, screenWidth, screenHeight)
            is GestureEvent.Armed,
            is GestureEvent.Disarmed,
            is GestureEvent.CursorMoved -> false
        }

        if (dispatched) {
            performHapticFeedback()
            // UG-09/UG-10 Fix: Trigger visual feedback callback
            // The accessibility service uses this to pulse the cursor, giving users
            // clear confirmation that their gesture was recognized and executed
            val actionName = getActionName(event)
            onGestureDispatched?.invoke(actionName)
            _dispatchedEvents.tryEmit(actionName)
        }

        return dispatched
    }

    // ========== Swipe dispatching ==========

    private fun dispatchSwipe(
        event: GestureEvent.Swipe,
        cursorX: Float,
        cursorY: Float,
        screenWidth: Int,
        screenHeight: Int,
    ): Boolean {
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
            // Use the actual cursor position rather than hardcoded screen center,
            // so a custom swipe mapped to TAP/DRAG/LONG_PRESS targets where the
            // user is actually pointing.
            return executeAction(customAction, cursorX, cursorY, screenWidth, screenHeight)
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
                // F8: capture the hand velocity at pinch start (the END event's
                // velocity reflects finger separation, not the approach).
                pinchStartVelocity = event.velocity
                dragStartX = normalizeToScreenX(cursorX, screenWidth)
                dragStartY = normalizeToScreenY(cursorY, screenHeight)
                dragCurrentX = dragStartX
                dragCurrentY = dragStartY
                isDragging = false
                // Issue 7 Fix: Lock drag state for a grace period
                dragLockUntilMs = System.currentTimeMillis() + DRAG_GRACE_PERIOD_MS
                dragGraceFrameCount = 0
                Timber.v("Pinch START at (%.2f, %.2f)", cursorX, cursorY)
                true
            }
            PinchPhase.MOVE -> {
                // Determine action based on hold duration
                val holdDurationMs = System.currentTimeMillis() - pinchStartTimeMs
                val effectiveAction = if (holdDurationMs >= currentPreferences.holdDuration.toLong()) {
                    gestureMap[KEY_POSE_PINCH_HOLD] ?: gestureMap[KEY_POSE_PINCH] ?: GestureAction.TAP
                } else {
                    gestureMap[KEY_POSE_PINCH] ?: GestureAction.TAP
                }
                if (effectiveAction == GestureAction.DRAG) {
                    // Issue 7 Fix: Reset grace frame counter on each MOVE.
                    // If tracking is still producing MOVE events, drag is alive.
                    dragGraceFrameCount = 0
                    lastDragMoveMs = System.currentTimeMillis()
                    dispatchDragStroke(cursorX, cursorY, screenWidth, screenHeight)
                } else {
                    false
                }
            }
            PinchPhase.END -> {
                val holdDurationMs = System.currentTimeMillis() - pinchStartTimeMs

                // F8 Stationary-click (Midas prevention): if the hand was moving
                // fast when the pinch began, treat it as accidental and do not fire
                // a tap/click. DRAG still proceeds (dragging by nature involves motion).
                val movingFast = pinchStartVelocity > STATIONARY_CLICK_VELOCITY_THRESHOLD

                // Check custom PINCH gestures (PINCH + NONE direction) first
                val customPinchAction = matchCustomGesture(Pose.PINCH)

                val effectiveAction = if (holdDurationMs >= currentPreferences.holdDuration.toLong()) {
                    gestureMap[KEY_POSE_PINCH_HOLD] ?: gestureMap[KEY_POSE_PINCH] ?: GestureAction.TAP
                } else {
                    gestureMap[KEY_POSE_PINCH] ?: GestureAction.TAP
                }

                // Custom gesture action takes priority if present
                var finalAction = customPinchAction ?: effectiveAction

                // F3 Double-pinch: upgrade a quick second tap to DOUBLE_TAP.
                val now = System.currentTimeMillis()
                if (finalAction == GestureAction.TAP) {
                    val isDoubleTap = lastTapTimeMs > 0L &&
                        (now - lastTapTimeMs) <= DOUBLE_TAP_WINDOW_MS
                    if (isDoubleTap) {
                        finalAction = GestureAction.DOUBLE_TAP
                        // Reset so the next tap starts a fresh single/double cycle.
                        lastTapTimeMs = 0L
                    } else {
                        lastTapTimeMs = now
                    }
                }

                // Apply stationary-click gate to discrete click actions (not DRAG).
                if (currentPreferences.stationaryClickEnabled &&
                    movingFast &&
                    finalAction != GestureAction.DRAG
                ) {
                    Timber.v("Stationary-click: suppressed moving-hand %s", finalAction)
                    return false
                }

                // Issue 7 Fix: Reset drag state tracking on END
                dragGraceFrameCount = 0
                dragLockUntilMs = 0L

                // Bug #2 Fix: Coordinate routing by action type.
                //
                // The Pinch event carries TWO coordinate pairs:
                //   - event.x / event.y           : live hand position (current index tip)
                //   - event.anchoredX / event.anchoredY : index tip position at pinch START
                //
                // For DRAG: the drop target must be where the user's hand is NOW
                //           (event.x/y). Using the anchored position would drop the
                //           dragged item back at the drag start — making drags feel
                //           broken (item snaps back).
                //
                // For TAP / LONG_PRESS / other discrete actions: the click target
                //           must be where the user was AIMING when they initiated
                //           the pinch (event.anchoredX/Y = pinchStartX/Y). The live
                //           position has already drifted as fingers separated.
                //
                // pinchStartX/Y (set during START from cursorX = event.anchoredX)
                // already holds the anchored position, so we keep using it for the
                // click-style actions and only override for DRAG.
                val dropX = event.x
                val dropY = event.y

                when {
                    finalAction == GestureAction.DRAG -> dispatchDragEnd(dropX, dropY, screenWidth, screenHeight)
                    holdDurationMs >= currentPreferences.holdDuration.toLong() && finalAction == effectiveAction ->
                        dispatchLongPress(pinchStartX, pinchStartY, screenWidth, screenHeight)
                    finalAction == GestureAction.NONE -> {
                        // Pinch mapped to NONE must NOT dispatch anything. Previously
                        // this fell through to the else branch and fired a spurious TAP.
                        false
                    }
                    finalAction != GestureAction.DRAG ->
                        executeAction(finalAction, pinchStartX, pinchStartY, screenWidth, screenHeight)
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
            // Bug #15 Fix: Minimum 3px displacement to guarantee the system
            // registers the touch as a tap (zero/sub-pixel displacement can be
            // misread as a touch-and-hold with no movement, which some apps
            // ignore). 3px is above the synthetic-path noise floor but well
            // below touch slop, so it reads as a discrete tap, not a scroll.
            lineTo(x + TAP_PATH_DISPLACEMENT_PX, y)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS))
            .build()

        return dispatchGestureWithRetry(gesture, "tap")
    }

    /**
     * Dispatches a double-tap at the cursor position (two quick taps).
     * Used by the F3 double-pinch gesture (Vision Pro double-tap equivalent).
     */
    private fun dispatchDoubleTap(normX: Float, normY: Float, screenWidth: Int, screenHeight: Int): Boolean {
        val x = normalizeToScreenX(normX, screenWidth)
        val y = normalizeToScreenY(normY, screenHeight)

        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + TAP_PATH_DISPLACEMENT_PX, y)
        }

        val gesture = GestureDescription.Builder()
            // Two taps with a short gap between them.
            .addStroke(GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS))
            .addStroke(GestureDescription.StrokeDescription(path, DOUBLE_TAP_GAP_MS, TAP_DURATION_MS))
            .build()

        return dispatchGestureWithRetry(gesture, "double_tap")
    }

    /**
     * Dispatches a long-press gesture at the cursor position.
     */
    private fun dispatchLongPress(normX: Float, normY: Float, screenWidth: Int, screenHeight: Int): Boolean {
        val x = normalizeToScreenX(normX, screenWidth)
        val y = normalizeToScreenY(normY, screenHeight)

        // Bug #15 Fix: Apply the same 3px minimum displacement as tap so the
        // long-press stroke is reliably registered by the gesture detector.
        val path = Path().apply { moveTo(x, y); lineTo(x + TAP_PATH_DISPLACEMENT_PX, y) }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, LONG_PRESS_DURATION_MS))
            .build()

        return dispatchGestureWithRetry(gesture, "long_press")
    }

    /**
     * Dispatches a drag stroke (continuous movement during pinch-hold).
     *
     * Issue 7 Fix: For drag MOVE events, we use the CURRENT hand position
     * (not the anchored pinch position) so the dragged item follows the hand.
     * The anchor is only used for the initial click target.
     */
    private fun dispatchDragStroke(normX: Float, normY: Float, screenWidth: Int, screenHeight: Int): Boolean {
        val x = normalizeToScreenX(normX, screenWidth)
        val y = normalizeToScreenY(normY, screenHeight)
        val fromX = dragCurrentX
        val fromY = dragCurrentY

        // Issue 7 Fix: Clamp step size to prevent wild jumps on tracking glitches
        val maxStepPx = screenWidth * MAX_DRAG_STEP_FRACTION
        val clampedX = fromX + (x - fromX).coerceIn(-maxStepPx, maxStepPx)
        val clampedY = fromY + (y - fromY).coerceIn(-maxStepPx, maxStepPx)

        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(clampedX, clampedY)
        }

        val label = if (!isDragging) "drag_start" else "drag_continue"
        isDragging = true
        dragCurrentX = clampedX
        dragCurrentY = clampedY

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

        // M-04: Use continueStroke to finalize the drag gesture.
        // Bug #14 Fix: Use DRAG_END_DURATION_MS (proportional to the increased
        // DRAG_STEP_DURATION_MS) so the final "drop" stroke has a duration
        // consistent with the preceding drag steps. Previously this was a
        // hardcoded 32L which was 2× the old 16ms step; we keep the 2× ratio
        // by making it 2× the new 50ms step (100ms).
        val stroke = if (lastDragStroke != null) {
            // Continue and finalize the drag stroke
            lastDragStroke!!.continueStroke(path, 0L, DRAG_END_DURATION_MS, false)
        } else {
            // No prior stroke — just dispatch a single stroke
            GestureDescription.StrokeDescription(path, 0L, DRAG_END_DURATION_MS)
        }
        lastDragStroke = null

        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureWithRetry(gesture, "drag_end")
    }

    // ========== Pose dispatching ==========

    /**
     * F4 (Palm → Home): dispatches the action mapped to the palm-hold gesture.
     * Gated on the palmHomeEnabled setting; defaults to HOME (visionOS 2 style).
     */
    private fun dispatchPalmHome(
        cursorX: Float,
        cursorY: Float,
        screenWidth: Int,
        screenHeight: Int,
    ): Boolean {
        if (!currentPreferences.palmHomeEnabled) return false
        val action = gestureMap[KEY_PALM_HOME] ?: GestureAction.HOME
        return executeAction(action, cursorX, cursorY, screenWidth, screenHeight)
    }

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
            Pose.OPEN_PALM, Pose.FIST, Pose.NONE,
            Pose.THREE_FINGERS, Pose.FOUR_FINGERS -> return false
        }

        val action = gestureMap[key] ?: GestureAction.NONE

        return executeAction(action, cursorX, cursorY, screenWidth, screenHeight)
    }

    /**
     * Bug: Custom Gestures Not Triggering Fix — Dispatches a user-defined custom
     * gesture that was matched via landmark template comparison in the engine.
     *
     * The engine emits [GestureEvent.CustomGestureTriggered] when the live hand
     * landmarks match a saved [LandmarkTemplate] within tolerance. This method
     * looks up the user's configured [GestureAction] by the gesture ID and
     * executes it.
     *
     * @param event The custom gesture event carrying the matched gesture ID.
     * @return true if the action was dispatched, false otherwise.
     */
    private fun dispatchCustomGesture(
        event: GestureEvent.CustomGestureTriggered,
        cursorX: Float,
        cursorY: Float,
        screenWidth: Int,
        screenHeight: Int,
    ): Boolean {
        // Find the custom gesture by ID in the cached list
        val customGesture = customGesturesList.find { it.id == event.gestureId }
        if (customGesture == null) {
            Timber.w("Custom gesture '%s' (id=%s) not found in cached list", event.gestureName, event.gestureId)
            return false
        }

        Timber.i("Dispatching custom gesture '%s' → action %s", event.gestureName, customGesture.action)
        return executeAction(customGesture.action, cursorX, cursorY, screenWidth, screenHeight)
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
                is CustomGestureTrigger.LandmarkTemplateTrigger -> {
                    // LandmarkTemplate triggers are matched by the StaticPoseClassifier's
                    // matchCustomTemplate() algorithm, NOT by this pose-based lookup.
                    // They emit CustomGestureTriggered events directly and are handled
                    // by dispatchCustomGesture(). This method (matchCustomGesture) is
                    // only called for standard PoseTriggered events, so a
                    // LandmarkTemplateTrigger can never match here — return false.
                    false
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
            GestureAction.DOUBLE_TAP -> dispatchDoubleTap(cursorX, cursorY, screenWidth, screenHeight)
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
        ContextCompat.registerReceiver(appCtx, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
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
        // Bug #15 Fix: Increased from 50ms to 90ms. Many target applications and
        // games reject taps shorter than ~80ms as "accidental touches" (Android's
        // own ViewConfiguration treats very short touch durations as potential
        // palm-touch noise). 90ms is safely above the rejection threshold while
        // still feeling instantaneous to the user (human perception of tap latency
        // is ~100ms). This fix is especially important for game UIs and launcher
        // icons that were silently swallowing 50ms taps.
        private const val TAP_DURATION_MS = 90L
        private const val LONG_PRESS_DURATION_MS = 500L
        // F3 Double-pinch: gap between the two taps in a DOUBLE_TAP gesture.
        private const val DOUBLE_TAP_GAP_MS = 80L
        // F3 Double-pinch: max time between two pinch taps to count as a double-tap.
        private const val DOUBLE_TAP_WINDOW_MS = 350L
        // F8 Stationary-click: normalized velocity (units/sec) above which a pinch
        // is treated as accidental (hand moving) and the tap is suppressed.
        private const val STATIONARY_CLICK_VELOCITY_THRESHOLD = 0.25f
        // Long-press hold threshold is user-configurable (settings "Hold Duration");
        // see currentPreferences.holdDuration — the constant has been removed.
        // Bug #15 Fix: Minimum touch-path displacement in pixels. Android's gesture
        // detector may ignore a tap whose touch path has zero or sub-pixel
        // displacement (it can't distinguish a tap from a touch-and-hold with no
        // movement). The old code used 1px, which was below the touch-slop
        // threshold on some devices and could be misread as a scroll attempt.
        // 3px is above touch slop on all known devices (typical slop is 8px, but
        // the path is a synthetic line, not a real drag, so even 3px is enough to
        // register as a discrete tap rather than a jittery hold).
        private const val TAP_PATH_DISPLACEMENT_PX = 3f
        // Bug #14 Fix: Increased from 16ms to 50ms to bridge the 24fps frame gap.
        //
        // At 24fps, frames arrive every ~42ms. With the old 16ms step duration,
        // consecutive continueStroke() calls could arrive 42ms apart while each
        // stroke was only "alive" for 16ms — Android would interpret the gap
        // between strokes as a touch-up + touch-down, breaking the continuous
        // drag into a series of taps. With 50ms step duration, each stroke stays
        // alive longer than the inter-frame interval, so Android sees a single
        // uninterrupted drag gesture.
        //
        // 80ms gives Android's gesture detector enough time to register
        // motion before the next continueStroke replaces the active stroke.
        // (Raised from 50ms: at 24fps the 42ms frame gap left only an 8ms margin,
        // so any delayed frame broke the drag into a tap series.)
        private const val DRAG_STEP_DURATION_MS = 80L
        // Bug #14 Fix: Final "drop" stroke duration. Proportional to the step
        // duration (2× ratio, matching the old hardcoded 32L vs 16L step).
        // Long enough for Android to register the final position before the
        // stroke ends with willContinue=false.
        private const val DRAG_END_DURATION_MS = 120L
        private const val HAPTIC_TICK_MS = 15L

        // ---- Virtual Box / Dead Zone viewport mapping (Bug #1 & #12 Fix) ----
        // The hand's physical range of motion is smaller than the camera viewport.
        // Instead of a power curve (which compressed edges and was hard to reason
        // about), we use a clean dead-zone approach: only the center 80% of the
        // camera X-range maps to the full screen width. Anything in the outer 10%
        // margins on either side is clamped to the nearest screen edge.
        //
        //   deadZoneStartX  = 0.10  (10% margin on the left)
        //   activeZoneWidth = 0.80  (center 80% is the active region)
        //   deadZoneEndX    = 0.90  (10% margin on the right)
        //
        // Formula:
        //   screenPos = clamp((handPos - deadZoneStart) / activeZoneWidth, 0, 1) * screenSize
        private const val X_DEAD_ZONE_START = 0.10f
        private const val X_ACTIVE_ZONE_WIDTH = 0.80f

        // Y-axis bias (Bug #12): the user should not have to lift their arm above
        // shoulder height to reach the top of the screen. Only the bottom 60% of
        // the camera Y-range maps to the full screen height; the top 40% is the
        // dead zone (clamped to the top of the screen). This means a hand held
        // at chest/lower-face height already covers the entire screen.
        //
        // FIX: MediaPipe Y is 0 at the TOP of the image and 1 at the BOTTOM. To
        // place the dead zone at the TOP (so the user doesn't have to raise
        // their hand to the camera's top edge to reach the screen top), the dead
        // zone must start at a value > 0. The previous value (0.0) put the dead
        // zone at the BOTTOM, inverting the cursor vertically.
        //
        // Precision fix: the active zone was 0.6 (1.67× vertical gain), which made
        // fine pointing hard (small hand moves = large cursor moves). Widened to
        // 0.7 (1.43× gain) and moved the dead zone to the top 30%.
        //
        //   yDeadZoneStart    = 0.3  (top 30% of camera clamps to top of screen)
        //   yActiveZoneHeight = 0.7  (bottom 70% of camera maps to full screen)
        private const val Y_DEAD_ZONE_START = 0.3f
        private const val Y_ACTIVE_ZONE_HEIGHT = 0.7f

        // Issue 7 Fix: Drag grace period — once drag starts, keep it alive
        // for at least this duration even if tracking flickers. This prevents
        // mid-drag drops caused by a single bad frame from MediaPipe.
        private const val DRAG_GRACE_PERIOD_MS = 500L

        // Issue 7 Fix: Maximum drag step per frame as fraction of screen width.
        // Prevents wild cursor jumps from tracking glitches during drag.
        private const val MAX_DRAG_STEP_FRACTION = 0.15f
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
        const val KEY_PALM_HOME = "palm_home"

        /**
         * Maps normalized X coordinate [0,1] to screen pixel using a Virtual Box
         * (dead-zone) mapping.
         *
         * Since the camera image is already mirrored in CameraService.imageProxyToMPImage
         * (selfie-view), MediaPipe landmarks are in selfie coordinates where the user's
         * right hand appears on the right side. No additional mirroring is needed here.
         *
         * VIRTUAL BOX MAPPING (Bug #1 & #12 Fix):
         * The hand's physical range of motion is smaller than the camera viewport.
         * Only the center 80% of the camera X-range maps to the full screen width.
         * The outer 10% on each side is a dead zone: hand positions there clamp to
         * the nearest screen edge. This lets the user reach screen corners without
         * having to move their hand to the edge of the camera field of view.
         *
         * Formula:
         *   screenPos = clamp((handPos - X_DEAD_ZONE_START) / X_ACTIVE_ZONE_WIDTH, 0, 1) * screenWidth
         *
         * Mapping table (with X_DEAD_ZONE_START=0.10, X_ACTIVE_ZONE_WIDTH=0.80):
         *   handPos=0.00 → screen 0.00 (clamped, left edge)
         *   handPos=0.10 → screen 0.00 (start of active zone)
         *   handPos=0.50 → screen 0.50 (center → center)
         *   handPos=0.90 → screen 1.00 (end of active zone)
         *   handPos=1.00 → screen 1.00 (clamped, right edge)
         */
        fun normalizeToScreenX(normX: Float, screenWidth: Int): Float {
            // No mirror — camera already provides selfie-view coordinates
            val activePos = (normX - dynamicXDeadZoneStart) / dynamicXActiveWidth
            val clamped = activePos.coerceIn(0f, 1f)
            return clamped * screenWidth
        }

        /**
         * Maps normalized Y coordinate [0,1] to screen pixel using a biased
         * Virtual Box mapping.
         *
         * Y-AXIS BIAS (Bug #12 Fix):
         * The user should not have to lift their arm above shoulder height to
         * reach the top of the screen. Only the bottom 60% of the camera Y-range
         * maps to the full screen height. Hand positions in the top 40% of the
         * camera clamp to the top of the screen. This lets the user operate the
         * full screen with their hand at chest/lower-face height.
         *
         * Formula:
         *   screenPos = clamp((handPos - Y_DEAD_ZONE_START) / Y_ACTIVE_ZONE_HEIGHT, 0, 1) * screenHeight
         *
         * Mapping table (with Y_DEAD_ZONE_START=0.3, Y_ACTIVE_ZONE_HEIGHT=0.7):
         *   handPos=0.00 → screen 0.00 (top of camera → clamps to top of screen)
         *   handPos=0.30 → screen 0.00 (start of active zone → top of screen)
         *   handPos=0.65 → screen 0.50 (mid active zone → center of screen)
         *   handPos=1.00 → screen 1.00 (bottom of camera → bottom of screen)
         */
        fun normalizeToScreenY(normY: Float, screenHeight: Int): Float {
            val activePos = (normY - dynamicYDeadZoneStart) / dynamicYActiveHeight
            val clamped = activePos.coerceIn(0f, 1f)
            return clamped * screenHeight
        }

        // F7 (Cursor gain) + F6 (Sit-back): dynamic dead-zone values, initialized to
        // the defaults above and adjusted at runtime from user settings.
        @Volatile
        private var dynamicXDeadZoneStart: Float = X_DEAD_ZONE_START
        @Volatile
        private var dynamicXActiveWidth: Float = X_ACTIVE_ZONE_WIDTH
        @Volatile
        private var dynamicYDeadZoneStart: Float = Y_DEAD_ZONE_START
        @Volatile
        private var dynamicYActiveHeight: Float = Y_ACTIVE_ZONE_HEIGHT

        /**
         * Applies the user's cursor gain (0..100) and sit-back mode to the
         * dead-zone mapping. Higher gain = wider active zone = less amplification
         * (slower, more precise cursor). Sit-back reduces the Y dead zone so the
         * user can reach the whole screen without raising their hand.
         */
        fun setCursorMapping(cursorGain: Int, sitBackMode: Boolean) {
            val g = cursorGain.coerceIn(0, 100) / 100f
            // X: active width 0.60..1.00 (default 0.80 at gain 50)
            dynamicXActiveWidth = 0.60f + g * 0.40f
            dynamicXDeadZoneStart = ((1f - dynamicXActiveWidth) / 2f).coerceAtLeast(0f)
            // Y: active height 0.50..0.90 (default 0.70 at gain 50)
            dynamicYActiveHeight = if (sitBackMode) {
                0.85f
            } else {
                0.50f + g * 0.40f
            }
            // Y dead zone = top of frame (clamped to screen top).
            dynamicYDeadZoneStart = (1f - dynamicYActiveHeight).coerceAtLeast(0f)
        }
    }

    // Pinch state tracking
    private var pinchStartTimeMs = 0L
    private var pinchStartX = 0f
    private var pinchStartVelocity = 0f
    private var pinchStartY = 0f

    // Drag state tracking
    private var isDragging = false
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragCurrentX = 0f
    private var dragCurrentY = 0f

    // UG-09/UG-10 Fix: Helper method to get action name for visual feedback
    private fun getActionName(event: GestureEvent): String {
        return when (event) {
            is GestureEvent.Swipe -> "swipe_${event.direction.name.lowercase()}"
            is GestureEvent.PoseTriggered -> "pose_${event.pose.name.lowercase()}"
            is GestureEvent.Pinch -> "pinch_${event.phase.name.lowercase()}"
            is GestureEvent.CustomGestureTriggered -> "custom_${event.gestureName}"
            is GestureEvent.CursorMoved -> "cursor_moved"
            is GestureEvent.Armed -> "armed"
            is GestureEvent.Disarmed -> "disarmed"
            is GestureEvent.PalmHome -> "palm_home"
        }
    }
}
