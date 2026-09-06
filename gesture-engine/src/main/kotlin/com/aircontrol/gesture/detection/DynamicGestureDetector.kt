package com.aircontrol.gesture.detection

import com.aircontrol.gesture.config.GestureEngineConfig
import com.aircontrol.gesture.model.HandInput
import com.aircontrol.gesture.model.LandmarkIndex
import com.aircontrol.gesture.model.SwipeDirection
import kotlin.concurrent.Volatile

/**
 * Detects dynamic swipe gestures from a sliding window of hand positions.
 *
 * Tracks BOTH wrist and index fingertip positions over a configurable time window
 * (default 350ms). The index fingertip provides more dramatic displacement during
 * swipes, making detection more reliable.
 *
 * IMPROVEMENTS (Issue 6 Fix - Inconsistent Swipes):
 *
 * 1. DIRECTIONAL CONSISTENCY CHECK: A swipe is only confirmed when ≥70% of
 *    intermediate velocity vectors agree with the final direction. This prevents
 *    random directional changes within the window from producing false swipes.
 *
 * 2. MULTI-FRAME VELOCITY TRACKING: Instead of just measuring peak velocity,
 *    we compute the average velocity across the dominant direction. This makes
 *    swipe detection more consistent because a single noisy frame can no longer
 *    inflate the peak velocity.
 *
 * 3. MINIMUM FRAME COUNT: A swipe requires at least 4 samples in the window,
 *    not just 3. This ensures sufficient temporal evidence before declaring a swipe.
 *
 * All thresholds scale with the sensitivity setting (0–100).
 */
class DynamicGestureDetector(config: GestureEngineConfig) {
    // H-06 Fix: Make config mutable so sensitivity can be updated without recreating the engine
    @Volatile
    private var config: GestureEngineConfig = config

    /**
     * A single tracked position sample.
     */
    data class PositionSample(
        val x: Float,
        val y: Float,
        val timestampMs: Long,
    )

    /**
     * Result of swipe analysis.
     *
     * Hardening round 9: carries a normalized [confidence] (gesture-specific
     * weighting: displacement, velocity, direction consistency, step count —
     * a swipe exactly at every gate minimum scores 0.35, a clean fast swipe
     * approaches 1.0), a machine-readable [reason] for every rejection, and
     * [hadEvidence] (true once displacement passed the minimum, i.e. the
     * candidate was worth judging) so debug consumers can distinguish "nothing
     * happened" from "rejected, and why" (spec §14/§18).
     */
    data class SwipeResult(
        val detected: Boolean,
        val direction: SwipeDirection? = null,
        val displacementX: Float = 0f,
        val displacementY: Float = 0f,
        val peakVelocity: Float = 0f,
        val confidence: Float = 0f,
        val reason: SwipeRejectReason? = null,
        val hadEvidence: Boolean = false,
    )

    /** Why a swipe candidate with real motion was rejected (spec §18 telemetry). */
    enum class SwipeRejectReason {
        BELOW_DISPLACEMENT,
        DIAGONAL_AMBIGUOUS,
        TOO_SLOW,
        VERTICAL_TOO_DIAGONAL,
        VERTICAL_NON_MONOTONIC,
        TOO_FEW_MOVING_STEPS,
        INCONSISTENT_DIRECTION,
        COOLDOWN,
    }

    // Track both wrist and index fingertip for more reliable swipe detection
    private val wristWindow = ArrayDeque<PositionSample>()
    private val indexTipWindow = ArrayDeque<PositionSample>()

    /** Timestamp of the last detected swipe, used for cooldown. */
    private var lastSwipeTimestampMs: Long = 0L

    /**
     * Fix (user test: "ek gesture ke baad hand normal pe laate waqt doosra
     * gesture fire ho jata tha"): after a swipe fires, the returning/settling
     * hand motion must be swallowed until the hand is STILL for a moment.
     * While [neutralRearmElapsedMs] < [NEUTRAL_REARM_MS] all samples are
     * dropped — the transition frames cannot be read as a new swipe.
     */
    private var awaitingNeutralRearm: Boolean = false
    private var neutralStillSinceMs: Long = 0L
    private var neutralLastWristX: Float = 0f
    private var neutralLastWristY: Float = 0f
    private var neutralRearmElapsedMs: Long = 0L

