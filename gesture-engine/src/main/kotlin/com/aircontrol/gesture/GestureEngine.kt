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
    private val _engineState = MutableStateFlow(GestureEngineState.DISARMED)
    val engineState: StateFlow<GestureEngineState> = _engineState.asStateFlow()
    private val _currentPose = MutableStateFlow(Pose.NONE)
    val currentPose: StateFlow<Pose> = _currentPose.asStateFlow()
    private val _armingProgress = MutableStateFlow(0f)
    val armingProgress: StateFlow<Float> = _armingProgress.asStateFlow()
    // Fix (verified G6): expose the low-confidence condition so the UI
    // service can explain why poses/pinches are being muted instead of
    // rejecting them with no hint at all.
    private val _lowConfidence = MutableStateFlow(false)
    val lowConfidence: StateFlow<Boolean> = _lowConfidence.asStateFlow()

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

    fun stop() { scopeJob.cancel() }

    /**
     * Optional debug hook (spec §18) — null in production, so there is NO log
     * spam by default. When set, it fires once per swipe DECISION that had real
     * evidence (committed, suppressed, or rejected past the displacement gate):
     * (detected, direction, confidence, reason, dispX, dispY, timestampMs).
     * Frames with no meaningful motion never fire it.
     */
    @Volatile var onSwipeDecision: ((detected: Boolean, direction: SwipeDirection?, confidence: Float, reason: String?, displacementX: Float, displacementY: Float, timestampMs: Long) -> Unit)? = null

    /**
     * Debug-only: the swipe state machine's view of the current motion (spec §18).
     * Forwarded untouched — the engine adds nothing, so what the overlay shows is what
     * the detector used. Callers that must not pay per frame (release builds) simply
     * never call it.
     */
    fun swipeDebugInfo(): DynamicGestureDetector.SwipeDebugInfo = dynamicDetector.swipeDebugInfo()

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

    suspend fun processFrame(input: HandInput) {
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
        val swipeAllowed = !config.swipeRequiresOpenHand || pose == Pose.OPEN_PALM
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
                    _semanticEvents.emit(GestureEvent.PalmHome(timestampMs))
                }
            } else resetPalmTracking()
        } else resetPalmTracking()

        if (transition.stateChanged) {
            when (transition.newState) {
                GestureEngineState.ARMED -> {
                    armedSinceMs = timestampMs
                    _semanticEvents.emit(GestureEvent.Armed(timestampMs))
                }
                GestureEngineState.DISARMED -> {
                    _semanticEvents.emit(GestureEvent.Disarmed(timestampMs))
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
            // A swipe is the one gesture whose *motion* evidence is measured from the
            // landmark trajectory itself, so it no longer inherits the low-confidence
            // mute. That flag is MediaPipe's HANDEDNESS score (left/right ambiguity),
            // and a fast flick depresses exactly that number — the previous code
            // therefore muted the gesture precisely when it was most clearly made, and
            // did so for three frames at a time through the latched lowConfidenceMode.
            // The tracker's uncertainty is still respected where it belongs: the
            // detector folds the same score into the intent evidence and refuses to
            // commit below its own quality floor (SwipeIntentArbiter.TRACKING_UNCERTAIN,
            // 0.30, i.e. "a hand is genuinely there"), which is far below the 0.70 that
            // made valid swipes impossible. Pinch interference stays a veto, because
            // that is cross-modal arbitration rather than a quality judgement.
            if (swipeResult.detected && swipeResult.direction != null && !swipeSuppressed) {
                _semanticEvents.emit(GestureEvent.Swipe(swipeResult.direction, timestampMs))
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
                // Round 10 (spec §11): poses were the last event type that
                // could still commit from unreliable tracking — 7 stable but
                // blurry frames could misclassify as VICTORY and change the
                // volume. Pose actions are discrete and can be destructive,
                // so low-confidence tracking mutes them like swipes/templates.
                val actionablePose = pose.takeIf { it != Pose.NONE && it != Pose.OPEN_PALM && it != Pose.FIST }
                if (actionablePose != null) _semanticEvents.emit(GestureEvent.PoseTriggered(actionablePose, timestampMs))
            }
            if (!lowConfidence && input.isDetected) {
                val matchedTemplate = poseClassifier.matchCustomTemplate(input)
                if (matchedTemplate != null) {
                    if (matchedTemplate.gestureId != lastCustomGestureId) {
                        lastCustomGestureId = matchedTemplate.gestureId
                        _semanticEvents.emit(GestureEvent.CustomGestureTriggered(matchedTemplate.gestureId, matchedTemplate.name, timestampMs))
                    }
                } else lastCustomGestureId = null
            }
        }

        val isPinchAnchored = (wasPinching || pinchState == PinchState.PINCH_START) && !pinchDragUnlocked
        val effectiveCursorX = if (isPinchAnchored) pinchAnchoredX else lastPalmX
        val effectiveCursorY = if (isPinchAnchored) pinchAnchoredY else lastPalmY
        if (input.isDetected && hasPalmPosition && (transition.newState == GestureEngineState.ARMING || transition.newState == GestureEngineState.ARMED || transition.newState == GestureEngineState.EXECUTING || transition.newState == GestureEngineState.COOLDOWN)) {
            // Fix B4: EXECUTING is included so the dot keeps following the palm
            // while a triggered action is in flight. Previously the dot froze
            // for the duration of every EXECUTING burst, which read as a stutter
            // right at the moment the user was most engaged.
            val isSilent = transition.newState == GestureEngineState.ARMING
            val hint = if (lowConfidence) LOW_CONFIDENCE_SMOOTHER_MIN_CUTOFF else null
            _cursorEvents.tryEmit(GestureEvent.CursorMoved(effectiveCursorX, effectiveCursorY, timestampMs, isSilent, hint))
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
    private suspend fun processPinch(input: HandInput, timestampMs: Long, allowEntry: Boolean) {
        val currentState = _engineState.value
        val isAllowedState = currentState == GestureEngineState.ARMED ||
            currentState == GestureEngineState.EXECUTING ||
            currentState == GestureEngineState.COOLDOWN ||
            (currentState == GestureEngineState.ARMING && input.isDetected)
        if (!isAllowedState) {
            if (wasPinching) { wasPinching = false; currentPinchPhase = null; pinchState = PinchState.IDLE; pinchDragUnlocked = false }
            return
        }
        if (!input.isDetected) {
            if (wasPinching) {
                _semanticEvents.emit(GestureEvent.Pinch(PinchPhase.END, pinchStartX, pinchStartY, timestampMs, pinchAnchoredX, pinchAnchoredY, currentVelocity))
                wasPinching = false; currentPinchPhase = null; pinchState = PinchState.IDLE; pinchDragUnlocked = false
            } else if (pinchState != PinchState.IDLE) {
                // Stress-audit bug #10 (§12): an UNCONFIRMED candidate (HOVER /
                // PINCH_START) used to survive hand loss — when the hand
                // reappeared with fingers still together, the stale candidate
                // satisfied its 80ms debounce across the gap and committed
                // without fresh validation. Tracking loss cancels incomplete
                // candidates; the returning hand starts from IDLE.
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
        // Stress-audit bug #9 (§11 numeric robustness): degenerate landmarks
        // (hand size ≤ EPSILON, or NaN — collapsed tracker output at frame
        // edges) used to yield thumbIndexDistance = 0, and 0 < EVERY
        // threshold, so a glitch could commit a real CLICK. Fail safe: a
        // degenerate hand reads as "fingers maximally apart" — no entry from
        // IDLE, and an existing HOVER demotes back to IDLE via the normal
        // hover-exit branch above.
        val handDegenerate = !handSize.isFinite() || handSize <= EPSILON
        val thumbIndexDistance = if (handDegenerate) {
            Float.MAX_VALUE
        } else {
            distance2D(thumbTip, indexTip) / handSize
        }
        val enterThreshold = config.scaledPinchDistanceRatio()
        val exitThreshold = config.scaledPinchReleaseRatio()
        val hoverThreshold = config.scaledPinchHoverRatio()
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
                        _semanticEvents.emit(GestureEvent.Armed(timestampMs))
                    }
                    pinchState = PinchState.PINCH_HOLD; pinchStateEntryTimeMs = timestampMs; wasPinching = true; currentPinchPhase = PinchPhase.START
                    pinchDragUnlocked = false
                    pinchStartX = pinchAnchoredX; pinchStartY = pinchAnchoredY
                    _semanticEvents.emit(GestureEvent.Pinch(PinchPhase.START, pinchAnchoredX, pinchAnchoredY, timestampMs, pinchAnchoredX, pinchAnchoredY, currentVelocity))
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
                    _semanticEvents.emit(GestureEvent.Pinch(PinchPhase.MOVE, emitX, emitY, timestampMs, pinchAnchoredX, pinchAnchoredY, currentVelocity))
                }
            }
            PinchState.PINCH_RELEASE -> {
                if (timeInState >= config.pinchReleaseConfirmMs) {
                    pinchState = PinchState.IDLE; pinchStateEntryTimeMs = timestampMs; wasPinching = false; currentPinchPhase = PinchPhase.END; lastPinchEndMs = timestampMs
                    val emitX = if (pinchDragUnlocked) lastPalmX else pinchAnchoredX
                    val emitY = if (pinchDragUnlocked) lastPalmY else pinchAnchoredY
                    _semanticEvents.emit(GestureEvent.Pinch(PinchPhase.END, emitX, emitY, timestampMs, pinchAnchoredX, pinchAnchoredY, currentVelocity))
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

    fun reset() {
        poseClassifier.reset(); poseClassifier.effectiveDebounceFrames = config.poseDebounceFrames; dynamicDetector.reset(); stateMachine.reset()
        pinchState = PinchState.IDLE; pinchStateEntryTimeMs = 0L; wasPinching = false; currentPinchPhase = null; pinchDragUnlocked = false
        pinchStartX = 0f; pinchStartY = 0f; pinchAnchoredX = 0f; pinchAnchoredY = 0f; lastPinchEndMs = 0L; lastCustomGestureId = null
        lowConfBadFrames = 0; lowConfGoodFrames = 0; lowConfidenceMode = false; _lowConfidence.value = false; prevIndexTipX = 0.5f; prevIndexTipY = 0.5f; prevIndexTipTimestampMs = 0L; currentVelocity = 0f
        resetPalmTracking(); handStillSinceMs = 0L; thumbPoseSinceMs = 0L; measuredFrameIntervalMs = 0L; prevFrameTimestampMs = 0L; armedSinceMs = 0L
        lastPalmX = 0.5f; lastPalmY = 0.5f; hasPalmPosition = false
        _engineState.value = GestureEngineState.DISARMED; _currentPose.value = Pose.NONE; _armingProgress.value = 0f
    }

    companion object {
        // Typical wrist->middle-MCP span in aspect-corrected normalized image
        // coordinates; used to keep pose-velocity thresholds calibrated after
        // hand-size normalization (Fix verified G4).
        private const val REFERENCE_HAND_SCALE = 0.2f
        // Prevents minor finger closure jitter from triggering accidental drag/selection,
        // while allowing deliberate slide gestures to unlock drag smoothly.
        private const val PINCH_DRAG_UNLOCK_THRESHOLD = 0.048f
        // Fix U-6: an 80ms post-release lockout added to the felt tap latency on
        // every second tap of a fast double-tap. 40ms still blocks a single
        // physical pinch from re-entering as two clicks.
        private const val PINCH_COOLDOWN_MS = 40L
        private const val SWIPE_SUPPRESSION_AFTER_PINCH_MS = 60L
        private const val CONFIDENCE_THRESHOLD = 0.7f
        private const val LOW_CONFIDENCE_MIN_FRAMES = 3
        private const val LOW_CONFIDENCE_EXIT_FRAMES = 3
        private const val LOW_CONFIDENCE_DEBOUNCE_FRAMES = 7
        private const val LOW_CONFIDENCE_SMOOTHER_MIN_CUTOFF = 2.0f
        private const val PALM_HOME_MIN_ARMED_MS = 1200L
        private const val EPSILON = 1e-6f
    }
}
