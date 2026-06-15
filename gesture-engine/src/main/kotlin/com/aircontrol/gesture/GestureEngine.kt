package com.aircontrol.gesture

import com.aircontrol.gesture.config.GestureEngineConfig
import com.aircontrol.gesture.detection.DynamicGestureDetector
import com.aircontrol.gesture.detection.StaticPoseClassifier
import com.aircontrol.gesture.model.GestureEngineState
import com.aircontrol.gesture.model.GestureEvent
import com.aircontrol.gesture.model.HandInput
import com.aircontrol.gesture.model.LandmarkIndex
import com.aircontrol.gesture.model.PinchPhase
import com.aircontrol.gesture.model.Pose
import com.aircontrol.gesture.statemachine.GestureStateMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Core gesture recognition engine.
 *
 * Input: [Flow]<[HandInput]> — raw hand tracking frames from MediaPipe.
 * Output: [Flow]<[GestureEvent]> — recognized gesture events.
 *
 * The engine orchestrates:
 * 1. Static pose classification (with N-frame debounce)
 * 2. Dynamic gesture detection (swipe via sliding window)
 * 3. Pinch tracking (lifecycle: start/move/end)
 * 4. State machine (DISARMED → ARMING → ARMED → EXECUTING → COOLDOWN → ARMED)
 * 5. Cursor position tracking (normalized index fingertip)
 *
 * Usage:
 * ```kotlin
 * val engine = GestureEngine(config)
 * engine.start(handFrameFlow)
 * // Collect events:
 * engine.gestureEvents.collect { event -> ... }
 * ```
 */