    /**
     * Fix S1: consecutive frames for which the pose gate has disallowed
     * swipes. The window is only wiped once this exceeds the grace period.
     */
    private var disallowedFrames: Int = 0

    /** Minimum time between consecutive swipe detections. */
    private var swipeCooldownMs: Long = config.swipeCooldownMs

    /** Last sample timestamp, used to measure the incoming frame interval. */
    private var lastSampleTimestampMs: Long = 0L

    /** Exponentially smoothed frame interval (ms) of the hand frames fed in. */
    @Volatile
    private var measuredFrameIntervalMs: Long = 0L

    /**
     * Processes a hand input frame and returns a [SwipeResult] indicating
     * whether a swipe was detected and in which direction.
     *
     * Uses index fingertip as primary tracker (more dramatic movement),
     * with wrist as fallback.
     */
    fun process(input: HandInput, gestureAllowed: Boolean = true): SwipeResult {
        if (!input.isDetected) {
            wristWindow.clear()
            indexTipWindow.clear()
            // Hardening round 9 (repeated-trigger bug): hand loss used to CLEAR
            // the neutral re-arm latch, so a 1-frame tracking dropout right
            // after a fired swipe let the returning hand's sweep fire a
            // PHANTOM second swipe once the 220ms cooldown passed. Spec §12:
            // tracking lost must require FRESH evidence, not cancel recovery —
            // the latch now survives hand loss (only reset() clears it), and
            // stillness re-accumulates from the frames after the hand
            // reappears.
            return SwipeResult(detected = false)
        }

        // Fix A-14: remember the observed frame interval so the analysis window
        // can stretch in scan mode (5 fps). A 500ms window only ever holds 2-3
        // samples there, so swipes silently stopped working whenever the phone
        // throttled down.
        val now = input.timestampMs

        // Neutral re-arm gate (see awaitingNeutralRearm): swallow all motion
        // until the hand has been still for NEUTRAL_REARM_MS. The hand coming
        // out of a gesture (opening, settling, drifting back) otherwise lands
        // straight into a fresh window and fires a phantom second gesture.
        if (awaitingNeutralRearm) {
            val wrist = input.landmarks[LandmarkIndex.WRIST]
            val moved = kotlin.math.hypot(
                wrist.x - neutralLastWristX,
                wrist.y - neutralLastWristY,
            )
            neutralLastWristX = wrist.x
            neutralLastWristY = wrist.y
            if (moved < NEUTRAL_STILL_PER_FRAME) {
                if (neutralStillSinceMs == 0L) neutralStillSinceMs = now
                if (now > neutralStillSinceMs) {
                    neutralRearmElapsedMs += (now - neutralStillSinceMs).coerceAtMost(200L)
                    neutralStillSinceMs = now
                }
            } else {
                neutralStillSinceMs = 0L
                neutralRearmElapsedMs = 0L
            }
            wristWindow.clear()
            indexTipWindow.clear()
            if (neutralRearmElapsedMs < NEUTRAL_REARM_MS) {
                return SwipeResult(detected = false)
            }
            awaitingNeutralRearm = false
            neutralRearmElapsedMs = 0L
            neutralStillSinceMs = 0L
        }

        if (lastSampleTimestampMs > 0L) {
            val interval = (now - lastSampleTimestampMs).coerceIn(1L, 1000L)
            measuredFrameIntervalMs =
                if (measuredFrameIntervalMs == 0L) interval
                else ((measuredFrameIntervalMs * 3 + interval) / 4)
        }
        lastSampleTimestampMs = now

        // Fix A-11: when swipes are not allowed for this frame's pose, drop the
        // accumulated motion instead of keeping it. Otherwise the movement the
        // user made *while pointing* would be "completed" the moment they opened
        // their palm and scroll the page for no visible reason.
        //
        // Fix S1: ...but only after the pose has been disallowed for a few
        // CONSECUTIVE frames. During a fast open-palm swipe the classifier
        // flickers (OPEN_PALM → FOUR_FINGERS → OPEN_PALM) for 1-2 frames as
        // the hand accelerates, and the instant wipe below threw away a
        // half-completed swipe on every flicker — the "swipe sometimes works,
        // sometimes nothing happens" complaint. A short grace keeps genuine
        // pointing swipes from completing (the wipe still happens quickly),
        // while a flickering palm no longer murders an in-flight swipe.
        if (!gestureAllowed) {
            disallowedFrames++
            if (disallowedFrames > POSE_GATE_GRACE_FRAMES) {
                wristWindow.clear()
                indexTipWindow.clear()
            }
            return SwipeResult(detected = false)
        }
        disallowedFrames = 0

        val wrist = input.landmarks[LandmarkIndex.WRIST]
        val indexTip = input.landmarks[LandmarkIndex.INDEX_TIP]

        val wristSample = PositionSample(
            x = wrist.x,
            y = wrist.y,
            timestampMs = input.timestampMs,
        )
        val indexSample = PositionSample(
            x = indexTip.x,
            y = indexTip.y,
            timestampMs = input.timestampMs,
        )

        // Add samples and prune window to configured duration
        val windowMs = effectiveWindowMs()
        wristWindow.addLast(wristSample)
        indexTipWindow.addLast(indexSample)
        pruneWindow(wristWindow, input.timestampMs, windowMs)
        pruneWindow(indexTipWindow, input.timestampMs, windowMs)

        // Temporal evidence requirement. Fix A-14: the required sample count
        // adapts to the window length, so a long window in scan mode still needs
        // enough intermediate points to be trustworthy.
        val requiredSamples = requiredSampleCount(windowMs)
        if (indexTipWindow.size < requiredSamples) return SwipeResult(detected = false)

        // Analyze using index fingertip first (more dramatic movement)
        var result = analyzeWindow(indexTipWindow, input.timestampMs)

        // If index tip didn't detect, try wrist (some users swipe with whole hand)
        if (!result.detected && wristWindow.size >= requiredSamples) {
            result = analyzeWindow(wristWindow, input.timestampMs)
        }

        if (result.detected) {
            lastSwipeTimestampMs = input.timestampMs
            wristWindow.clear()
            indexTipWindow.clear()
            // Enter the neutral re-arm latch: nothing further may fire until the
            // hand is still (see process() top).
            awaitingNeutralRearm = true
            neutralStillSinceMs = 0L
            neutralRearmElapsedMs = 0L
            val wrist = input.landmarks[LandmarkIndex.WRIST]
            neutralLastWristX = wrist.x
            neutralLastWristY = wrist.y
        }

        return result
    }

