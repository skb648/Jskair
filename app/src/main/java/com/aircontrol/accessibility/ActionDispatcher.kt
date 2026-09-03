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
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.aircontrol.data.model.UserPreferences
import com.aircontrol.util.CrashGuard
import com.aircontrol.util.collectGuarded
import com.aircontrol.util.launchGuarded
import com.aircontrol.data.model.CustomGesture
import com.aircontrol.data.model.CustomGestureDirection
import com.aircontrol.data.model.CustomGesturePose
import com.aircontrol.data.model.CustomGestureTrigger
import com.aircontrol.data.model.FingerType
import com.aircontrol.data.repository.SettingsRepository
import com.aircontrol.gesture.model.GestureEngineState
import com.aircontrol.gesture.model.GestureEvent
import com.aircontrol.gesture.model.PinchPhase
import com.aircontrol.gesture.model.Pose
import com.aircontrol.gesture.model.SwipeDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
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
    DOUBLE_TAP,
    LONG_PRESS,
    DRAG,
}

/**
 * Maps GestureEvent → system actions using the user's gesture configuration.
 *
 * Lifecycle:
 * - [attachService] / [detachService] bind/unbind the dispatcher to the
 *   accessibility service instance. Settings collectors are started on attach
 *   and cancelled on detach, so toggling the accessibility service off and on
 *   correctly re-subscribes to preferences.
 */
