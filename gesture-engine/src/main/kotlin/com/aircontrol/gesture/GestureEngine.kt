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
import kotlin.jvm.Volatile

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

    private var scopeJob = SupervisorJob()
    private var scope = CoroutineScope(scopeJob + Dispatchers.Default)

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
    @Volatile
    private var wasPinching: Boolean = false
    @Volatile
    private var pinchStartX: Float = 0f
    @Volatile
    private var pinchStartY: Float = 0f

    // Issue 4 Fix: Pinch Intent Shift — anchor cursor at pinch start position.
    // When pinch starts, the physical movement of bringing fingers together shifts
    // the hand. We lock the action target to the START position (where the user
    // was pointing when they initiated the pinch) rather than the midpoint which
    // drifts as fingers close.
    @Volatile
    private var pinchAnchoredX: Float = 0f
    @Volatile
    private var pinchAnchoredY: Float = 0f

    // Issue 4 Fix: Use INDEX fingertip as anchor (not pinch center) because
    // that's where the user was pointing. The pinch center shifts as fingers close.
    private var lastIndexTipX: Float = 0.5f
    private var lastIndexTipY: Float = 0.5f

    // Bug #4 Fix: Tracks the CURRENT pinch phase so processFrame() can decide
    // whether to freeze the visual cursor at the anchor (START) or let it follow
    // the hand (MOVE). Previously, `wasPinching` was used as the freeze signal,
    // which froze the cursor for the ENTIRE pinch (START + MOVE), making the
    // visual cursor dot not follow the hand during drag operations.
    //
    //   null              : not pinching → cursor follows lastIndexTipX/Y
    //   PinchPhase.START  : just started → freeze at pinchAnchoredX/Y (one frame)
    //   PinchPhase.MOVE   : drag ongoing → cursor follows lastIndexTipX/Y (live)
    //   PinchPhase.END    : pinch ending  → cursor follows lastIndexTipX/Y (live)
    @Volatile
    private var currentPinchPhase: PinchPhase? = null

    // Bug #10 Fix: Timestamp of the last PinchPhase.END emission. Used to enforce
    // a 300ms cooldown before the next PinchPhase.START is accepted, ignoring
    // accidental quick re-pinches (double-tap glitch). Also used by the Bug #9
    // fix (swipe suppression after pinch END) — see SWIPE_SUPPRESSION_AFTER_PINCH_MS.
    @Volatile
    private var lastPinchEndMs: Long = 0L

    // Bug: Custom Gestures Not Triggering Fix — ID of the last matched custom
    // gesture template. Used to prevent the same custom gesture from firing
    // repeatedly while the user holds the pose (similar to lastExecutedPose for
    // standard poses). Cleared when the hand shape changes enough that no
    // template matches, or when the state returns to DISARMED.
    @Volatile
    private var lastCustomGestureId: String? = null

    // Bug: Intermittent Pinch Misfire Fix — Proximity hysteresis state.
    //
    // When the pinch classifier loses the PINCH pose for a single frame (due to
    // tracking noise, low confidence, or a brief finger jitter), we DON'T
    // immediately terminate the pinch. Instead, if the thumb-index distance is
    // still within a hysteresis margin (PINCH_HYSTERESIS_MARGIN × the pinch
    // threshold) AND the hand is stable (low velocity), we treat the frame as a
    // MOVE (pinch still alive). This prevents the user from having to pinch
    // multiple times to register a single click.
    //
    // To prevent infinite extension (fingers drifting apart slowly but staying
    // within the margin), we cap consecutive hysteresis-extended frames at
    // MAX_HYSTERESIS_EXTENSION_FRAMES.
    @Volatile
    private var pinchHysteresisExtensionCount: Int = 0

    // Previous wrist position, used to compute hand velocity for the hysteresis
    // "stable hand" check. Updated every frame in processPinch.
    @Volatile
    private var prevWristX: Float = 0.5f
    @Volatile
    private var prevWristY: Float = 0.5f
    @Volatile
    private var prevWristTimestampMs: Long = 0L

    // Bug #13 Fix: Counter for consecutive low-confidence frames. Used to apply
    // hysteresis — a single bad frame shouldn't trigger the low-confidence path,
    // but sustained low confidence (e.g., hand near camera edge) should.
    @Volatile
    private var lowConfidenceFrameCount: Int = 0

    /**
     * Stops the engine, cancelling any ongoing coroutine collection.
     */
    fun stop() {
        scopeJob.cancel()
    }

    /**
     * Bug: Custom Gestures Not Triggering Fix — Updates the dynamic list of
     * user-defined landmark templates that the classifier matches against live
     * hand frames.
     *
     * This is the bridge between the app-layer DataStore (where custom gestures
     * are persisted) and the pure-Kotlin gesture engine (which cannot depend on
     * Android). The app layer converts [com.aircontrol.data.model.CustomGesture]
     * objects to [LandmarkTemplate] instances and passes them here.
     *
     * Safe to call from any thread. The templates are applied atomically on the
     * next frame processed by [processFrame].
     *
     * @param templates The new list of landmark templates. Pass an empty list to
     *   disable custom gesture matching.
     */
    fun updateCustomTemplates(templates: List<LandmarkTemplate>) {
        poseClassifier.updateCustomTemplates(templates)
        // Clear the last-matched ID so a removed template doesn't block future matches.
        lastCustomGestureId = null
    }

    /**
     * Starts processing hand input frames.
     * Collects from the provided flow and emits gesture events.
     * Cancels any previous collection before starting a new one.
     */
    fun start(inputFlow: Flow<HandInput>) {
        stop() // Cancel any previous collection
        scopeJob = SupervisorJob()
        scope = CoroutineScope(scopeJob + Dispatchers.Default)
        _engineState.value = GestureEngineState.DISARMED
        wasPinching = false
        currentPinchPhase = null
        lastPinchEndMs = 0L
        lastCustomGestureId = null
        pinchHysteresisExtensionCount = 0
        prevWristX = 0.5f
        prevWristY = 0.5f
        prevWristTimestampMs = 0L
        lowConfidenceFrameCount = 0
        // Restore default debounce in case a previous session raised it.
        poseClassifier.effectiveDebounceFrames = config.poseDebounceFrames
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

        // Bug #13 Fix: Low-confidence frame detection.
        //
        // MediaPipe's hand-landmarker confidence score reflects how reliably the
        // landmarks were tracked. When the hand is near the camera edge, partially
        // occluded, or moving fast, confidence drops and the landmarks become
        // erratic — poses can flip frame-to-frame and the index tip can jump by
        // large amounts. We detect this via a strict 0.7 confidence threshold and
        // apply two mitigations:
        //   1. Raise the pose debounce from the default (5) to 7 frames, so a
        //      longer run of agreeing frames is required before a pose change is
        //      confirmed. This suppresses erratic pose flips.
        //   2. Skip the lastIndexTipX/Y update on low-confidence frames, so the
        //      cursor stays at its last known good position instead of jumping
        //      to an erratic landmark. This is the in-engine equivalent of
        //      increasing the CursorSmoother's minCutoff — both suppress motion,
        //      but doing it at the source prevents the smoother from ever seeing
        //      the bad data.
        //
        // Hysteresis: we require LOW_CONFIDENCE_MIN_FRAMES consecutive low-
        // confidence frames before activating the mitigations, so a single bad
        // frame (common during normal tracking) doesn't trigger them. The
        // mitigations clear immediately when confidence recovers.
        val isLowConfidence = input.isDetected && input.confidence < CONFIDENCE_THRESHOLD
        if (isLowConfidence) {
            lowConfidenceFrameCount++
        } else {
            lowConfidenceFrameCount = 0
        }
        val applyLowConfidenceMitigations = lowConfidenceFrameCount >= LOW_CONFIDENCE_MIN_FRAMES
        if (applyLowConfidenceMitigations) {
            poseClassifier.effectiveDebounceFrames = LOW_CONFIDENCE_DEBOUNCE_FRAMES
        } else {
            poseClassifier.effectiveDebounceFrames = config.poseDebounceFrames
        }

        // 1. Classify static pose (with debounce — may be raised for low confidence)
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
                    // Bug: Intermittent Pinch Misfire Fix — clear hysteresis state
                    // on disarm so the next arming session starts clean.
                    pinchHysteresisExtensionCount = 0
                    // Bug: Custom Gestures Not Triggering Fix — clear the last
                    // matched custom gesture ID on disarm so it can re-fire after
                    // re-arming.
                    lastCustomGestureId = null
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
            // Bug #9 Fix: Suppress swipe detection for 200ms after a pinch END.
            // When the user releases a pinch, their fingers spread apart rapidly.
            // The DynamicGestureDetector can misinterpret this finger-spreading
            // motion as a horizontal swipe (especially when the index tip moves
            // sideways as the fingers separate). By ignoring all swipes during
            // this window, we eliminate the false positive.
            val swipeSuppressed = lastPinchEndMs > 0L &&
                timestampMs - lastPinchEndMs < SWIPE_SUPPRESSION_AFTER_PINCH_MS

            // Swipes — only emit if not suppressed and not in low-confidence mode
            // (low-confidence frames can produce erratic wrist trajectories that
            // look like swipes)
            if (swipeResult.detected && swipeResult.direction != null &&
                !swipeSuppressed && !applyLowConfidenceMitigations
            ) {
                _gestureEvents.tryEmit(GestureEvent.Swipe(swipeResult.direction, timestampMs))
            }

            // Pose-triggered gestures (when transitioning to EXECUTING)
            // Note: PINCH, OPEN_PALM, and FIST are excluded because pinch
            // has its own lifecycle (START/MOVE/END) and OPEN_PALM/FIST are
            // used for arming/disarming rather than gesture execution.
            if (transition.shouldExecute) {
                val actionablePose = pose.takeIf {
                    it != Pose.NONE && it != Pose.OPEN_PALM && it != Pose.FIST
                }
                if (actionablePose != null) {
                    _gestureEvents.tryEmit(GestureEvent.PoseTriggered(actionablePose, timestampMs))
                }
            }

            // Bug: Custom Gestures Not Triggering Fix — Landmark template matching.
            //
            // After the default pose classification, check if the live hand frame
            // matches any user-defined landmark template. If a match is found AND
            // it's a NEW match (different from lastCustomGestureId), emit a
            // CustomGestureTriggered event. This runs in ARMED/EXECUTING/COOLDOWN
            // states so custom gestures can fire even during cooldown from a
            // previous standard gesture.
            //
            // Rapid-fire prevention: once a custom gesture fires, lastCustomGestureId
            // is set to its ID. Subsequent frames with the same match are suppressed
            // until the hand shape changes (no template matches, which clears the ID).
            // This mirrors the lastExecutedPose behavior for standard poses.
            //
            // Low-confidence frames are excluded — the classifier's matchCustomTemplate
            // already gates on confidence, but we double-check here for clarity.
            if (!applyLowConfidenceMitigations && input.isDetected) {
                val matchedTemplate = poseClassifier.matchCustomTemplate(input)
                if (matchedTemplate != null) {
                    if (matchedTemplate.gestureId != lastCustomGestureId) {
                        // New custom gesture match — emit event
                        lastCustomGestureId = matchedTemplate.gestureId
                        _gestureEvents.tryEmit(
                            GestureEvent.CustomGestureTriggered(
                                gestureId = matchedTemplate.gestureId,
                                gestureName = matchedTemplate.name,
                                timestampMs = timestampMs,
                            ),
                        )
                    }
                    // If matchedTemplate.gestureId == lastCustomGestureId, the user
                    // is still holding the same custom gesture — suppress (no repeat).
                } else {
                    // No template matched — clear the last ID so the next match can fire.
                    // This is the "neutral pose clears the lock" behavior, analogous to
                    // lastExecutedPose being cleared on NONE/POINTING.
                    if (lastCustomGestureId != null) {
                        lastCustomGestureId = null
                    }
                }
            }
        }

        // 7. Cursor position (always emitted when hand is detected and armed)
        // Track index tip position for pinch anchoring (Issue 4 fix).
        //
        // Bug #13 Fix: On low-confidence frames, do NOT update lastIndexTipX/Y.
        // The cursor stays at its last known good position instead of jumping to
        // an erratic landmark. This is the in-engine equivalent of increasing the
        // CursorSmoother's minCutoff — both suppress motion, but doing it at the
        // source prevents the smoother from ever seeing the bad data.
        if (input.isDetected && !applyLowConfidenceMitigations) {
            val indexTip = input.landmarks[LandmarkIndex.INDEX_TIP]
            lastIndexTipX = indexTip.x
            lastIndexTipY = indexTip.y
        }

        // Bug #4 Fix: Cursor freeze logic during pinch.
        //
        //   - PinchPhase.START: freeze at the anchor (where the user was pointing
        //     when the pinch began). This is a single-frame freeze that lets the
        //     ActionDispatcher record a stable click target. The anchor equals
        //     lastIndexTipX/Y captured at START, so this is essentially "freeze at
        //     the index tip position captured at pinch start".
        //   - PinchPhase.MOVE: let the cursor follow the live index tip so the
        //     visual dot tracks the hand during drag. Previously, the cursor was
        //     frozen for the entire pinch duration, causing the dot to stay at the
        //     drag origin while the hand moved — making drags feel broken.
        //   - PinchPhase.END or not pinching: follow the live index tip.
        val effectiveCursorX = if (currentPinchPhase == PinchPhase.START) {
            pinchAnchoredX
        } else {
            lastIndexTipX
        }
        val effectiveCursorY = if (currentPinchPhase == PinchPhase.START) {
            pinchAnchoredY
        } else {
            lastIndexTipY
        }

        // Bug #18 Fix: Emit CursorMoved during ARMING (in addition to ARMED and
        // COOLDOWN) so the CursorSmoother / OneEuroFilter can pre-establish and
        // stabilize the hand position BEFORE the cursor overlay fades in.
        //
        // Previously, CursorMoved was only emitted in ARMED/COOLDOWN. This meant
        // the smoother's first input was the very first ARMED frame — so the
        // filter's "first sample passes through unchanged" fast path produced a
        // cursor at the raw (un-smoothed) hand position, then subsequent frames
        // pulled it toward the smoothed position, causing a visible jump when the
        // cursor first appeared.
        //
        // By emitting during ARMING, the smoother processes several frames while
        // the cursor overlay is still hidden (the overlay only becomes visible in
        // ARMED). When the cursor finally fades in at ARMED, the smoother has
        // already converged on a stable position — no jump.
        //
        // ARMING is also excluded from low-confidence cursor suppression: even if
        // confidence is low during arming, we still want the smoother to pre-warm
        // with the (possibly noisy) data so it's ready when ARMED begins. The
        // smoother itself will damp the noise.
        if (input.isDetected && (
            transition.newState == GestureEngineState.ARMING ||
                transition.newState == GestureEngineState.ARMED ||
                transition.newState == GestureEngineState.COOLDOWN
        )
        ) {
            // Bug #18 Fix: Mark CursorMoved events emitted during ARMING as
            // "silent" so the consumer pre-warms the CursorSmoother but does
            // NOT show/update the visual cursor overlay. The overlay should
            // remain hidden until ARMED.
            val isSilent = transition.newState == GestureEngineState.ARMING

            // Bug #13 Fix: When low-confidence mitigations are active, hint the
            // consumer to increase the CursorSmoother's minCutoff for adaptive
            // smoothing. This complements the in-engine cursor freeze (skipping
            // lastIndexTipX/Y updates) — the smoother additionally dampens any
            // residual motion that does get through. When confidence recovers,
            // minCutoffHint is null, signaling the consumer to restore defaults.
            val minCutoffHint: Float? = if (applyLowConfidenceMitigations) {
                LOW_CONFIDENCE_SMOOTHER_MIN_CUTOFF
            } else {
                null
            }

            _gestureEvents.tryEmit(
                GestureEvent.CursorMoved(
                    x = effectiveCursorX,
                    y = effectiveCursorY,
                    timestampMs = timestampMs,
                    isSilent = isSilent,
                    minCutoffHint = minCutoffHint,
                ),
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
        // Gate pinch events on engine state — only emit when armed or active
        val currentState = _engineState.value
        if (currentState != GestureEngineState.ARMED &&
            currentState != GestureEngineState.EXECUTING &&
            currentState != GestureEngineState.COOLDOWN
        ) {
            if (wasPinching) {
                wasPinching = false
                currentPinchPhase = null
                pinchHysteresisExtensionCount = 0
                // Emit pinch END event even when disarmed to clean up state
            }
            return
        }

        if (!input.isDetected) {
            if (wasPinching) {
                _gestureEvents.tryEmit(
                    GestureEvent.Pinch(PinchPhase.END, pinchStartX, pinchStartY, timestampMs),
                )
                wasPinching = false
                currentPinchPhase = null
                pinchHysteresisExtensionCount = 0
            }
            // Reset prevWrist so the next detected frame starts fresh (no stale
            // velocity spike from a large dt across the hand-lost gap).
            prevWristTimestampMs = 0L
            return
        }

        val thumbTip = input.landmarks[LandmarkIndex.THUMB_TIP]
        val indexTip = input.landmarks[LandmarkIndex.INDEX_TIP]
        val wrist = input.landmarks[LandmarkIndex.WRIST]
        val middleMcp = input.landmarks[LandmarkIndex.MIDDLE_MCP]

        // Use the pinch center as the gesture position
        val pinchX = (thumbTip.x + indexTip.x) / 2f
        val pinchY = (thumbTip.y + indexTip.y) / 2f

        if (pose == Pose.PINCH) {
            // Reset hysteresis extension counter — the pinch is confidently held.
            pinchHysteresisExtensionCount = 0

            if (!wasPinching) {
                // Bug #10 Fix: Pinch cooldown — ignore accidental quick re-pinches
                // (double-tap glitch) within 300ms of the previous pinch END.
                // Only enforce if a previous pinch has actually ended (lastPinchEndMs > 0);
                // otherwise the very first pinch of a session would be blocked because
                // lastPinchEndMs initializes to 0L and (timestampMs - 0L) < 300L for
                // any reasonable session-start timestamp.
                if (lastPinchEndMs > 0L && timestampMs - lastPinchEndMs < PINCH_COOLDOWN_MS) {
                    // Too soon after the last pinch — ignore this START.
                    // Do NOT set wasPinching or currentPinchPhase; treat as if no
                    // pinch happened this frame.
                    updatePrevWrist(wrist, timestampMs)
                    return
                }

                // Pinch START
                wasPinching = true
                currentPinchPhase = PinchPhase.START
                pinchStartX = pinchX
                pinchStartY = pinchY
                // Issue 4 Fix: Anchor at INDEX fingertip position (where user was pointing),
                // NOT the pinch center (which shifts as fingers close together).
                // This prevents the cursor from drifting away from the target during pinch.
                pinchAnchoredX = lastIndexTipX
                pinchAnchoredY = lastIndexTipY
                _gestureEvents.tryEmit(
                    GestureEvent.Pinch(
                        phase = PinchPhase.START,
                        x = pinchAnchoredX,
                        y = pinchAnchoredY,
                        timestampMs = timestampMs,
                        anchoredX = pinchAnchoredX,
                        anchoredY = pinchAnchoredY,
                    ),
                )
            } else {
                // Pinch MOVE — emit ACTUAL current hand position for drag tracking.
                // x/y = current position (hand is moving, drag follows the hand)
                // anchoredX/anchoredY = original anchor (for tap/long-press targeting)
                currentPinchPhase = PinchPhase.MOVE
                _gestureEvents.tryEmit(
                    GestureEvent.Pinch(
                        phase = PinchPhase.MOVE,
                        x = lastIndexTipX,
                        y = lastIndexTipY,
                        timestampMs = timestampMs,
                        anchoredX = pinchAnchoredX,
                        anchoredY = pinchAnchoredY,
                    ),
                )
            }
        } else if (wasPinching) {
            // Bug: Intermittent Pinch Misfire Fix — Proximity hysteresis.
            //
            // The pinch classifier lost the PINCH pose this frame (pose != PINCH).
            // Before terminating the pinch, check if this is a transient tracking
            // drop rather than a genuine finger separation:
            //
            // 1. PROXIMITY: Is the thumb-index distance still within a hysteresis
            //    margin of the pinch threshold? (PINCH_HYSTERESIS_MARGIN × threshold)
            //    If the fingers are still close together, the classifier likely just
            //    lost the pose due to noise — not because the user opened their hand.
            //
            // 2. STABILITY: Is the hand velocity near zero? If the hand is stable
            //    (not moving rapidly), a brief classification drop is almost
            //    certainly noise. If the hand is moving fast, the drop is more
            //    likely a genuine finger separation during motion.
            //
            // 3. EXTENSION CAP: Have we already extended via hysteresis for too
            //    many consecutive frames? If so, terminate to prevent infinite
            //    extension (fingers drifting apart slowly but staying in margin).
            //
            // If all three checks pass, treat this frame as a MOVE (pinch alive)
            // instead of an END. This prevents the user from having to pinch
            // multiple times to register a single click.
            val handSize = distance2D(wrist, middleMcp)
            val pinchDistance = distance2D(thumbTip, indexTip)
            val pinchThreshold = config.scaledPinchDistanceRatio()
            val hysteresisActive = handSize > EPSILON &&
                pinchHysteresisExtensionCount < MAX_HYSTERESIS_EXTENSION_FRAMES &&
                pinchDistance / handSize < pinchThreshold * PINCH_HYSTERESIS_MARGIN &&
                isHandStable(wrist, timestampMs)

            if (hysteresisActive) {
                // Extend the pinch — treat as MOVE, don't emit END.
                pinchHysteresisExtensionCount++
                currentPinchPhase = PinchPhase.MOVE
                _gestureEvents.tryEmit(
                    GestureEvent.Pinch(
                        phase = PinchPhase.MOVE,
                        x = lastIndexTipX,
                        y = lastIndexTipY,
                        timestampMs = timestampMs,
                        anchoredX = pinchAnchoredX,
                        anchoredY = pinchAnchoredY,
                    ),
                )
            } else {
                // Pinch END — use the live index tip position for drag drop target
                // (Bug #2 Fix), and the anchored position for tap/long-press targeting.
                wasPinching = false
                currentPinchPhase = PinchPhase.END
                lastPinchEndMs = timestampMs
                pinchHysteresisExtensionCount = 0
                _gestureEvents.tryEmit(
                    GestureEvent.Pinch(
                        phase = PinchPhase.END,
                        x = lastIndexTipX,
                        y = lastIndexTipY,
                        timestampMs = timestampMs,
                        anchoredX = pinchAnchoredX,
                        anchoredY = pinchAnchoredY,
                    ),
                )
                // After END is emitted, clear the phase so subsequent frames (no pinch)
                // use the live index tip for the cursor.
                currentPinchPhase = null
            }
        }

        // Update previous wrist position for the next frame's velocity computation.
        updatePrevWrist(wrist, timestampMs)
    }

    /**
     * Bug: Intermittent Pinch Misfire Fix — Computes the Euclidean distance
     * between two landmarks in 2D (X/Y only, ignoring Z). Used for the pinch
     * hysteresis proximity check.
     */
    private fun distance2D(
        a: com.aircontrol.gesture.model.Landmark3D,
        b: com.aircontrol.gesture.model.Landmark3D,
    ): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /**
     * Bug: Intermittent Pinch Misfire Fix — Checks if the hand is "stable"
     * (low velocity) for the pinch hysteresis check.
     *
     * Computes the wrist velocity (normalized units per second) between the
     * previous frame and the current frame. If the velocity is below
     * [STABLE_HAND_VELOCITY_THRESHOLD], the hand is considered stable.
     *
     * A stable hand means a brief pinch-classification drop is almost certainly
     * tracking noise, not a genuine finger separation (which typically involves
     * hand motion). This prevents false pinch-END emissions during steady holds.
     */
    private fun isHandStable(
        wrist: com.aircontrol.gesture.model.Landmark3D,
        timestampMs: Long,
    ): Boolean {
        if (prevWristTimestampMs <= 0L) return false
        val dtMs = (timestampMs - prevWristTimestampMs).coerceAtLeast(1L)
        val dx = wrist.x - prevWristX
        val dy = wrist.y - prevWristY
        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
        val velocity = distance / (dtMs / 1000f) // normalized units per second
        return velocity < STABLE_HAND_VELOCITY_THRESHOLD
    }

    /**
     * Bug: Intermittent Pinch Misfire Fix — Updates the previous wrist position
     * and timestamp for the next frame's velocity computation.
     */
    private fun updatePrevWrist(
        wrist: com.aircontrol.gesture.model.Landmark3D,
        timestampMs: Long,
    ) {
        prevWristX = wrist.x
        prevWristY = wrist.y
        prevWristTimestampMs = timestampMs
    }

    /** Resets all gesture engine state. */
    fun reset() {
        poseClassifier.reset()
        // Bug #13 Fix: Restore default debounce on reset.
        poseClassifier.effectiveDebounceFrames = config.poseDebounceFrames
        dynamicDetector.reset()
        stateMachine.reset()
        wasPinching = false
        currentPinchPhase = null
        pinchStartX = 0f
        pinchStartY = 0f
        pinchAnchoredX = 0f
        pinchAnchoredY = 0f
        lastIndexTipX = 0.5f
        lastIndexTipY = 0.5f
        lastPinchEndMs = 0L
        lastCustomGestureId = null
        pinchHysteresisExtensionCount = 0
        prevWristX = 0.5f
        prevWristY = 0.5f
        prevWristTimestampMs = 0L
        lowConfidenceFrameCount = 0
        _engineState.value = GestureEngineState.DISARMED
        _currentPose.value = Pose.NONE
        _armingProgress.value = 0f
    }

    companion object {
        // Bug #10 Fix: Cooldown after a pinch END before the next pinch START is
        // accepted. Prevents accidental double-tap glitches where the user's
        // fingers briefly separate and re-pinch within a few frames, which the
        // classifier can read as two separate pinches and dispatch two actions.
        // 300ms is longer than a typical frame-to-frame gap (~33ms at 30fps) but
        // shorter than an intentional re-pinch.
        private const val PINCH_COOLDOWN_MS = 300L

        // Bug #9 Fix: Duration after a pinch END during which horizontal swipe
        // detections are suppressed. When the user releases a pinch, their
        // fingers spread apart rapidly — the DynamicGestureDetector can
        // misinterpret this finger-spreading motion as a horizontal swipe.
        // 200ms is long enough to cover the finger-spread transient (typically
        // 1-3 frames at 30fps) but short enough that an intentional swipe
        // immediately after a pinch is still detected.
        private const val SWIPE_SUPPRESSION_AFTER_PINCH_MS = 200L

        // Bug #13 Fix: Confidence threshold below which a frame is considered
        // "low confidence". MediaPipe's hand-landmarker confidence reflects how
        // reliably the landmarks were tracked. 0.7 is a strict threshold — below
        // this, landmarks become erratic (poses flip frame-to-frame, index tip
        // jumps by large amounts). Frames below this threshold trigger the
        // low-confidence mitigations (raised debounce + cursor freeze).
        private const val CONFIDENCE_THRESHOLD = 0.7f

        // Bug #13 Fix: Minimum consecutive low-confidence frames before the
        // mitigations activate. Prevents a single bad frame (common during
        // normal tracking) from triggering the mitigations. 3 frames ≈ 100ms
        // at 30fps — long enough to filter transient dips, short enough to
        // react quickly to sustained low confidence (e.g., hand at camera edge).
        private const val LOW_CONFIDENCE_MIN_FRAMES = 3

        // Bug #13 Fix: Pose debounce frame count during low-confidence tracking.
        // Raised from the default (5) to 7 so a longer run of agreeing frames is
        // required before a pose change is confirmed. Suppresses erratic pose
        // flips from noisy landmarks near camera boundaries.
        private const val LOW_CONFIDENCE_DEBOUNCE_FRAMES = 7

        // Bug #13 Fix: minCutoff hint passed to the CursorSmoother via
        // CursorMoved.minCutoffHint when low-confidence mitigations are active.
        // The CursorSmoother's default minCutoff is 0.45 (set in
        // GestureControlAccessibilityService). Raising it to 1.2 for low-
        // confidence frames applies much heavier smoothing at rest, suppressing
        // the erratic jumps that survive the in-engine cursor freeze. When
        // confidence recovers, minCutoffHint is null, signaling the consumer to
        // restore the 0.45 default.
        private const val LOW_CONFIDENCE_SMOOTHER_MIN_CUTOFF = 1.2f

        // Bug: Intermittent Pinch Misfire Fix — Epsilon for float comparisons
        // in the pinch hysteresis distance check (prevents divide-by-zero when
        // hand size is degenerate).
        private const val EPSILON = 1e-6f

        // Bug: Intermittent Pinch Misfire Fix — Hysteresis margin for the pinch
        // proximity check. When the classifier loses the PINCH pose but the
        // thumb-index distance ratio is still below (threshold × this margin),
        // the pinch is kept alive. 1.5× means the fingers can drift apart up to
        // 50% beyond the normal pinch threshold before the pinch terminates.
        // This tolerates the natural finger jitter that causes single-frame
        // classification drops without letting a genuine finger-separation
        // (which rapidly exceeds 1.5×) go undetected.
        private const val PINCH_HYSTERESIS_MARGIN = 1.5f

        // Bug: Intermittent Pinch Misfire Fix — Maximum consecutive frames the
        // pinch can be extended via hysteresis. Prevents infinite extension when
        // fingers drift apart slowly but stay within the hysteresis margin.
        // 3 frames ≈ 100ms at 30fps — long enough to bridge a transient tracking
        // drop, short enough that a genuine finger separation terminates promptly.
        private const val MAX_HYSTERESIS_EXTENSION_FRAMES = 3

        // Bug: Intermittent Pinch Misfire Fix — Velocity threshold (normalized
        // units per second) below which the hand is considered "stable" for the
        // hysteresis check. 0.15 norm/sec means the wrist is moving less than
        // 15% of the frame width per second — essentially a held-still hand.
        // A stable hand means a pinch-classification drop is almost certainly
        // noise, not a genuine finger separation (which usually involves motion).
        private const val STABLE_HAND_VELOCITY_THRESHOLD = 0.15f
    }
}