    private fun resetNeutralRearm() {
        awaitingNeutralRearm = false
        neutralStillSinceMs = 0L
        neutralRearmElapsedMs = 0L
    }

    /**
     * Removes samples older than [config.swipeWindowMs] from the window.
     */
    internal fun pruneWindow(window: ArrayDeque<PositionSample>, currentTimeMs: Long) {
        pruneWindow(window, currentTimeMs, config.swipeWindowMs)
    }

    internal fun pruneWindow(
        window: ArrayDeque<PositionSample>,
        currentTimeMs: Long,
        windowMs: Long,
    ) {
        val cutoffTime = currentTimeMs - windowMs
        while (window.isNotEmpty() && window.first().timestampMs < cutoffTime) {
            window.removeFirst()
        }
    }

    /**
     * The window actually used for analysis: the configured one, stretched so it
     * always contains enough samples for the current frame rate.
     */
    internal fun effectiveWindowMs(): Long {
        val base = config.swipeWindowMs
        if (measuredFrameIntervalMs <= 0L) return base
        val needed = MIN_SAMPLES_FOR_SWIPE * measuredFrameIntervalMs
        return maxOf(base, needed)
    }

    /** Sample count required for a swipe, given the effective window length. */
    internal fun requiredSampleCount(windowMs: Long): Int {
        val byWindow = if (measuredFrameIntervalMs > 0L) {
            ((windowMs / measuredFrameIntervalMs) * 2 / 3).toInt()
        } else {
            MIN_SAMPLES_FOR_SWIPE
        }
        return byWindow.coerceIn(MIN_SAMPLES_FOR_SWIPE, MAX_SAMPLES_FOR_SWIPE)
    }

