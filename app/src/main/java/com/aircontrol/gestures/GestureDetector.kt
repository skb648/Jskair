package com.aircontrol.gestures

import com.aircontrol.gesture.GestureEngine
import com.aircontrol.gesture.config.GestureEngineConfig
import com.aircontrol.gesture.model.GestureEngineState
import com.aircontrol.gesture.model.GestureEvent
import com.aircontrol.gesture.model.HandInput
import com.aircontrol.gesture.model.LandmarkTemplate
import com.aircontrol.gesture.model.Pose
import com.aircontrol.tracking.HandFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.Volatile

/**
 * Bridges the Android tracking layer (HandFrame from MediaPipe)
 * with the pure-Kotlin gesture engine (HandInput).
 *
 * Converts [HandFrame] → [HandInput] and exposes the engine's
 * [GestureEvent] flow and [GestureEngineState] for UI consumption.
 */
interface GestureDetector : AutoCloseable {
    val gestureEvents: SharedFlow<GestureEvent>
    val engineState: StateFlow<GestureEngineState>
    val currentPose: StateFlow<Pose>
    val armingProgress: StateFlow<Float>
    fun processHandFrame(frame: HandFrame)
    fun updateSensitivity(sensitivity: Int)

    /**
     * Fix A-11: whether a swipe requires the open-palm pose. Forwarded to the
     * engine so that moving the cursor can never scroll the page.
     */
    fun updateSwipeRequiresOpenHand(requiresOpenHand: Boolean)

    /**
     * Bug: Custom Gestures Not Triggering Fix — Updates the dynamic list of
     * user-defined landmark templates that the engine matches against live
     * hand frames. The templates are converted from app-layer
     * [com.aircontrol.data.model.CustomGesture] objects by the caller.
     *
     * Safe to call from any thread. The templates are applied atomically on
     * the next frame.
     */
    fun updateCustomTemplates(templates: List<LandmarkTemplate>)

    /**
     * Personalizes pinch detection using user-measured calibration data.
     * Pass 0f (or handSizeMm <= 0) to clear calibration.
     */
    fun updateCalibration(handSizeMm: Float, pinchDistanceMm: Float)

    fun reset()

    override fun close() {
        // Default no-op; implementations should cancel their coroutine scope
    }
}

@Singleton
class GestureDetectorImpl @Inject constructor() : GestureDetector {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // AtomicReference overkill but kept for thread-safe swap; could be @Volatile var
    private val engineRef = AtomicReference(GestureEngine(GestureEngineConfig()))
    private val engine: GestureEngine get() = engineRef.get()
    private var engineEventsJob: Job? = null
    @Volatile
    private var currentSensitivity: Int = 70

    private val _gestureEvents = MutableSharedFlow<GestureEvent>(
        // Fix (audit #16): deeper buffer so pinch start/move/end sequences can
        // never be dropped by a transient slow collector (see GestureEngine).
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    override val gestureEvents: SharedFlow<GestureEvent> = _gestureEvents.asSharedFlow()

    private val _engineState = MutableStateFlow(GestureEngineState.DISARMED)
    override val engineState: StateFlow<GestureEngineState> = _engineState.asStateFlow()

    private val _currentPose = MutableStateFlow(Pose.NONE)
    override val currentPose: StateFlow<Pose> = _currentPose.asStateFlow()

    private val _armingProgress = MutableStateFlow(0f)
    override val armingProgress: StateFlow<Float> = _armingProgress.asStateFlow()

    init {
        collectEngineEvents()
    }

    override fun processHandFrame(frame: HandFrame) {
        val input = frame.toHandInput()
        engine.processFrame(input)

        // Forward state from engine
        _engineState.value = engine.engineState.value
        _currentPose.value = engine.currentPose.value
        _armingProgress.value = engine.armingProgress.value
    }

    override fun updateSensitivity(sensitivity: Int) {
        val clamped = sensitivity.coerceIn(0, 100)
        if (clamped == currentSensitivity) return

        Timber.i("Updating gesture engine sensitivity to %d (without engine recreation)", clamped)
        currentSensitivity = clamped
        // H-06 Fix: Update sensitivity without recreating the entire engine.
        // The old code destroyed and recreated GestureEngine on every slider change,
        // which lost all in-progress gesture state (arming, pinch, swipe detection).
        // Now we call the engine's updateSensitivity() which propagates the new config
        // to all detectors while preserving state.
        engine.updateSensitivity(clamped)
    }

    /**
     * Bug: Custom Gestures Not Triggering Fix — Delegates to the engine's
     * [GestureEngine.updateCustomTemplates]. The templates are forwarded as-is
     * (they're already converted from app-layer CustomGesture objects by the
     * caller in GestureControlAccessibilityService).
     */
    override fun updateCustomTemplates(templates: List<LandmarkTemplate>) {
        engine.updateCustomTemplates(templates)
    }

    /**
     * Fix A-11: forward the user's "swipes need an open palm" setting to the
     * engine. Without this the engine kept its compiled-in default, so the
     * Settings switch did nothing and pointer travel kept scrolling pages.
     */
    override fun updateSwipeRequiresOpenHand(requiresOpenHand: Boolean) {
        Timber.i("Updating swipe pose gate: requiresOpenHand=%s", requiresOpenHand)
        engine.updateSwipeRequiresOpenHand(requiresOpenHand)
    }

    override fun updateCalibration(handSizeMm: Float, pinchDistanceMm: Float) {
        engine.updateCalibration(handSizeMm, pinchDistanceMm)
    }

    override fun reset() {
        engine.reset()
        resetStateFlows()
        Timber.d("Gesture detector reset")
    }

    private fun collectEngineEvents() {
        engineEventsJob = scope.launch {
            engine.gestureEvents.collect { event ->
                _gestureEvents.tryEmit(event)
            }
        }
    }

    private fun resetStateFlows() {
        _engineState.value = GestureEngineState.DISARMED
        _currentPose.value = Pose.NONE
        _armingProgress.value = 0f
    }

    override fun close() {
        engine.stop()
        scope.cancel()
        Timber.d("GestureDetector closed and scope cancelled")
    }

    /**
     * Maps [HandFrame] from the tracking layer to [HandInput] for the gesture engine.
     * This is the only place where Android tracking types touch the pure-Kotlin engine.
     */
    private fun HandFrame.toHandInput(): HandInput {
        return HandInput(
            landmarks = landmarks.map { lm ->
                com.aircontrol.gesture.model.Landmark3D(
                    x = lm.x,
                    y = lm.y,
                    z = lm.z,
                )
            },
            handedness = when (handedness) {
                com.aircontrol.tracking.Handedness.LEFT -> com.aircontrol.gesture.model.Handedness.LEFT
                com.aircontrol.tracking.Handedness.RIGHT -> com.aircontrol.gesture.model.Handedness.RIGHT
                com.aircontrol.tracking.Handedness.UNKNOWN -> com.aircontrol.gesture.model.Handedness.UNKNOWN
            },
            timestampMs = timestampMs,
            confidence = confidence,
        )
    }
}
