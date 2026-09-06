package com.aircontrol.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import com.aircontrol.data.model.CustomGesture
import com.aircontrol.data.model.CustomGestureDirection
import com.aircontrol.data.model.CustomGesturePose
import com.aircontrol.data.model.CustomGestureTrigger
import com.aircontrol.data.model.FingerType
import com.aircontrol.data.model.UserPreferences
import com.aircontrol.data.repository.SettingsRepository
import com.aircontrol.gesture.model.GestureEngineState
import com.aircontrol.gesture.model.GestureEvent
import com.aircontrol.gesture.model.PinchPhase
import com.aircontrol.gesture.model.Pose
import com.aircontrol.gesture.model.SwipeDirection
import com.aircontrol.ui.Suppression
import com.aircontrol.util.CrashGuard
import com.aircontrol.util.collectGuarded
import com.aircontrol.util.launchGuarded
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
 *   and cancelled on detach, so toggling the accessibility service off/on
 *   correctly re-subscribes to preferences.
 */
@Singleton
class ActionDispatcher @Inject constructor(
    private val settingsRepository: SettingsRepository,
) {
    @Volatile
    var onGestureDispatched: ((String) -> Unit)? = null

    private val _dispatchedEvents = MutableSharedFlow<String>(
        extraBufferCapacity = 32,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val dispatchedEvents: SharedFlow<String> = _dispatchedEvents.asSharedFlow()

    private var serviceScope: CoroutineScope? = null
    private val settingsJobs = mutableListOf<Job>()
    private var accessibilityServiceRef = WeakReference<AccessibilityService>(null)
    private var audioManager: AudioManager? = null
    @Volatile
    private var currentPreferences = UserPreferences()

    private val gestureMapRef = java.util.concurrent.atomic.AtomicReference(
        ConcurrentHashMap<String, GestureAction>()
    )

    private val gestureMap: ConcurrentHashMap<String, GestureAction>
        get() = gestureMapRef.get() ?: buildDefaultMap()

    companion object {
        private const val MAX_RETRIES = 1
        private const val SCROLL_DURATION_MS = 250L
        private const val TAP_DURATION_MS = 90L
        private const val LONG_PRESS_DURATION_MS = 500L
        private const val DOUBLE_TAP_GAP_MS = 100L
        private const val DOUBLE_TAP_WINDOW_MS = 350L
        private const val INTENTIONAL_PINCH_HOLD_MS = 180L
        private const val MIN_MOVING_PINCH_VELOCITY = 0.35f
        private const val MAX_MOVING_PINCH_VELOCITY = 0.95f
        private const val TAP_PATH_DISPLACEMENT_PX = 3f
        private const val DRAG_STEP_DURATION_MS = 80L
        private const val DRAG_END_DURATION_MS = 120L
        private const val HAPTIC_TICK_MS = 15L

        private const val MAX_DRAG_STEP_FRACTION = 0.15f

        // Fix P1: a pinch-held-MOVE must travel this far (fraction of screen
        // width) before the dispatcher starts a DRAG stroke. Previously the
        // first MOVE frame — which the engine emits on EVERY frame of a held
        // pinch, moving or not — started a continued drag stroke immediately,
        // and Pinch END then saw isDragging and returned *without* dispatching
        // the tap. Every "tap" was really an abandoned drag stroke
        // (DOWN…CANCEL), which apps and OEMs treat inconsistently: the
        // "pinch registers visually but nothing clicks" complaint.
        private const val DRAG_START_SLOP_FRACTION = 0.025f
        private const val MIN_DRAG_START_SLOP_PX = 12f

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

        @Volatile private var cursorGain = 0.5f
        @Volatile private var sitBackModeEnabled = false

        /**
         * Fix B1: the pointer-gain factor derived from the "Cursor Speed"
         * setting. 0 → 0.5× (slow, precise), 50 (default) → 1.0× (unchanged
         * from the previous fixed behaviour), 100 → 1.5× (fast). The gain is
         * applied around the screen centre after the dead-zone mapping, so the
         * screen edges stay reachable at every gain (results are clamped).
         */
        private fun pointerGainFactor(): Float = 0.5f + cursorGain.coerceIn(0f, 1f)

        /**
         * Fix A2: gaze coordinates are already screen-normalized (either the
         * calibrated affine/personalized output or the gain+invert applied in
         * the service). They must be mapped straight to pixels — routing them
         * through the hand mapping's margins and dead zones shifted the whole
         * eye cursor up by ~21% of the screen even after a perfect calibration.
         */
        fun normalizeDirect(norm: Float, screenDim: Int): Float =
            if (screenDim <= 0) 0f else norm.coerceIn(0f, 1f) * screenDim

        fun normalizeToScreenX(normX: Float, screenWidth: Int): Float {
            if (screenWidth <= 0) return 0f
            val margin = 0.10f
            val active = 1.0f - 2f * margin
            val clamped = normX.coerceIn(0f, 1f)
            val mapped = ((clamped - margin) / active).coerceIn(0f, 1f)
            // Fix B1: apply the user's pointer gain (default 1.0× at 50%).
            val amplified = 0.5f + (mapped - 0.5f) * pointerGainFactor()
            return amplified.coerceIn(0f, 1f) * screenWidth
        }

        fun normalizeToScreenY(normY: Float, screenHeight: Int): Float {
            if (screenHeight <= 0) return 0f
            // Fix B2: 0.30 top dead-zone meant the hand had to be pushed very
            // low in the camera frame to reach the bottom of the screen. 0.20
            // (and 0.10 in sit-back mode) keeps the whole screen reachable with
            // comfortable hand heights.
            val topDeadZone = if (sitBackModeEnabled) 0.10f else 0.20f
            val active = 1.0f - topDeadZone
            val clamped = normY.coerceIn(0f, 1f)
            val mapped = ((clamped - topDeadZone) / active).coerceIn(0f, 1f)
            val amplified = 0.5f + (mapped - 0.5f) * pointerGainFactor()
            return amplified.coerceIn(0f, 1f) * screenHeight
        }

        fun setCursorMapping(cursorGainPercent: Int, sitBackMode: Boolean) {
            cursorGain = cursorGainPercent.coerceIn(0, 100) / 100f
            sitBackModeEnabled = sitBackMode
        }

        val FINGERS_PER_POSE: Map<Pose, Set<FingerType>> = mapOf(
            Pose.OPEN_PALM to setOf(FingerType.THUMB, FingerType.INDEX, FingerType.MIDDLE, FingerType.RING, FingerType.PINKY),
            Pose.FOUR_FINGERS to setOf(FingerType.INDEX, FingerType.MIDDLE, FingerType.RING, FingerType.PINKY),
            Pose.THREE_FINGERS to setOf(FingerType.INDEX, FingerType.MIDDLE, FingerType.RING),
            Pose.VICTORY to setOf(FingerType.INDEX, FingerType.MIDDLE),
            Pose.POINTING to setOf(FingerType.INDEX),
            Pose.THUMB_UP to setOf(FingerType.THUMB),
            Pose.THUMB_DOWN to setOf(FingerType.THUMB),
            Pose.PINCH to emptySet(),
            Pose.FIST to emptySet(),
        )

        fun buildDefaultMap(): ConcurrentHashMap<String, GestureAction> {
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

    @Volatile
    private var customGesturesList: List<CustomGesture> = emptyList()

    @Volatile
    private var currentPose: Pose = Pose.NONE

    private var lastDragStroke: GestureDescription.StrokeDescription? = null
    @Volatile private var isDragging = false
    @Volatile private var dragCurrentX = 0f
    @Volatile private var dragCurrentY = 0f

    @Volatile private var pinchStartTimeMs = 0L
    @Volatile private var pinchStartX = 0f
    @Volatile private var pinchStartY = 0f
    @Volatile private var pinchStartVelocity = 0f
    @Volatile private var pinchIsDrag = false

    // Fix P1: pinch start mapped to pixels, used for the drag-start slop test.
    @Volatile private var pinchStartPixelX = 0f
    @Volatile private var pinchStartPixelY = 0f

    @Volatile private var lastTapDispatchMs = 0L
    @Volatile private var pendingSecondTap = false
    @Volatile private var pendingSecondTapJob: Job? = null

    init {
        gestureMapRef.set(buildDefaultMap())
    }

    fun getGestureMap(): Map<String, GestureAction> = HashMap(gestureMap)

    fun updateGestureAction(key: String, action: GestureAction) {
        gestureMap[key] = action
    }

    fun attachService(service: AccessibilityService) {
        accessibilityServiceRef = WeakReference(service)
        audioManager = service.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        settingsJobs.forEach { it.cancel() }
        settingsJobs.clear()
        serviceScope?.cancel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CrashGuard.handler)
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
            }
        })

        settingsJobs.add(scope.launchGuarded("custom gestures", restart = true) {
            settingsRepository.customGestures.collectGuarded("custom gestures") { gestures ->
                customGesturesList = gestures.filter { it.isEnabled }
            }
        })
    }

    fun detachService() {
        settingsJobs.forEach { it.cancel() }
        settingsJobs.clear()
        serviceScope?.cancel()
        serviceScope = null
        resetTransientGestureState()
        accessibilityServiceRef.clear()
        audioManager = null
    }

    fun resetTransientGestureState() {
        pendingSecondTapJob?.cancel()
        pendingSecondTapJob = null
        pendingSecondTap = false
        lastTapDispatchMs = 0L
        lastDragStroke = null
        isDragging = false
        pinchIsDrag = false
        pinchStartTimeMs = 0L
        pinchStartPixelX = 0f
        pinchStartPixelY = 0f
    }

    internal fun actionAllowed(action: GestureAction): Boolean {
        if (!currentPreferences.gesturesEnabled) return false
        if (Suppression.isSuppressed()) {
            return action == GestureAction.TAP ||
                action == GestureAction.DOUBLE_TAP ||
                action == GestureAction.LONG_PRESS ||
                action == GestureAction.DRAG ||
                action == GestureAction.NONE
        }
        return true
    }

    fun dispatch(
        event: GestureEvent,
        engineState: GestureEngineState,
        cursorX: Float,
        cursorY: Float,
        screenWidth: Int,
        screenHeight: Int,
        /**
         * Fix A3: true when the coordinates come from the eye/gaze cursor.
         * Gaze coordinates are screen-normalized and must be mapped straight
         * to pixels (see [normalizeDirect]) instead of through the hand's
         * dead-zone mapping — the previous mismatch made eye-mode pinch clicks
         * land where the *hand* was, not where the user was looking.
         */
        fromGaze: Boolean = false,
    ): Boolean {
        if (engineState != GestureEngineState.ARMED &&
            engineState != GestureEngineState.EXECUTING
        ) return false

        val service = accessibilityServiceRef.get()

        if (event is GestureEvent.Pinch) {
            when (event.phase) {
                PinchPhase.START -> {
                    pinchStartTimeMs = nowMonotonicMs()
                    pinchStartX = cursorX
                    pinchStartY = cursorY
                    pinchStartVelocity = event.velocity
                    pinchIsDrag = false
                    pinchStartPixelX = mapCursorX(cursorX, screenWidth, fromGaze)
                    pinchStartPixelY = mapCursorY(cursorY, screenHeight, fromGaze)
                    return true
                }
                PinchPhase.MOVE -> {
                    if (service == null) return false
                    val customPinchHold = matchCustomGesture(Pose.PINCH)
                    val action = customPinchHold ?: gestureMap[KEY_POSE_PINCH_HOLD] ?: GestureAction.DRAG
                    if (!actionAllowed(action)) return false
                    val targetX = mapCursorX(cursorX, screenWidth, fromGaze)
                    val targetY = mapCursorY(cursorY, screenHeight, fromGaze)
                    if (action == GestureAction.DRAG) {
                        // Fix P1: while the pinch has not moved beyond the slop
                        // radius, this is still a pending TAP — do not start a
                        // drag stroke. A clean tap is dispatched on END below.
                        if (!isDragging) {
                            val slop = maxOf(MIN_DRAG_START_SLOP_PX, screenWidth * DRAG_START_SLOP_FRACTION)
                            val dx = targetX - pinchStartPixelX
                            val dy = targetY - pinchStartPixelY
                            if (kotlin.math.sqrt(dx * dx + dy * dy) < slop) return true
                        }
                        return dispatchDrag(service, targetX, targetY, screenWidth, screenHeight)
                    }
                    return false
                }
                PinchPhase.END -> {
                    if (isDragging) {
                        resetDragState()
                        return true
                    }
                    if (service == null) return false
                    val now = nowMonotonicMs()
                    val holdDurationMs = now - pinchStartTimeMs
                    if (isAccidentalMovingPinch(pinchStartVelocity, holdDurationMs)) {
                        return false
                    }
                    val customPinchAction = matchCustomGesture(Pose.PINCH)
                    val action = customPinchAction ?: gestureMap[KEY_POSE_PINCH] ?: GestureAction.TAP
                    if (!actionAllowed(action)) return false
                    return executeAction(action, pinchStartX, pinchStartY, screenWidth, screenHeight, fromGaze)
                }
            }
        }

        if (service == null) return false

        val action = when (event) {
            is GestureEvent.Swipe -> gestureMap[swipeKey(event.direction)]
            is GestureEvent.PoseTriggered -> {
                val custom = matchCustomGesture(event.pose)
                custom ?: gestureMap[poseKey(event.pose)]
            }
            is GestureEvent.PalmHome -> gestureMap[KEY_PALM_HOME]
            is GestureEvent.CustomGestureTriggered -> customGesturesList
                .firstOrNull { it.id == event.gestureId && it.isEnabled }
                ?.action
            is GestureEvent.CursorMoved,
            is GestureEvent.Armed,
            is GestureEvent.Disarmed -> GestureAction.NONE
            is GestureEvent.Pinch -> GestureAction.NONE
        } ?: GestureAction.NONE

        currentPose = when (event) {
            is GestureEvent.PoseTriggered -> event.pose
            else -> currentPose
        }

        if (!actionAllowed(action)) return false

        return executeAction(action, cursorX, cursorY, screenWidth, screenHeight, fromGaze)
    }

    /**
     * Maps a cursor coordinate to pixels. Fix D6: inputs are ALWAYS
     * screen-normalized [0,1] — the old `value in 0f..1f` range sniffing
     * remapped genuine pixel values that happened to lie in 0..1, and mis-
     * classified normalized edge values; the source (gaze vs hand) is now an
     * explicit flag instead of a guess from the magnitude.
     */
    private fun mapCursorX(cursorX: Float, screenWidth: Int, fromGaze: Boolean): Float =
        if (fromGaze) normalizeDirect(cursorX, screenWidth)
        else normalizeToScreenX(cursorX, screenWidth)

    private fun mapCursorY(cursorY: Float, screenHeight: Int, fromGaze: Boolean): Float =
        if (fromGaze) normalizeDirect(cursorY, screenHeight)
        else normalizeToScreenY(cursorY, screenHeight)

    private fun executeAction(
        action: GestureAction,
        cursorX: Float,
        cursorY: Float,
        screenWidth: Int,
        screenHeight: Int,
        fromGaze: Boolean = false,
    ): Boolean {
        val service = accessibilityServiceRef.get() ?: return false
        val targetPixelX = mapCursorX(cursorX, screenWidth, fromGaze)
        val targetPixelY = mapCursorY(cursorY, screenHeight, fromGaze)

        return when (action) {
            GestureAction.NONE -> false
            GestureAction.SCROLL_UP -> dispatchScroll(service, targetPixelX, targetPixelY, 0f, -1f, screenWidth, screenHeight)
            GestureAction.SCROLL_DOWN -> dispatchScroll(service, targetPixelX, targetPixelY, 0f, 1f, screenWidth, screenHeight)
            GestureAction.SCROLL_LEFT -> dispatchScroll(service, targetPixelX, targetPixelY, -1f, 0f, screenWidth, screenHeight)
            GestureAction.SCROLL_RIGHT -> dispatchScroll(service, targetPixelX, targetPixelY, 1f, 0f, screenWidth, screenHeight)
            GestureAction.BACK -> performGlobalAction(service, AccessibilityService.GLOBAL_ACTION_BACK, GestureAction.BACK)
            GestureAction.HOME -> performGlobalAction(service, AccessibilityService.GLOBAL_ACTION_HOME, GestureAction.HOME)
            GestureAction.RECENTS -> performGlobalAction(service, AccessibilityService.GLOBAL_ACTION_RECENTS, GestureAction.RECENTS)
            GestureAction.NOTIFICATIONS -> performGlobalAction(service, AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, GestureAction.NOTIFICATIONS)
            GestureAction.QUICK_SETTINGS -> performGlobalAction(service, AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, GestureAction.QUICK_SETTINGS)
            GestureAction.VOLUME_UP -> pressVolume(true)
            GestureAction.VOLUME_DOWN -> pressVolume(false)
            GestureAction.MEDIA_PLAY_PAUSE -> pressMediaPlayPause()
            GestureAction.SCREENSHOT -> performGlobalAction(
                service,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT else AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS,
                GestureAction.SCREENSHOT
            )
            GestureAction.LOCK_SCREEN -> performGlobalAction(
                service,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN else AccessibilityService.GLOBAL_ACTION_HOME,
                GestureAction.LOCK_SCREEN
            )
            GestureAction.TAP -> dispatchTap(service, targetPixelX, targetPixelY)
            GestureAction.DOUBLE_TAP -> dispatchDoubleTap(service, targetPixelX, targetPixelY)
            GestureAction.LONG_PRESS -> dispatchLongPress(service, targetPixelX, targetPixelY)
            GestureAction.DRAG -> dispatchDrag(service, targetPixelX, targetPixelY, screenWidth, screenHeight)
        }
    }

    /**
     * Blink taps are gaze-only: their coordinates are screen-normalized and are
     * mapped directly to pixels (Fix A1/A2 — previously they landed on the
     * frozen (0.5, 0.5) cursor state, i.e. always the screen centre).
     */
    fun dispatchBlinkTap(normX: Float, normY: Float, screenWidth: Int, screenHeight: Int): Boolean {
        if (!currentPreferences.blinkClickEnabled) return false
        val service = accessibilityServiceRef.get() ?: return false
        if (!actionAllowed(GestureAction.TAP)) return false
        val pxX = normalizeDirect(normX, screenWidth)
        val pxY = normalizeDirect(normY, screenHeight)
        return dispatchTap(service, pxX, pxY)
    }

    fun dispatchDwellTap(
        normX: Float,
        normY: Float,
        screenWidth: Int,
        screenHeight: Int,
        /** Fix A2: dwell ticks from the gaze cursor map directly; hand-path dwell keeps the hand mapping. */
        fromGaze: Boolean = false,
    ): Boolean {
        val service = accessibilityServiceRef.get() ?: return false
        if (!actionAllowed(GestureAction.TAP)) return false
        // Fix D6: coordinates are always screen-normalized — no pixel/normalized
        // range guessing.
        val pxX = if (fromGaze) normalizeDirect(normX, screenWidth) else normalizeToScreenX(normX, screenWidth)
        val pxY = if (fromGaze) normalizeDirect(normY, screenHeight) else normalizeToScreenY(normY, screenHeight)
        return dispatchTap(service, pxX, pxY)
    }

    private fun isAccidentalMovingPinch(startVelocity: Float, holdDurationMs: Long): Boolean {
        if (!currentPreferences.stationaryClickEnabled) return false
        if (holdDurationMs >= INTENTIONAL_PINCH_HOLD_MS) return false
        val norm = currentPreferences.sensitivity.coerceIn(0, 100) / 100f
        val allowedVelocity = MIN_MOVING_PINCH_VELOCITY +
            norm * (MAX_MOVING_PINCH_VELOCITY - MIN_MOVING_PINCH_VELOCITY)
        return startVelocity > allowedVelocity
    }

    private fun nowMonotonicMs(): Long {
        return try {
            SystemClock.elapsedRealtime()
        } catch (_: Throwable) {
            System.currentTimeMillis()
        }
    }

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
            Pose.NONE -> null
        } ?: return null

        return customGesturesList.find { gesture ->
            when (val trigger = gesture.triggerPose) {
                is CustomGestureTrigger.PoseWithDirection ->
                    trigger.pose == customPose && trigger.direction == CustomGestureDirection.NONE
                is CustomGestureTrigger.FingerCount -> {
                    val fingers = FINGERS_PER_POSE[pose] ?: return@find false
                    fingers.size == trigger.extendedFingers &&
                        (trigger.whichFingers.isEmpty() || fingers == trigger.whichFingers)
                }
                is CustomGestureTrigger.LandmarkTemplateTrigger -> false
            }
        }?.action
    }

    private fun performGlobalAction(service: AccessibilityService, globalAction: Int, action: GestureAction): Boolean {
        val success = service.performGlobalAction(globalAction)
        if (success) {
            _dispatchedEvents.tryEmit(action.name)
            onGestureDispatched?.invoke(action.name)
        }
        return success
    }

    private fun dispatchTap(service: AccessibilityService, x: Float, y: Float): Boolean {
        val path = Path()
        path.moveTo(x.coerceAtLeast(1f), y.coerceAtLeast(1f))
        return submitGesture(service, GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS)
        ).build(), GestureAction.TAP)
    }

    private fun dispatchDoubleTap(service: AccessibilityService, x: Float, y: Float): Boolean {
        val now = nowMonotonicMs()
        if (pendingSecondTap && now - lastTapDispatchMs <= DOUBLE_TAP_WINDOW_MS) {
            pendingSecondTapJob?.cancel()
            pendingSecondTap = false
            return dispatchTap(service, x, y)
        }
        lastTapDispatchMs = now
        pendingSecondTap = true
        pendingSecondTapJob?.cancel()
        pendingSecondTapJob = serviceScope?.launch {
            delay(DOUBLE_TAP_WINDOW_MS)
            pendingSecondTap = false
        }
        return dispatchTap(service, x, y)
    }

    private fun dispatchLongPress(service: AccessibilityService, x: Float, y: Float): Boolean {
        val path = Path()
        path.moveTo(x.coerceAtLeast(1f), y.coerceAtLeast(1f))
        return submitGesture(service, GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(path, 0, LONG_PRESS_DURATION_MS)
        ).build(), GestureAction.LONG_PRESS)
    }

    private fun dispatchScroll(service: AccessibilityService, x: Float, y: Float, dx: Float, dy: Float, screenWidth: Int, screenHeight: Int): Boolean {
        val path = Path()
        val startX = x.coerceIn(0f, screenWidth.toFloat())
        val startY = y.coerceIn(0f, screenHeight.toFloat())
        path.moveTo(startX, startY)
        path.lineTo(
            (startX + dx * screenWidth * 0.22f).coerceIn(0f, screenWidth.toFloat()),
            (startY + dy * screenHeight * 0.22f).coerceIn(0f, screenHeight.toFloat())
        )
        return submitGesture(
            service,
            GestureDescription.Builder().addStroke(
                GestureDescription.StrokeDescription(path, 0, SCROLL_DURATION_MS)
            ).build(),
            if (dx > 0) GestureAction.SCROLL_RIGHT else if (dx < 0) GestureAction.SCROLL_LEFT else if (dy > 0) GestureAction.SCROLL_DOWN else GestureAction.SCROLL_UP
        )
    }

    private fun dispatchDrag(service: AccessibilityService, x: Float, y: Float, screenWidth: Int, screenHeight: Int): Boolean {
        if (!isDragging) {
            val path = Path()
            path.moveTo(x.coerceAtLeast(1f), y.coerceAtLeast(1f))
            lastDragStroke = GestureDescription.StrokeDescription(path, 0, DRAG_STEP_DURATION_MS, true)
            isDragging = submitGesture(service, GestureDescription.Builder().addStroke(lastDragStroke!!).build(), GestureAction.DRAG)
            dragCurrentX = x
            dragCurrentY = y
            pinchStartTimeMs = nowMonotonicMs()
            pinchStartX = x
            pinchStartY = y
            pinchIsDrag = isDragging
            return isDragging
        }

        // Fix B3: the per-step clamp used a hardcoded 1080px "screen". On 2K/QHD
        // panels the steps were far too small (sluggish drags); on low-res panels
        // too large (skippy drags). Use the real panel dimensions, per axis.
        val maxStepX = screenSafeStep(screenWidth.toFloat())
        val maxStepY = screenSafeStep(screenHeight.toFloat())
        val deltaX = (x - dragCurrentX).coerceIn(-maxStepX, maxStepX)
        val deltaY = (y - dragCurrentY).coerceIn(-maxStepY, maxStepY)
        if (deltaX == 0f && deltaY == 0f) return true
        // Fix (audit #13): the clamp was computed but thrown away — the stroke
        // jumped straight to the RAW target, so fast drags skipped across the
        // path. Continue the stroke by the CLAMPED step and track the clamped
        // position, so the injected path is exactly the smooth path we intended.
        val nextX = dragCurrentX + deltaX
        val nextY = dragCurrentY + deltaY
        val path = Path()
        path.moveTo(dragCurrentX, dragCurrentY)
        path.lineTo(nextX, nextY)
        val nextStroke = lastDragStroke?.continueStroke(path, 0L, DRAG_STEP_DURATION_MS, true)
            ?: GestureDescription.StrokeDescription(path, 0, DRAG_STEP_DURATION_MS, true)
        val submitted = submitGesture(service, GestureDescription.Builder().addStroke(nextStroke).build(), GestureAction.DRAG)
        if (submitted) {
            lastDragStroke = nextStroke
            dragCurrentX = nextX
            dragCurrentY = nextY
        }
        return submitted
    }

    private fun screenSafeStep(screenWidth: Float): Float = screenWidth * MAX_DRAG_STEP_FRACTION

    private fun submitGesture(service: AccessibilityService, gesture: GestureDescription, action: GestureAction): Boolean {
        return try {
            service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    _dispatchedEvents.tryEmit(action.name)
                    onGestureDispatched?.invoke(action.name)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Timber.w("Gesture cancelled: %s", action)
                    if (action == GestureAction.DRAG) resetDragState()
                }
            }, null)
        } catch (e: Throwable) {
            Timber.e(e, "dispatchGesture failed: %s", action)
            if (action == GestureAction.DRAG) resetDragState()
            false
        }
    }

    private fun resetDragState() {
        lastDragStroke = null
        isDragging = false
        pinchIsDrag = false
        pinchStartTimeMs = 0L
    }

    private fun pressMediaPlayPause(): Boolean {
        val audio = audioManager ?: return false
        return try {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            _dispatchedEvents.tryEmit(GestureAction.MEDIA_PLAY_PAUSE.name)
            onGestureDispatched?.invoke(GestureAction.MEDIA_PLAY_PAUSE.name)
            true
        } catch (e: Throwable) {
            Timber.e(e, "Media play/pause dispatch failed")
            false
        }
    }

    private fun pressVolume(up: Boolean): Boolean {
        val audio = audioManager ?: return false
        return try {
            val action = if (up) GestureAction.VOLUME_UP else GestureAction.VOLUME_DOWN
            audio.adjustSuggestedStreamVolume(
                if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                AudioManager.USE_DEFAULT_STREAM_TYPE,
                AudioManager.FLAG_SHOW_UI
            )
            _dispatchedEvents.tryEmit(action.name)
            onGestureDispatched?.invoke(action.name)
            true
        } catch (e: Throwable) {
            Timber.e(e, "volume dispatch failed")
            false
        }
    }

    private fun swipeKey(direction: SwipeDirection): String = when (direction) {
        SwipeDirection.LEFT -> KEY_SWIPE_LEFT
        SwipeDirection.RIGHT -> KEY_SWIPE_RIGHT
        SwipeDirection.UP -> KEY_SWIPE_UP
        SwipeDirection.DOWN -> KEY_SWIPE_DOWN
    }

    private fun poseKey(pose: Pose): String = when (pose) {
        Pose.PINCH -> KEY_POSE_PINCH
        Pose.POINTING -> KEY_POSE_POINTING
        Pose.VICTORY -> KEY_POSE_VICTORY
        Pose.THUMB_UP -> KEY_POSE_THUMB_UP
        Pose.THUMB_DOWN -> KEY_POSE_THUMB_DOWN
        else -> "pose_${pose.name.lowercase()}"
    }
}