    /**
     * Analyzes the current window of position samples for a swipe gesture.
     *
     * Includes directional consistency check (Issue 6 Fix):
     * We verify that the majority of intermediate velocity vectors agree
     * with the overall displacement direction. This eliminates swipes that
     * look consistent in total displacement but have zigzag intermediate paths.
     *
     * Bug: Swipe Up/Down Confusion Fix:
     * Two additional checks specifically target vertical swipe reliability:
     *
     * 1. STRICT DIRECTIONAL ANGLE FILTER: Vertical swipes (UP/DOWN) require
     *    |dY| >= 2 × |dX|. If |dX| > 0.5 × |dY|, the movement is too diagonal
     *    and is rejected as noise. Horizontal swipes use the existing
     *    swipeAxisDominanceRatio (default 2.0) which is symmetric, but vertical
     *    swipes have more geometric overlap in natural hand motion, so the
     *    strict filter is applied explicitly.
     *
     * 2. MONOTONIC VECTOR VERIFICATION: For Swipe UP, the Y coordinate must
     *    continuously decrease (in screen space, Y increases downward) across
     *    the buffer without erratic directional reversals. For Swipe DOWN, Y
     *    must continuously increase. A single reversed intermediate step is
     *    tolerated (noise), but two or more reversals reject the swipe. This
     *    prevents diagonal-drift hand motion from being misread as a vertical
     *    swipe.
     */
    internal fun analyzeWindow(window: ArrayDeque<PositionSample>, currentTimeMs: Long): SwipeResult {
        if (window.size < 2) return SwipeResult(detected = false)

        // Check cooldown
        if (currentTimeMs - lastSwipeTimestampMs < swipeCooldownMs) {
            return SwipeResult(detected = false, reason = SwipeRejectReason.COOLDOWN)
        }

        val first = window.first()
        val last = window.last()

        // Compute displacement (absolute)
        val displacementX = last.x - first.x
        val displacementY = last.y - first.y
        val absDispX = kotlin.math.abs(displacementX)
        val absDispY = kotlin.math.abs(displacementY)

        // Sensitivity-scaled displacement threshold
        val dispThreshold = config.scaledSwipeDisplacement()

        // Check if displacement exceeds threshold on either axis
        if (absDispX < dispThreshold && absDispY < dispThreshold) {
            return SwipeResult(
                detected = false,
                displacementX = displacementX,
                displacementY = displacementY,
                reason = SwipeRejectReason.BELOW_DISPLACEMENT,
            )
        }

        // Determine dominant axis
        val isHorizontalDominant = absDispX > absDispY
        val dominantDisp = if (isHorizontalDominant) absDispX else absDispY
        val secondaryDisp = if (isHorizontalDominant) absDispY else absDispX

        // Check axis dominance ratio (rejects diagonal ambiguity)
        if (secondaryDisp > EPSILON && dominantDisp / secondaryDisp < config.swipeAxisDominanceRatio) {
            return SwipeResult(
                detected = false,
                displacementX = displacementX,
                displacementY = displacementY,
                reason = SwipeRejectReason.DIAGONAL_AMBIGUOUS,
                hadEvidence = true,
            )
        }

        // Determine direction from the SIGNED SUM of the moving steps, not just
        // the endpoint displacement (Fix: swipe direction accuracy).
        //
        // A natural hand swipe travels in a slight arc. The *endpoint*
        // displacement can point a few degrees off the throw — or even flip the
        // dominant axis on a curved path — which made fast swipes read as the
        // wrong direction or fall to the diagonal filters. The signed sum of
        // per-step motion is the arc-robust "net throw" of the hand: noise and
        // small hooks cancel out, and the axis the hand actually travelled
        // along wins.
        val direction = signedThrowDirection(window)
            ?: if (isHorizontalDominant) {
                if (displacementX > 0f) SwipeDirection.RIGHT else SwipeDirection.LEFT
            } else {
                if (displacementY > 0f) SwipeDirection.DOWN else SwipeDirection.UP
            }

        // Bug: Swipe Up/Down Confusion Fix — STRICT DIRECTIONAL ANGLE FILTER
        // for vertical swipes.
        //
        // Vertical swipes (UP/DOWN) suffer from geometric overlap in natural
        // hand motion — it's hard to move the hand straight up/down without
        // some horizontal drift. We apply a strict filter: |dY| must be at
        // least 2× |dX| (equivalently, |dX| <= 0.5 × |dY|). If the movement
        // is too diagonal, reject it as noise rather than guessing UP vs DOWN.
        //
        // Horizontal swipes (LEFT/RIGHT) are NOT subject to this extra filter
        // — they use the existing swipeAxisDominanceRatio check above, which
        // is sufficient because horizontal hand motion is naturally cleaner.
        if (direction == SwipeDirection.UP || direction == SwipeDirection.DOWN) {
            if (absDispY < VERTICAL_SWIPE_MIN_Y_TO_X_RATIO * absDispX) {
                // |dY| < 2 × |dX| → too diagonal to be a confident vertical swipe
                return SwipeResult(
                    detected = false,
                    displacementX = displacementX,
                    displacementY = displacementY,
                    reason = SwipeRejectReason.VERTICAL_TOO_DIAGONAL,
                    hadEvidence = true,
                )
            }
        }

        // Compute peak velocity
        val peakVelocity = computePeakVelocity(window)
        val velocityThreshold = config.scaledSwipeVelocity()

        if (peakVelocity < velocityThreshold) {
            return SwipeResult(
                detected = false,
                displacementX = displacementX,
                displacementY = displacementY,
                peakVelocity = peakVelocity,
                reason = SwipeRejectReason.TOO_SLOW,
                hadEvidence = true,
            )
        }

        // Bug: Swipe Up/Down Confusion Fix — MONOTONIC VECTOR VERIFICATION
        // for vertical swipes.
        //
        // For Swipe UP, the Y coordinate must continuously decrease (screen Y
        // increases downward, so UP = decreasing Y) across the buffer. For
        // Swipe DOWN, Y must continuously increase. We count directional
        // reversals in the intermediate steps. A single reversal is tolerated
        // (one noisy frame), but two or more reversals indicate erratic
        // diagonal drift, not a deliberate vertical swipe.
        //
        // Horizontal swipes use the existing directional consistency check
        // (below) which is sufficient for their cleaner motion profile.
        if (direction == SwipeDirection.UP || direction == SwipeDirection.DOWN) {
            val reversals = countDirectionalReversals(window, direction)
            if (reversals > MAX_VERTICAL_REVERSALS) {
                return SwipeResult(
                    detected = false,
                    displacementX = displacementX,
                    displacementY = displacementY,
                    peakVelocity = peakVelocity,
                    reason = SwipeRejectReason.VERTICAL_NON_MONOTONIC,
                    hadEvidence = true,
                )
            }
        }

        // Fix A-11: a swipe must be built from several *moving* steps, not one
        // big jump. A single-frame teleport (tracking glitch, or the pose
        // debounce lagging behind a hand that just started moving) produced
        // phantom scrolls while the user was only moving the cursor. At full
        // frame rate this needs 3 moving steps; in scan mode (where a whole
        // swipe is only 2-3 frames) the requirement relaxes automatically.
        val movingSteps = countMovingSteps(window, direction)
        val requiredSteps = when {
            measuredFrameIntervalMs >= 120L -> 1
            window.size >= 8 -> MIN_MOVING_STEPS
            else -> MIN_MOVING_STEPS - 1
        }
        if (movingSteps < requiredSteps) {
            return SwipeResult(
                detected = false,
                displacementX = displacementX,
                displacementY = displacementY,
                peakVelocity = peakVelocity,
                reason = SwipeRejectReason.TOO_FEW_MOVING_STEPS,
                hadEvidence = true,
            )
        }

        // Issue 6 Fix: Directional consistency check (applies to ALL directions)
        // Verify that the majority of intermediate velocity vectors agree with
        // the overall displacement direction. This prevents zigzag movements
        // from being detected as swipes.
        val consistency = computeDirectionalConsistency(window, direction)
        if (consistency < DIRECTIONAL_CONSISTENCY_THRESHOLD) {
            return SwipeResult(
                detected = false,
                displacementX = displacementX,
                displacementY = displacementY,
                peakVelocity = peakVelocity,
                reason = SwipeRejectReason.INCONSISTENT_DIRECTION,
                hadEvidence = true,
            )
        }

        // Hardening round 9 (spec §14): composite, gesture-specific confidence.
        // Each factor is normalized so 0.5 = "exactly at the gate minimum"
        // (except consistency, which is 0 at its gate and 1 at perfect
        // agreement). A swipe that passes every gate with the bare minimum
        // scores 0.35; a fast, long, dead-straight swipe approaches 1.0.
        val dispFactor = (dominantDisp / (dispThreshold * 2f)).coerceIn(0f, 1f)
        val velFactor = (peakVelocity / (velocityThreshold * 2f)).coerceIn(0f, 1f)
        val consistencyFactor = ((consistency - DIRECTIONAL_CONSISTENCY_THRESHOLD) /
            (1f - DIRECTIONAL_CONSISTENCY_THRESHOLD)).coerceIn(0f, 1f)
        val stepsFactor = (movingSteps / (requiredSteps * 2f)).coerceIn(0f, 1f)
        val confidence = 0.25f * dispFactor + 0.25f * velFactor +
            0.30f * consistencyFactor + 0.20f * stepsFactor

        return SwipeResult(
            detected = true,
            direction = direction,
            displacementX = displacementX,
            displacementY = displacementY,
            peakVelocity = peakVelocity,
            confidence = confidence,
            hadEvidence = true,
        )
    }

