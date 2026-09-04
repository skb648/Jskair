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

/** Maps gesture types to system actions. */
enum class GestureAction {
    NONE, SCROLL_UP, SCROLL_DOWN, SCROLL_LEFT, SCROLL_RIGHT, BACK, HOME, RECENTS,
    NOTIFICATIONS, QUICK_SETTINGS, VOLUME_UP, VOLUME_DOWN, MEDIA_PLAY_PAUSE, SCREENSHOT,
    LOCK_SCREEN, TAP, DOUBLE_TAP, LONG_PRESS, DRAG,
}

/** Maps GestureEvent → system actions using the user's gesture configuration. */
@Singleton
class ActionDispatcher @Inject constructor(
    private val settingsRepository: SettingsRepository,
) {
    @Volatile var onGestureDispatched: ((String) -> Unit)? = null

    private val _dispatchedEvents = MutableSharedFlow<String>(
        extraBufferCapacity = 32,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val dispatchedEvents: SharedFlow<String> = _dispatchedEvents.asSharedFlow()

    private var serviceScope: CoroutineScope? = null
    private val settingsJobs = mutableListOf<Job>()
    private var accessibilityServiceRef = WeakReference<AccessibilityService>(null)
    private var audioManager: AudioManager? = null
    @Volatile private var currentPreferences = UserPreferences()

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

        // Full-frame mapping defaults. Cursor sensitivity is expressed by the
        // configured gain, but the mapping remains symmetric around the frame
        // center so the top/bottom and left/right edges have the same feel.
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

        fun normalizeToScreenX(normX: Float, screenWidth: Int): Float =
            mapSymmetric(normX, screenWidth, symmetricActiveFraction(cursorGain, false))

        fun normalizeToScreenY(normY: Float, screenHeight: Int): Float =
            mapSymmetric(normY, screenHeight, symmetricActiveFraction(cursorGain, true))

        @Volatile private var cursorGain: Float = 0.5f
        @Volatile private var sitBackModeEnabled: Boolean = false

        private fun symmetricActiveFraction(gain: Float, vertical: Boolean): Float {
            if (vertical && sitBackModeEnabled) return SIT_BACK_ACTIVE_Y_FRACTION
            val normalizedGain = gain.coerceIn(0f, 1f)
            return MIN_ACTIVE_FRACTION +
                normalizedGain * (MAX_ACTIVE_FRACTION - MIN_ACTIVE_FRACTION)
        }

        private fun mapSymmetric(norm: Float, screenSize: Int, activeFraction: Float): Float {
            if (screenSize <= 0) return 0f
            val active = activeFraction.coerceIn(MIN_ACTIVE_FRACTION, 1f)
            val margin = (1f - active) * 0.5f
            val normalized = ((norm.coerceIn(0f, 1f) - margin) / active).coerceIn(0f, 1f)
            return normalized * screenSize
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

    @Volatile private var customGesturesList: List<CustomGesture> = emptyList()
    @Volatile private var currentPose: Pose = Pose.NONE
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

    // Remaining implementation is unchanged from the existing dispatcher.
    // The mapping functions above are the only Phase-1 behavioural change in this file.
