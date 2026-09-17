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
        private const val SCROLL_DURATION_MS = 140L
        private const val TAP_DURATION_MS = 15L
        private const val LONG_PRESS_DURATION_MS = 500L
        private const val DOUBLE_TAP_GAP_MS = 100L
        private const val DOUBLE_TAP_WINDOW_MS = 500L
        private const val INTENTIONAL_PINCH_HOLD_MS = 80L
        private const val MIN_MOVING_PINCH_VELOCITY = 0.55f
        private const val MAX_MOVING_PINCH_VELOCITY = 1.10f
        private const val TAP_PATH_DISPLACEMENT_PX = 3f
        private const val DRAG_STEP_DURATION_MS = 36L
        private const val DRAG_END_DURATION_MS = 120L
        private const val HAPTIC_TICK_MS = 15L
        private const val MAX_DRAG_STEP_FRACTION = 0.15f
        private const val DRAG_START_SLOP_FRACTION = 0.025f
        private const val MIN_DRAG_START_SLOP_PX = 12f
        private const val MIN_DRAG_VELOCITY_PX_MS = 0.10f

        const val KEY_SWIPE_LEFT = "swipe_left"
        const val KEY_SWIPE_RIGHT = "swipe_right"
        const val KEY_SWIPE_UP = "swipe_up"
        const val KEY_SWIPE_DOWN = "swipe_down"
        const val KEY_POSE_PINCH = "pose_pinch"
        const val KEY_POSE_POINTING = "pose_pointing"
        const val KEY_POSE_VICTORY = "pose_victory"
        const val KEY_POSE_THUMB_UP = "pose_thumb_up"
        const val KEY_POSE_THUMB_DOWN = "pose_thumb_down"
        const val KEY_POSE_THREE_FINGERS = "pose_three_fingers"
        const val KEY_POSE_PINCH_HOLD = "pose_pinch_hold"
        const val KEY_PALM_HOME = "palm_home"

        const val TAP_OWNER_HAND = "hand_gesture"
        const val TAP_OWNER_BLINK = "blink"
        const val TAP_OWNER_GAZE_DWELL = "gaze_dwell"
        const val TAP_OWNER_HAND_DWELL = "hand_dwell"

        @Volatile private var cursorGain = 0.5f
        @Volatile private var sitBackModeEnabled = false
        @Volatile private var invertScrollDirection = false

        fun setInvertScrollDirection(invert: Boolean) {
            invertScrollDirection = invert
        }

        const val EDGE_ACCEL_COEFF = 0.16f
        const val SIDE_MARGIN = 0.08f
        const val TOP_DEAD_ZONE = 0.06f
        const val TOP_DEAD_ZONE_SIT_BACK = 0.03f

        private fun pointerGainFactor(): Float = 0.5f + cursorGain.coerceIn(0f, 1f)

        fun normalizeDirect(norm: Float, screenDim: Int): Float =
            if (screenDim <= 0 || !norm.isFinite()) 0f else norm.coerceIn(0f, 1f) * screenDim

        private fun applyEdgeAcceleration(norm: Float): Float {
            val u = ((norm - 0.5f) * 2f).coerceIn(-1f, 1f)
            val absU = kotlin.math.abs(u)
            val accelerated = absU + EDGE_ACCEL_COEFF * absU * absU * absU
            val res = kotlin.math.sign(u) * accelerated
            return (0.5f + res * 0.5f).coerceIn(0f, 1f)
        }

        fun normalizeToScreenX(normX: Float, screenWidth: Int): Float {
            if (screenWidth <= 0 || !normX.isFinite()) return 0f
            val margin = SIDE_MARGIN
            val active = 1.0f - 2f * margin
            val clamped = normX.coerceIn(0f, 1f)
            val mapped = ((clamped - margin) / active).coerceIn(0f, 1f)
            val accelerated = applyEdgeAcceleration(mapped)
            val amplified = 0.5f + (accelerated - 0.5f) * pointerGainFactor()
            return amplified.coerceIn(0f, 1f) * screenWidth
        }

        fun normalizeToScreenY(normY: Float, screenHeight: Int, screenWidth: Int = 0): Float {
            if (screenHeight <= 0 || !normY.isFinite()) return 0f
            val topDeadZone = if (sitBackModeEnabled) TOP_DEAD_ZONE_SIT_BACK else TOP_DEAD_ZONE
            val active = 1.0f - topDeadZone
            val clamped = normY.coerceIn(0f, 1f)
            val mapped = ((clamped - topDeadZone) / active).coerceIn(0f, 1f)
            val accelerated = applyEdgeAcceleration(mapped)
            val aspectCorrection = if (screenWidth > 0 && screenHeight > screenWidth) {
                val aspect = screenHeight.toFloat() / screenWidth.toFloat()
                (1.5f / aspect).coerceIn(0.72f, 1.0f)
            } else {
                1.0f
            }
            val amplified = 0.5f + (accelerated - 0.5f) * pointerGainFactor() * aspectCorrection
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
            defaultMap[KEY_POSE_THREE_FINGERS] = GestureAction.NONE
            defaultMap[KEY_POSE_PINCH_HOLD] = GestureAction.DRAG
            defaultMap[KEY_PALM_HOME] = GestureAction.HOME
            return defaultMap
        }
    }

    @Volatile private var customGesturesList: List<CustomGesture> = emptyList()
    @Volatile private var currentPose: Pose = Pose.NONE
    private val tapOwnership = InputOwnershipPolicy()
    val blockReasonHub = BlockReasonHub()

    private fun reportBlocked(reason: BlockReason) {
        blockReasonHub.record(reason, nowMonotonicMs())
    }

    private fun maybeReportSuppressed() {
        if (Suppression.isSuppressed()) reportBlocked(BlockReason.SUPPRESSED_DURING_CALIBRATION)
    }

    private var lastDragStroke: GestureDescription.StrokeDescription? = null
    @Volatile private var isDragging = false
    @Volatile private var wasEverDraggingThisPinch = false
    @Volatile private var lastDragEndMs = 0L
    @Volatile private var dragCurrentX = 0f
    @Volatile private var dragCurrentY = 0f
    @Volatile private var pinchStartTimeMs = 0L
    @Volatile private var pinchStartX = 0f
    @Volatile private var pinchStartY = 0f
    @Volatile private var pinchStartVelocity = 0f
    @Volatile private var pinchIsDrag = false
    @Volatile private var pinchStartPixelX = 0f
    @Volatile private var pinchStartPixelY = 0f
    @Volatile private var pinchLastX = 0f
    @Volatile private var pinchLastY = 0f
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
            settingsRepository.userPreferences.collectGuarded("dispatcher settings") {
                currentPreferences = it
            }
        })
        settingsJobs.add(scope.launchGuarded("gesture map", restart = true) {
            settingsRepository.gestureMapConfig.collectGuarded("gesture map") { config ->
                val newMap = buildDefaultMap()
                config.entries.forEach { entry -> newMap[entry.key] = entry.action }
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
        if (Suppression.isSuppressed() && action != GestureAction.NONE) {
            maybeReportSuppressed()
            return false
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
        fromGaze: Boolean = false,
    ): Boolean {
        if (engineState != GestureEngineState.ARMED && engineState != GestureEngineState.EXECUTING) {
            if (event is GestureEvent.Pinch && event.phase == PinchPhase.START) {
                pinchStartTimeMs = nowMonotonicMs()
                pinchStartX = cursorX
                pinchStartY = cursorY
                pinchStartPixelX = mapCursorX(cursorX, screenWidth, fromGaze)
                pinchStartPixelY = mapCursorY(cursorY, screenHeight, fromGaze, screenWidth)
                pinchLastX = pinchStartPixelX
                pinchLastY = pinchStartPixelY
                pinchIsDrag = false
                wasEverDraggingThisPinch = false
            } else if (engineState == GestureEngineState.COOLDOWN) {
                reportBlocked(BlockReason.GESTURE_COOLDOWN)
            }
            return false
        }

        val service = accessibilityServiceRef.get()
        if (event is GestureEvent.Pinch) {
            when (event.phase) {
                PinchPhase.START -> {
                    pinchStartTimeMs = nowMonotonicMs()
                    pinchStartX = cursorX
                    pinchStartY = cursorY
                    pinchStartVelocity = event.velocity
                    pinchIsDrag = false
                    wasEverDraggingThisPinch = false
                    pinchStartPixelX = mapCursorX(cursorX, screenWidth, fromGaze)
                    pinchStartPixelY = mapCursorY(cursorY, screenHeight, fromGaze, screenWidth)
                    pinchLastX = pinchStartPixelX
                    pinchLastY = pinchStartPixelY
                    return true
                }
                PinchPhase.MOVE -> {
                    if (service == null) return false
                    val customPinchHold = matchCustomGesture(Pose.PINCH)
                    val action = customPinchHold ?: gestureMap[KEY_POSE_PINCH_HOLD] ?: GestureAction.DRAG
                    if (!actionAllowed(action)) return false
                    val targetX = mapCursorX(cursorX, screenWidth, fromGaze)
                    val targetY = mapCursorY(cursorY, screenHeight, fromGaze, screenWidth)
                    pinchLastX = targetX
                    pinchLastY = targetY
                    if (action == GestureAction.DRAG) {
                        if (!isDragging) {
                            val slop = maxOf(MIN_DRAG_START_SLOP_PX, screenWidth * DRAG_START_SLOP_FRACTION)
                            val dx = targetX - pinchStartPixelX
                            val dy = targetY - pinchStartPixelY
                            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                            if (dist < slop) return true
                            val elapsedMs = nowMonotonicMs() - pinchStartTimeMs
                            if (elapsedMs > 200L && (dist / elapsedMs) < MIN_DRAG_VELOCITY_PX_MS) return true
                        }
                        return dispatchDrag(service, targetX, targetY, screenWidth, screenHeight)
                    }
                    return false
                }
                PinchPhase.END -> {
                    val now = nowMonotonicMs()
                    if (isDragging || wasEverDraggingThisPinch || (now - lastDragEndMs < 250L)) {
                        wasEverDraggingThisPinch = false
                        resetDragState()
                        return true
                    }
                    if (service == null) return false
                    val holdDurationMs = now - pinchStartTimeMs
                    if (isAccidentalMovingPinch(pinchStartVelocity, holdDurationMs)) {
                        reportBlocked(BlockReason.PINCH_MOVED_TOO_FAST)
                        return false
                    }
                    val customPinchAction = matchCustomGesture(Pose.PINCH)
                    val baseAction = customPinchAction ?: gestureMap[KEY_POSE_PINCH] ?: GestureAction.TAP
                    val action = if (baseAction == GestureAction.TAP && holdDurationMs >= LONG_PRESS_DURATION_MS) {
                        GestureAction.LONG_PRESS
                    } else {
                        baseAction
                    }
                    if (!actionAllowed(action)) return false
                    val drift = kotlin.math.hypot(pinchLastX - pinchStartPixelX, pinchLastY - pinchStartPixelY)
                    val slopNow = maxOf(MIN_DRAG_START_SLOP_PX, screenWidth * DRAG_START_SLOP_FRACTION)
                    val overX = if (drift > slopNow) pinchLastX else null
                    val overY = if (drift > slopNow) pinchLastY else null
                    return executeAction(action, pinchStartX, pinchStartY, screenWidth, screenHeight, fromGaze, overX, overY)
                }
            }
        }

        if (service == null) return false

        val action = when (event) {
            is GestureEvent.Swipe -> {
                val mapped = gestureMap[swipeKey(event.direction)]
                if (cursorY < 0.10f && event.direction == com.aircontrol.gesture.model.SwipeDirection.DOWN && mapped == GestureAction.SCROLL_DOWN) {
                    GestureAction.NOTIFICATIONS
                } else mapped
            }
            is GestureEvent.PoseTriggered -> {
                val custom = matchCustomGesture(event.pose)
                custom ?: gestureMap[poseKey(event.pose)]
            }
            is GestureEvent.PalmHome -> gestureMap[KEY_PALM_HOME]
            is GestureEvent.CustomGestureTriggered -> customGesturesList.firstOrNull { it.id == event.gestureId && it.isEnabled }?.action
            is GestureEvent.CursorMoved, is GestureEvent.Armed, is GestureEvent.Disarmed -> GestureAction.NONE
            is GestureEvent.Pinch -> GestureAction.NONE
        } ?: GestureAction.NONE

        currentPose = when (event) {
            is GestureEvent.PoseTriggered -> event.pose
            else -> currentPose
        }

        if (!actionAllowed(action)) return false
        return executeAction(action, cursorX, cursorY, screenWidth, screenHeight, fromGaze)
    }

    private fun mapCursorX(cursorX: Float, screenWidth: Int, fromGaze: Boolean): Float =
        if (fromGaze) normalizeDirect(cursorX, screenWidth) else normalizeToScreenX(cursorX, screenWidth)

    private fun mapCursorY(cursorY: Float, screenHeight: Int, fromGaze: Boolean, screenWidth: Int = 0): Float =
        if (fromGaze) normalizeDirect(cursorY, screenHeight) else normalizeToScreenY(cursorY, screenHeight, screenWidth)

    private fun executeAction(
        action: GestureAction,
        cursorX: Float,
        cursorY: Float,
        screenWidth: Int,
        screenHeight: Int,
        fromGaze: Boolean = false,
        overridePixelX: Float? = null,
        overridePixelY: Float? = null,
    ): Boolean {
        if (!actionAllowed(action)) return false
        val service = accessibilityServiceRef.get() ?: return false
        val targetPixelX = overridePixelX ?: mapCursorX(cursorX, screenWidth, fromGaze)
        val targetPixelY = overridePixelY ?: mapCursorY(cursorY, screenHeight, fromGaze, screenWidth)

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
            GestureAction.SCREENSHOT -> {
                if (!ActionCapabilityChecker.isSupported(GestureAction.SCREENSHOT, Build.VERSION.SDK_INT)) {
                    reportBlocked(BlockReason.ACTION_UNSUPPORTED)
                    return false
                }
                performGlobalAction(service, AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT, GestureAction.SCREENSHOT)
            }
            GestureAction.LOCK_SCREEN -> {
                if (!ActionCapabilityChecker.isSupported(GestureAction.LOCK_SCREEN, Build.VERSION.SDK_INT)) {
                    reportBlocked(BlockReason.ACTION_UNSUPPORTED)
                    return false
                }
                performGlobalAction(service, AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, GestureAction.LOCK_SCREEN)
            }
            GestureAction.TAP -> if (acquireTapOwnership(TAP_OWNER_HAND)) dispatchTap(service, targetPixelX, targetPixelY) else {
                reportBlocked(BlockReason.ANOTHER_MODALITY_ACTED)
                true
            }
            GestureAction.DOUBLE_TAP -> if (acquireTapOwnership(TAP_OWNER_HAND)) dispatchDoubleTap(service, targetPixelX, targetPixelY) else {
                reportBlocked(BlockReason.ANOTHER_MODALITY_ACTED)
                true
            }
            GestureAction.LONG_PRESS -> dispatchLongPress(service, targetPixelX, targetPixelY)
            GestureAction.DRAG -> dispatchDrag(service, targetPixelX, targetPixelY, screenWidth, screenHeight)
        }
    }

    private fun acquireTapOwnership(source: String): Boolean =
        tapOwnership.tryAcquire(source, nowMonotonicMs())

    fun dispatchBlinkTap(normX: Float, normY: Float, screenWidth: Int, screenHeight: Int): Boolean {
        if (!currentPreferences.blinkClickEnabled) return false
        val service = accessibilityServiceRef.get() ?: run {
            reportBlocked(BlockReason.ACCESSIBILITY_SERVICE_UNAVAILABLE)
            return false
        }
        if (!actionAllowed(GestureAction.TAP)) return false
        if (!acquireTapOwnership(TAP_OWNER_BLINK)) {
            reportBlocked(BlockReason.ANOTHER_MODALITY_ACTED)
            return true
        }
        val pxX = normalizeDirect(normX, screenWidth)
        val pxY = normalizeDirect(normY, screenHeight)
        return dispatchTap(service, pxX, pxY)
    }

    fun dispatchDwellTap(normX: Float, normY: Float, screenWidth: Int, screenHeight: Int, fromGaze: Boolean = false): Boolean {
        val service = accessibilityServiceRef.get() ?: run {
            reportBlocked(BlockReason.ACCESSIBILITY_SERVICE_UNAVAILABLE)
            return false
        }
        if (!actionAllowed(GestureAction.TAP)) return false
        if (!acquireTapOwnership(if (fromGaze) TAP_OWNER_GAZE_DWELL else TAP_OWNER_HAND_DWELL)) {
            reportBlocked(BlockReason.ANOTHER_MODALITY_ACTED)
            return true
        }
        val pxX = if (fromGaze) normalizeDirect(normX, screenWidth) else normalizeToScreenX(normX, screenWidth)
        val pxY = if (fromGaze) normalizeDirect(normY, screenHeight) else normalizeToScreenY(normY, screenHeight, screenWidth)
        return dispatchTap(service, pxX, pxY)
    }

    private fun isAccidentalMovingPinch(startVelocity: Float, holdDurationMs: Long): Boolean {
        if (!currentPreferences.stationaryClickEnabled) return false
        if (holdDurationMs >= INTENTIONAL_PINCH_HOLD_MS) return false
        val norm = currentPreferences.sensitivity.coerceIn(0, 100) / 100f
        val allowedVelocity = MIN_MOVING_PINCH_VELOCITY + norm * (MAX_MOVING_PINCH_VELOCITY - MIN_MOVING_PINCH_VELOCITY)
        return startVelocity > allowedVelocity
    }

    private fun nowMonotonicMs(): Long = runCatching { SystemClock.elapsedRealtime() }.getOrElse { System.currentTimeMillis() }

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
                is CustomGestureTrigger.PoseWithDirection -> trigger.pose == customPose && trigger.direction == CustomGestureDirection.NONE
                is CustomGestureTrigger.FingerCount -> {
                    val fingers = FINGERS_PER_POSE[pose] ?: return@find false
                    fingers.size == trigger.extendedFingers && (trigger.whichFingers.isEmpty() || fingers == trigger.whichFingers)
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
        val path = Path().apply { moveTo(x.coerceAtLeast(1f), y.coerceAtLeast(1f)) }
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
        val path = Path().apply { moveTo(x.coerceAtLeast(1f), y.coerceAtLeast(1f)) }
        return submitGesture(service, GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(path, 0, LONG_PRESS_DURATION_MS)
        ).build(), GestureAction.LONG_PRESS)
    }

    private fun dispatchScroll(service: AccessibilityService, x: Float, y: Float, dx: Float, dy: Float, screenWidth: Int, screenHeight: Int): Boolean {
        val mult = if (invertScrollDirection) -1f else 1f
        val effectiveDx = dx * mult
        val effectiveDy = dy * mult
        val marginX = screenWidth * 0.06f
        val marginY = screenHeight * 0.06f
        val scrollLengthX = screenWidth * 0.40f
        val scrollLengthY = screenHeight * 0.42f
        val safeStartX = x.coerceIn(marginX, screenWidth - marginX)
        val safeStartY = y.coerceIn(marginY, screenHeight - marginY)
        val path = Path().apply {
            moveTo(safeStartX, safeStartY)
            lineTo(
                (safeStartX + effectiveDx * scrollLengthX).coerceIn(0f, screenWidth.toFloat()),
                (safeStartY + effectiveDy * scrollLengthY).coerceIn(0f, screenHeight.toFloat()),
            )
        }
        return submitGesture(
            service,
            GestureDescription.Builder().addStroke(
                GestureDescription.StrokeDescription(path, 0, SCROLL_DURATION_MS)
            ).build(),
            if (effectiveDx > 0) GestureAction.SCROLL_RIGHT else if (effectiveDx < 0) GestureAction.SCROLL_LEFT else if (effectiveDy > 0) GestureAction.SCROLL_DOWN else GestureAction.SCROLL_UP,
        )
    }

    private fun dispatchDrag(service: AccessibilityService, x: Float, y: Float, screenWidth: Int, screenHeight: Int): Boolean {
        wasEverDraggingThisPinch = true
        if (!isDragging) {
            val path = Path().apply { moveTo(x.coerceAtLeast(1f), y.coerceAtLeast(1f)) }
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
        val maxStepX = screenSafeStep(screenWidth.toFloat())
        val maxStepY = screenSafeStep(screenHeight.toFloat())
        val deltaX = (x - dragCurrentX).coerceIn(-maxStepX, maxStepX)
        val deltaY = (y - dragCurrentY).coerceIn(-maxStepY, maxStepY)
        if (deltaX == 0f && deltaY == 0f) return true
        val nextX = dragCurrentX + deltaX
        val nextY = dragCurrentY + deltaY
        val path = Path().apply {
            moveTo(dragCurrentX, dragCurrentY)
            lineTo(nextX, nextY)
        }
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
                    if (action != GestureAction.DRAG) reportBlocked(BlockReason.PROTECTED_SCREEN_BLOCKED)
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
        lastDragEndMs = nowMonotonicMs()
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
            audio.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI,
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