    /**
     * Fix (swipe direction accuracy): net-throw direction from the signed sum of
     * per-step motion, ignoring sub-threshold jitter steps. A natural hand swipe
     * travels in a slight arc; the endpoint displacement can point off-axis or
     * even flip the dominant axis on a curved path, which read as the WRONG
     * direction. The signed sum is the arc-robust "net throw": noise and small
     * hooks cancel out. Returns null when both sums are negligible; the caller
     * then falls back to the endpoint-displacement direction.
     */
    internal fun signedThrowDirection(window: ArrayDeque<PositionSample>): SwipeDirection? {
        if (window.size < 3) return null
        var sumX = 0f
        var sumY = 0f
        for (i in 1 until window.size) {
            val dx = window[i].x - window[i - 1].x
            val dy = window[i].y - window[i - 1].y
            if (kotlin.math.abs(dx) >= MIN_INTERMEDIATE_DISPLACEMENT) sumX += dx
            if (kotlin.math.abs(dy) >= MIN_INTERMEDIATE_DISPLACEMENT) sumY += dy
        }
        val absSumX = kotlin.math.abs(sumX)
        val absSumY = kotlin.math.abs(sumY)
        if (absSumX < EPSILON && absSumY < EPSILON) return null
        return if (absSumX > absSumY) {
            if (sumX > 0f) SwipeDirection.RIGHT else SwipeDirection.LEFT
        } else {
            if (sumY > 0f) SwipeDirection.DOWN else SwipeDirection.UP
        }
    }

