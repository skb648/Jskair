package com.aircontrol.gesture

import com.aircontrol.gesture.config.GestureEngineConfig
import com.aircontrol.gesture.detection.DynamicGestureDetector
import com.aircontrol.gesture.detection.StaticPoseClassifier
import com.aircontrol.gesture.model.GestureEngineState
import com.aircontrol.gesture.model.GestureEvent
import com.aircontrol.gesture.model.HandGestureSnapshot
import com.aircontrol.gesture.model.HandInput
import com.aircontrol.gesture.model.Handedness
import com.aircontrol.gesture.model.LandmarkIndex
import com.aircontrol.gesture.model.LandmarkTemplate
import com.aircontrol.gesture.model.MultiHandInput
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
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

private enum class PinchState { IDLE, HOVER, PINCH_START, PINCH_HOLD, PINCH_RELEASE }

class GestureEngine(
    initialConfig: GestureEngineConfig = GestureEngineConfig(),
    private val isSecondarySlot: Boolean = false,
) {
    @Volatile var config: GestureEngineConfig = initialConfig
        private set

    private var scopeJob = SupervisorJob()
    private var scope = CoroutineScope(scopeJob + Dispatchers.Default)
    private val poseClassifier = StaticPoseClassifier(initialConfig)
    private val dynamicDetector = DynamicGestureDetector(initialConfig)
    private val stateMachine = GestureStateMachine(initialConfig)

    /** Secondary slot engine for genuine dual-hand tracking (only allocated on primary engine). */
    private val secondarySlot: GestureEngine? = if (!isSecondarySlot) {
        GestureEngine(initialConfig, isSecondarySlot = true)
    } else {
        null
    }
    private var primarySlotTrackId: Int? = null
    private var primarySlotHandedness: Handedness = Handedness.UNKNOWN
    private var secondarySlotTrackId: Int? = null
    private var secondarySlotHandedness: Handedness = Handedness.UNKNOWN
    private var lastCrossHandDiscreteActionMs: Long = 0L
    private var customTemplatesCache: List<LandmarkTemplate> = emptyList()

    /**
     * When bufferedMode is active on a slot during multi-hand arbitration, emitted
     * semantic and cursor events are staged in [stagedSemanticEvents] / [stagedCursorEvents]
     * so the multi-hand coordinator can arbitrate conflicts before emitting externally.
     */
    private var bufferedMode: Boolean = false
    private val stagedSemanticEvents = ArrayList<GestureEvent>(4)
    private val stagedCursorEvents = ArrayList<GestureEvent.CursorMoved>(2)

    /**
     * Continuous cursor transport: latest-wins is intentional because old positions
     * are obsolete. Semantic gesture events never enter this flow.
     */
    private val _cursorEvents = MutableSharedFlow<GestureEvent.CursorMoved>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val cursorEvents: SharedFlow<GestureEvent.CursorMoved> = _cursorEvents.asSharedFlow()

    /**
     * Semantic transport: bounded and lossless. A slow consumer applies backpressure
     * to frame processing rather than silently deleting PINCH_START/END, SWIPE or
     * POSE events. Kotlin's suspending emit provides that bounded backpressure.
     */
    private val _semanticEvents = MutableSharedFlow<GestureEvent>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.SUSPEND,
    )
    val semanticEvents: SharedFlow<GestureEvent> = _semanticEvents.asSharedFlow()

    /**
     * Compatibility view for existing diagnostics/tests. Production action dispatch
     * must consume [semanticEvents], while [cursorEvents] remains latest-wins.
     * This merged view is intentionally not used for correctness-sensitive dispatch.
     */
    val gestureEvents: Flow<GestureEvent> = merge(
        semanticEvents,
        cursorEvents,
    )
    private val _engineState = MutableStateFlow(GestureEngineState.DISARMED)
    val engineState: StateFlow<GestureEngineState> = _engineState.asStateFlow()
    private val _currentPose = MutableStateFlow(Pose.NONE)
    val currentPose: StateFlow<Pose> = _currentPose.asStateFlow()
    private val _armingProgress = MutableStateFlow(0f)
    val armingProgress: StateFlow<Float> = _armingProgress.asStateFlow()
    private val _lowConfidence = MutableStateFlow(false)
    val lowConfidence: StateFlow<Boolean> = _lowConfidence.asStateFlow()
    private val _pinchProximity = MutableStateFlow(0f)
    val pinchProximity: StateFlow<Float> = _pinchProximity.asStateFlow()
    private val _perHandStates = MutableStateFlow<Map<Int, HandGestureSnapshot>>(emptyMap())
    val perHandStates: StateFlow<Map<Int, HandGestureSnapshot>> = _perHandStates.asStateFlow()

    private var pinchState = PinchState.IDLE
    private var pinchStateEntryTimeMs = 0L
    @Volatile private var wasPinching = false
    @Volatile private var pinchStartX = 0f
    @Volatile private var pinchStartY = 0f
    @Volatile private var pinchAnchoredX = 0f
    @Volatile private var pinchAnchoredY = 0f
    @Volatile private var pinchDragUnlocked = false
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
    @Volatile private var lowConfBadFrames = 0
    @Volatile private var lowConfGoodFrames = 0
    @Volatile private var lowConfidenceMode = false

    @Volatile private var lastPalmX = 0.5f
    @Volatile private var lastPalmY = 0.5f
    @Volatile private var hasPalmPosition = false

    internal val isPinchActive: Boolean
        get() = wasPinching || pinchState == PinchState.PINCH_START || pinchState == PinchState.PINCH_HOLD || pinchState == PinchState.PINCH_RELEASE

    fun stop() {
        secondarySlot?.stop()
        scopeJob.cancel()
    }

    /**
     * Optional debug hook (spec §18) — null in production, so there is NO log
     * spam by default.
     */
    @Volatile var onSwipeDecision: ((detected: Boolean, direction: SwipeDirection?, confidence: Float, reason: String?, displacementX: Float, displacementY: Float, timestampMs: Long) -> Unit)? = null

    fun swipeDebugInfo(): DynamicGestureDetector.SwipeDebugInfo = dynamicDetector.swipeDebugInfo()

    fun updateSensitivity(sensitivity: Int) {
        val newConfig = config.copy(sensitivity = sensitivity)
        config = newConfig
        poseClassifier.updateConfig(newConfig)
        dynamicDetector.updateConfig(newConfig)
        stateMachine.updateConfig(newConfig)
        secondarySlot?.updateSensitivity(sensitivity)
    }

    fun updateSwipeRequiresOpenHand(requiresOpenHand: Boolean) {
        if (config.swipeRequiresOpenHand == requiresOpenHand) return
        val newConfig = config.copy(swipeRequiresOpenHand = requiresOpenHand)
        config = newConfig
        dynamicDetector.updateConfig(newConfig)
        secondarySlot?.updateSwipeRequiresOpenHand(requiresOpenHand)
    }

    fun updateCalibration(handSizeMm: Float, pinchDistanceMm: Float) {
        val ratio = if (handSizeMm > 0f && pinchDistanceMm > 0f) pinchDistanceMm / handSizeMm else null
        val newConfig = config.copy(calibratedPinchRatio = ratio)
        config = newConfig
        poseClassifier.updateConfig(newConfig)
        dynamicDetector.updateConfig(newConfig)
        stateMachine.updateConfig(newConfig)
        secondarySlot?.updateCalibration(handSizeMm, pinchDistanceMm)
    }

    fun updateCustomTemplates(templates: List<LandmarkTemplate>) {
        customTemplatesCache = templates
        poseClassifier.updateCustomTemplates(templates)
        lastCustomGestureId = null
        secondarySlot?.updateCustomTemplates(templates)
    }

    private suspend fun emitSemanticEvent(event: GestureEvent) {
        if (bufferedMode) {
            stagedSemanticEvents.add(event)
        } else {
            _semanticEvents.emit(event)
        }
    }

    private fun emitCursorEvent(event: GestureEvent.CursorMoved) {
        if (bufferedMode) {
            stagedCursorEvents.clear()
            stagedCursorEvents.add(event)
        } else {
            _cursorEvents.tryEmit(event)
        }
    }

    /** Compatibility entry point for synchronous unit callers. Production adapters use the suspend variant. */
    fun processFrame(input: HandInput) = kotlinx.coroutines.runBlocking { processFrameSuspending(input) }

    /** Multi-hand synchronous entry point for unit callers. */
    fun processMultiHandFrame(multiInput: MultiHandInput) =
        kotlinx.coroutines.runBlocking { processMultiHandFrameSuspending(multiInput) }

    /**
     * Processes up to two simultaneously tracked hands with independent per-track
     * gesture state machines and cross-hand action arbitration.
     */
    suspend fun processMultiHandFrameSuspending(multiInput: MultiHandInput) {
        val secondary = secondarySlot
        val validHands = multiInput.hands.filter { it.isDetected }
        val singleHandMatchesPrimary = validHands.size <= 1 &&
            secondarySlotTrackId == null &&
            (validHands.isEmpty() || primarySlotTrackId == null || validHands[0].trackId == 0 || validHands[0].trackId == primarySlotTrackId)
        if (secondary == null || singleHandMatchesPrimary) {
            // Fast path when only single-hand input is active and no secondary hand track is bound
            val primaryHand = if (multiInput.primaryTrackId != null) {
                validHands.firstOrNull { it.trackId == multiInput.primaryTrackId || it.trackId == 0 }
                    ?: HandInput.EMPTY.copy(timestampMs = multiInput.timestampMs)
            } else {
                HandInput.EMPTY.copy(timestampMs = multiInput.timestampMs)
            }
            if (primaryHand.isDetected && primaryHand.trackId != 0) {
                primarySlotTrackId = primaryHand.trackId
                primarySlotHandedness = primaryHand.handedness
            }
            processSingleSlotSuspending(primaryHand)
            if (!primaryHand.isDetected && _engineState.value == GestureEngineState.DISARMED && !this.isPinchActive) {
                primarySlotTrackId = null
            }
            publishSingleSlotSnapshot(multiInput)
            return
        }

        val timestampMs = multiInput.timestampMs
        // Map incoming hands by trackId to Slot A (this) and Slot B (secondary)
        var handForSlotA: HandInput? = null
        var handForSlotB: HandInput? = null
        val unassigned = ArrayList<HandInput>(2)

        for (hand in validHands) {
            val tid = if (hand.trackId != 0) hand.trackId else when (hand.handedness) {
                Handedness.LEFT -> 1
                Handedness.RIGHT -> 2
                Handedness.UNKNOWN -> 1
            }
            val normalizedHand = if (hand.trackId == tid) hand else hand.copy(trackId = tid)
            when (tid) {
                primarySlotTrackId -> handForSlotA = normalizedHand
                secondarySlotTrackId -> handForSlotB = normalizedHand
                else -> unassigned.add(normalizedHand)
            }
        }

        for (hand in unassigned) {
            val prefersPrimarySlot = hand.trackId == multiInput.primaryTrackId
            if (handForSlotA == null && !this.isPinchActive && (primarySlotTrackId == null || prefersPrimarySlot || handForSlotB != null)) {
                if (primarySlotTrackId != null && primarySlotTrackId != hand.trackId) {
                    resetSingleSlotOnly()
                }
                primarySlotTrackId = hand.trackId
                primarySlotHandedness = hand.handedness
                handForSlotA = hand
            } else if (handForSlotB == null && !secondary.isPinchActive) {
                if (secondarySlotTrackId != null && secondarySlotTrackId != hand.trackId) {
                    secondary.resetSingleSlotOnly()
                }
                secondarySlotTrackId = hand.trackId
                secondarySlotHandedness = hand.handedness
                handForSlotB = hand
            }
        }

        val inputA = handForSlotA ?: HandInput.EMPTY.copy(timestampMs = timestampMs, trackId = primarySlotTrackId ?: 0)
        val inputB = handForSlotB ?: HandInput.EMPTY.copy(timestampMs = timestampMs, trackId = secondarySlotTrackId ?: 0)

        if (handForSlotA != null) primarySlotHandedness = handForSlotA.handedness
        if (handForSlotB != null) secondarySlotHandedness = handForSlotB.handedness

        val slotAPinchingBefore = this.isPinchActive
        val slotBPinchingBefore = secondary.isPinchActive

        // Run both slots in buffered mode so we can arbitrate conflicts before emitting externally
        this.bufferedMode = true
        this.stagedSemanticEvents.clear()
        this.stagedCursorEvents.clear()
        secondary.bufferedMode = true
        secondary.stagedSemanticEvents.clear()
        secondary.stagedCursorEvents.clear()

        try {
            this.processSingleSlotSuspending(inputA)
            if (secondarySlotTrackId != null || handForSlotB != null) {
                secondary.processSingleSlotSuspending(inputB)
            }
        } finally {
            this.bufferedMode = false
            secondary.bufferedMode = false
        }

        // Determine which slot owns pointer & primary eligibility
        val pointerOwnerIsSlotA = when {
            slotAPinchingBefore || this.isPinchActive -> true
            slotBPinchingBefore || secondary.isPinchActive -> false
            multiInput.primaryTrackId != null && multiInput.primaryTrackId == secondarySlotTrackId -> false
            multiInput.primaryTrackId != null && multiInput.primaryTrackId == primarySlotTrackId -> true
            multiInput.primaryTrackId == null -> true // Neither eligible for pointer
            else -> handForSlotA != null
        }

        val primaryEligible = multiInput.primaryTrackId != null
        val ownerEvents = if (pointerOwnerIsSlotA) this.stagedSemanticEvents else secondary.stagedSemanticEvents
        val otherEvents = if (pointerOwnerIsSlotA) secondary.stagedSemanticEvents else this.stagedSemanticEvents
        val otherWasPinching = if (pointerOwnerIsSlotA) slotBPinchingBefore else slotAPinchingBefore
        val ownerNowPinching = if (pointerOwnerIsSlotA) (slotAPinchingBefore || this.isPinchActive) else (slotBPinchingBefore || secondary.isPinchActive)

        var discreteFiredThisFrame = false

        // 1. Emit owner semantic events (if eligible or terminating an active pinch/disarm)
        for (ev in ownerEvents) {
            val isLifecycleOrRelease = ev is GestureEvent.Armed ||
                ev is GestureEvent.Disarmed ||
                (ev is GestureEvent.Pinch && ev.phase == PinchPhase.END)
            if (!primaryEligible && !isLifecycleOrRelease) continue

            val isDiscrete = ev is GestureEvent.Swipe ||
                ev is GestureEvent.PoseTriggered ||
                ev is GestureEvent.PalmHome ||
                ev is GestureEvent.CustomGestureTriggered ||
                (ev is GestureEvent.Pinch && ev.phase == PinchPhase.START)

            if (isDiscrete) {
                discreteFiredThisFrame = true
                lastCrossHandDiscreteActionMs = timestampMs
            }
            _semanticEvents.emit(ev)
        }

        // 2. Arbitrate secondary slot semantic events:
        //    - Always allow Pinch(END) if secondary slot was already pinching (never strand a drag!).
        //    - Block new pinches/swipes/poses from secondary if owner is pinching, or if a discrete
        //      action already fired in this frame or within CROSS_HAND_ARBITRATION_MS, or if hand
        //      preference restricted eligibility to a single hand that isn't this slot.
        val secondaryAllowedToAct = primaryEligible &&
            !ownerNowPinching &&
            !discreteFiredThisFrame &&
            (lastCrossHandDiscreteActionMs == 0L || timestampMs - lastCrossHandDiscreteActionMs >= CROSS_HAND_ARBITRATION_MS)

        for (ev in otherEvents) {
            val isPinchEnd = ev is GestureEvent.Pinch && ev.phase == PinchPhase.END
            if (isPinchEnd && otherWasPinching) {
                _semanticEvents.emit(ev)
                continue
            }
            if (!secondaryAllowedToAct) {
                // If secondary tried to start a pinch while blocked by primary, cancel secondary's pinch state
                if (ev is GestureEvent.Pinch && ev.phase == PinchPhase.START) {
                    val blockedSlot = if (pointerOwnerIsSlotA) secondary else this
                    blockedSlot.abortPinchInternal()
                }
                continue
            }
            val isDiscrete = ev is GestureEvent.Swipe ||
                ev is GestureEvent.PoseTriggered ||
                ev is GestureEvent.PalmHome ||
                ev is GestureEvent.CustomGestureTriggered ||
                (ev is GestureEvent.Pinch && ev.phase == PinchPhase.START)
            if (isDiscrete) {
                if (discreteFiredThisFrame) continue
                discreteFiredThisFrame = true
                lastCrossHandDiscreteActionMs = timestampMs
                _semanticEvents.emit(ev)
            } else if (ev is GestureEvent.Pinch && ev.phase == PinchPhase.MOVE && otherWasPinching) {
                _semanticEvents.emit(ev)
            }
        }

        // 3. Emit cursor event ONLY from the pointer-owning slot when eligible
        if (primaryEligible) {
            val ownerCursor = if (pointerOwnerIsSlotA) this.stagedCursorEvents else secondary.stagedCursorEvents
            ownerCursor.lastOrNull()?.let { _cursorEvents.tryEmit(it) }
        }

        // 4. Clean up disarmed unbound slots
        if (handForSlotA == null && _engineState.value == GestureEngineState.DISARMED && !this.isPinchActive) {
            primarySlotTrackId = null
        }
        if (handForSlotB == null && secondary.engineState.value == GestureEngineState.DISARMED && !secondary.isPinchActive) {
            secondarySlotTrackId = null
        }

        // 5. Publish per-hand snapshots and synchronize top-level state flows to the active pointer slot
        val snapshots = LinkedHashMap<Int, HandGestureSnapshot>(2)
        primarySlotTrackId?.let { tid ->
            if (handForSlotA != null || _engineState.value != GestureEngineState.DISARMED) {
                snapshots[tid] = HandGestureSnapshot(
                    trackId = tid,
                    handedness = primarySlotHandedness,
                    engineState = _engineState.value,
                    currentPose = _currentPose.value,
                    armingProgress = _armingProgress.value,
                    pinchProximity = _pinchProximity.value,
                    lowConfidence = _lowConfidence.value,
                    isPrimaryPointer = pointerOwnerIsSlotA && primaryEligible,
                )
            }
        }
        secondarySlotTrackId?.let { tid ->
            if (handForSlotB != null || secondary.engineState.value != GestureEngineState.DISARMED) {
                snapshots[tid] = HandGestureSnapshot(
                    trackId = tid,
                    handedness = secondarySlotHandedness,
                    engineState = secondary.engineState.value,
                    currentPose = secondary.currentPose.value,
                    armingProgress = secondary.armingProgress.value,
                    pinchProximity = secondary.pinchProximity.value,
                    lowConfidence = secondary.lowConfidence.value,
                    isPrimaryPointer = !pointerOwnerIsSlotA && primaryEligible,
                )
            }
        }
        _perHandStates.value = snapshots

        if (!pointerOwnerIsSlotA && primaryEligible) {
            _engineState.value = secondary.engineState.value
            _currentPose.value = secondary.currentPose.value
            _armingProgress.value = secondary.armingProgress.value
            _lowConfidence.value = secondary.lowConfidence.value
            _pinchProximity.value = secondary.pinchProximity.value
        }
    }

    private fun publishSingleSlotSnapshot(multiInput: MultiHandInput) {
        val snapshots = LinkedHashMap<Int, HandGestureSnapshot>(2)
        for (hand in multiInput.hands) {
            val tid = if (hand.trackId != 0) hand.trackId else 1
            val isPrimary = multiInput.primaryTrackId == tid || (multiInput.primaryTrackId == null && hand.isDetected && multiInput.hands.size == 1)
            snapshots[tid] = HandGestureSnapshot(
                trackId = tid,
                handedness = hand.handedness,
                engineState = if (isPrimary) _engineState.value else GestureEngineState.DISARMED,
                currentPose = if (isPrimary) _currentPose.value else Pose.NONE,
                armingProgress = if (isPrimary) _armingProgress.value else 0f,
                pinchProximity = if (isPrimary) _pinchProximity.value else 0f,
                lowConfidence = if (isPrimary) _lowConfidence.value else false,
                isPrimaryPointer = isPrimary && multiInput.primaryTrackId != null,
            )
        }
        _perHandStates.value = snapshots
    }

    private fun abortPinchInternal() {
        wasPinching = false
        currentPinchPhase = null
        pinchState = PinchState.IDLE
        pinchDragUnlocked = false
    }

    suspend fun processFrameSuspending(input: HandInput) {
        processSingleSlotSuspending(input)
        if (input.isDetected) {
            val tid = if (input.trackId != 0) input.trackId else 1
            _perHandStates.value = mapOf(
                tid to HandGestureSnapshot(
                    trackId = tid,
                    handedness = input.handedness,
                    engineState = _engineState.value,
                    currentPose = _currentPose.value,
                    armingProgress = _armingProgress.value,
                    pinchProximity = _pinchProximity.value,
                    lowConfidence = _lowConfidence.value,
                    isPrimaryPointer = true,
                ),
            )
        } else {
            _perHandStates.value = emptyMap()
        }
    }

    private suspend fun processSingleSlotSuspending(input: HandInput) {
        val timestampMs = input.timestampMs
        val isLowConfidence = input.isDetected && input.confidence < CONFIDENCE_THRESHOLD
        // Hardening round 10 (spec §5/§17 — threshold oscillation): entering
        // low-confidence mode needs 3 consecutive bad frames, but a SINGLE good
        // frame used to exit it. With confidence flickering around 0.7 (bad
        // lighting) the mode flapped open/closed, and pinch entry was allowed
        // inside the brief "good" windows — an oscillating-confidence pinch
        // could click. Exit is now symmetric: 3 consecutive good frames.
        // Undetected frames hold the state (a dropout neither proves nor
        // clears unreliability).
        if (isLowConfidence) {
            lowConfBadFrames++
            lowConfGoodFrames = 0
        } else if (input.isDetected) {
            lowConfGoodFrames++
            lowConfBadFrames = 0
        }
        if (!lowConfidenceMode && lowConfBadFrames >= LOW_CONFIDENCE_MIN_FRAMES) {
            lowConfidenceMode = true
        } else if (lowConfidenceMode && lowConfGoodFrames >= LOW_CONFIDENCE_EXIT_FRAMES) {
            lowConfidenceMode = false
        }
        val lowConfidence = lowConfidenceMode
        // Fix (verified G8): the low-confidence debounce was a fixed 7
        // frames = 583 ms at 12 fps (thermal/dim light), making arming and
        // poses crawl exactly when tracking was already degraded. Derive the
        // frame count from the MEASURED frame interval for a constant ~420 ms
        // time budget at any frame rate.
        val lowConfDebounceFrames = if (measuredFrameIntervalMs > 0L) {
            (420f / measuredFrameIntervalMs).toInt().coerceIn(2, 8)
        } else LOW_CONFIDENCE_DEBOUNCE_FRAMES
        _lowConfidence.value = lowConfidence
        poseClassifier.effectiveDebounceFrames = if (lowConfidence) lowConfDebounceFrames else config.poseDebounceFrames

        if (input.isDetected) {
            updateMeasuredFrameInterval(timestampMs)
            poseClassifier.effectiveDebounceFrames = if (lowConfidence) lowConfDebounceFrames
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
                // Fix (verified G4): the pose/thumb stillness velocity used raw
                // image-coordinate deltas, so a hand close to the camera looked
                // 3-5x "faster" than the same motion performed far away —
                // poses only fired at one magic holding distance. Normalize by
                // the user's own palm span (wrist -> middle-MCP), like the
                // swipe detector already does; REFERENCE_SCALE keeps existing
                // thresholds calibrated at a typical holding distance.
                val wrist = input.landmarks[LandmarkIndex.WRIST]
                val middleBase = input.landmarks[LandmarkIndex.MIDDLE_MCP]
                val aspect = input.frameAspectRatio.takeIf { it.isFinite() && it in 0.4f..2.5f } ?: 1f
                val handScale = kotlin.math.hypot(
                    (middleBase.x - wrist.x) * aspect,
                    middleBase.y - wrist.y,
                ).coerceIn(0.05f, 0.6f)
                val dx = (indexTip.x - prevIndexTipX) * aspect
                val dy = indexTip.y - prevIndexTipY
                val raw = kotlin.math.sqrt(dx * dx + dy * dy) / (dtMs / 1000f)
                currentVelocity = raw / handScale * REFERENCE_HAND_SCALE
            } else currentVelocity = 0f
            prevIndexTipX = indexTip.x
            prevIndexTipY = indexTip.y
            prevIndexTipTimestampMs = timestampMs
            if (!lowConfidence && hasPalmPosition) {
                if (currentVelocity > config.thumbGestureMaxVelocity) handStillSinceMs = 0L
                else if (handStillSinceMs == 0L) handStillSinceMs = timestampMs
            }
        } else {
            // Stress-audit bug #11 (Task 14, spec §6 — tracking loss → fresh
            // validation): an undetected gap must not feed the frame-interval
            // estimator. The first delta computed after a dropout is the GAP
            // itself (e.g. 240ms), which inflated the EMA and dropped the
            // effective pose debounce to ceil(120/90) = 2 frames — or to 1
            // frame after a multi-second dropout (still inside the 10s
            // auto-disarm window). A returning hand could then commit a pose
            // on 1–2 frames instead of the spec'd ~120ms of fresh validation.
            // Zeroing the previous-frame timestamp makes updateMeasuredFrame
            // Interval() skip the meaningless post-gap delta; the estimator
            // keeps its pre-dropout value and the debounce stays honest.
            prevFrameTimestampMs = 0L
        }

        val pose = poseClassifier.classify(input, currentVelocity)
        _currentPose.value = pose
        val fingerState = poseClassifier.lastFingerState
        val rawPose = poseClassifier.lastRawPose
        val openHandLikeForSwipe = pose == Pose.OPEN_PALM ||
            rawPose == Pose.OPEN_PALM ||
            (fingerState.totalExtendedCount >= 3 &&
                pose != Pose.PINCH && pose != Pose.FIST && pose != Pose.POINTING &&
                rawPose != Pose.PINCH && rawPose != Pose.FIST && rawPose != Pose.POINTING)
        val swipeAllowed = !config.swipeRequiresOpenHand || openHandLikeForSwipe
        val swipeResult = dynamicDetector.process(input, gestureAllowed = swipeAllowed)

        val isThumbPose = pose == Pose.THUMB_UP || pose == Pose.THUMB_DOWN
        if (isThumbPose) {
            if (thumbPoseSinceMs == 0L) thumbPoseSinceMs = timestampMs
        } else thumbPoseSinceMs = 0L
        val still = handStillSinceMs > 0L
        val thumbHeldLongEnough = still && isThumbPose && timestampMs - thumbPoseSinceMs >= config.thumbGestureHoldMs
        // Round 10: low-confidence tracking suppresses pose EXECUTION (not just
        // emission) — otherwise the state machine silently consumed the
        // one-shot lastExecutedPose latch while muted, and the pose could never
        // fire after tracking recovered.
        val suppressExecution = (isThumbPose && !thumbHeldLongEnough) || lowConfidence

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
                    emitSemanticEvent(GestureEvent.PalmHome(timestampMs))
                }
            } else resetPalmTracking()
        } else resetPalmTracking()

        if (transition.stateChanged) {
            when (transition.newState) {
                GestureEngineState.ARMED -> {
                    armedSinceMs = timestampMs
                    emitSemanticEvent(GestureEvent.Armed(timestampMs))
                }
                GestureEngineState.DISARMED -> {
                    emitSemanticEvent(GestureEvent.Disarmed(timestampMs))
                    wasPinching = false
                    lastCustomGestureId = null
                }
                else -> Unit
            }
        }

        val canDispatchSwipeOrPose = transition.newState == GestureEngineState.ARMED ||
            transition.newState == GestureEngineState.EXECUTING ||
            transition.newState == GestureEngineState.COOLDOWN ||
            (transition.newState == GestureEngineState.ARMING && swipeResult.detected && swipeResult.direction != null)
        if (canDispatchSwipeOrPose) {
            val pinchActive = wasPinching || currentPinchPhase != null
            val swipeSuppressed = pinchActive ||
                (lastPinchEndMs > 0L && timestampMs - lastPinchEndMs < SWIPE_SUPPRESSION_AFTER_PINCH_MS)
            if (swipeResult.detected && swipeResult.direction != null && !swipeSuppressed) {
                if (_engineState.value == GestureEngineState.ARMING) {
                    _engineState.value = GestureEngineState.ARMED
                    armedSinceMs = timestampMs
                    emitSemanticEvent(GestureEvent.Armed(timestampMs))
                }
                emitSemanticEvent(GestureEvent.Swipe(swipeResult.direction, timestampMs))
                onSwipeDecision?.invoke(
                    true, swipeResult.direction, swipeResult.confidence, null,
                    swipeResult.displacementX, swipeResult.displacementY, timestampMs,
                )
            } else if (swipeResult.detected) {
                onSwipeDecision?.invoke(
                    false, swipeResult.direction, swipeResult.confidence,
                    "PINCH_ACTIVE",
                    swipeResult.displacementX, swipeResult.displacementY, timestampMs,
                )
            } else if (swipeResult.hadEvidence) {
                onSwipeDecision?.invoke(
                    false, null, swipeResult.confidence, swipeResult.reason?.name,
                    swipeResult.displacementX, swipeResult.displacementY, timestampMs,
                )
            }
            if (transition.shouldExecute && !lowConfidence) {
                val actionablePose = pose.takeIf { it != Pose.NONE && it != Pose.OPEN_PALM && it != Pose.FIST }
                if (actionablePose != null) emitSemanticEvent(GestureEvent.PoseTriggered(actionablePose, timestampMs))
            }
            if (!lowConfidence && input.isDetected) {
                val matchedTemplate = poseClassifier.matchCustomTemplate(input)
                if (matchedTemplate != null) {
                    if (matchedTemplate.gestureId != lastCustomGestureId) {
                        lastCustomGestureId = matchedTemplate.gestureId
                        emitSemanticEvent(GestureEvent.CustomGestureTriggered(matchedTemplate.gestureId, matchedTemplate.name, timestampMs))
                    }
                } else lastCustomGestureId = null
            }
        }

        val isPinchAnchored = (wasPinching || pinchState == PinchState.PINCH_START) && !pinchDragUnlocked
        val effectiveCursorX = if (isPinchAnchored) pinchAnchoredX else lastPalmX
        val effectiveCursorY = if (isPinchAnchored) pinchAnchoredY else lastPalmY
        if (input.isDetected && hasPalmPosition && (transition.newState == GestureEngineState.ARMING || transition.newState == GestureEngineState.ARMED || transition.newState == GestureEngineState.EXECUTING || transition.newState == GestureEngineState.COOLDOWN)) {
            val isSilent = transition.newState == GestureEngineState.ARMING
            val hint = if (lowConfidence) LOW_CONFIDENCE_SMOOTHER_MIN_CUTOFF else null
            emitCursorEvent(GestureEvent.CursorMoved(effectiveCursorX, effectiveCursorY, timestampMs, isSilent, hint))
        }
    }

    private suspend fun processPinch(input: HandInput, timestampMs: Long, allowEntry: Boolean) {
        val currentState = _engineState.value
        val isAllowedState = currentState == GestureEngineState.ARMED ||
            currentState == GestureEngineState.EXECUTING ||
            currentState == GestureEngineState.COOLDOWN ||
            (currentState == GestureEngineState.ARMING && input.isDetected)
        if (!isAllowedState) {
            _pinchProximity.value = 0f
            if (wasPinching) { wasPinching = false; currentPinchPhase = null; pinchState = PinchState.IDLE; pinchDragUnlocked = false }
            return
        }
        if (!input.isDetected) {
            _pinchProximity.value = 0f
            if (wasPinching) {
                emitSemanticEvent(GestureEvent.Pinch(PinchPhase.END, pinchStartX, pinchStartY, timestampMs, pinchAnchoredX, pinchAnchoredY, currentVelocity))
                wasPinching = false; currentPinchPhase = null; pinchState = PinchState.IDLE; pinchDragUnlocked = false
            } else if (pinchState != PinchState.IDLE) {
                pinchState = PinchState.IDLE
                pinchStateEntryTimeMs = timestampMs
            }
            return
        }
        val thumbTip = input.landmarks[LandmarkIndex.THUMB_TIP]
        val indexTip = input.landmarks[LandmarkIndex.INDEX_TIP]
        val wrist = input.landmarks[LandmarkIndex.WRIST]
        val middleMcp = input.landmarks[LandmarkIndex.MIDDLE_MCP]
        val handSize = distance2D(wrist, middleMcp)
        val handDegenerate = !handSize.isFinite() || handSize <= EPSILON
        val thumbIndexDistance = if (handDegenerate) {
            Float.MAX_VALUE
        } else {
            distance2D(thumbTip, indexTip) / handSize
        }
        val enterThreshold = config.scaledPinchDistanceRatio()
        val exitThreshold = config.scaledPinchReleaseRatio()
        val hoverThreshold = config.scaledPinchHoverRatio()
        val span = (hoverThreshold - enterThreshold).coerceAtLeast(0.02f)
        _pinchProximity.value = if (handDegenerate) 0f else ((hoverThreshold - thumbIndexDistance) / span).coerceIn(0f, 1f)
        val timeInState = timestampMs - pinchStateEntryTimeMs

        when (pinchState) {
            PinchState.IDLE -> if (allowEntry && thumbIndexDistance < hoverThreshold) { pinchState = PinchState.HOVER; pinchStateEntryTimeMs = timestampMs }
            PinchState.HOVER -> {
                val inCooldown = lastPinchEndMs > 0L && timestampMs - lastPinchEndMs < PINCH_COOLDOWN_MS
                if (allowEntry && thumbIndexDistance < enterThreshold && !inCooldown) {
                    pinchState = PinchState.PINCH_START
                    pinchStateEntryTimeMs = timestampMs
                    val palm = if (hasPalmPosition) lastPalmX to lastPalmY else 0.5f to 0.5f
                    pinchAnchoredX = palm.first
                    pinchAnchoredY = palm.second
                }
                else if (thumbIndexDistance > hoverThreshold * 1.5f) { pinchState = PinchState.IDLE; pinchStateEntryTimeMs = timestampMs }
            }
            PinchState.PINCH_START -> {
                if (timeInState >= config.pinchConfirmMs && allowEntry) {
                    if (_engineState.value == GestureEngineState.ARMING) {
                        _engineState.value = GestureEngineState.ARMED
                        emitSemanticEvent(GestureEvent.Armed(timestampMs))
                    }
                    pinchState = PinchState.PINCH_HOLD; pinchStateEntryTimeMs = timestampMs; wasPinching = true; currentPinchPhase = PinchPhase.START
                    pinchDragUnlocked = false
                    pinchStartX = pinchAnchoredX; pinchStartY = pinchAnchoredY
                    emitSemanticEvent(GestureEvent.Pinch(PinchPhase.START, pinchAnchoredX, pinchAnchoredY, timestampMs, pinchAnchoredX, pinchAnchoredY, currentVelocity))
                } else if (thumbIndexDistance > exitThreshold) { pinchState = PinchState.HOVER; pinchStateEntryTimeMs = timestampMs }
            }
            PinchState.PINCH_HOLD -> {
                if (thumbIndexDistance > exitThreshold) { pinchState = PinchState.PINCH_RELEASE; pinchStateEntryTimeMs = timestampMs }
                else {
                    val dragDist = kotlin.math.hypot(lastPalmX - pinchAnchoredX, lastPalmY - pinchAnchoredY)
                    if (dragDist >= PINCH_DRAG_UNLOCK_THRESHOLD) {
                        pinchDragUnlocked = true
                    }
                    val emitX = if (pinchDragUnlocked) lastPalmX else pinchAnchoredX
                    val emitY = if (pinchDragUnlocked) lastPalmY else pinchAnchoredY
                    currentPinchPhase = PinchPhase.MOVE
                    emitSemanticEvent(GestureEvent.Pinch(PinchPhase.MOVE, emitX, emitY, timestampMs, pinchAnchoredX, pinchAnchoredY, currentVelocity))
                }
            }
            PinchState.PINCH_RELEASE -> {
                if (timeInState >= config.pinchReleaseConfirmMs) {
                    pinchState = PinchState.IDLE; pinchStateEntryTimeMs = timestampMs; wasPinching = false; currentPinchPhase = PinchPhase.END; lastPinchEndMs = timestampMs
                    val emitX = if (pinchDragUnlocked) lastPalmX else pinchAnchoredX
                    val emitY = if (pinchDragUnlocked) lastPalmY else pinchAnchoredY
                    emitSemanticEvent(GestureEvent.Pinch(PinchPhase.END, emitX, emitY, timestampMs, pinchAnchoredX, pinchAnchoredY, currentVelocity))
                    currentPinchPhase = null
                    pinchDragUnlocked = false
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

    private fun resetSingleSlotOnly() {
        poseClassifier.reset(); poseClassifier.effectiveDebounceFrames = config.poseDebounceFrames; dynamicDetector.reset(); stateMachine.reset()
        poseClassifier.updateCustomTemplates(customTemplatesCache)
        pinchState = PinchState.IDLE; pinchStateEntryTimeMs = 0L; wasPinching = false; currentPinchPhase = null; pinchDragUnlocked = false
        pinchStartX = 0f; pinchStartY = 0f; pinchAnchoredX = 0f; pinchAnchoredY = 0f; lastPinchEndMs = 0L; lastCustomGestureId = null
        lowConfBadFrames = 0; lowConfGoodFrames = 0; lowConfidenceMode = false; _lowConfidence.value = false; prevIndexTipX = 0.5f; prevIndexTipY = 0.5f; prevIndexTipTimestampMs = 0L; currentVelocity = 0f
        resetPalmTracking(); handStillSinceMs = 0L; thumbPoseSinceMs = 0L; measuredFrameIntervalMs = 0L; prevFrameTimestampMs = 0L; armedSinceMs = 0L
        lastPalmX = 0.5f; lastPalmY = 0.5f; hasPalmPosition = false
        stagedSemanticEvents.clear(); stagedCursorEvents.clear(); bufferedMode = false
        _engineState.value = GestureEngineState.DISARMED; _currentPose.value = Pose.NONE; _armingProgress.value = 0f; _pinchProximity.value = 0f
    }

    fun reset() {
        resetSingleSlotOnly()
        secondarySlot?.resetSingleSlotOnly()
        primarySlotTrackId = null
        primarySlotHandedness = Handedness.UNKNOWN
        secondarySlotTrackId = null
        secondarySlotHandedness = Handedness.UNKNOWN
        lastCrossHandDiscreteActionMs = 0L
        _perHandStates.value = emptyMap()
    }

    companion object {
        private const val REFERENCE_HAND_SCALE = 0.2f
        private const val PINCH_DRAG_UNLOCK_THRESHOLD = 0.048f
        private const val PINCH_COOLDOWN_MS = 40L
        private const val SWIPE_SUPPRESSION_AFTER_PINCH_MS = 60L
        private const val CROSS_HAND_ARBITRATION_MS = 180L
        private const val CONFIDENCE_THRESHOLD = 0.7f
        private const val LOW_CONFIDENCE_MIN_FRAMES = 3
        private const val LOW_CONFIDENCE_EXIT_FRAMES = 3
        private const val LOW_CONFIDENCE_DEBOUNCE_FRAMES = 7
        private const val LOW_CONFIDENCE_SMOOTHER_MIN_CUTOFF = 2.0f
        private const val PALM_HOME_MIN_ARMED_MS = 1200L
        private const val EPSILON = 1e-6f
    }
}
