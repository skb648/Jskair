package com.aircontrol.gesture.detection

import com.aircontrol.gesture.config.GestureEngineConfig
import com.aircontrol.gesture.model.HandInput
import com.aircontrol.gesture.model.LandmarkIndex
import com.aircontrol.gesture.model.SwipeDirection
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Detects dynamic swipe gestures from a sliding window of hand positions.
 *
 * Tracks BOTH wrist and index fingertip positions over a configurable time window
 * (default 350ms). The index fingertip provides more dramatic displacement during
 * swipes, making detection more reliable.
 *
 * Recognition is a [SwipeIntentArbiter] state machine fed one composite score, NOT a
 * chain of independent gates. The previous version of this class layered nine serial
 * conditions (sample count, displacement, peak velocity, axis dominance, per-axis
 * dominance for the vertical directions, moving steps, direction consistency,
 * reversals, plus a handedness-confidence veto upstream) and required ALL of them;
 * a deliberate half-second sweep failed the velocity gate, a natural arcing swipe
 * failed the dominance cone, and no threshold change could fix either, because each
 * gate was individually defensible. Now each of those quantities is a weighted
 * contribution to one intent score, two of them (hard minimum travel and a real
 * temporal-evidence floor) stay as validity floors, and the hysteresis between
 * "candidate" and "commit" is what keeps single-frame noise from toggling the output.
 *
 * MEASUREMENT UNITS: displacement and velocity are normalised by the user's own hand
 * size (wrist to middle-finger base) instead of a fraction of the frame, and the x
 * axis is multiplied by the analysis image's aspect ratio before any comparison, so a
 * horizontal and a vertical gesture of the same real length are judged identically.
 * The previous code compared `absDispX` (per-width normalisation) against `absDispY`
 * (per-height normalisation) directly, which on a 4:3 sensor mis-measured every
 * diagonal by ~25 %.
 *
 * Sensitivity (0-100) still moves the acceptance anchors, via
 * [com.aircontrol.gesture.config.GestureEngineConfig.scaledSwipeCandidateScore], and
 * stays inside the band a real gesture can reach.
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

    /**
     * Debug-only view of the swipe state machine, sampled after each frame (spec §18:
     * phase, intent score, both thresholds, candidate age, explicit rejection reason).
     *
     * Every value is the one the arbiter actually used for its last decision — the
     * point of the snapshot is that a user can see why the motion they just made was
     * held, so it deliberately does NOT recompute anything. Nothing in the dispatch
     * path reads it; the app layer only renders it in debug builds.
     */
    data class SwipeDebugInfo(
        val phase: SwipeIntentArbiter.Phase,
        val direction: SwipeDirection?,
        val holdReason: SwipeHoldReason?,
        val intentScore: Float,
        val candidateScore: Float,
        val commitScore: Float,
        val heldMs: Long,
        val cooldownRemainingMs: Long,
        val awaitingStillHand: Boolean,
        val displacementInHandSpans: Float,
        val peakVelocitySpansPerSecond: Float,
        val sampleCount: Int,
        val movingSteps: Int,
        val reversingSteps: Int,
        val timestampMs: Long,
    ) {
        /** One-line rendering for the debug overlay and the rate-limited logcat line. */
        fun format(): String = buildString {
            append("phase=").append(phase.name)
            append(" score=").append((intentScore * 100f).toInt() / 100f)
            append('/').append((commitScore * 100f).toInt() / 100f)
            append(" cand=").append((candidateScore * 100f).toInt() / 100f)
            append(" held=").append(heldMs).append("ms")
            if (direction != null) append(" dir=").append(direction.name)
            if (holdReason != null) append(" reason=").append(holdReason.name)
            if (cooldownRemainingMs > 0L) append(" cooldown=").append(cooldownRemainingMs).append("ms")
            if (awaitingStillHand) append(" awaitingStillHand")
            append(" spans=").append((displacementInHandSpans * 100f).toInt() / 100f)
            append(" steps=").append(movingSteps).append('+').append(reversingSteps).append('-')
            append(" n=").append(sampleCount)
        }
    }

    // Track both wrist and index fingertip for more reliable swipe detection
    private val wristWindow = ArrayDeque<PositionSample>()
    private val indexTipWindow = ArrayDeque<PositionSample>()

    /**
     * The same points over a LONGER horizon, used only to judge the SHAPE of what is
     * happening around the throw (curvature and net-versus-path). A swipe and a wave can
     * be indistinguishable inside a 350 ms window - a quarter of a circle is a straight,
     * directed little throw - and differ completely over twice that.
     *
     * It is bounded by [MAX_SHAPE_SAMPLES] as well as by time, so no frame-rate pattern
     * can grow it without limit.
     */
    private val wristShape = ArrayDeque<PositionSample>()
    private val indexTipShape = ArrayDeque<PositionSample>()

    /**
     * The whole decision: phase, score, hysteresis, cooldown and the natural re-arm.
     * Nothing else in this class decides anything; the windows below only measure.
     */
    private val arbiter = SwipeIntentArbiter(
        candidateScore = config.swipeCandidateScore,
        commitScore = config.swipeCommitScore,
        cooldownMs = config.swipeCooldownMs,
    )

    /**
     * Fix S1: consecutive frames for which the pose gate has disallowed
     * swipes. The window is only wiped once this exceeds the grace period.
     */
    /**
     * Last decision/evidence pair, kept only so the debug snapshot can show what the
     * machine saw. Written from the frame thread, read by the debug screen; a stale
     * read shows one frame of lag, never a torn value (each field is independent and
     * the whole object is published as a single reference).
     */
    @Volatile
    private var lastDebugDecision: SwipeIntentArbiter.Decision? = null

    @Volatile
    private var lastDebugEvidence: SwipeEvidence? = null

    @Volatile
    private var lastDebugTimestampMs: Long = 0L

    private var disallowedFrames: Int = 0

    /** Set while the machine re-arms: the next incoming sample is a boundary, not motion. */
    private var dropNextSample: Boolean = false

    /** Last sample timestamp, used to measure the incoming frame interval. */
    private var lastSampleTimestampMs: Long = 0L

    /** Exponentially smoothed frame interval (ms) of the hand frames fed in. */
    @Volatile
    private var measuredFrameIntervalMs: Long = 0L

    /**
     * Analyses one hand frame and returns whether it completes a swipe.
     *
     * [gestureAllowed] is the pose gate from [com.aircontrol.gesture.GestureEngine]:
     * false when the user is not showing an open palm. It is a policy about which
     * gestures the app is currently willing to act on, so it stays in the caller's
     * hands; a short grace window keeps a one-frame flicker in the openness estimate
     * from wiping a swipe that is mid-flight.
     *
     * The tracked point with the stronger evidence wins (index fingertip normally,
     * wrist when the fingertip is worse), so a fingertip that escapes the frame for a
     * frame degrades the gesture instead of cancelling it.
     */
    fun process(input: HandInput, gestureAllowed: Boolean = true): SwipeResult {
        val timestampMs = input.timestampMs
        arbiter.updateThresholds(
            candidateScore = config.scaledSwipeCandidateScore(),
            commitScore = config.scaledSwipeCommitScore(),
            cooldownMs = config.swipeCooldownMs,
        )
        trackFrameInterval(timestampMs)

        if (!input.isDetected) {
            clearWindows()
            // Feed the loss to the arbiter: a live candidate is abandoned, and the
            // cooldown keeps ticking, so dropping the hand for a frame cannot be used
            // to reset the machine into firing twice for one motion.
            swipeDebugRecord(timestampMs, arbiter.decide(timestampMs, evidence = null), null)
            return SwipeResult(detected = false, confidence = 0f, hadEvidence = false)
        }

        // Fix A-11 / S1 (kept from the previous implementation, unchanged in meaning):
        // while the pose gate blocks swipes, accumulated motion is dropped rather than
        // kept, so movement made while POINTING cannot be "completed" the instant the
        // palm opens. The drop is delayed by a couple of frames because the classifier
        // flickers during a fast open-palm swipe, and an instant wipe was the "sometimes
        // nothing happens" complaint.
        if (!gestureAllowed) {
            if (++disallowedFrames > POSE_GATE_GRACE_FRAMES) {
                clearWindows()
                disallowedFrames = 0
                // The candidate is released (no evidence follows), but the cooldown and
                // the re-arm keep running: resetting the whole machine here is what let a
                // pose flicker after a swipe re-arm it instantly and fire a second time.
                swipeDebugRecord(timestampMs, arbiter.decide(timestampMs, evidence = null), null)
            }
            // Inside the grace window the arbiter is deliberately NOT fed. The
            // classifier flickers OPEN_PALM -> FOUR_FINGERS -> OPEN_PALM for a frame or
            // two in the middle of a real swipe; feeding "no evidence" there would
            // release the candidate and throw away the temporal evidence it had already
            // accumulated, which is the "sometimes it works, sometimes nothing happens"
            // complaint. Silence keeps the candidate exactly where it was.
            return SwipeResult(detected = false, confidence = 0f, hadEvidence = false)
        }
        disallowedFrames = 0

        val aspect = aspectCorrectionFor(input)
        val rawHandScale = handScaleOf(input, aspect)
        val ruler = rawHandScale.coerceIn(MIN_PLAUSIBLE_HAND_SCALE, MAX_PLAUSIBLE_HAND_SCALE)

        // Stillness is bookkept before anything else, from the raw position, so the
        // frame on which the machine re-arms can be judged as the first frame of a new
        // gesture rather than as the last frame of the old one.
        val wristForStillness = input.landmarks.getOrNull(LandmarkIndex.WRIST)
        if (wristForStillness != null &&
            wristForStillness.x.isFinite() && wristForStillness.y.isFinite()
        ) {
            if (arbiter.noteHandPosition(
                    nowMs = timestampMs,
                    xSpans = wristForStillness.x * aspect / ruler,
                    ySpans = wristForStillness.y / ruler,
                )
            ) {
                // The latch just expired while the hand came to rest. Whatever the
                // windows hold at this instant is the gesture that already fired, so
                // they are dropped and this frame reports nothing: that is what makes
                // "one physical swipe, one action" true even when the swipe outlives
                // the cooldown.
                clearWindows()
                wristShape.clear()
                indexTipShape.clear()
                // The frame that ENDED the latch is the boundary between the old motion
                // and the new one, and the sample that starts the next window is on the
                // far side of that boundary: drop it too, so the first measured step of
                // a re-armed machine is a step of the new gesture and not the tail (or
                // the jump back) of the previous one.
                dropNextSample = true
                return SwipeResult(detected = false, confidence = 0f, hadEvidence = false)
            }
        }
        val wrist = input.landmarks[LandmarkIndex.WRIST]
        val indexTip = input.landmarks[LandmarkIndex.INDEX_TIP]
        // A non-finite landmark is not a measurement of anything: it is the tracker
        // having lost the plot. Feeding it to the windows would poison the path sums for
        // as long as the window is (350 ms of real motion thrown away), so both the throw
        // and the shape horizon are dropped and this frame reports no evidence, which the
        // arbiter reads as a decay rather than as a rejection.
        if (!wrist.x.isFinite() || !wrist.y.isFinite() ||
            !indexTip.x.isFinite() || !indexTip.y.isFinite()
        ) {
            clearWindows()
            wristShape.clear()
            indexTipShape.clear()
            swipeDebugRecord(timestampMs, arbiter.decide(timestampMs, evidence = null), null)
            return SwipeResult(detected = false, confidence = 0f, hadEvidence = false)
        }
        val windowMs = effectiveWindowMs()
        if (dropNextSample) {
            dropNextSample = false
            wristWindow.clear()
            indexTipWindow.clear()
            wristShape.clear()
            indexTipShape.clear()
        }
        val wristSample = PositionSample(wrist.x, wrist.y, timestampMs)
        val tipSample = PositionSample(indexTip.x, indexTip.y, timestampMs)
        // Continuity is decided BEFORE the sample joins the windows: comparing a sample
        // against a window that already holds it finds a zero-length step, and a
        // zero-length step is indistinguishable from a stalled stream.
        //
        // A step no hand could physically make is not motion, it is a discontinuity - a
        // tracking gap, a reidentification, a resumed session - and the samples before it
        // are not part of this movement. Truncating at the jump rather than rejecting the
        // frame is what lets a hand that reappeared somewhere else be judged on what it
        // does next, which is also the natural neutral point after a commit.
        if (isDiscontinuity(wristShape, wristSample, ruler, aspect) ||
            isDiscontinuity(wristWindow, wristSample, ruler, aspect)
        ) {
            wristShape.clear()
            indexTipShape.clear()
            wristWindow.clear()
            indexTipWindow.clear()
        }
        wristWindow.addLast(wristSample)
        indexTipWindow.addLast(tipSample)
        pruneWindow(wristWindow, timestampMs, windowMs)
        pruneWindow(indexTipWindow, timestampMs, windowMs)
        wristShape.addLast(wristSample)
        indexTipShape.addLast(tipSample)
        pruneWindow(wristShape, timestampMs, windowMs * SHAPE_HORIZON_MULTIPLIER)
        pruneWindow(indexTipShape, timestampMs, windowMs * SHAPE_HORIZON_MULTIPLIER)
        while (wristShape.size > MAX_SHAPE_SAMPLES) wristShape.removeFirst()
        while (indexTipShape.size > MAX_SHAPE_SAMPLES) indexTipShape.removeFirst()

        val wristEvidence = evidenceOf(wristWindow, wristShape, rawHandScale, aspect, input)
        val tipEvidence = evidenceOf(indexTipWindow, indexTipShape, rawHandScale, aspect, input)
        val evidence = when {
            wristEvidence == null -> tipEvidence
            tipEvidence == null -> wristEvidence
            else -> {
                val wristScore = arbiter.scoreOf(wristEvidence)
                val tipScore = arbiter.scoreOf(tipEvidence)
                if (tipScore > wristScore + TIP_PREFERRED_MARGIN) tipEvidence else wristEvidence
            }
        }

        val decision = arbiter.decide(timestampMs, evidence).also { swipeDebugRecord(timestampMs, it, evidence) }

        if (decision.isCommit && decision.direction != null) {
            wristShape.clear()
            indexTipShape.clear()
            // The returning/settling hand after a commit must not read as a new swipe:
            // drop the windows and let the arbiter's stillness-based re-arm decide when
            // the next one may start.
            clearWindows()
            return SwipeResult(
                detected = true,
                direction = decision.direction,
                displacementX = evidence?.netX ?: 0f,
                displacementY = evidence?.netY ?: 0f,
                peakVelocity = evidence?.peakVelocitySpansPerSecond ?: 0f,
                confidence = decision.score,
                hadEvidence = true,
            )
        }

        return SwipeResult(
            detected = false,
            displacementX = evidence?.netX ?: 0f,
            displacementY = evidence?.netY ?: 0f,
            peakVelocity = evidence?.peakVelocitySpansPerSecond ?: 0f,
            confidence = decision.score,
            reason = decision.note?.let { mapHoldReason(it) },
            hadEvidence = evidence?.hasMeaningfulTravel(SwipeIntentArbiter.MIN_COMMIT_SPANS) == true,
        )
    }

    /** Store the pair the debug snapshot renders. Kept in one place so no decide()
     *  site can be added later without its snapshot being updated. */
    private fun swipeDebugRecord(
        nowMs: Long,
        decision: SwipeIntentArbiter.Decision,
        evidence: SwipeEvidence?,
    ) {
        lastDebugDecision = decision
        lastDebugEvidence = evidence
        lastDebugTimestampMs = nowMs
    }

    /**
     * The swipe machine's own view of the current motion, for debug instrumentation.
     * Safe to call from any thread (see the fields' `@Volatile`).
     */
    fun swipeDebugInfo(): SwipeDebugInfo {
        val decision = lastDebugDecision
        val evidence = lastDebugEvidence
        val frameMs = lastDebugTimestampMs
        return SwipeDebugInfo(
            phase = arbiter.currentPhase,
            direction = decision?.direction,
            holdReason = decision?.note,
            intentScore = decision?.score ?: 0f,
            candidateScore = arbiter.candidateScore,
            commitScore = arbiter.commitScore,
            heldMs = decision?.heldMs ?: 0L,
            cooldownRemainingMs = arbiter.cooldownRemainingMs(frameMs),
            awaitingStillHand = arbiter.isAwaitingStillHand,
            displacementInHandSpans = evidence?.displacementInHandSpans ?: 0f,
            peakVelocitySpansPerSecond = evidence?.peakVelocitySpansPerSecond ?: 0f,
            sampleCount = evidence?.sampleCount ?: 0,
            movingSteps = evidence?.movingSteps ?: 0,
            reversingSteps = evidence?.reversingSteps ?: 0,
            timestampMs = frameMs,
        )
    }

    /**
     * x/y unit reconciliation. `frameAspectRatio` is width/height of the image the
     * landmark coordinates were normalised against; multiplying every x difference by
     * it puts both axes in the same physical unit (fractions of the image height). A
     * missing or absurd value degrades to 1.0 - the pre-fix behaviour - rather than to
     * something that silently shifts the gesture thresholds.
     */
    private fun aspectCorrectionFor(input: HandInput): Float {
        val ratio = input.frameAspectRatio
        return if (ratio.isFinite() && ratio in 0.4f..2.5f) ratio else 1f
    }

    /**
     * The ruler a swipe is measured with: the user's own palm length, wrist to the base
     * of the middle finger. Palm joints rather than fingertips, because fingers curl
     * while the wrist does not.
     *
     * A hand far from the camera resolves to only a few pixels, so the measured length
     * also feeds the tracking-quality term: below [MIN_RESOLVED_HAND_SCALE] the
     * landmarks cannot be trusted to carry a direction, and the score loses the
     * quality contribution instead of the thresholds being quietly lowered.
     */
    private fun handScaleOf(input: HandInput, aspect: Float): Float {
        val landmarks = input.landmarks
        if (landmarks.size <= LandmarkIndex.MIDDLE_MCP) return MIN_RESOLVED_HAND_SCALE
        val wrist = landmarks[LandmarkIndex.WRIST]
        val middleBase = landmarks[LandmarkIndex.MIDDLE_MCP]
        val length = hypot((middleBase.x - wrist.x) * aspect, middleBase.y - wrist.y)
        return if (length.isFinite()) length else MIN_RESOLVED_HAND_SCALE
    }

    /**
     * Turns a window of positions into [SwipeEvidence]. Null means "no evidence this
     * frame", which the arbiter reads as a decay toward NEUTRAL, not as a rejection.
     */
    private fun evidenceOf(
        window: ArrayDeque<PositionSample>,
        shapeWindow: ArrayDeque<PositionSample>,
        rawHandScale: Float,
        aspect: Float,
        input: HandInput,
    ): SwipeEvidence? {
        if (window.size < SwipeEvidence.MIN_SAMPLES) return null

        // The ruler is clamped to what this camera can actually be pointed at, because
        // an implausible measurement must not move the goal posts: a palm measured at
        // 0.7 of the frame (a rendering artefact, a two-hand merge, a hand pressed
        // against the lens) would make every gesture look like a twitch, and one
        // measured at 0.005 would make a tremor look like a throw. Inside the range the
        // measurement is used as-is, which is what makes the gesture scale invariant.
        // Trustworthiness is judged on the RAW measurement, not the clamped one, so
        // clamping cannot launder an unresolvable hand into a valid gesture.
        val handScale = rawHandScale.coerceIn(MIN_PLAUSIBLE_HAND_SCALE, MAX_PLAUSIBLE_HAND_SCALE)
        // One tremor step, in the hand's own units. A hand held still by a healthy
        // adult wanders well under a tenth of a palm; a twentieth of a palm per frame
        // is the line between "this moved" and "this is noise".
        val jitter = handScale * JITTER_FRACTION_OF_HAND
        var sumX = 0f
        var sumY = 0f
        var pathLength = 0f
        var peakVelocity = 0f
        var movingSteps = 0
        var previous = window.first()
        for (i in 1 until window.size) {
            val current = window[i]
            val dx = (current.x - previous.x) * aspect
            val dy = current.y - previous.y
            sumX += dx
            sumY += dy
            val stepLength = hypot(dx, dy)
            pathLength += stepLength
            val dtMs = current.timestampMs - previous.timestampMs
            if (dtMs > 0L) {
                val spanPerSecond = stepLength / handScale / (dtMs / 1000f)
                if (spanPerSecond > peakVelocity) peakVelocity = spanPerSecond
            }
            if (stepLength > jitter) movingSteps++
            previous = current
        }

        val netX = sumX
        val netY = sumY
        val magnitude = hypot(netX, netY)
        if (!magnitude.isFinite() || magnitude <= 0f) return null

        // Path shape over the longer horizon.
        val shapeNetX = sumAxis(shapeWindow, horizontal = true, aspect)
        val shapeNetY = sumAxis(shapeWindow, horizontal = false, aspect)
        val shapePath = pathLengthOf(shapeWindow, aspect)
        val shapeNet = hypot(shapeNetX, shapeNetY)
        val shapeEfficiency = if (shapePath > jitter) {
            (shapeNet / shapePath).coerceIn(0f, 1f)
        } else {
            1f
        }
        val headingDrift = headingDriftOf(shapeWindow, aspect)
        if (!shapeNet.isFinite() || !shapeEfficiency.isFinite() || !headingDrift.isFinite()) {
            return null
        }

        val dominantIsHorizontal = abs(netX) >= abs(netY)
        val along = if (dominantIsHorizontal) abs(netX) else abs(netY)
        val across = if (dominantIsHorizontal) abs(netY) else abs(netX)
        val dominance = (along / maxOf(across, 1e-4f)).coerceAtMost(MAX_AXIS_DOMINANCE)

        val direction = if (dominance >= SwipeIntentArbiter.DOMINANCE_DIAGONAL) {
            directionFor(dominantIsHorizontal, (if (dominantIsHorizontal) netX else netY) > 0f)
        } else {
            null
        }

        // Progress and reversal, measured against the same aspect-corrected step
        // vectors, so a curved-but-progressing path scores well and a path that doubles
        // back does not.
        val unitX = if (dominantIsHorizontal) signOf(netX) else 0f
        val unitY = if (dominantIsHorizontal) 0f else signOf(netY)
        var alongSum = 0f
        var reversingSteps = 0
        previous = window.first()
        for (i in 1 until window.size) {
            val current = window[i]
            val dx = (current.x - previous.x) * aspect
            val dy = current.y - previous.y
            val projection = dx * unitX + dy * unitY
            if (projection > -jitter) {
                alongSum += abs(projection)
            }
            if (projection < -jitter) reversingSteps++
            previous = current
        }
        val consistency = if (pathLength > jitter) {
            (alongSum / pathLength).coerceIn(0f, 1f)
        } else {
            0f
        }

        val resolution = SwipeIntentArbiter.ramp(rawHandScale, MIN_RESOLVED_HAND_SCALE, TYPICAL_HAND_SCALE)

        return SwipeEvidence(
            netX = netX,
            netY = netY,
            displacementInHandSpans = magnitude / handScale,
            peakVelocitySpansPerSecond = peakVelocity,
            axisDominance = dominance,
            directionalConsistency = consistency,
            sampleCount = window.size,
            movingSteps = movingSteps,
            reversingSteps = reversingSteps,
            trackingQuality = input.confidence.coerceIn(0f, 1f),
            landmarkResolution = resolution,
            pathEfficiency = minOf(
                (magnitude / maxOf(pathLength, 1e-4f)).coerceIn(0f, 1f),
                shapeEfficiency,
            ),
            headingDriftDeg = headingDrift,
            direction = direction,
        )
    }

    /**
     * True when the step into [next] implies a hand speed no wrist can produce. A peak
     * flick is about 2.5 m/s, which for a 10 cm palm is 25 palm lengths per second; the
     * bound here is deliberately 2.5x looser than that, so only a genuine discontinuity -
     * a reidentification, a tracker jump, a resumed session - trips it and a fast user
     * never does.
     */
    private fun isDiscontinuity(
        window: ArrayDeque<PositionSample>,
        next: PositionSample,
        handScale: Float,
        aspect: Float,
    ): Boolean {
        val previous = window.lastOrNull() ?: return false
        val dtMs = next.timestampMs - previous.timestampMs
        // A non-advancing timestamp is not motion either: the sample stream went
        // backwards or stalled, which is exactly as untrustworthy as a jump.
        if (dtMs <= 0L) return true
        val distance = hypot((next.x - previous.x) * aspect, next.y - previous.y)
        if (distance / handScale / (dtMs / 1000f) > MAX_HUMAN_HAND_SPEED_SPANS_PER_S) return true
        if (window.size < 3) return false

        // The second case matters as much as the first: a tracker that re-finds the hand
        // somewhere else is not limited by how fast a hand can move, so an absolute speed
        // bound cannot see it. What it always produces is a step utterly out of scale
        // with the rest of the window AND against the direction the window is going - a
        // throw that reverses itself six times harder than its own steps is not a person
        // changing their mind, it is a different measurement. A genuine backswing before a
        // swipe is small (a fifth of the throw) and stays.
        var pathSoFar = 0f
        for (i in 1 until window.size) {
            pathSoFar += hypot(
                (window[i].x - window[i - 1].x) * aspect,
                window[i].y - window[i - 1].y,
            )
        }
        val typicalStep = pathSoFar / (window.size - 1)
        if (!typicalStep.isFinite() || typicalStep <= 0f) return false
        // "Out of scale with the window" needs a window that is actually moving: a hand
        // at rest has a typical step of nothing, and its first real movement would
        // otherwise look like an infinite outlier and wipe the gesture on every frame.
        if (typicalStep < handScale * JITTER_FRACTION_OF_HAND) return false
        if (distance <= OUTLIER_STEP_MULTIPLIER * typicalStep) return false
        val netX = window.last().x - window.first().x
        val netY = window.last().y - window.first().y
        // And a direction to oppose: without a running trend there is nothing to
        // contradict, and a lone big step is just a fast hand.
        val netLength = hypot(netX, netY)
        if (netLength < typicalStep) return false
        return ((next.x - previous.x) * netX + (next.y - previous.y) * netY) < 0f
    }

    private fun sumAxis(
        window: ArrayDeque<PositionSample>,
        horizontal: Boolean,
        aspect: Float,
    ): Float {
        var total = 0f
        for (i in 1 until window.size) {
            val dx = window[i].x - window[i - 1].x
            val dy = window[i].y - window[i - 1].y
            total += (if (horizontal) dx * aspect else dy)
        }
        return total
    }

    private fun pathLengthOf(window: ArrayDeque<PositionSample>, aspect: Float): Float {
        var total = 0f
        for (i in 1 until window.size) {
            total += hypot(
                (window[i].x - window[i - 1].x) * aspect,
                window[i].y - window[i - 1].y,
            )
        }
        return total
    }

    /** Unsigned turn accumulated at every joint of the path, in degrees. */
    private fun headingDriftOf(window: ArrayDeque<PositionSample>, aspect: Float): Float {
        if (window.size < 3) return 0f
        var drift = 0.0
        for (i in 2 until window.size) {
            val ax = (window[i - 1].x - window[i - 2].x) * aspect
            val ay = window[i - 1].y - window[i - 2].y
            val bx = (window[i].x - window[i - 1].x) * aspect
            val by = window[i].y - window[i - 1].y
            val cross = ax * by - ay * bx
            val dot = ax * bx + ay * by
            if (abs(cross) > 1e-6f || dot != 0f) {
                drift += abs(kotlin.math.atan2(cross.toDouble(), dot.toDouble()))
            }
        }
        return Math.toDegrees(drift).toFloat()
    }

    /** Maps the arbiter's hold reason onto the pre-existing telemetry vocabulary. */
    private fun mapHoldReason(reason: SwipeHoldReason): SwipeRejectReason? = when (reason) {
        SwipeHoldReason.NO_TRAVEL -> SwipeRejectReason.BELOW_DISPLACEMENT
        SwipeHoldReason.LOW_VELOCITY -> SwipeRejectReason.TOO_SLOW
        SwipeHoldReason.AMBIGUOUS_AXIS -> SwipeRejectReason.DIAGONAL_AMBIGUOUS
        SwipeHoldReason.INCONSISTENT_PATH -> SwipeRejectReason.INCONSISTENT_DIRECTION
        SwipeHoldReason.INSUFFICIENT_SAMPLES -> SwipeRejectReason.TOO_FEW_MOVING_STEPS
        // Not reportable as a rejection: these frames carry no candidate to reject.
        SwipeHoldReason.LOW_TRACKING_QUALITY,
        SwipeHoldReason.COOLDOWN,
        SwipeHoldReason.LOST_MID_GESTURE,
        -> null
    }

    /** The one place that names an axis, so the arbiter and the legacy helper cannot disagree. */
    private fun directionFor(horizontal: Boolean, positive: Boolean): SwipeDirection = when {
        horizontal && positive -> SwipeDirection.RIGHT
        horizontal -> SwipeDirection.LEFT
        positive -> SwipeDirection.DOWN
        else -> SwipeDirection.UP
    }

    private fun signOf(value: Float): Float = if (value >= 0f) 1f else -1f

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




    /** Resets the detector state. */
    fun reset() {
        clearWindows()
        lastSampleTimestampMs = 0L
        measuredFrameIntervalMs = 0L
        disallowedFrames = 0
        dropNextSample = false
        arbiter.reset()
    }

    /**
     * Drops the measured positions without touching the machine's phase, so a commit
     * cannot re-read the returning hand as a new throw while the re-arm is running.
     * The shape horizons are cleared alongside it at the two moments that bound one
     * gesture (the commit, and the re-arm that follows it): their only job is to
     * describe the shape of the motion being judged right now, and once the machine has
     * decided to listen again, the previous motion is not context, it is contamination.
     */
    private fun clearWindows() {
        wristWindow.clear()
        indexTipWindow.clear()
        // The shape horizon is NOT cleared: it is what tells a returning hand from a
        // new throw, and dropping it at the commit is exactly when the information is
        // needed. It ages out on its own.
    }

    /**
     * Exponentially smoothed inter-frame interval of the hand frames fed in, weighted
     * 3:1 towards the past. It exists so the analysis window can be stretched at low
     * frame rates instead of silently holding fewer samples - and so a window is never
     * judged by a sample count the tracker could not physically deliver.
     */
    private fun trackFrameInterval(nowMs: Long) {
        if (lastSampleTimestampMs > 0L) {
            val interval = (nowMs - lastSampleTimestampMs).coerceIn(1L, 1000L)
            measuredFrameIntervalMs =
                if (measuredFrameIntervalMs == 0L) interval
                else ((measuredFrameIntervalMs * 3 + interval) / 4)
        }
        lastSampleTimestampMs = nowMs
    }

    companion object {
        // Minimum samples for a trustworthy swipe. Fix A-14: 4 was impossible to
        // reach in 5 fps scan mode (a 500ms window only holds 2-3 samples), which
        // silently disabled swipes whenever the phone throttled. 3 is the floor and
        // stays the floor: the window is stretched to the frame rate instead, so a low
        // frame rate never raises the bar on its own.
        private const val MIN_SAMPLES_FOR_SWIPE = 3

        // Fix S1: how many consecutive pose-gated frames tolerate keeping the
        // swipe window before it is wiped. 2 frames (~80ms at 24fps) absorbs
        // classifier flicker during fast palm swipes without letting a
        // pointing-hand sweep complete later.
        private const val POSE_GATE_GRACE_FRAMES = 2

        /**
         * The fingertip is the primary tracker because it amplifies a throw, but it is
         * also the point that leaves the frame and jitters most. It therefore has to be
         * *clearly* better than the wrist before it wins, otherwise the wrist's calmer
         * evidence is the better description of the same motion.
         */
        private const val TIP_PREFERRED_MARGIN = 0.05f

        /**
         * Smallest palm length (wrist to middle-finger base, as a fraction of image
         * height) the detector will treat as a measurable hand. Below it a "swipe"
         * would be a couple of pixels of landmark noise.
         */
        private const val MIN_RESOLVED_HAND_SCALE = 0.045f

        /** Lower bound of the ruler: about the size of a hand at arm's length here. */
        private const val MIN_PLAUSIBLE_HAND_SCALE = 0.06f

        /** Upper bound: a palm larger than this is not a hand at a usable distance. */
        private const val MAX_PLAUSIBLE_HAND_SCALE = 0.22f

        /** Palm length at which landmark resolution is considered fully reliable. */
        private const val TYPICAL_HAND_SCALE = 0.11f

        /** Per-frame motion below a twentieth of a palm is tremor, not travel. */
        private const val JITTER_FRACTION_OF_HAND = 0.055f

        /** Axis dominance is capped: past 4:1 the extra margin carries no information. */
        private const val MAX_AXIS_DOMINANCE = 4f

        /** The shape horizon is twice the throw window; see [wristShape]. */
        private const val SHAPE_HORIZON_MULTIPLIER = 2L

        /** Hard bound on the shape horizon, independent of frame rate. */
        private const val MAX_SHAPE_SAMPLES = 24

        /** See [isDiscontinuity]. */
        private const val MAX_HUMAN_HAND_SPEED_SPANS_PER_S = 60f

        /** A step must be this much bigger than the window's own typical step to count
         *  as a discontinuity rather than as a fast movement. */
        private const val OUTLIER_STEP_MULTIPLIER = 4f

    }

    /**
     * H-06 Fix: Update the config (e.g., sensitivity change) without recreating the detector.
     * This preserves the current sliding window state and avoids losing in-progress swipe detection.
     */
    fun updateConfig(newConfig: GestureEngineConfig) {
        this.config = newConfig
        // The cooldown and both score levels are re-read from the config on the next
        // frame (see process); caching them here is what let a sensitivity change sit
        // unapplied in the earlier implementation.
    }
}