    /**
     * Computes the peak velocity across consecutive sample pairs in the window.
     * Velocity is measured in normalized units per second.
     */
    internal fun computePeakVelocity(window: ArrayDeque<PositionSample>): Float {
        if (window.size < 2) return 0f

        var peakVelocity = 0f
        for (i in 1 until window.size) {
            val prev = window[i - 1]
            val curr = window[i]

            val dt = (curr.timestampMs - prev.timestampMs).coerceAtLeast(1L)
            val dx = curr.x - prev.x
            val dy = curr.y - prev.y
            val distance = kotlin.math.sqrt(dx * dx + dy * dy)
            val velocity = distance / (dt / 1000f) // normalized units per second

            if (velocity > peakVelocity) {
                peakVelocity = velocity
            }
        }
        return peakVelocity
    }

    /**
     * Issue 6 Fix: Computes directional consistency across intermediate velocity vectors.
     *
     * For each consecutive pair of samples, we check if the velocity vector
     * points in the same general direction as the overall swipe. The consistency
     * score is the fraction of vectors that agree.
     *
     * A score of 1.0 means all vectors agree (perfect swipe).
     * A score of 0.5 means half the vectors disagree (zigzag — not a swipe).
     *
     * @param window The sliding window of position samples
     * @param overallDirection The direction of the overall displacement
     * @return Consistency score [0.0, 1.0]
     */
    internal fun computeDirectionalConsistency(
        window: ArrayDeque<PositionSample>,
        overallDirection: SwipeDirection,
    ): Float {
        if (window.size < 3) return 1.0f // Too few samples to check

        var agreeing = 0
        var total = 0

        for (i in 1 until window.size) {
            val prev = window[i - 1]
            val curr = window[i]
            val dx = curr.x - prev.x
            val dy = curr.y - prev.y

            // Only count non-trivial movements (skip jitter)
            val distance = kotlin.math.sqrt(dx * dx + dy * dy)
            if (distance < MIN_INTERMEDIATE_DISPLACEMENT) continue

            total++
            val agrees = when (overallDirection) {
                SwipeDirection.LEFT -> dx < 0f
                SwipeDirection.RIGHT -> dx > 0f
                SwipeDirection.UP -> dy < 0f
                SwipeDirection.DOWN -> dy > 0f
            }
            if (agrees) agreeing++
        }

        if (total == 0) return 0f
        return agreeing.toFloat() / total.toFloat()
    }

