package com.aircontrol.gestures

import com.aircontrol.gesture.GestureEngine
import com.aircontrol.gesture.config.GestureEngineConfig
import com.aircontrol.gesture.model.GestureEngineState
import com.aircontrol.gesture.model.GestureEvent
import com.aircontrol.gesture.model.HandInput
import com.aircontrol.gesture.model.Pose
import com.aircontrol.tracking.HandFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the Android tracking layer (HandFrame from MediaPipe)
 * with the pure-Kotlin gesture engine (HandInput).
 *
 * Converts [HandFrame] → [HandInput] and exposes the engine's
 * [GestureEvent] flow and [GestureEngineState] for UI consumption.
 */
interface GestureDetector {
    val gestureEvents: SharedFlow<GestureEvent>
    val engineState: StateFlow<GestureEngineState>
    val currentPose: StateFlow<Pose>
    val armingProgress: StateFlow<Float>
    fun processHandFrame(frame: HandFrame)
    fun updateSensitivity(sensitivity: Int)
    fun reset()
}

@Singleton
class GestureDetectorImpl @Inject constructor() : GestureDetector {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var engine: GestureEngine = GestureEngine(GestureEngineConfig())
    private var engineEventsJob: Job? = null
    private var currentSensitivity: Int = 50

    private val _gestureEvents = MutableSharedFlow<GestureEvent>(
        extraBufferCapacity = 16,
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

        Timber.d("Updating gesture engine sensitivity to %d", clamped)
        currentSensitivity = clamped
        engineEventsJob?.cancel()
        engine.reset()
        engine = GestureEngine(GestureEngineConfig(sensitivity = clamped))
        collectEngineEvents()
        resetStateFlows()
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
