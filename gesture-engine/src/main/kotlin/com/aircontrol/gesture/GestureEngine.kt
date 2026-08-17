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
import kotlin.concurrent.Volatile

/**
 * Layer 2: Dual-Threshold FSM States (Apple Vision Pro Architecture)
 * Prevents state flickering with hysteresis and time debouncing
 */
private enum class PinchState {
    IDLE,           // No pinch detected
    HOVER,          // Fingers approaching (pre-interaction certainty)
    PINCH_START,    // Pinch just initiated (time debouncing in progress)
    PINCH_HOLD,     // Pinch confirmed and held (can drag)
    PINCH_RELEASE   // Pinch just released (time debouncing before IDLE)
}

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
    initialConfig: GestureEngineConfig = GestureEngineConfig(),
) {
    // H-06 Fix: Make config mutable so sensitivity can be updated without recreating the engine
    @Volatile
    var config: GestureEngineConfig = initialConfig
        private set

    private var scopeJob = SupervisorJob()
    private var scope = CoroutineScope(scopeJob + Dispatchers.Default)

    private val poseClassifier = StaticPoseClassifier(initialConfig)
    private val dynamicDetector = DynamicGestureDetector(initialConfig)
    private val stateMachine = GestureStateMachine(initialConfig)

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

    // ========== Layer 2: Dual-Threshold FSM (Apple Vision Pro Architecture) ==========
    // Prevents state flickering by using separate enter/exit thresholds with time debouncing
    // States: [IDLE] -> [HOVER] -> [PINCH_START] -> [PINCH_HOLD/DRAG] -> [PINCH_RELEASE]
    
    // Dual-Threshold Hysteresis (Apple Vision Pro specs)
    private var pinchState: PinchState = PinchState.IDLE
    private var pinchStateEntryTimeMs: Long = 0L

    // Time debouncing - require continuous recognition for 35ms before state transition
    private val TIME_DEBOUNCE_MS = 80L
    
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

    // F8 (Stationary-click / Midas prevention): index-tip velocity tracking.
    // Re-introduced (wired this time) to detect whether the hand was moving fast
    // when a pinch began — a moving-hand pinch is usually accidental.
    @Volatile
    private var prevIndexTipX: Float = 0.5f
    @Volatile
    private var prevIndexTipY: Float = 0.5f
    @Volatile
    private var prevIndexTipTimestampMs: Long = 0L
    @Volatile
    private var currentVelocity: Float = 0f

    // F4 (Palm → Home): tracks how long an open palm has been held while ARMED.
    // When it exceeds config.palmHomeHoldMs, a PalmHome event is emitted once per
    // palm presentation (the pose must change away from OPEN_PALM before it can
    // fire again — prevents repeated HOME while the user simply rests with an
    // open palm).
    @Volatile
    private var palmHoldStartMs: Long = 0L
    @Volatile
    private var palmHolding: Boolean = false
    @Volatile
    private var palmHomeFired: Boolean = false

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
     * H-06 Fix: Update the engine configuration (e.g., sensitivity change) without
     * recreating the entire engine. This preserves all in-progress gesture state
     * (arming, pinch, swipe detection) and avoids the UX disruption of losing
     * gesture context when the user adjusts the sensitivity slider.
     *
     * @param sensitivity New sensitivity value (0-100)
     */
    fun updateSensitivity(sensitivity: Int) {
        // Preserve all other config (including any calibrated pinch ratio) instead
        // of rebuilding from defaults — rebuilding silently dropped calibration.
        val newConfig = config.copy(sensitivity = sensitivity)
        config = newConfig
        poseClassifier.updateConfig(newConfig)
        dynamicDetector.updateConfig(newConfig)
        stateMachine.updateConfig(newConfig)
        // Note: No logging here — gesture-engine is a pure Kotlin module with no
        // Android/Timber dependency. The app-layer GestureDetectorImpl logs this change.
    }

    /**
     * Personalizes pinch detection using user-measured calibration data.
     *
     * @param handSizeMm The user's measured hand size (wrist-to-middle-MCP, mm).
     * @param pinchDistanceMm The user's measured pinch distance (thumb-tip to
     *   index-tip at the moment of a deliberate pinch, mm).
     *
     * The ratio pinchDistanceMm / handSizeMm is the user's natural "finger-touch"
     * distance normalized by hand size — the ideal pinch-enter threshold for them.
     * Pass 0f values (or a handSizeMm <= 0) to clear calibration and fall back to
     * the default sensitivity-scaled threshold.
     */
    fun updateCalibration(handSizeMm: Float, pinchDistanceMm: Float) {
        val ratio = if (handSizeMm > 0f && pinchDistanceMm > 0f) {
            pinchDistanceMm / handSizeMm
        } else {
            null
        }
        val newConfig = config.copy(calibratedPinchRatio = ratio)
        config = newConfig
        poseClassifier.updateConfig(newConfig)
        dynamicDetector.updateConfig(newConfig)
        stateMachine.updateConfig(newConfig)
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
     * L-10 Fix: Removed the unused start(inputFlow: Flow<HandInput>) method.
     * 
     * The engine now has a single, clear API: processFrame(input: HandInput).
     * This eliminates confusion about which method to use and prevents
     * accidental double-processing if both APIs were somehow called.
     * 
     * The app layer (GestureDetectorImpl) calls processFrame() directly from
     * its own coroutine that collects HandFrame events from HandTracker.
     * 
     * If you need flow-based collection in the future, implement it in the
     * app layer, not in the engine. The engine should remain a pure state
     * machine with no coroutine lifecycle management.
     */

    /**
     * Processes a single hand input frame synchronously.
     * This is the primary API for feeding hand tracking data to the engine.
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

        // 3. Process through state machine FIRST so processPinch (step 4) gates on
        //    the CURRENT frame's state — previously pinch read _engineState.value
        //    before it was updated, causing a one-frame lag when entering ARMED.
        val transition = stateMachine.process(pose, input.isDetected, timestampMs)
        _engineState.value = transition.newState
        _armingProgress.value = stateMachine.armingProgress

        // 4. Process pinch lifecycle (now sees the current frame's state)
        processPinch(input, timestampMs)

        // 4.5 F4 (Palm → Home): track open-palm hold while ARMED. OPEN_PALM is the
        // neutral ARMED pose, so a sustained 2s hold is a deliberate "show me home"
        // gesture. Emits a single PalmHome event per hold.
        if (transition.newState == GestureEngineState.ARMED && input.isDetected) {
            if (pose == Pose.OPEN_PALM) {
                if (!palmHolding) {
                    palmHolding = true
                    palmHoldStartMs = timestampMs
                } else if (!palmHomeFired && timestampMs - palmHoldStartMs >= config.palmHomeHoldMs) {
                    palmHomeFired = true
                    _gestureEvents.tryEmit(GestureEvent.PalmHome(timestampMs))
                }
            } else {
                palmHolding = false
                palmHoldStartMs = 0L
                palmHomeFired = false
            }
        } else {
            palmHolding = false
            palmHoldStartMs = 0L
            palmHomeFired = false
        }

        // 5. Emit events based on state transitions and gesture detection
        if (transition.stateChanged) {
            when (transition.newState) {
                GestureEngineState.ARMED -> {
                    _gestureEvents.tryEmit(GestureEvent.Armed(timestampMs))
                }
                GestureEngineState.DISARMED -> {
                    _gestureEvents.tryEmit(GestureEvent.Disarmed(timestampMs))
                    wasPinching = false
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
            // F8 (Stationary-click): compute index-tip velocity (normalized units
            // per second) so the app layer can reject accidental moving-hand taps.
            if (prevIndexTipTimestampMs > 0L) {
                val dtMs = (timestampMs - prevIndexTipTimestampMs).coerceAtLeast(1L)
                val dx = indexTip.x - prevIndexTipX
                val dy = indexTip.y - prevIndexTipY
                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                currentVelocity = dist / (dtMs / 1000f)
            }
            prevIndexTipX = indexTip.x
            prevIndexTipY = indexTip.y
            prevIndexTipTimestampMs = timestampMs
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
     * Layer 2: Dual-Threshold FSM with Hysteresis (Apple Vision Pro Architecture)
     * 
     * Prevents state flickering by using:
     * 1. Dual distance thresholds (D_enter < D_exit)
     * 2. Time debouncing (35ms continuous recognition)
     * 3. Proper state machine: IDLE -> HOVER -> PINCH_START -> PINCH_HOLD -> PINCH_RELEASE
     * 
     * This eliminates the "blinking" and unreliable gesture detection that occurs
     * with single-threshold approaches.
     */
    private fun processPinch(input: HandInput, timestampMs: Long) {
        // Gate pinch events on engine state — only emit when armed or active
        val currentState = _engineState.value
        if (currentState != GestureEngineState.ARMED &&
            currentState != GestureEngineState.EXECUTING &&
            currentState != GestureEngineState.COOLDOWN
        ) {
            if (wasPinching) {
                wasPinching = false
                currentPinchPhase = null
                pinchState = PinchState.IDLE
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
                pinchState = PinchState.IDLE
            }
            return
        }

        val thumbTip = input.landmarks[LandmarkIndex.THUMB_TIP]
        val indexTip = input.landmarks[LandmarkIndex.INDEX_TIP]
        val wrist = input.landmarks[LandmarkIndex.WRIST]
        val middleMcp = input.landmarks[LandmarkIndex.MIDDLE_MCP]

        // Calculate actual thumb-index distance (normalized by hand size)
        val handSize = distance2D(wrist, middleMcp)
        val thumbIndexDistance = if (handSize > EPSILON) {
            distance2D(thumbTip, indexTip) / handSize
        } else {
            0f
        }

        // Sensitivity- and calibration-aware pinch thresholds. All three are
        // derived from a single "enter" base so the dual-threshold hysteresis
        // relationships (hover > exit > enter) stay intact at every sensitivity.
        val enterThreshold = config.calibratedPinchRatio
            ?.let { (it * CALIBRATED_PINCH_ENTER_MARGIN).coerceIn(MIN_PINCH_ENTER, MAX_PINCH_ENTER) }
            ?: (PINCH_ENTER_THRESHOLD * config.pinchSensitivityFactor)
        val exitThreshold = enterThreshold * (PINCH_EXIT_THRESHOLD / PINCH_ENTER_THRESHOLD)
        val hoverThreshold = enterThreshold * (PINCH_HOVER_THRESHOLD / PINCH_ENTER_THRESHOLD)

        // BUG #8 FIX: Removed redundant pinchX/pinchY calculation
        // We use lastIndexTipX/Y for cursor position, not pinch center
        // This eliminates unnecessary computation

        // ========== Dual-Threshold FSM Logic ==========
        val timeInState = timestampMs - pinchStateEntryTimeMs
        
        when (pinchState) {
            PinchState.IDLE -> {
                // Check if fingers are approaching (enter HOVER zone)
                if (thumbIndexDistance < hoverThreshold) {
                    pinchState = PinchState.HOVER
                    pinchStateEntryTimeMs = timestampMs
                }
            }
            
            PinchState.HOVER -> {
                // BUG #6 FIX: Check pinch cooldown BEFORE entering PINCH_START
                // This prevents the user from wasting 35ms holding a pinch that will be rejected
                // If we're still in the cooldown period (150ms after last release), stay in HOVER
                val inCooldown = lastPinchEndMs > 0L && timestampMs - lastPinchEndMs < PINCH_COOLDOWN_MS
                
                // Pre-interaction certainty - fingers close but not pinching yet
                // Check if pinch threshold crossed (enter PINCH_START)
                if (thumbIndexDistance < enterThreshold && !inCooldown) {
                    pinchState = PinchState.PINCH_START
                    pinchStateEntryTimeMs = timestampMs
                } 
                // Check if fingers moved away (return to IDLE)
                else if (thumbIndexDistance > hoverThreshold * 1.5f) {
                    pinchState = PinchState.IDLE
                    pinchStateEntryTimeMs = timestampMs
                }
            }
            
            PinchState.PINCH_START -> {
                // Time debouncing - require 35ms of continuous pinch before confirming
                if (timeInState >= TIME_DEBOUNCE_MS) {
                    // Pinch confirmed! Transition to PINCH_HOLD
                    pinchState = PinchState.PINCH_HOLD
                    pinchStateEntryTimeMs = timestampMs
                    
                    // BUG #6 FIX: Cooldown check moved to HOVER state (earlier check)
                    // No need to check here since we already validated before entering PINCH_START
                    
                    // Emit PINCH_START event
                    wasPinching = true
                    currentPinchPhase = PinchPhase.START
                    // BUG #8 FIX: Use lastIndexTipX/Y instead of pinch center
                    pinchStartX = lastIndexTipX
                    pinchStartY = lastIndexTipY
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
                            velocity = currentVelocity,
                        ),
                    )
                }
                // Check if pinch threshold not maintained during debounce (abort)
                else if (thumbIndexDistance > exitThreshold) {
                    pinchState = PinchState.HOVER
                    pinchStateEntryTimeMs = timestampMs
                }
            }
            
            PinchState.PINCH_HOLD -> {
                // Pinch is active - can drag
                // Check if pinch released (fingers separated beyond exit threshold)
                if (thumbIndexDistance > exitThreshold) {
                    pinchState = PinchState.PINCH_RELEASE
                    pinchStateEntryTimeMs = timestampMs
                } else {
                    // Continue pinch hold - emit MOVE event
                    currentPinchPhase = PinchPhase.MOVE
                    _gestureEvents.tryEmit(
                        GestureEvent.Pinch(
                            phase = PinchPhase.MOVE,
                            x = lastIndexTipX,
                            y = lastIndexTipY,
                            timestampMs = timestampMs,
                            anchoredX = pinchAnchoredX,
                            anchoredY = pinchAnchoredY,
                            velocity = currentVelocity,
                        ),
                    )
                }
            }
            
            PinchState.PINCH_RELEASE -> {
                // Time debouncing before returning to IDLE
                if (timeInState >= TIME_DEBOUNCE_MS) {
                    // Release confirmed
                    pinchState = PinchState.IDLE
                    pinchStateEntryTimeMs = timestampMs
                    
                    wasPinching = false
                    currentPinchPhase = PinchPhase.END
                    lastPinchEndMs = timestampMs

                    _gestureEvents.tryEmit(
                        GestureEvent.Pinch(
                            phase = PinchPhase.END,
                            x = lastIndexTipX,
                            y = lastIndexTipY,
                            timestampMs = timestampMs,
                            anchoredX = pinchAnchoredX,
                            anchoredY = pinchAnchoredY,
                            velocity = currentVelocity,
                        ),
                    )
                    currentPinchPhase = null
                }
                // Check if pinch re-engaged during release debounce (abort release)
                else if (thumbIndexDistance < enterThreshold) {
                    pinchState = PinchState.PINCH_HOLD
                    pinchStateEntryTimeMs = timestampMs
                }
            }
        }

        // (Dead-code cleanup) The formerly documented "Intermittent Pinch Misfire"
        // hysteresis subsystem (isHandStable / updatePrevWrist / pinchHysteresis-
        // ExtensionCount) was never wired in — this closing brace now just ends
        // processPinch. The dual-threshold FSM above provides the actual hysteresis.
    }

    /**
     * Computes the Euclidean distance between two landmarks in 2D (X/Y only,
     * ignoring Z). Used to normalize thumb-index distance by hand size.
     */
    private fun distance2D(
        a: com.aircontrol.gesture.model.Landmark3D,
        b: com.aircontrol.gesture.model.Landmark3D,
    ): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /** Resets all gesture engine state. */
    fun reset() {
        poseClassifier.reset()
        // Bug #13 Fix: Restore default debounce on reset.
        poseClassifier.effectiveDebounceFrames = config.poseDebounceFrames
        dynamicDetector.reset()
        stateMachine.reset()
        
        // Layer 2: Reset FSM state
        pinchState = PinchState.IDLE
        pinchStateEntryTimeMs = 0L

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
        lowConfidenceFrameCount = 0
        prevIndexTipX = 0.5f
        prevIndexTipY = 0.5f
        prevIndexTipTimestampMs = 0L
        currentVelocity = 0f
        palmHoldStartMs = 0L
        palmHolding = false
        palmHomeFired = false
        _engineState.value = GestureEngineState.DISARMED
        _currentPose.value = Pose.NONE
        _armingProgress.value = 0f
    }

    companion object {
        // ========== Layer 2: Dual-Threshold FSM Constants (Apple Vision Pro) ==========
        // These thresholds prevent state flickering with proper hysteresis
        
        // PINCH_HOVER_THRESHOLD: Fingers approaching, pre-interaction certainty
        // When thumb-index distance < this, enter HOVER state
        private const val PINCH_HOVER_THRESHOLD = 0.08f
        
        // PINCH_ENTER_THRESHOLD: Pinch engagement threshold (tighter)
        // When thumb-index distance < this AND held for 35ms, confirm pinch.
        // Raised from 0.035 to 0.05 — 0.035 required fingers to practically touch,
        // making clicks unreliable ("pinch click kaam nahi karta").
        private const val PINCH_ENTER_THRESHOLD = 0.05f
        
        // PINCH_EXIT_THRESHOLD: Pinch disengagement threshold (looser)
        // When thumb-index distance > this AND held for 35ms, confirm release
        private const val PINCH_EXIT_THRESHOLD = 0.065f

        // Calibration-aware pinch threshold bounds. When the user has completed
        // calibration, the calibrated pinch ratio (scaled by a small margin) is
        // used as the pinch-enter threshold, clamped to these safe bounds so a
        // bad/outlier calibration can never make pinch unusable.
        private const val CALIBRATED_PINCH_ENTER_MARGIN = 1.2f
        private const val MIN_PINCH_ENTER = 0.015f
        private const val MAX_PINCH_ENTER = 0.08f
        
        // TIME_DEBOUNCE_MS: Already defined as instance variable (35L)
        // Required continuous recognition before state transition
        
        // UG-01 Fix: Reduced from 300ms to 150ms for faster double-tap recognition.
        // Further reduced to 80ms — 150ms still dropped rapid re-pinches.
        private const val PINCH_COOLDOWN_MS = 80L

        // UG-02 Fix: Reduced from 200ms to 100ms to allow quick swipes after pinch.
        // Further reduced to 60ms — 100ms still suppressed intentional quick swipes.
        private const val SWIPE_SUPPRESSION_AFTER_PINCH_MS = 60L

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

        // BUG #3 FIX: minCutoff hint passed to the CursorSmoother via
        // CursorMoved.minCutoffHint when low-confidence mitigations are active.
        // The CursorSmoother's default minCutoff is 1.0 (Apple Vision Pro spec).
        // Raising it to 2.0 for low-confidence frames applies 2x heavier smoothing
        // at rest, suppressing the erratic jumps that survive the in-engine cursor
        // freeze. When confidence recovers, minCutoffHint is null, signaling the
        // consumer to restore the 1.0 default.
        // Old value was 1.2f but with base 1.0 that was only +20% (ineffective).
        // New value 2.0f provides +100% increase for effective jitter suppression.
        private const val LOW_CONFIDENCE_SMOOTHER_MIN_CUTOFF = 2.0f

        // Epsilon for float comparisons (prevents divide-by-zero when the hand
        // size is degenerate).
        private const val EPSILON = 1e-6f
    }
}
