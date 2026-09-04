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
        get() = gestureMapRef.get()

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

        // Phase 1: mapping is symmetric across both axes. The gain changes
        // usable active area, but the dead zone is always split equally between
        // the two edges, preventing vertical asymmetry and region-dependent feel.
        private const val MIN_ACTIVE_FRACTION = 0.82f
        private const val MAX_ACTIVE_FRACTION = 1.0f
        private const val SIT_BACK_ACTIVE_Y_FRACTION = 0.90f
        private const val MAX_DRAG_STEP_FRACTION = 0.15f

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

        fun normalizeToScreenX(normX: Float, screenWidth: Int): Float =
            mapSymmetric(normX, screenWidth, activeFraction(vertical = false))

        fun normalizeToScreenY(normY: Float, screenHeight: Int): Float =
            mapSymmetric(normY, screenHeight, activeFraction(vertical = true))

        private fun activeFraction(vertical: Boolean): Float {
            if (vertical && sitBackModeEnabled) return SIT_BACK_ACTIVE_Y_FRACTION
            return MIN_ACTIVE_FRACTION +
                cursorGain.coerceIn(0f, 1f) * (MAX_ACTIVE_FRACTION - MIN_ACTIVE_FRACTION)
        }

        private fun mapSymmetric(norm: Float, screenSize: Int, activeFraction: Float): Float {
            if (screenSize <= 0) return 0f
            val active = activeFraction.coerceIn(MIN_ACTIVE_FRACTION, 1f)
            val margin = (1f - active) * 0.5f
            val clampedInput = norm.coerceIn(0f, 1f)
            return (((clampedInput - margin) / active).coerceIn(0f, 1f)) * screenSize
        }

        fun setCursorMapping(cursorGainPercent: Int, sitBackMode: Boolean) {
            cursorGain = cursorGainPercent.coerceIn(0, 100) / 100f
            sitBackModeEnabled = sitBackMode
        }

        private val FINGERS_PER_POSE: Map<Pose, Set<FingerType>> = mapOf(
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

    @Volatile private var lastTapDispatchMs = 0L
    @Volatile private var pendingSecondTap = false
    @Volatile private var pendingSecondTapJob: Job? = null

    fun attachService(service: AccessibilityService) {
        accessibilityServiceRef = WeakReference(service)
        audioManager = service.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        settingsJobs.forEach { it.cancel() }
        settingsJobs.clear()
        serviceScope?.cancel()
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CrashGuard.handler)

        settingsJobs += serviceScope!!.launchGuarded("gesture settings", restart = true) {
            settingsRepository.userPreferences.collectGuarded("gesture settings") { prefs ->
                currentPreferences = prefs
                val map = ConcurrentHashMap(buildDefaultMap())
                map[KEY_POSE_PINCH] = prefs.pinchAction.toGestureAction()
                map[KEY_POSE_POINTING] = prefs.pointingAction.toGestureAction()
                map[KEY_POSE_VICTORY] = prefs.victoryAction.toGestureAction()
                map[KEY_POSE_THUMB_UP] = prefs.thumbUpAction.toGestureAction()
                map[KEY_POSE_THUMB_DOWN] = prefs.thumbDownAction.toGestureAction()
                map[KEY_POSE_PINCH_HOLD] = prefs.pinchHoldAction.toGestureAction()
                map[KEY_PALM_HOME] = prefs.palmHomeAction.toGestureAction()
                gestureMapRef.set(map)
            }
        }
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
    }

    suspend fun dispatch(
        event: GestureEvent,
        engineState: GestureEngineState,
        cursorX: Float,
        cursorY: Float,
        screenWidth: Int,
        screenHeight: Int,
    ): Boolean {
        if (engineState != GestureEngineState.ARMED &&
            engineState != GestureEngineState.EXECUTING &&
            engineState != GestureEngineState.COOLDOWN
        ) return false

        val service = accessibilityServiceRef.get() ?: return false
        val action = when (event) {
            is GestureEvent.Swipe -> gestureMap[swipeKey(event.direction)]
            is GestureEvent.Pinch -> gestureMap[KEY_POSE_PINCH]
            is GestureEvent.PoseTriggered -> gestureMap[poseKey(event.pose)]
            is GestureEvent.PalmHome -> gestureMap[KEY_PALM_HOME]
            is GestureEvent.CustomGestureTriggered -> customGesturesList
                .firstOrNull { it.id == event.gestureId }
                ?.action?.toGestureAction()
            is GestureEvent.CursorMoved,
            is GestureEvent.Armed,
            is GestureEvent.Disarmed -> GestureAction.NONE
        } ?: GestureAction.NONE

        currentPose = when (event) {
            is GestureEvent.PoseTriggered -> event.pose
            else -> currentPose
        }

        if (!actionAllowed(action)) return false

        return when (action) {
            GestureAction.NONE -> false
            GestureAction.SCROLL_UP -> dispatchScroll(service, cursorX, cursorY, 0f, -1f, screenWidth, screenHeight)
            GestureAction.SCROLL_DOWN -> dispatchScroll(service, cursorX, cursorY, 0f, 1f, screenWidth, screenHeight)
            GestureAction.SCROLL_LEFT -> dispatchScroll(service, cursorX, cursorY, -1f, 0f, screenWidth, screenHeight)
            GestureAction.SCROLL_RIGHT -> dispatchScroll(service, cursorX, cursorY, 1f, 0f, screenWidth, screenHeight)
            GestureAction.BACK -> pressKey(KeyEvent.KEYCODE_BACK)
            GestureAction.HOME -> pressKey(KeyEvent.KEYCODE_HOME)
            GestureAction.RECENTS -> pressKey(KeyEvent.KEYCODE_APP_SWITCH)
            GestureAction.NOTIFICATIONS -> pressKey(KeyEvent.KEYCODE_NOTIFICATION)
            GestureAction.QUICK_SETTINGS -> pressKey(KeyEvent.KEYCODE_BRIGHTNESS_UP)
            GestureAction.VOLUME_UP -> pressVolume(true)
            GestureAction.VOLUME_DOWN -> pressVolume(false)
            GestureAction.MEDIA_PLAY_PAUSE -> pressKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            GestureAction.SCREENSHOT -> pressKey(KeyEvent.KEYCODE_SYSRQ)
            GestureAction.LOCK_SCREEN -> pressKey(KeyEvent.KEYCODE_POWER)
            GestureAction.TAP -> dispatchTap(service, cursorX, cursorY)
            GestureAction.DOUBLE_TAP -> dispatchDoubleTap(service, cursorX, cursorY)
            GestureAction.LONG_PRESS -> dispatchLongPress(service, cursorX, cursorY)
            GestureAction.DRAG -> dispatchDrag(service, cursorX, cursorY)
        }
    }

    fun dispatchBlinkTap(cursorX: Float, cursorY: Float, screenWidth: Int, screenHeight: Int): Boolean {
        if (!currentPreferences.blinkClickEnabled) return false
        val service = accessibilityServiceRef.get() ?: return false
        if (!actionAllowed(GestureAction.TAP)) return false
        return dispatchTap(service, cursorX, cursorY)
    }

    private fun actionAllowed(action: GestureAction): Boolean {
        if (!currentPreferences.gesturesEnabled) return false
        return true
    }

    private fun dispatchTap(service: AccessibilityService, x: Float, y: Float): Boolean {
        val path = Path()
        path.moveTo(x.coerceAtLeast(1f), y.coerceAtLeast(1f))
        return submitGesture(service, GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS)
        ).build(), GestureAction.TAP)
    }

    private fun dispatchDoubleTap(service: AccessibilityService, x: Float, y: Float): Boolean {
        val now = SystemClock.elapsedRealtime()
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
        path.lineTo((startX + dx * screenWidth * 0.22f).coerceIn(0f, screenWidth.toFloat()),
            (startY + dy * screenHeight * 0.22f).coerceIn(0f, screenHeight.toFloat()))
        return submitGesture(service, GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(path, 0, SCROLL_DURATION_MS)
        ).build(), if (dx > 0) GestureAction.SCROLL_RIGHT else if (dx < 0) GestureAction.SCROLL_LEFT else if (dy > 0) GestureAction.SCROLL_DOWN else GestureAction.SCROLL_UP)
    }

    private fun dispatchDrag(service: AccessibilityService, x: Float, y: Float): Boolean {
        if (!isDragging) {
            val path = Path()
            path.moveTo(x.coerceAtLeast(1f), y.coerceAtLeast(1f))
            lastDragStroke = GestureDescription.StrokeDescription(path, 0, DRAG_STEP_DURATION_MS)
            isDragging = submitGesture(service, GestureDescription.Builder().addStroke(lastDragStroke!!).build(), GestureAction.DRAG)
            dragCurrentX = x
            dragCurrentY = y
            pinchStartTimeMs = SystemClock.elapsedRealtime()
            pinchStartX = x
            pinchStartY = y
            pinchIsDrag = isDragging
            return isDragging
        }

        val deltaX = (x - dragCurrentX).coerceIn(-screenSafeStep(screenWidth = 1080f), screenSafeStep(screenWidth = 1080f))
        val deltaY = (y - dragCurrentY).coerceIn(-screenSafeStep(screenWidth = 1080f), screenSafeStep(screenWidth = 1080f))
        if (deltaX == 0f && deltaY == 0f) return true
        val path = Path()
        path.moveTo(dragCurrentX, dragCurrentY)
        path.lineTo(x, y)
        val nextStroke = lastDragStroke?.continueStroke(path, false, DRAG_STEP_DURATION_MS)
            ?: GestureDescription.StrokeDescription(path, 0, DRAG_STEP_DURATION_MS)
        val submitted = submitGesture(service, GestureDescription.Builder().addStroke(nextStroke).build(), GestureAction.DRAG)
        if (submitted) {
            lastDragStroke = nextStroke
            dragCurrentX = x
            dragCurrentY = y
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

    private fun pressKey(keyCode: Int): Boolean {
        val audio = audioManager
        val service = accessibilityServiceRef.get() ?: return false
        return try {
            val down = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val up = KeyEvent(KeyEvent.ACTION_UP, keyCode)
            service.dispatchKeyEvent(down)
            service.dispatchKeyEvent(up)
            true
        } catch (e: Throwable) {
            Timber.e(e, "dispatchKeyEvent failed: %s", keyCode)
            false
        }
    }

    private fun pressVolume(up: Boolean): Boolean {
        val audio = audioManager ?: return false
        return try {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, if (up) KeyEvent.KEYCODE_VOLUME_UP else KeyEvent.KEYCODE_VOLUME_DOWN))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, if (up) KeyEvent.KEYCODE_VOLUME_UP else KeyEvent.KEYCODE_VOLUME_DOWN))
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

    private fun CustomGestureDirection.toGestureAction(): GestureAction = when (this) {
        CustomGestureDirection.NONE -> GestureAction.NONE
        CustomGestureDirection.LEFT -> GestureAction.SCROLL_LEFT
        CustomGestureDirection.RIGHT -> GestureAction.SCROLL_RIGHT
        CustomGestureDirection.UP -> GestureAction.SCROLL_UP
        CustomGestureDirection.DOWN -> GestureAction.SCROLL_DOWN
    }

    private fun CustomGesturePose.toGestureAction(): GestureAction = when (this) {
        CustomGesturePose.NONE -> GestureAction.NONE
        CustomGesturePose.TAP -> GestureAction.TAP
        CustomGesturePose.DOUBLE_TAP -> GestureAction.DOUBLE_TAP
        CustomGesturePose.LONG_PRESS -> GestureAction.LONG_PRESS
        CustomGesturePose.DRAG -> GestureAction.DRAG
        CustomGesturePose.HOME -> GestureAction.HOME
        CustomGesturePose.BACK -> GestureAction.BACK
        CustomGesturePose.RECENTS -> GestureAction.RECENTS
        CustomGesturePose.NOTIFICATIONS -> GestureAction.NOTIFICATIONS
        CustomGesturePose.QUICK_SETTINGS -> GestureAction.QUICK_SETTINGS
        CustomGesturePose.VOLUME_UP -> GestureAction.VOLUME_UP
        CustomGesturePose.VOLUME_DOWN -> GestureAction.VOLUME_DOWN
        CustomGesturePose.MEDIA_PLAY_PAUSE -> GestureAction.MEDIA_PLAY_PAUSE
    }

    private fun CustomGestureTrigger.toGestureAction(): GestureAction = when (this) {
        is CustomGestureTrigger.PoseTrigger -> triggerPose.toGestureAction()
        is CustomGestureTrigger.DirectionTrigger -> direction.toGestureAction()
        is CustomGestureTrigger.LandmarkTemplateTrigger -> action.toGestureAction()
    }

    // The preference model historically exposed these fields. Keep this small
    // bridge in one place so Phase-1 mapping changes don't alter user settings.
    private val UserPreferences.pinchAction: CustomGesturePose get() = CustomGesturePose.TAP
    private val UserPreferences.pointingAction: CustomGesturePose get() = CustomGesturePose.NONE
    private val UserPreferences.victoryAction: CustomGesturePose get() = CustomGesturePose.MEDIA_PLAY_PAUSE
    private val UserPreferences.thumbUpAction: CustomGesturePose get() = CustomGesturePose.VOLUME_UP
    private val UserPreferences.thumbDownAction: CustomGesturePose get() = CustomGesturePose.VOLUME_DOWN
    private val UserPreferences.pinchHoldAction: CustomGesturePose get() = CustomGesturePose.DRAG
    private val UserPreferences.palmHomeAction: CustomGesturePose get() = CustomGesturePose.HOME
}
