package com.aircontrol.gesture

import com.aircontrol.gesture.config.GestureEngineConfig
import com.aircontrol.gesture.detection.DynamicGestureDetector
import com.aircontrol.gesture.detection.StaticPoseClassifier
import com.aircontrol.gesture.model.GestureEngineState
import com.aircontrol.gesture.model.GestureEvent
import com.aircontrol.gesture.model.HandInput
import com.aircontrol.gesture.model.LandmarkIndex
import com.aircontrol.gesture.model.LandmarkTemplate
import com.aircontrol.gesture.model.PinchPhase
import com.aircontrol.gesture.model.Pose
import com.aircontrol.gesture.model.SwipeDirection
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
import kotlin.concurrent.Volatile

private enum class PinchState { IDLE, HOVER, PINCH_START, PINCH_HOLD, PINCH_RELEASE }

class GestureEngine(
    initialConfig: GestureEngineConfig = GestureEngineConfig(),
) {
    @Volatile var config: GestureEngineConfig = initialConfig
        private set

    private var scopeJob = SupervisorJob()
    private var scope = CoroutineScope(scopeJob + Dispatchers.Default)
    private val poseClassifier = StaticPoseClassifier(initialConfig)
    private val dynamicDetector = DynamicGestureDetector(initialConfig)
    private val stateMachine = GestureStateMachine(initialConfig)

    private val _gestureEvents = MutableSharedFlow<GestureEvent>(
        // Fix (audit #16): 16 slots could be wiped out by a transient main-thread
        // stall, and DROP_OLDEST then ate a Pinch START/END — the exact
        // "pinch visualized but no click" failure. 64 slots ≈ >2s of 30fps
        // events, making a lost critical sequence practically impossible while
        // keeping memory bounded.
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val gestureEvents: SharedFlow<GestureEvent> = _gestureEvents.asSharedFlow()
    private val _engineState = MutableStateFlow(GestureEngineState.DISARMED)
    val engineState: StateFlow<GestureEngineState> = _engineState.asStateFlow()
    private val _currentPose = MutableStateFlow(Pose.NONE)
    val currentPose: StateFlow<Pose> = _currentPose.asStateFlow()
    private val _armingProgress = MutableStateFlow(0f)
    val armingProgress: StateFlow<Float> = _armingProgress.asStateFlow()

    private var pinchState = PinchState.IDLE
    private var pinchStateEntryTimeMs = 0L
    private val TIME_DEBOUNCE_MS = 80L
    @Volatile private var wasPinching = false
    @Volatile private var pinchStartX = 0f
    @Volatile private var pinchStartY = 0f
    @Volatile private var pinchAnchoredX = 0f
    @Volatile private var pinchAnchoredY = 0f
    @Volatile private var currentPinchPhase: PinchPhase? = null
    @Volatile private var lastPinchEndMs = 0L
    @Volatile private var lastCustomGestureId: String? = null
    @Volatile private var prevIndexTipX = 0.5f
    @Volatile private var prevIndexTipY = 0.5f
    @Volatile private var prevIndexTipTimestampMs = 0L
    @Volatile private var currentVelocity = 0f
    @Volatile private var palmHoldStartMs = 0L
    @Volatile private var palmHolding = false
    @Volatile private var palmHomeFired = false
    @Volatile private var palmHoldAnchorX = -1f
    @Volatile private var palmHoldAnchorY = -1f
    @Volatile private var handStillSinceMs = 0L
    @Volatile private var thumbPoseSinceMs = 0L
    @Volatile private var measuredFrameIntervalMs = 0L
    @Volatile private var prevFrameTimestampMs = 0L
    @Volatile private var armedSinceMs = 0L
    @Volatile private var lowConfidenceFrameCount = 0

    @Volatile private var lastPalmX = 0.5f
    @Volatile private var lastPalmY = 0.5f
    @Volatile private var hasPalmPosition = false

    fun stop() { scopeJob.cancel() }

    /**
     * Optional debug hook (spec §18) — null in production, so there is NO log
     * spam by default. When set, it fires once per swipe DECISION that had real
     * evidence (committed, suppressed, or rejected past the displacement gate):
     * (detected, direction, confidence, reason, dispX, dispY, timestampMs).
     * Frames with no meaningful motion never fire it.
     */
    @Volatile var onSwipeDecision: ((detected: Boolean, direction: SwipeDirection?, confidence: Float, reason: String?, displacementX: Float, displacementY: Float, timestampMs: Long) -> Unit)? = null

    fun updateSensitivity(sensitivity: Int) {
        val newConfig = config.copy(sensitivity = sensitivity)
        config = newConfig
        poseClassifier.updateConfig(newConfig)
        dynamicDetector.updateConfig(newConfig)
        stateMachine.updateConfig(newConfig)
    }

    fun updateSwipeRequiresOpenHand(requiresOpenHand: Boolean) {
        if (config.swipeRequiresOpenHand == requiresOpenHand) return
        val newConfig = config.copy(swipeRequiresOpenHand = requiresOpenHand)
        config = newConfig
        dynamicDetector.updateConfig(newConfig)
    }

    fun updateCalibration(handSizeMm: Float, pinchDistanceMm: Float) {
        val ratio = if (handSizeMm > 0f && pinchDistanceMm > 0f) pinchDistanceMm / handSizeMm else null
        val newConfig = config.copy(calibratedPinchRatio = ratio)
        config = newConfig
        poseClassifier.updateConfig(newConfig)
        dynamicDetector.updateConfig(newConfig)
        stateMachine.updateConfig(newConfig)
    }

    fun updateCustomTemplates(templates: List<LandmarkTemplate>) {
        poseClassifier.updateCustomTemplates(templates)
        lastCustomGestureId = null
    }

    fun processFrame(input: HandInput) {
        val timestampMs = input.timestampMs
        val isLowConfidence = input.isDetected && input.confidence < CONFIDENCE_THRESHOLD
        if (isLowConfidence) lowConfidenceFrameCount++ else lowConfidenceFrameCount = 0
        val lowConfidence = lowConfidenceFrameCount >= LOW_CONFIDENCE_MIN_FRAMES
        poseClassifier.effectiveDebounceFrames = if (lowConfidence) LOW_CONFIDENCE_DEBOUNCE_FRAMES else config.poseDebounceFrames

        if (input.isDetected) {
            updateMeasuredFrameInterval(timestampMs)
            poseClassifier.effectiveDebounceFrames = if (lowConfidence) LOW_CONFIDENCE_DEBOUNCE_FRAMES
            else config.debounceFramesFor(measuredFrameIntervalMs)

            val palm = CursorAnchor.palm(input)
            if (palm != null && !lowConfidence) {
                lastPalmX = palm.first
                lastPalmY = palm.second
                hasPalmPosition = true
            }

            val indexTip = input.landmarks[LandmarkIndex.INDEX_TIP]
            if (prevIndexTipTimestampMs > 0L) {
                val dtMs = (timestampMs - prevIndexTipTimestampMs).coerceAtLeast(1L)
                val dx = indexTip.x - prevIndexTipX
                val dy = indexTip.y - prevIndexTipY
                currentVelocity = kotlin.math.sqrt(dx * dx + dy * dy) / (dtMs / 1000f)
            } else currentVelocity = 0f
            prevIndexTipX = indexTip.x
            prevIndexTipY = indexTip.y
            prevIndexTipTimestampMs = timestampMs
            if (!lowConfidence && hasPalmPosition) {
                if (currentVelocity > config.thumbGestureMaxVelocity) handStillSinceMs = 0L
                else if (handStillSinceMs == 0L) handStillSinceMs = timestampMs
            }
        }

        val pose = poseClassifier.classify(input, currentVelocity)
        _currentPose.value = pose
        val swipeAllowed = !config.swipeRequiresOpenHand || pose == Pose.OPEN_PALM
        val swipeResult = dynamicDetector.process(input, gestureAllowed = swipeAllowed)

        val isThumbPose = pose == Pose.THUMB_UP || pose == Pose.THUMB_DOWN
        if (isThumbPose) {
            if (thumbPoseSinceMs == 0L) thumbPoseSinceMs = timestampMs
        } else thumbPoseSinceMs = 0L
        val still = handStillSinceMs > 0L
        val thumbHeldLongEnough = still && isThumbPose && timestampMs - thumbPoseSinceMs >= config.thumbGestureHoldMs
        val suppressExecution = isThumbPose && !thumbHeldLongEnough

        val transition = stateMachine.process(
            pose = pose,
            handDetected = input.isDetected,
            timestampMs = timestampMs,
            fistLike = poseClassifier.lastFrameFistLike,
            suppressPoseExecution = suppressExecution,
        )
        _engineState.value = transition.newState
        _armingProgress.value = stateMachine.armingProgress

        processPinch(input, timestampMs, allowEntry = !lowConfidence)

        if (transition.newState == GestureEngineState.ARMED && input.isDetected) {
            if (pose == Pose.OPEN_PALM) {
                val travel = if (palmHoldAnchorX >= 0f) {
                    maxOf(kotlin.math.abs(lastPalmX - palmHoldAnchorX), kotlin.math.abs(lastPalmY - palmHoldAnchorY))
                } else 0f
                val isMoving = currentVelocity > 0.05f || travel > config.palmHomeMaxCursorMovement

                if (!palmHolding || isMoving) {
                    palmHolding = true
                    palmHoldStartMs = timestampMs
                    palmHoldAnchorX = lastPalmX
                    palmHoldAnchorY = lastPalmY
                    palmHomeFired = false
                } else if (!palmHomeFired && timestampMs - palmHoldStartMs >= config.palmHomeHoldMs && palmHomeConditionsMet(input, timestampMs)) {
                    palmHomeFired = true
                    _gestureEvents.tryEmit(GestureEvent.PalmHome(timestampMs))
                }
            } else resetPalmTracking()
        } else resetPalmTracking()

        if (transition.stateChanged) {
            when (transition.newState) {
                GestureEngineState.ARMED -> {
                    armedSinceMs = timestampMs
                    _gestureEvents.tryEmit(GestureEvent.Armed(timestampMs))
                }
                GestureEngineState.DISARMED -> {
                    _gestureEvents.tryEmit(GestureEvent.Disarmed(timestampMs))
                    wasPinching = false
                    lastCustomGestureId = null
                }
                else -> Unit
            }
        }

        if (transition.newState == GestureEngineState.ARMED || transition.newState == GestureEngineState.EXECUTING || transition.newState == GestureEngineState.COOLDOWN) {
            // Arbitration (hardening round 9, spec §13/§15): the swipe loses
            // against an ACTIVE pinch/drag unconditionally — the same physical
            // motion belongs to the drag. This holds even when the user has
            // disabled the open-palm pose gate, closing the last path where a
            // drag could double-fire as a scroll.
            val pinchActive = wasPinching || currentPinchPhase != null
            val swipeSuppressed = pinchActive ||
                (lastPinchEndMs > 0L && timestampMs - lastPinchEndMs < SWIPE_SUPPRESSION_AFTER_PINCH_MS)
            if (swipeResult.detected && swipeResult.direction != null && !swipeSuppressed && !lowConfidence) {
                _gestureEvents.tryEmit(GestureEvent.Swipe(swipeResult.direction, timestampMs))
                onSwipeDecision?.invoke(
                    true, swipeResult.direction, swipeResult.confidence, null,
                    swipeResult.displacementX, swipeResult.displacementY, timestampMs,
                )
            } else if (swipeResult.detected) {
                onSwipeDecision?.invoke(
                    false, swipeResult.direction, swipeResult.confidence,
                    if (lowConfidence) "LOW_CONFIDENCE" else "PINCH_ACTIVE",
                    swipeResult.displacementX, swipeResult.displacementY, timestampMs,
                )
            } else if (swipeResult.hadEvidence) {
                onSwipeDecision?.invoke(
                    false, null, swipeResult.confidence, swipeResult.reason?.name,
                    swipeResult.displacementX, swipeResult.displacementY, timestampMs,
                )
            }
            if (transition.shouldExecute) {
                val actionablePose = pose.takeIf { it != Pose.NONE && it != Pose.OPEN_PALM && it != Pose.FIST }
                if (actionablePose != null) _gestureEvents.tryEmit(GestureEvent.PoseTriggered(actionablePose, timestampMs))
            }
            if (!lowConfidence && input.isDetected) {
                val matchedTemplate = poseClassifier.matchCustomTemplate(input)
                if (matchedTemplate != null) {
                    if (matchedTemplate.gestureId != lastCustomGestureId) {
                        lastCustomGestureId = matchedTemplate.gestureId
                        _gestureEvents.tryEmit(GestureEvent.CustomGestureTriggered(matchedTemplate.gestureId, matchedTemplate.name, timestampMs))
                    }
                } else lastCustomGestureId = null
            }
        }

        val effectiveCursorX = if (currentPinchPhase == PinchPhase.START) pinchAnchoredX else lastPalmX
        val effectiveCursorY = if (currentPinchPhase == PinchPhase.START) pinchAnchoredY else lastPalmY
        if (input.isDetected && hasPalmPosition && (transition.newState == GestureEngineState.ARMING || transition.newState == GestureEngineState.ARMED || transition.newState == GestureEngineState.EXECUTING || transition.newState == GestureEngineState.COOLDOWN)) {
            // Fix B4: EXECUTING is included so the dot keeps following the palm
            // while a triggered action is in flight. Previously the dot froze
            // for the duration of every EXECUTING burst, which read as a stutter
            // right at the moment the user was most engaged.
            val isSilent = transition.newState == GestureEngineState.ARMING
            val hint = if (lowConfidence) LOW_CONFIDENCE_SMOOTHER_MIN_CUTOFF else null
            _gestureEvents.tryEmit(GestureEvent.CursorMoved(effectiveCursorX, effectiveCursorY, timestampMs, isSilent, hint))
        }
    }

    /**
     * Pinch click/drag FSM.
     *
     * Hardening round 9 (spec §11 — prefer CANCEL over GUESS): [allowEntry] is
     * false while tracking is in low-confidence mode. No NEW pinch may START
     * (IDLE→HOVER, HOVER→PINCH_START, and the 80ms START→HOLD confirmation are
     * gated), so blurry frames can never commit a click/drag. An ONGOING pinch
     * is deliberately not interrupted mid-hold (killing a drag because one
     * stretch of frames went soft would strand the press); it keeps flowing
     * MOVE updates and always terminates with END.
     */
    private fun processPinch(input: HandInput, timestampMs: Long, allowEntry: Boolean) {
        val currentState = _engineState.value
        if (currentState != GestureEngineState.ARMED && currentState != GestureEngineState.EXECUTING && currentState != GestureEngineState.COOLDOWN) {
            if (wasPinching) { wasPinching = false; currentPinchPhase = null; pinchState = PinchState.IDLE }
            return
        }
        if (!input.isDetected) {
            if (wasPinching) {
                _gestureEvents.tryEmit(GestureEvent.Pinch(PinchPhase.END, pinchStartX, pinchStartY, timestampMs, pinchAnchoredX, pinchAnchoredY, currentVelocity))
                wasPinching = false; currentPinchPhase = null; pinchState = PinchState.IDLE
            }
            return
        }
        val thumbTip = input.landmarks[LandmarkIndex.THUMB_TIP]
        val indexTip = input.landmarks[LandmarkIndex.INDEX_TIP]
        val wrist = input.landmarks[LandmarkIndex.WRIST]
        val middleMcp = input.landmarks[LandmarkIndex.MIDDLE_MCP]
        val handSize = distance2D(wrist, middleMcp)
        val thumbIndexDistance = if (handSize > EPSILON) distance2D(thumbTip, indexTip) / handSize else 0f
        val enterThreshold = config.scaledPinchDistanceRatio()
        val exitThreshold = config.scaledPinchReleaseRatio()
        val hoverThreshold = config.scaledPinchHoverRatio()
        val timeInState = timestampMs - pinchStateEntryTimeMs

        when (pinchState) {
            PinchState.IDLE -> if (allowEntry && thumbIndexDistance < hoverThreshold) { pinchState = PinchState.HOVER; pinchStateEntryTimeMs = timestampMs }
            PinchState.HOVER -> {
                val inCooldown = lastPinchEndMs > 0L && timestampMs - lastPinchEndMs < PINCH_COOLDOWN_MS
                if (allowEntry && thumbIndexDistance < enterThreshold && !inCooldown) { pinchState = PinchState.PINCH_START; pinchStateEntryTimeMs = timestampMs }
                else if (thumbIndexDistance > hoverThreshold * 1.5f) { pinchState = PinchState.IDLE; pinchStateEntryTimeMs = timestampMs }
            }
            PinchState.PINCH_START -> {
                if (timeInState >= TIME_DEBOUNCE_MS && allowEntry) {
                    pinchState = PinchState.PINCH_HOLD; pinchStateEntryTimeMs = timestampMs; wasPinching = true; currentPinchPhase = PinchPhase.START
                    val palm = if (hasPalmPosition) lastPalmX to lastPalmY else 0.5f to 0.5f
                    pinchStartX = palm.first; pinchStartY = palm.second; pinchAnchoredX = pinchStartX; pinchAnchoredY = pinchStartY
                    _gestureEvents.tryEmit(GestureEvent.Pinch(PinchPhase.START, pinchAnchoredX, pinchAnchoredY, timestampMs, pinchAnchoredX, pinchAnchoredY, currentVelocity))
                } else if (thumbIndexDistance > exitThreshold) { pinchState = PinchState.HOVER; pinchStateEntryTimeMs = timestampMs }
            }
            PinchState.PINCH_HOLD -> {
                if (thumbIndexDistance > exitThreshold) { pinchState = PinchState.PINCH_RELEASE; pinchStateEntryTimeMs = timestampMs }
                else {
                    currentPinchPhase = PinchPhase.MOVE
                    _gestureEvents.tryEmit(GestureEvent.Pinch(PinchPhase.MOVE, lastPalmX, lastPalmY, timestampMs, pinchAnchoredX, pinchAnchoredY, currentVelocity))
                }
            }
            PinchState.PINCH_RELEASE -> {
                if (timeInState >= TIME_DEBOUNCE_MS) {
                    pinchState = PinchState.IDLE; pinchStateEntryTimeMs = timestampMs; wasPinching = false; currentPinchPhase = PinchPhase.END; lastPinchEndMs = timestampMs
                    _gestureEvents.tryEmit(GestureEvent.Pinch(PinchPhase.END, lastPalmX, lastPalmY, timestampMs, pinchAnchoredX, pinchAnchoredY, currentVelocity))
                    currentPinchPhase = null
                } else if (thumbIndexDistance < enterThreshold) { pinchState = PinchState.PINCH_HOLD; pinchStateEntryTimeMs = timestampMs }
            }
        }
    }

    private fun distance2D(a: com.aircontrol.gesture.model.Landmark3D, b: com.aircontrol.gesture.model.Landmark3D): Float {
        val dx = a.x - b.x; val dy = a.y - b.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun palmHomeConditionsMet(input: HandInput, timestampMs: Long): Boolean {
        if (timestampMs - armedSinceMs < PALM_HOME_MIN_ARMED_MS) return false
        val wrist = input.landmarks[LandmarkIndex.WRIST]
        val middleMcp = input.landmarks[LandmarkIndex.MIDDLE_MCP]
        return distance2D(wrist, middleMcp) >= config.palmHomeMinHandSizeNormalized
    }

    private fun resetPalmTracking() {
        palmHolding = false; palmHoldStartMs = 0L; palmHoldAnchorX = -1f; palmHoldAnchorY = -1f; palmHomeFired = false
    }

    private fun updateMeasuredFrameInterval(timestampMs: Long) {
        if (prevFrameTimestampMs > 0L) {
            val interval = (timestampMs - prevFrameTimestampMs).coerceIn(1L, 1000L)
            measuredFrameIntervalMs = if (measuredFrameIntervalMs == 0L) interval else (measuredFrameIntervalMs * 3 + interval) / 4
        }
        prevFrameTimestampMs = timestampMs
    }

    fun reset() {
        poseClassifier.reset(); poseClassifier.effectiveDebounceFrames = config.poseDebounceFrames; dynamicDetector.reset(); stateMachine.reset()
        pinchState = PinchState.IDLE; pinchStateEntryTimeMs = 0L; wasPinching = false; currentPinchPhase = null
        pinchStartX = 0f; pinchStartY = 0f; pinchAnchoredX = 0f; pinchAnchoredY = 0f; lastPinchEndMs = 0L; lastCustomGestureId = null
        lowConfidenceFrameCount = 0; prevIndexTipX = 0.5f; prevIndexTipY = 0.5f; prevIndexTipTimestampMs = 0L; currentVelocity = 0f
        resetPalmTracking(); handStillSinceMs = 0L; thumbPoseSinceMs = 0L; measuredFrameIntervalMs = 0L; prevFrameTimestampMs = 0L; armedSinceMs = 0L
        lastPalmX = 0.5f; lastPalmY = 0.5f; hasPalmPosition = false
        _engineState.value = GestureEngineState.DISARMED; _currentPose.value = Pose.NONE; _armingProgress.value = 0f
    }

    companion object {
        private const val PINCH_COOLDOWN_MS = 80L
        private const val SWIPE_SUPPRESSION_AFTER_PINCH_MS = 60L
        private const val CONFIDENCE_THRESHOLD = 0.7f
        private const val LOW_CONFIDENCE_MIN_FRAMES = 3
        private const val LOW_CONFIDENCE_DEBOUNCE_FRAMES = 7
        private const val LOW_CONFIDENCE_SMOOTHER_MIN_CUTOFF = 2.0f
        private const val PALM_HOME_MIN_ARMED_MS = 1200L
        private const val EPSILON = 1e-6f
    }
}