@Singleton
class ActionDispatcher @Inject constructor(
    private val settingsRepository: SettingsRepository,
) {
    // Callback for visual feedback when gesture is successfully dispatched.
    // Cleared on detach to prevent leaks across service lifecycle events.
    @Volatile
    var onGestureDispatched: ((String) -> Unit)? = null

    // Emits an event every time a gesture action is successfully dispatched.
    private val _dispatchedEvents = MutableSharedFlow<String>(
        extraBufferCapacity = 32,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val dispatchedEvents: SharedFlow<String> = _dispatchedEvents.asSharedFlow()

    // Scope tied to the current service attachment; recreated on each attach.
    private var serviceScope: CoroutineScope? = null
    private val settingsJobs = mutableListOf<Job>()

    private var accessibilityServiceRef = WeakReference<AccessibilityService>(null)
    private var audioManager: AudioManager? = null
    @Volatile
    private var currentPreferences = UserPreferences()

    /**
     * Current gesture-to-action mapping. Uses AtomicReference for thread-safe
     * atomic swaps to prevent race conditions during settings updates.
     */
    private val gestureMapRef = java.util.concurrent.atomic.AtomicReference(
        ConcurrentHashMap<String, GestureAction>()
    )

    private val gestureMap: ConcurrentHashMap<String, GestureAction>
        get() = gestureMapRef.get()

    companion object {
        private const val MAX_RETRIES = 1

        private const val SCROLL_DURATION_MS = 250L
        private const val TAP_DURATION_MS = 90L
        private const val LONG_PRESS_DURATION_MS = 500L
        // A proper double-tap needs a gap between the two taps (sequential, not overlapping).
        // First stroke: 0..90ms, gap: 90..190ms, second stroke: 190..280ms.
        private const val DOUBLE_TAP_GAP_MS = 100L
        // Max time between two pinch taps to count as a double-tap.
        private const val DOUBLE_TAP_WINDOW_MS = 350L
        // Hold duration that proves a pinch was deliberate even if the hand was
        // moving when the fingers closed.
        private const val INTENTIONAL_PINCH_HOLD_MS = 180L
        // Allowed start velocity for "pinch while moving", at sensitivity 0 and 100.
        private const val MIN_MOVING_PINCH_VELOCITY = 0.35f
        private const val MAX_MOVING_PINCH_VELOCITY = 0.95f
        // Minimum touch-path displacement in pixels so Android reliably registers a tap.
        private const val TAP_PATH_DISPLACEMENT_PX = 3f
        // Drag stroke duration long enough to bridge a 24fps frame gap.
        private const val DRAG_STEP_DURATION_MS = 80L
        // Final "drop" stroke duration.
        private const val DRAG_END_DURATION_MS = 120L
        private const val HAPTIC_TICK_MS = 15L

        // Virtual box / dead-zone defaults.
        private const val X_DEAD_ZONE_START = 0.10f
        private const val X_ACTIVE_ZONE_WIDTH = 0.80f
        private const val Y_DEAD_ZONE_START = 0.3f
        private const val Y_ACTIVE_ZONE_HEIGHT = 0.7f

        // Maximum drag step per frame as fraction of screen width.
        private const val MAX_DRAG_STEP_FRACTION = 0.15f

        // Gesture map keys.
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

        fun normalizeToScreenX(normX: Float, screenWidth: Int): Float {
            val activePos = (normX - dynamicXDeadZoneStart) / dynamicXActiveWidth
            val clamped = activePos.coerceIn(0f, 1f)
            return clamped * screenWidth
        }

        fun normalizeToScreenY(normY: Float, screenHeight: Int): Float {
            val activePos = (normY - dynamicYDeadZoneStart) / dynamicYActiveHeight
            val clamped = activePos.coerceIn(0f, 1f)
            return clamped * screenHeight
        }

        @Volatile private var dynamicXDeadZoneStart: Float = X_DEAD_ZONE_START
        @Volatile private var dynamicXActiveWidth: Float = X_ACTIVE_ZONE_WIDTH
        @Volatile private var dynamicYDeadZoneStart: Float = Y_DEAD_ZONE_START
        @Volatile private var dynamicYActiveHeight: Float = Y_ACTIVE_ZONE_HEIGHT

        fun setCursorMapping(cursorGain: Int, sitBackMode: Boolean) {
            val g = cursorGain.coerceIn(0, 100) / 100f
            dynamicXActiveWidth = 0.60f + g * 0.40f
            dynamicXDeadZoneStart = ((1f - dynamicXActiveWidth) / 2f).coerceAtLeast(0f)
            dynamicYActiveHeight = if (sitBackMode) {
                0.85f
            } else {
                0.50f + g * 0.40f
            }
            dynamicYDeadZoneStart = (1f - dynamicYActiveHeight).coerceAtLeast(0f)
        }

        /**
         * Which digits are extended for each engine pose, used to evaluate the
         * user-recorded "N fingers" custom gestures in [matchCustomGesture].
         */
        private val FINGERS_PER_POSE: Map<Pose, Set<FingerType>> = mapOf(
            Pose.OPEN_PALM to setOf(
                FingerType.THUMB, FingerType.INDEX, FingerType.MIDDLE,
                FingerType.RING, FingerType.PINKY,
            ),
            Pose.FOUR_FINGERS to setOf(
                FingerType.INDEX, FingerType.MIDDLE, FingerType.RING, FingerType.PINKY,
            ),
            Pose.THREE_FINGERS to setOf(
                FingerType.INDEX, FingerType.MIDDLE, FingerType.RING,
            ),
            Pose.VICTORY to setOf(FingerType.INDEX, FingerType.MIDDLE),
            Pose.POINTING to setOf(FingerType.INDEX),
            Pose.THUMB_UP to setOf(FingerType.THUMB),
            Pose.THUMB_DOWN to setOf(FingerType.THUMB),
            Pose.PINCH to emptySet(),
            Pose.FIST to emptySet(),
        )

        /** Build a default gesture map (used both on init and on re-attach). */
        private fun buildDefaultMap(): ConcurrentHashMap<String, GestureAction> {
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
            return defaultMap
        }
    }

    // Custom gestures from user configuration.
    @Volatile
    private var customGesturesList: List<CustomGesture> = emptyList()

    // Current detected pose, updated from gesture event pipeline for custom gesture matching.
    @Volatile
    private var currentPose: Pose = Pose.NONE

    // Drag stroke continuation tracking.
    private var lastDragStroke: GestureDescription.StrokeDescription? = null
    @Volatile
    private var isDragging = false
    @Volatile
    private var dragCurrentX = 0f
    @Volatile
    private var dragCurrentY = 0f

    // Pinch state tracking.
    @Volatile private var pinchStartTimeMs = 0L
    @Volatile private var pinchStartX = 0f
    @Volatile private var pinchStartY = 0f
    @Volatile private var pinchStartVelocity = 0f
    // Tracks whether the current pinch has actually turned into a drag.
    @Volatile private var pinchIsDrag = false

    // F3 (Double-pinch): timestamp of last dispatched tap (after stationary-click gate passes).
    @Volatile
    private var lastTapTimeMs: Long = 0L

    // Keyguard state caching.
    @Volatile
    private var cachedKeyguardLocked = false
    private var keyguardReceiver: BroadcastReceiver? = null
    private var keyguardReceiverContext: android.content.Context? = null

    init {
        gestureMapRef.set(buildDefaultMap())
    }

    /**
     * Binds this dispatcher to the accessibility service instance.
     * Re-starts settings collectors so preference changes are picked up even if
     * the service was detached and re-attached (e.g. a11y toggle off/on).
     */
    fun attachService(service: AccessibilityService) {
        // Cancel any leftover jobs from a previous attach.
        cancelSettingsJobs()

        accessibilityServiceRef = WeakReference(service)
        audioManager = service.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        // Initialize keyguard cache using the proper APIs.
        val km = service.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        cachedKeyguardLocked = km?.isKeyguardLocked ?: true

        registerKeyguardReceiver(service)

        // Create a fresh scope and subscribe to settings.
        //
        // Fix B-2: this scope lives exactly as long as the accessibility service
        // and had no exception handler at all. A throw inside any collector below
        // (an unexpected preference value, an OEM DataStore hiccup) reached the
        // thread's default uncaught handler and killed the process, which is what
        // "the app closes itself the moment I enable accessibility" actually was.
        // Every collector is now guarded and self-restarting, so one bad read
        // degrades to "keep the last known settings" instead of a crash.
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Main.immediate + CrashGuard.handler,
        )
        serviceScope = scope

        settingsJobs.add(scope.launchGuarded("dispatcher settings", restart = true) {
            settingsRepository.userPreferences.collectGuarded("dispatcher settings") { prefs ->
                currentPreferences = prefs
            }
        })
        settingsJobs.add(scope.launchGuarded("gesture map", restart = true) {
            settingsRepository.gestureMapConfig.collectGuarded("gesture map") { config ->
                val newMap = buildDefaultMap()
                config.entries.forEach { entry ->
                    newMap[entry.key] = entry.action
                }
                gestureMapRef.set(newMap)
                Timber.d("Loaded %d gesture mappings from settings", newMap.size)
            }
        })
        settingsJobs.add(scope.launchGuarded("custom gestures", restart = true) {
            settingsRepository.customGestures.collectGuarded("custom gestures") { gestures ->
                customGesturesList = gestures.filter { it.isEnabled }
                Timber.d("Loaded %d custom gestures", customGesturesList.size)
            }
        })

        Timber.i("ActionDispatcher attached to accessibility service")
    }

    /**
     * Detaches from the accessibility service.
     * Cancels settings collectors and clears the callback to prevent leaks.
     */
    fun detachService() {
        unregisterKeyguardReceiver()
        cancelSettingsJobs()
        serviceScope?.cancel()
        serviceScope = null
        accessibilityServiceRef.clear()
        audioManager = null
        lastDragStroke = null
        onGestureDispatched = null
        isDragging = false
        Timber.i("ActionDispatcher detached from accessibility service")
    }

    private fun cancelSettingsJobs() {
        settingsJobs.forEach { it.cancel() }
        settingsJobs.clear()
    }

    /**
     * Returns the current gesture-to-action mapping (read-only snapshot).
     */
    fun getGestureMap(): Map<String, GestureAction> = gestureMap.toMap()

    /**
     * Drops all in-flight synthetic-gesture state.
     *
     * Fix B-6: the system interrupts a dispatched gesture whenever it needs the
     * screen back (incoming-call UI, the user touching the display, a shade
     * swipe). A drag is a *chain* of continuing strokes, so once the chain is
     * interrupted every later `continueStroke()` on the dead stroke throws
     * IllegalArgumentException - which used to leave drags permanently broken (and
     * spam an exception per frame) until the service was restarted. The service
     * calls this from onInterrupt() so the next pinch starts a fresh chain.
     */
    fun resetTransientGestureState() {
        synchronized(this) {
            lastDragStroke = null
            isDragging = false
            pinchIsDrag = false
        }
    }

    fun dispatchDwellTap(cursorX: Float, cursorY: Float, screenWidth: Int, screenHeight: Int): Boolean {
        if (!currentPreferences.gesturesEnabled || !currentPreferences.dwellEnabled) return false
        return performFeedbackTap(cursorX, cursorY, screenWidth, screenHeight, "dwell_tap")
    }

    fun dispatchBlinkTap(cursorX: Float, cursorY: Float, screenWidth: Int, screenHeight: Int): Boolean {
        if (!currentPreferences.gesturesEnabled || !currentPreferences.blinkClickEnabled) return false
        return performFeedbackTap(cursorX, cursorY, screenWidth, screenHeight, "blink_tap")
    }

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
     * Update a single gesture mapping. Persistence is handled by the caller via
     * [SettingsRepository]; this method atomically swaps the map to avoid races.
     */
    fun updateGestureAction(key: String, action: GestureAction) {
        while (true) {
            val current = gestureMapRef.get()
            val next = ConcurrentHashMap(current)
            next[key] = action
            if (gestureMapRef.compareAndSet(current, next)) break
        }
        Timber.d("Updated gesture mapping: %s → %s", key, action)
    }

    fun dispatch(
        event: GestureEvent,
        engineState: GestureEngineState,
        cursorX: Float,
        cursorY: Float,
        screenWidth: Int,
        screenHeight: Int,
    ): Boolean {
        if (engineState != GestureEngineState.ARMED &&
            engineState != GestureEngineState.EXECUTING &&
            engineState != GestureEngineState.COOLDOWN) {
            return false
        }

        if (!currentPreferences.gesturesEnabled) {
            Timber.v("Gestures disabled in settings, ignoring event")
            return false
        }

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

        // Only fire haptics + ripple + counter for *discrete* actions, not for
        // every frame of a drag move (which would buzz continuously and spam the counter).
        if (dispatched) {
            val isDragMove = event is GestureEvent.Pinch && event.phase == PinchPhase.MOVE
            val isPinchStart = event is GestureEvent.Pinch && event.phase == PinchPhase.START
            if (!isDragMove) {
                performHapticFeedback()
            }
            val actionName = getActionName(event)
            // Don't spam visual feedback on drag-move frames either.
            if (!isDragMove && !isPinchStart) {
                onGestureDispatched?.invoke(actionName)
                _dispatchedEvents.tryEmit(actionName)
            }
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
        val customAction = customGesturesList.find { gesture ->
            val trigger = gesture.triggerPose as? CustomGestureTrigger.PoseWithDirection
            trigger != null && trigger.direction == customDirection &&
                (customPose == null || trigger.pose == customPose)
        }?.action
        if (customAction != null && customAction != GestureAction.NONE) {
            return executeAction(customAction, cursorX, cursorY, screenWidth, screenHeight)
        }

        val action = when (event.direction) {
            SwipeDirection.LEFT -> gestureMap[KEY_SWIPE_LEFT] ?: GestureAction.NONE
            SwipeDirection.RIGHT -> gestureMap[KEY_SWIPE_RIGHT] ?: GestureAction.NONE
            SwipeDirection.UP -> gestureMap[KEY_SWIPE_UP] ?: GestureAction.NONE
            SwipeDirection.DOWN -> gestureMap[KEY_SWIPE_DOWN] ?: GestureAction.NONE
        }

        return when (action) {
            // Fix A-22: scroll strokes are anchored on the cursor instead of the
            // middle of the screen. On a tablet, in two-pane apps, or next to a
            // side panel, a centre-screen swipe scrolled the wrong list; now the
            // column the user is pointing at is the column that scrolls.
            GestureAction.SCROLL_UP -> dispatchScrollGesture(
                screenWidth, screenHeight, scrollUp = true, anchorX = normalizeToScreenX(cursorX, screenWidth),
            )
            GestureAction.SCROLL_DOWN -> dispatchScrollGesture(
                screenWidth, screenHeight, scrollUp = false, anchorX = normalizeToScreenX(cursorX, screenWidth),
            )
            GestureAction.SCROLL_LEFT -> dispatchHorizontalScroll(
                screenWidth, screenHeight, scrollLeft = true, anchorY = normalizeToScreenY(cursorY, screenHeight),
            )
            GestureAction.SCROLL_RIGHT -> dispatchHorizontalScroll(
                screenWidth, screenHeight, scrollLeft = false, anchorY = normalizeToScreenY(cursorY, screenHeight),
            )
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

    private fun dispatchScrollGesture(
        screenWidth: Int,
        screenHeight: Int,
        scrollUp: Boolean,
        anchorX: Float = screenWidth / 2f,
    ): Boolean {
        // Keep the stroke inside the "safe band" so it can never be interpreted as
        // an edge swipe (Back/Recents) or land on a system gesture area.
        val centerX = anchorX.coerceIn(screenWidth * 0.15f, screenWidth * 0.85f)
        val startY = if (scrollUp) screenHeight * 0.7f else screenHeight * 0.3f
        val endY = if (scrollUp) screenHeight * 0.3f else screenHeight * 0.7f

        val path = Path().apply {
            moveTo(centerX, startY)
            val steps = 10
            for (i in 1..steps) {
                val t = i.toFloat() / steps
                val easedT = 1f - (1f - t) * (1f - t)
                lineTo(centerX, startY + (endY - startY) * easedT)
            }
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, SCROLL_DURATION_MS))
            .build()

        return dispatchGestureWithRetry(gesture, "scroll_${if (scrollUp) "up" else "down"}")
    }

    private fun dispatchHorizontalScroll(
        screenWidth: Int,
        screenHeight: Int,
        scrollLeft: Boolean,
        anchorY: Float = screenHeight / 2f,
    ): Boolean {
        val centerY = anchorY.coerceIn(screenHeight * 0.20f, screenHeight * 0.80f)
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
                pinchStartTimeMs = SystemClock.uptimeMillis()
                pinchStartX = cursorX
                pinchStartY = cursorY
                pinchStartVelocity = event.velocity
                dragCurrentX = normalizeToScreenX(cursorX, screenWidth)
                dragCurrentY = normalizeToScreenY(cursorY, screenHeight)
                isDragging = false
                pinchIsDrag = false
                // Only fire haptic/ripple for START when the mapped action isn't NONE.
                val startAction = gestureMap[KEY_POSE_PINCH] ?: GestureAction.TAP
                startAction != GestureAction.NONE
            }
            PinchPhase.MOVE -> {
                val holdDurationMs = SystemClock.uptimeMillis() - pinchStartTimeMs
                val effectiveAction = if (holdDurationMs >= currentPreferences.holdDuration.toLong()) {
                    gestureMap[KEY_POSE_PINCH_HOLD] ?: gestureMap[KEY_POSE_PINCH] ?: GestureAction.TAP
                } else {
                    gestureMap[KEY_POSE_PINCH] ?: GestureAction.TAP
                }
                if (effectiveAction == GestureAction.DRAG) {
                    pinchIsDrag = true
                    // Dispatch drag stroke without triggering haptic/ripple per frame.
                    // Return true but the outer dispatch() skips haptic/feedback for MOVE.
                    dispatchDragStroke(cursorX, cursorY, screenWidth, screenHeight)
                } else {
                    false
                }
            }
            PinchPhase.END -> {
                val now = SystemClock.uptimeMillis()
                val holdDurationMs = now - pinchStartTimeMs

                val movingFast = isAccidentalMovingPinch(pinchStartVelocity, holdDurationMs)

                val customPinchAction = matchCustomGesture(Pose.PINCH)

                val effectiveAction = if (holdDurationMs >= currentPreferences.holdDuration.toLong()) {
                    gestureMap[KEY_POSE_PINCH_HOLD] ?: gestureMap[KEY_POSE_PINCH] ?: GestureAction.TAP
                } else {
                    gestureMap[KEY_POSE_PINCH] ?: GestureAction.TAP
                }

                val finalAction = customPinchAction ?: effectiveAction

                // Stationary-click gate: suppress accidental taps from moving hands.
                // Apply BEFORE recording lastTapTime so suppressed pinches don't
                // prime the double-tap window.
                if (currentPreferences.stationaryClickEnabled &&
                    movingFast &&
                    finalAction != GestureAction.DRAG) {
                    Timber.v("Stationary-click: suppressed moving-hand %s", finalAction)
                    return false
                }

                // If this pinch turned into a drag, end it and return.
                if (pinchIsDrag || finalAction == GestureAction.DRAG) {
                    return dispatchDragEnd(event.x, event.y, screenWidth, screenHeight)
                }

                if (finalAction == GestureAction.NONE) {
                    return false
                }

                // Long press only fires when the mapped hold action is actually LONG_PRESS,
                // not for any arbitrary held action (e.g. VOLUME_UP held should not become
                // a long-press on screen).
                if (holdDurationMs >= currentPreferences.holdDuration.toLong() &&
                    finalAction == GestureAction.LONG_PRESS) {
                    val dispatched = dispatchLongPress(pinchStartX, pinchStartY, screenWidth, screenHeight)
                    if (dispatched) lastTapTimeMs = now
                    return dispatched
                }

                // Double-tap detection: if this tap is within DOUBLE_TAP_WINDOW_MS of the
                // last successful tap AND the mapped action is TAP (not something else),
                // upgrade to DOUBLE_TAP.
                val withinDoubleTapWindow = (now - lastTapTimeMs) in 1..DOUBLE_TAP_WINDOW_MS
                val actionToDispatch = if (withinDoubleTapWindow && finalAction == GestureAction.TAP) {
                    GestureAction.DOUBLE_TAP
                } else {
                    finalAction
                }

                val dispatched = executeAction(actionToDispatch, pinchStartX, pinchStartY, screenWidth, screenHeight)
                if (dispatched) {
                    lastTapTimeMs = now
                }
                dispatched
            }
        }
    }

    /**
     * Decides whether a pinch-release was an accident (a pinch that merely
     * happened while the hand was travelling to a target).
     *
     * Fix A-7: this used to be a single hard-coded ceiling of 0.25 normalized
     * units per second, applied no matter how long the user held the pinch. A
     * normal reach easily exceeds that, so a large share of deliberate clicks
     * were silently dropped at END: the dot was on the button, the user pinched,
     * nothing happened, no feedback. That was the second-most reported complaint
     * after arming ("half my taps do nothing").
     *
     * The gate now:
     *  - only applies while the user keeps "ignore pinches while moving" on,
     *  - scales the allowed velocity with the sensitivity slider,
     *  - always honours a deliberately held pinch (holding IS intent), and
     *  - never suppresses a drag (the drag path handles its own motion).
     */
    private fun isAccidentalMovingPinch(startVelocity: Float, holdDurationMs: Long): Boolean {
        if (!currentPreferences.stationaryClickEnabled) return false
        if (holdDurationMs >= INTENTIONAL_PINCH_HOLD_MS) return false
        val norm = currentPreferences.sensitivity.coerceIn(0, 100) / 100f
        val allowedVelocity = MIN_MOVING_PINCH_VELOCITY +
            norm * (MAX_MOVING_PINCH_VELOCITY - MIN_MOVING_PINCH_VELOCITY)
        if (startVelocity > allowedVelocity) {
            Timber.d(
                "Stationary-click: suppressed pinch (startVelocity=%.2f > %.2f, hold=%dms)",
                startVelocity, allowedVelocity, holdDurationMs,
            )
            return true
        }
        return false
    }

    private fun dispatchTap(normX: Float, normY: Float, screenWidth: Int, screenHeight: Int): Boolean {
        val x = normalizeToScreenX(normX, screenWidth)
        val y = normalizeToScreenY(normY, screenHeight)

        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + TAP_PATH_DISPLACEMENT_PX, y)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS))
            .build()

        return dispatchGestureWithRetry(gesture, "tap")
    }

    private fun dispatchDoubleTap(normX: Float, normY: Float, screenWidth: Int, screenHeight: Int): Boolean {
        val x = normalizeToScreenX(normX, screenWidth)
        val y = normalizeToScreenY(normY, screenHeight)

        // Use two separate Paths to make the two taps clearly sequential strokes.
        // Tap 1: 0..TAP_DURATION_MS; gap until second tap starts at TAP_DURATION_MS+DOUBLE_TAP_GAP_MS.
        val path1 = Path().apply {
            moveTo(x, y)
            lineTo(x + TAP_PATH_DISPLACEMENT_PX, y)
        }
        val path2 = Path().apply {
            moveTo(x, y)
            lineTo(x + TAP_PATH_DISPLACEMENT_PX, y)
        }

        val secondStart = TAP_DURATION_MS + DOUBLE_TAP_GAP_MS
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path1, 0L, TAP_DURATION_MS))
            .addStroke(GestureDescription.StrokeDescription(path2, secondStart, TAP_DURATION_MS))
            .build()

        return dispatchGestureWithRetry(gesture, "double_tap")
    }

    private fun dispatchLongPress(normX: Float, normY: Float, screenWidth: Int, screenHeight: Int): Boolean {
        val x = normalizeToScreenX(normX, screenWidth)
        val y = normalizeToScreenY(normY, screenHeight)

        val path = Path().apply { moveTo(x, y); lineTo(x + TAP_PATH_DISPLACEMENT_PX, y) }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, LONG_PRESS_DURATION_MS))
            .build()

        return dispatchGestureWithRetry(gesture, "long_press")
    }

    private fun dispatchDragStroke(normX: Float, normY: Float, screenWidth: Int, screenHeight: Int): Boolean {
        val x = normalizeToScreenX(normX, screenWidth)
        val y = normalizeToScreenY(normY, screenHeight)
        val fromX = dragCurrentX
        val fromY = dragCurrentY

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

        val previousStroke = lastDragStroke
        val stroke = if (previousStroke == null) {
            GestureDescription.StrokeDescription(path, 0L, DRAG_STEP_DURATION_MS, true)
        } else {
            previousStroke.continueStroke(path, 0L, DRAG_STEP_DURATION_MS, true)
        }
        lastDragStroke = stroke

        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureWithRetry(gesture, label)
    }

    private fun dispatchDragEnd(normX: Float, normY: Float, screenWidth: Int, screenHeight: Int): Boolean {
        isDragging = false
        pinchIsDrag = false
        val x = normalizeToScreenX(normX, screenWidth)
        val y = normalizeToScreenY(normY, screenHeight)

        val path = Path().apply {
            moveTo(dragCurrentX, dragCurrentY)
            lineTo(x, y)
        }

        val previousStroke = lastDragStroke
        val stroke = if (previousStroke != null) {
            // Continue from the last stroke so Android sees a single continuous drag.
            previousStroke.continueStroke(path, 0L, DRAG_END_DURATION_MS, false)
        } else {
            GestureDescription.StrokeDescription(path, 0L, DRAG_END_DURATION_MS)
        }
        lastDragStroke = null

        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val result = dispatchGestureWithRetry(gesture, "drag_end")
        // Do not update lastTapTimeMs for drags - they shouldn't prime double-tap.
        return result
    }

    // ========== Pose dispatching ==========

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

    private fun dispatchCustomGesture(
        event: GestureEvent.CustomGestureTriggered,
        cursorX: Float,
        cursorY: Float,
        screenWidth: Int,
        screenHeight: Int,
    ): Boolean {
        val customGesture = customGesturesList.find { it.id == event.gestureId }
        if (customGesture == null) {
            Timber.w("Custom gesture '%s' (id=%s) not found in cached list", event.gestureName, event.gestureId)
            return false
        }

        Timber.i("Dispatching custom gesture '%s' → action %s", event.gestureName, customGesture.action)
        return executeAction(customGesture.action, cursorX, cursorY, screenWidth, screenHeight)
    }

    /**
     * Which digits are up for each engine pose. Kept in one place so the count and
     * the identity checks in [matchCustomGesture] cannot drift apart.
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

        return customGesturesList.find { gesture ->
            when (val trigger = gesture.triggerPose) {
                is CustomGestureTrigger.PoseWithDirection ->
                    trigger.pose == customPose && trigger.direction == CustomGestureDirection.NONE

                is CustomGestureTrigger.FingerCount -> {
                    // Fix C-3: the previous table counted only index-style poses, so
                    // THUMB_UP / THUMB_DOWN / PINCH / FIST were mapped to 0 extended
                    // fingers. A gesture recorded as "one finger" while giving a
                    // thumbs-up was therefore saved happily and never fired - exactly
                    // the "custom gestures do nothing" report. Counting now includes
                    // the thumb, matches the same definition the capture flow uses
                    // (FingerExtensionDetector), and additionally honours whichFingers
                    // when the recording captured which digits were up.
                    val fingers = FINGERS_PER_POSE[pose] ?: return@find false
                    fingers.size == trigger.extendedFingers &&
                        (trigger.whichFingers.isEmpty() || fingers == trigger.whichFingers)
                }

                // Templates are matched inside the engine (StaticPoseClassifier), which
                // emits CustomGestureTriggered for them; nothing to do on the pose path.
                is CustomGestureTrigger.LandmarkTemplateTrigger -> false
            }
        }?.action
    }

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
            GestureAction.DRAG -> false
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

    @Suppress("DEPRECATION")
    private fun adjustVolume(up: Boolean): Boolean {
        val am = audioManager ?: return false
        val direction = if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        return try {
            // Fix #28: prefer adjustVolume() (API 26+) which routes to the active
            // stream, falling back to STREAM_MUSIC on API 26 as a safety net.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                am.adjustVolume(direction, AudioManager.FLAG_SHOW_UI)
            } else {
                am.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    direction,
                    AudioManager.FLAG_SHOW_UI,
                )
            }
            Timber.i("Volume %s dispatched", if (up) "up" else "down")
            true
        } catch (e: SecurityException) {
            Timber.e(e, "Cannot adjust volume — permission denied")
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun toggleMediaPlayback(): Boolean {
        val am = audioManager ?: return false
        return try {
            val now = SystemClock.uptimeMillis()
            val downEvent = KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0)
            val upEvent = KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0)
            am.dispatchMediaKeyEvent(downEvent)
            am.dispatchMediaKeyEvent(upEvent)
            Timber.i("Media play/pause dispatched")
            true
        } catch (e: SecurityException) {
            Timber.e(e, "Cannot dispatch media key event")
            false
        }
    }

    private fun takeScreenshot(): Boolean {
        // GLOBAL_ACTION_TAKE_SCREENSHOT was added in API 30, not 28.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Timber.w("Screenshot global action requires API 30+ (current: %d)", Build.VERSION.SDK_INT)
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

        // Don't retry drag continue-strokes — retrying a stale segment mid-drag
        // causes the cursor to jump back. Retries are only meaningful for
        // one-shot gestures (tap, scroll, drag_end).
        val shouldRetry = label != "drag_continue" && label != "drag_start"

        var retryCount = 0
        var result = false
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Timber.v("Gesture '%s' completed", label)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (shouldRetry && retryCount < MAX_RETRIES) {
                    retryCount++
                    Timber.w("Gesture '%s' cancelled, retrying (%d/%d)", label, retryCount, MAX_RETRIES)
                    // Build a fresh GestureDescription for the retry — re-dispatching
                    // the completed/cancelled one can corrupt the gesture.
                    // This runs on a Binder thread with nothing above it to catch a
                    // throw, so it must never be allowed to propagate.
                    val again = runCatching { service.dispatchGesture(gesture, this, null) }
                        .getOrDefault(false)
                    if (!again) Timber.w("Gesture '%s' retry could not be dispatched", label)
                } else {
                    Timber.w("Gesture '%s' cancelled, max retries reached", label)
                }
            }
        }

        // dispatchGesture is documented to return false when it cannot accept the
        // gesture, but OEM implementations also throw here (IllegalArgumentException
        // for a stale continued stroke, SecurityException on a locked device). An
        // exception out of here used to escape into the collector that drives every
        // gesture, so a single bad frame could take the pipeline down.
        result = runCatching { service.dispatchGesture(gesture, callback, null) }
            .getOrElse { error ->
                Timber.w(error, "Gesture '%s' threw while dispatching - dropping it", label)
                resetTransientGestureState()
                false
            }
        Timber.v("Gesture '%s' dispatch result: %s", label, result)
        return result
    }

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
                    Intent.ACTION_SCREEN_OFF -> cachedKeyguardLocked = true
                    Intent.ACTION_SCREEN_ON -> {
                        // Re-check keyguard state when screen turns on; it may already be unlocked.
                        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                        cachedKeyguardLocked = km?.isKeyguardLocked ?: true
                    }
                    Intent.ACTION_USER_PRESENT -> cachedKeyguardLocked = false
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
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
                // Not registered.
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