class GestureEngine(
    private val config: GestureEngineConfig = GestureEngineConfig(),
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val poseClassifier = StaticPoseClassifier(config)
    private val dynamicDetector = DynamicGestureDetector(config)
    private val stateMachine = GestureStateMachine(config)

    private val _gestureEvents = MutableSharedFlow<GestureEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val gestureEvents: SharedFlow<GestureEvent> = _gestureEvents.asSharedFlow()

    private val _engineState = MutableStateFlow(GestureEngineState.DISARMED)
    val engineState: StateFlow<GestureEngineState> = _engineState.asStateFlow()

    private val _currentPose = MutableStateFlow(Pose.NONE)
    val currentPose: StateFlow<Pose> = _currentPose.asStateFlow()

    private val _armingProgress = MutableStateFlow(0f)
    val armingProgress: StateFlow<Float> = _armingProgress.asStateFlow()

    // Pinch tracking state
    private var wasPinching: Boolean = false
    private var pinchStartX: Float = 0f
    private var pinchStartY: Float = 0f

    /**
     * Starts processing hand input frames.
     * Collects from the provided flow and emits gesture events.
     */
    fun start(inputFlow: Flow<HandInput>) {
        scope.launch {
            inputFlow.collect { input ->
                processFrame(input)
            }
        }
    }

    /**
     * Processes a single hand input frame synchronously.
     * Useful for testing or when manual frame processing is preferred.
     */
    fun processFrame(input: HandInput) {
        val timestampMs = input.timestampMs

        // 1. Classify static pose (with debounce)
        val pose = poseClassifier.classify(input)
        _currentPose.value = pose

        // 2. Detect dynamic gestures (swipes)
        val swipeResult = dynamicDetector.process(input)

        // 3. Process pinch lifecycle
        processPinch(input, pose, timestampMs)

        // 4. Process through state machine
        val transition = stateMachine.process(pose, input.isDetected, timestampMs)
        _engineState.value = transition.newState
        _armingProgress.value = stateMachine.armingProgress

        // 5. Emit events based on state transitions and gesture detection
        if (transition.stateChanged) {
            when (transition.newState) {
                GestureEngineState.ARMED -> {
                    _gestureEvents.tryEmit(GestureEvent.Armed(timestampMs))
                }
                GestureEngineState.DISARMED -> {
                    _gestureEvents.tryEmit(GestureEvent.Disarmed(timestampMs))
                    wasPinching = false
                }
                GestureEngineState.EXECUTING -> {
                    // The gesture that triggered execution will be emitted below
                }
                GestureEngineState.ARMING -> {
                    // No event for arming start — progress is observable via armingProgress
                }
                GestureEngineState.COOLDOWN -> {
                    // No event — internal state
                }
            }
        }

        // 6. Emit gesture events (when ARMED, EXECUTING, or COOLDOWN)
        // COOLDOWN is included so swipes can be detected even after a pose gesture
        if (transition.newState == GestureEngineState.ARMED ||
            transition.newState == GestureEngineState.EXECUTING ||
            transition.newState == GestureEngineState.COOLDOWN
        ) {
            // Swipes
            if (swipeResult.detected && swipeResult.direction != null) {
                _gestureEvents.tryEmit(GestureEvent.Swipe(swipeResult.direction, timestampMs))
            }

            // Pose-triggered gestures (when transitioning to EXECUTING)
            if (transition.shouldExecute) {
                val actionablePose = pose.takeIf {
                    it != Pose.NONE && it != Pose.OPEN_PALM && it != Pose.FIST
                }
                if (actionablePose != null) {
                    _gestureEvents.tryEmit(GestureEvent.PoseTriggered(actionablePose, timestampMs))
                }
            }
        }

        // 7. Cursor position (always emitted when hand is detected and armed)
        if (input.isDetected && (
            transition.newState == GestureEngineState.ARMED ||
                transition.newState == GestureEngineState.COOLDOWN
        )
        ) {
            val indexTip = input.landmarks[LandmarkIndex.INDEX_TIP]
            _gestureEvents.tryEmit(
                GestureEvent.CursorMoved(indexTip.x, indexTip.y, timestampMs),
            )
        }
    }

    /**
     * Manages the pinch gesture lifecycle.
     * - START: When pinch is newly detected (was not pinching before)
     * - MOVE: When pinch is ongoing (thumb-index distance still small)
     * - END: When fingers separate after a pinch
     */
    private fun processPinch(input: HandInput, pose: Pose, timestampMs: Long) {
        if (!input.isDetected) {
            if (wasPinching) {
                _gestureEvents.tryEmit(
                    GestureEvent.Pinch(PinchPhase.END, pinchStartX, pinchStartY, timestampMs),
                )
                wasPinching = false
            }
            return
        }

        val thumbTip = input.landmarks[LandmarkIndex.THUMB_TIP]
        val indexTip = input.landmarks[LandmarkIndex.INDEX_TIP]

        // Use the pinch center as the gesture position
        val pinchX = (thumbTip.x + indexTip.x) / 2f
        val pinchY = (thumbTip.y + indexTip.y) / 2f

        if (pose == Pose.PINCH) {
            if (!wasPinching) {
                // Pinch START
                wasPinching = true
                pinchStartX = pinchX
                pinchStartY = pinchY
                _gestureEvents.tryEmit(
                    GestureEvent.Pinch(PinchPhase.START, pinchX, pinchY, timestampMs),
                )
            } else {
                // Pinch MOVE
                _gestureEvents.tryEmit(
                    GestureEvent.Pinch(PinchPhase.MOVE, pinchX, pinchY, timestampMs),
                )
            }
        } else if (wasPinching) {
            // Pinch END
            wasPinching = false
            _gestureEvents.tryEmit(
                GestureEvent.Pinch(PinchPhase.END, pinchStartX, pinchStartY, timestampMs),
            )
        }
    }

    /** Resets all gesture engine state. */
    fun reset() {
        poseClassifier.reset()
        dynamicDetector.reset()
        stateMachine.reset()
        wasPinching = false
        pinchStartX = 0f
        pinchStartY = 0f
        _engineState.value = GestureEngineState.DISARMED
        _currentPose.value = Pose.NONE
        _armingProgress.value = 0f
    }
}