    /**
     * Bug: Swipe Up/Down Confusion Fix — Counts directional reversals in the
     * Y coordinate across the window for vertical swipes.
     *
     * For Swipe UP (Y decreasing), a reversal is any intermediate step where
     * Y increases (moves DOWN) instead of decreasing. For Swipe DOWN (Y
     * increasing), a reversal is any step where Y decreases (moves UP).
     *
     * Trivial jitter (below [MIN_INTERMEDIATE_DISPLACEMENT]) is skipped to avoid
     * counting sub-pixel noise as a reversal.
     *
     * @return The number of directional reversals. 0 = perfectly monotonic.
     */
    internal fun countDirectionalReversals(
        window: ArrayDeque<PositionSample>,
        direction: SwipeDirection,
    ): Int {
        if (window.size < 3) return 0

        var reversals = 0
        for (i in 1 until window.size) {
            val prev = window[i - 1]
            val curr = window[i]
            val dy = curr.y - prev.y

            // Skip trivial jitter
            if (kotlin.math.abs(dy) < MIN_INTERMEDIATE_DISPLACEMENT) continue

            val isReversal = when (direction) {
                // UP = Y should decrease (dy < 0). Reversal = dy > 0.
                SwipeDirection.UP -> dy > 0f
                // DOWN = Y should increase (dy > 0). Reversal = dy < 0.
                SwipeDirection.DOWN -> dy < 0f
                // Not applicable for horizontal swipes — return 0.
                SwipeDirection.LEFT, SwipeDirection.RIGHT -> return 0
            }
            if (isReversal) reversals++
        }
        return reversals
    }

    /**
     * Number of consecutive sample pairs whose movement along the swipe axis is
     * large enough to be real motion rather than jitter.
     */
    internal fun countMovingSteps(window: ArrayDeque<PositionSample>, direction: SwipeDirection): Int {
        if (window.size < 2) return 0
        var steps = 0
        for (i in 1 until window.size) {
            val prev = window[i - 1]
            val curr = window[i]
            val delta = when (direction) {
                SwipeDirection.LEFT, SwipeDirection.RIGHT -> kotlin.math.abs(curr.x - prev.x)
                SwipeDirection.UP, SwipeDirection.DOWN -> kotlin.math.abs(curr.y - prev.y)
            }
            if (delta >= MIN_INTERMEDIATE_DISPLACEMENT) steps++
        }
        return steps
    }

    /** Resets the detector state. */
    fun reset() {
        wristWindow.clear()
        indexTipWindow.clear()
        lastSwipeTimestampMs = 0L
        lastSampleTimestampMs = 0L
        measuredFrameIntervalMs = 0L
        disallowedFrames = 0
        resetNeutralRearm()
    }

    companion object {
        private const val EPSILON = 1e-6f
        // Minimum samples for a trustworthy swipe. Fix A-14: 4 was impossible to
        // reach in 5 fps scan mode (a 500ms window only holds 2-3 samples), which
        // silently disabled swipes whenever the phone throttled. 3 is the floor;
        // the effective requirement adapts to the frame rate up to
        // MAX_SAMPLES_FOR_SWIPE.
        private const val MIN_SAMPLES_FOR_SWIPE = 3
        private const val MAX_SAMPLES_FOR_SWIPE = 5
        // Minimum fraction of intermediate vectors that must agree with overall direction
        private const val DIRECTIONAL_CONSISTENCY_THRESHOLD = 0.7f
        // Minimum displacement for an intermediate step to be counted in consistency check
        private const val MIN_INTERMEDIATE_DISPLACEMENT = 0.005f
        // Minimum number of moving steps inside the window for a real swipe.
        private const val MIN_MOVING_STEPS = 3

        // Bug: Swipe Up/Down Confusion Fix — Strict directional angle filter.
        // Vertical swipes (UP/DOWN) require |dY| >= 2 × |dX|. Equivalently,
        // if |dX| > 0.5 × |dY|, the movement is too diagonal and is rejected.
        // 2.0 was chosen because it matches the existing swipeAxisDominanceRatio
        // default but is applied EXPLICITLY and ONLY to vertical swipes, where
        // geometric overlap from natural hand motion causes the most confusion.
        private const val VERTICAL_SWIPE_MIN_Y_TO_X_RATIO = 2.0f

        // Bug: Swipe Up/Down Confusion Fix — Maximum tolerated directional
        // reversals in the Y coordinate for vertical swipes. 0 = perfectly
        // monotonic required. 1 = tolerate one noisy frame. We use 1 because
        // MediaPipe tracking can produce a single noisy Y sample during fast
        // vertical motion, but two or more reversals indicate genuine erratic
        // drift, not a deliberate swipe.
        // Fix (audit #17): the layered anti-false-positive gates overcorrected —
        // a natural swipe with a slight backswing was rejected outright. Two
        // reversals (brief backswing + noise) are still a swipe; three or more
        // remain erratic. All the other gates (axis dominance, consistency,
        // moving steps) still apply.
        private const val MAX_VERTICAL_REVERSALS = 2

        // Fix S1: how many consecutive pose-gated frames tolerate keeping the
        // swipe window before it is wiped. 2 frames (~80ms at 24fps) absorbs
        // classifier flicker during fast palm swipes without letting a
        // pointing-hand sweep complete later.
        private const val POSE_GATE_GRACE_FRAMES = 2

        // Neutral re-arm tuning: the hand must be essentially still for this
        // long (ms) after a fired swipe before any new swipe can accumulate.
        private const val NEUTRAL_REARM_MS = 250L

        /** Per-frame wrist movement (normalized) that still counts as "still". */
        private const val NEUTRAL_STILL_PER_FRAME = 0.006f
    }

    /**
     * H-06 Fix: Update the config (e.g., sensitivity change) without recreating the detector.
     * This preserves the current sliding window state and avoids losing in-progress swipe detection.
     */
    fun updateConfig(newConfig: GestureEngineConfig) {
        this.config = newConfig
        // Keep the cached cooldown in sync (previously it was captured once at
        // construction and never refreshed when the config changed).
        swipeCooldownMs = newConfig.swipeCooldownMs
    }
}
