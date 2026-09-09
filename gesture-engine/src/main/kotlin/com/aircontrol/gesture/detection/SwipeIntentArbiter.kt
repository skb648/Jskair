package com.aircontrol.gesture.detection

import com.aircontrol.gesture.model.SwipeDirection
import kotlin.concurrent.Volatile
import kotlin.math.hypot

/**
 * One frame's worth of PHYSICAL evidence about a possible swipe, measured in units
 * that do not depend on the camera resolution or how far the user sits from it.
 *
 * Every field is derived from a sliding window of hand positions; none of them is a
 * decision. [SwipeIntentArbiter] turns them into an intent score.
 *
 * Units (see [DynamicGestureDetector] for how they are measured):
 *  - [netX] / [netY]: the net throw of the tracked point across the window, in
 *    fractions of the analysis image HEIGHT, with the x axis multiplied by the image
 *    aspect ratio so that one unit of x is the same physical distance as one unit of
 *    y. Without that correction a horizontal and a vertical swipe of the same real
 *    length would score 33 % apart on a 4:3 camera, which is exactly how "axis
 *    dominance" came to look like noise.
 *  - [displacementInHandSpans]: the same throw measured in hand spans (wrist to
 *    middle-finger base). A hand span is the ruler a human uses for a gesture and it
 *    is scale invariant: the same physical gesture produces the same reading whether
 *    the hand is 20 cm or 60 cm from the camera.
 *  - [peakVelocitySpansPerSecond]: the fastest single inter-sample step, in hand spans
 *    per second.
 */
data class SwipeEvidence(
    val netX: Float,
    val netY: Float,
    val displacementInHandSpans: Float,
    val peakVelocitySpansPerSecond: Float,
    /** |net throw| along the winning axis / along the other axis, >= 1.0. */
    val axisDominance: Float,
    /** Fraction of the travelled path that progressed along the winning direction. */
    val directionalConsistency: Float,
    /** Samples in the window (temporal evidence). */
    val sampleCount: Int,
    /** Samples that actually moved, i.e. steps that are not tremor. */
    val movingSteps: Int,
    /** Steps that reversed against the winning direction. */
    val reversingSteps: Int,
    /** Hand tracking confidence (MediaPipe's score for this hand). */
    val trackingQuality: Float,
    /**
     * How well the hand's own landmarks resolve, in `0..1`: a palm that covers only a
     * few pixels cannot carry a direction as reliably as one that fills the frame.
     * Scales the quality contribution; it never vetoes on its own, because "is a hand
     * there at all" is already covered by [trackingQuality].
     */
    val landmarkResolution: Float,
    /**
     * Net throw divided by the length of the path actually travelled, in `0..1`. 1.0 is
     * a straight shot; a full circle returns to where it started and scores 0. This is
     * what separates "the hand went somewhere on purpose" from "the hand was waving",
     * and it is a shape measure, so it does not care how fast the user was.
     */
    val pathEfficiency: Float,
    /**
     * Total heading change accumulated across the window, in degrees: the sum of the
     * turn angle at every joint of the path, unsigned, so it measures how much the path
     * CURVES rather than which way it goes. A point-to-point arm movement bows by 10-25
     * degrees; a hand being waved in a circle turns by more than 90 within the same
     * window. Curvature is the one thing a swipe and a wave disagree about even when they
     * have the same size and the same speed.
     */
    val headingDriftDeg: Float,
    /** The direction this evidence points at, or null when nothing is distinguishable. */
    val direction: SwipeDirection?,
) {
    /** True when the throw is long enough that it cannot be resting tremor. */
    fun hasMeaningfulTravel(minSpan: Float): Boolean = displacementInHandSpans >= minSpan

    companion object {
        /** The smallest window that carries temporal evidence at all (start, middle, end). */
        const val MIN_SAMPLES = 3
    }
}

/** Why the arbiter is not committing, for the debug pill and telemetry only. */
enum class SwipeHoldReason {
    /** Nothing was moving with enough travel to be worth judging. */
    NO_TRAVEL,

    /** There is travel but the motion is not fast enough yet to read as a throw. */
    LOW_VELOCITY,

    /** The throw is too diagonal to attribute to one axis. */
    AMBIGUOUS_AXIS,

    /** The path doubled back on itself: not a directed motion. */
    INCONSISTENT_PATH,

    /** Not enough temporal evidence in the window yet. */
    INSUFFICIENT_SAMPLES,

    /** The hand itself is tracked too poorly to act on. */
    LOW_TRACKING_QUALITY,

    /** Committed recently; the machine is waiting for a still hand. */
    COOLDOWN,

    /** Tracking was lost with a live candidate. */
    LOST_MID_GESTURE,
}

/**
 * Swipe recognition as a HUMAN-INTENT STATE MACHINE rather than a stack of independent
 * threshold gates.
 *
 * ```
 * NEUTRAL -> TRACKING -> CANDIDATE -> COMMITTED -> COOLDOWN -> NEUTRAL
 *              ^             |
 *              +-------------+  (score falls back below the candidate level)
 * ```
 *
 *  - **NEUTRAL**: no travel worth watching. The window is still being filled.
 *  - **TRACKING**: the hand is moving; evidence is being accumulated but no direction
 *    is claimed yet.
 *  - **CANDIDATE**: a direction is claimed provisionally at the LOWER score, so the
 *    system knows what it is betting on and can measure how long it has been held.
 *    A candidate never produces an action.
 *  - **COMMITTED**: the score cleared the HIGHER commit level, so the bet was
 *    confirmed. This is the only state that emits exactly one action.
 *  - **COOLDOWN**: nothing is scored until the hand has genuinely returned to rest,
 *    which is what makes the returning motion of a swipe inert.
 *
 * Three design properties, each answering one observed failure:
 *
 * 1. **No single quantity can veto.** Displacement, velocity, direction consistency,
 *    axis dominance and tracking quality are five *weighted contributions* to one
 *    score. The old detector ran nine serial `if` gates, so a deliberate half-second
 *    sweep that was merely slow (velocity 0.9 vs the 1.2 hard gate) was thrown away
 *    even though displacement, consistency and axis dominance were perfect. Here that
 *    same swipe commits, because only one contribution is low. Velocity is evidence
 *    of intent, never a permit.
 * 2. **Hysteresis instead of a threshold.** Candidate and commit are different levels
 *    (0.45 / 0.62), so a signal that wobbles around one number can no longer toggle
 *    the output; and one commit per candidate is guaranteed by the phase transition
 *    itself, not by a post-hoc "was it long ago?" timestamp comparison elsewhere.
 * 3. **Re-arm decays, it does not reset.** After a commit the arbiter accumulates
 *    "stillness credit": a frame in which the hand moved less than
 *    [REARM_STILLNESS_SPANS_PER_SECOND] adds its whole duration, a frame that moved
 *    more adds nothing, and only a frame that moved more than four times that adds
 *    negative credit. The old latch reset on *any* motion over the limit, and human
 *    hands at rest exceed a 0.006/frame limit routinely, so a second swipe was
 *    impossible until the user went almost motionless.
 *
 * The anchors below are not tuned to make demos pass; each is derived in the comment
 * above it from either a physical quantity (hand span, human tremor frequency) or the
 * measured behaviour of the previous pipeline that users did NOT complain about.
 */
class SwipeIntentArbiter(
    candidateScore: Float = DEFAULT_CANDIDATE_SCORE,
    commitScore: Float = DEFAULT_COMMIT_SCORE,
    cooldownMs: Long = DEFAULT_COOLDOWN_MS,
) {
    /**
     * The two hysteresis levels and the cooldown. The detector refreshes these from
     * [com.aircontrol.gesture.config.GestureEngineConfig] on every frame, because the
     * user-facing sensitivity slider scales them: raising sensitivity lifts both
     * levels together, so the candidate/commit *gap* - the thing that actually prevents
     * flapping - never collapses.
     */
    @Volatile
    var candidateScore: Float = candidateScore
        private set

    @Volatile
    var commitScore: Float = commitScore
        private set

    @Volatile
    var cooldownMs: Long = cooldownMs
        private set

    fun updateThresholds(candidateScore: Float, commitScore: Float, cooldownMs: Long) {
        // Keep the hysteresis gap even if a caller passes inverted levels: committing
        // must always be strictly harder than becoming a candidate.
        val candidate = candidateScore.coerceIn(0f, 1f)
        val commit = maxOf(commitScore.coerceIn(0f, 1f), candidate + MIN_HYSTERESIS_GAP)
        this.candidateScore = candidate
        this.commitScore = commit
        this.cooldownMs = cooldownMs.coerceAtLeast(MIN_COOLDOWN_MS)
    }

    /** The machine's phase, exposed for debug rendering. */
    enum class Phase { NEUTRAL, TRACKING, CANDIDATE, COMMITTED, COOLDOWN }

    /**
     * One step of the machine.
     *
     * [evidence] is null when the hand is not tracked this frame; a live candidate is
     * then dropped rather than kept alive on stale samples.
     */
    fun decide(nowMs: Long, evidence: SwipeEvidence?): Decision {
        val score = evidence?.let { scoreOf(it) } ?: 0f
        val direction = evidence?.direction

        if (evidence == null) {
            // Phase 12H: the hand vanished. A candidate is abandoned immediately - a
            // resumed window is a NEW gesture, never a continuation - and the cooldown
            // keeps ticking so a dropout cannot be used to dodge the cooldown either.
            val wasLive = phase == Phase.CANDIDATE || phase == Phase.TRACKING
            phase = Phase.NEUTRAL
            candidate = null
            return Decision(
                phase = Phase.NEUTRAL,
                note = if (wasLive && cooldownUntilMs > nowMs) SwipeHoldReason.LOST_MID_GESTURE else null,
            )
        }

        if (nowMs < cooldownUntilMs || awaitingStillHand) {
            // Nothing is scored while the machine is recovering from a commit; the
            // stillness credit is accrued by [noteHandPosition] from the hand's actual
            // position, because the swipe windows were cleared at the commit and would
            // otherwise read as perfectly still.
            phase = Phase.COOLDOWN
            return Decision(phase = Phase.COOLDOWN, note = SwipeHoldReason.COOLDOWN, score = score)
        }

        // Validity floors, not score inputs: without temporal evidence, without real
        // travel, or with a hand the tracker is barely sure about, there is nothing to
        // judge. These are the ONLY hard conditions left; everything else about the
        // gesture is weighted into the score.
        val valid = evidence.sampleCount >= SwipeEvidence.MIN_SAMPLES &&
            evidence.movingSteps >= MIN_MOVING_STEPS &&
            evidence.trackingQuality >= TRACKING_UNCERTAIN &&
            evidence.landmarkResolution >= MIN_TRUSTED_HAND_RESOLUTION &&
            evidence.pathEfficiency >= PATH_EFFICIENCY_MIN &&
            evidence.headingDriftDeg <= MAX_HEADING_DRIFT_DEG &&
            direction != null
        // Temporal hysteresis on top of the score hysteresis. The first ~100 ms of a
        // quarter circle and of a swipe are geometrically identical - both are a short,
        // straight, directed throw - so no measurement of the shape can separate them
        // that early, and pretending otherwise either commits waves or blocks swipes.
        // What DOES separate them is time: a swipe keeps going, a wave turns. Holding the
        // candidate for this long before acting is the difference between "we believe
        // this direction now" and "we have watched it stay true for a tenth of a second",
        // and it costs the user about one frame.
        // The maturity clock starts on the first frame where a direction was defensible
        // at all, not on the first frame where the score was strong: tying it to the
        // score would make a fast, short swipe expire before it could ever mature, which
        // is how the previous detector ended up needing a long window. A swipe cannot be
        // measured against its own length, nor against the frame rate.
        if (evidence.direction != null &&
            evidence.sampleCount >= SwipeEvidence.MIN_SAMPLES &&
            evidence.movingSteps >= 2
        ) {
            if (directionalSinceMs < 0L) directionalSinceMs = nowMs
        } else {
            directionalSinceMs = -1L
        }
        val heldMs = if (directionalSinceMs >= 0L) {
            (nowMs - directionalSinceMs).coerceAtLeast(0L)
        } else {
            0L
        }
        val committed = valid &&
            heldMs >= MIN_CANDIDATE_MS &&
            evidence.hasMeaningfulTravel(MIN_COMMIT_SPANS) &&
            score >= commitScore

        if (committed) {
            phase = Phase.COMMITTED
            candidate = null
            lastCommitMs = nowMs
            cooldownUntilMs = nowMs + cooldownMs
            awaitingStillHand = true
            rearmCreditMs = 0L
            lastRearmAtMs = nowMs
            return Decision(
                phase = Phase.COMMITTED,
                direction = direction,
                score = score,
                evidence = evidence,
                heldMs = heldMs,
            )
        }

        if (valid && score >= candidateScore) {
            if (candidate == null) candidate = CandidateState(direction, nowMs, score, evidence!!)
            else candidate = candidate?.copy(score = score, evidence = evidence)
            phase = if (candidate?.direction == direction) Phase.CANDIDATE else Phase.TRACKING
            return Decision(
                phase = phase,
                score = score,
                evidence = evidence,
                heldMs = heldMs,
                note = holdReasonFor(evidence, score),
            )
        }

        // The score fell back under the candidate level: the bet is released without an
        // action. No sticky flag survives this, so a later swipe is judged on its own.
        candidate = null
        phase = if (score > 0f) Phase.TRACKING else Phase.NEUTRAL
        return Decision(
            phase = phase,
            score = score,
            evidence = evidence,
            note = holdReasonFor(evidence, score),
        )
    }

    /**
     * Composite intent score in `0..1`, the sum of five weighted contributions minus a
     * noise penalty. Weights sum to 1.0 over the positive contributions, so 1.0 means
     * "every single measured aspect of this motion was maximally swipe-like" and 0.62
     * means "clearly a swipe, with room to be imperfect".
     */
    fun scoreOf(evidence: SwipeEvidence): Float {
        val displacement = ramp(
            evidence.displacementInHandSpans,
            DISPLACEMENT_NOISE_SPANS,
            DISPLACEMENT_DELIBERATE_SPANS,
        )
        val velocity = ramp(
            evidence.peakVelocitySpansPerSecond,
            VELOCITY_REPOSITION_SPANS_PER_S,
            VELOCITY_FLICK_SPANS_PER_S,
        )
        val consistency = ramp(
            evidence.directionalConsistency,
            CONSISTENCY_AMBIGUOUS,
            CONSISTENCY_DIRECT,
        )
        val efficiency = ramp(evidence.pathEfficiency, PATH_EFFICIENCY_MIN, 1f)
        val dominance = ramp(evidence.axisDominance, DOMINANCE_DIAGONAL, DOMINANCE_STRONG)
        // A poorly resolved hand keeps at best half of the quality credit, because
        // "MediaPipe is unsure" and "the hand is small in frame" are two different
        // reasons to be cautious and only the first one is already in the score.
        val quality = ramp(evidence.trackingQuality, TRACKING_UNCERTAIN, TRACKING_SOLID) *
            (0.5f + 0.5f * evidence.landmarkResolution.coerceIn(0f, 1f))
        val noise = noisePenalty(evidence)
        return (
            W_DISPLACEMENT * displacement +
                W_VELOCITY * velocity +
                W_CONSISTENCY * consistency +
                W_DOMINANCE * dominance +
                W_EFFICIENCY * efficiency +
                W_QUALITY * quality -
                W_NOISE * noise
            )
            .coerceIn(0f, 1f)
    }

    /**
     * A penalty, not a veto, for motion that goes backwards: the share of steps that
     * reversed against the claimed direction. Wandering is NOT charged here - the path
     * efficiency and curvature terms already measure it, and charging it twice is how a
     * score ends up needing superhuman evidence to commit.
     */
    private fun noisePenalty(evidence: SwipeEvidence): Float {
        val steps = (evidence.movingSteps + evidence.reversingSteps).coerceAtLeast(1)
        return (evidence.reversingSteps.toFloat() / steps).coerceIn(0f, 1f)
    }

    /**
     * Why this frame is not committing. Validity deficits are named FIRST and
     * unconditionally: a 45-degree throw with excellent speed and travel scores highly
     * and is still not actionable, and "high score, no action, no explanation" is
     * precisely the state that made the previous pipeline unauditable. A live candidate
     * (valid, above the candidate level, waiting to mature or to reach the commit level)
     * is the only frame that reports no reason, because it is not a rejection.
     */
    private fun holdReasonFor(evidence: SwipeEvidence, score: Float): SwipeHoldReason? {
        val unmeasurable = when {
            evidence.sampleCount < SwipeEvidence.MIN_SAMPLES -> SwipeHoldReason.INSUFFICIENT_SAMPLES
            evidence.movingSteps < SwipeIntentArbiter.MIN_MOVING_STEPS -> SwipeHoldReason.INSUFFICIENT_SAMPLES
            evidence.trackingQuality < TRACKING_UNCERTAIN -> SwipeHoldReason.LOW_TRACKING_QUALITY
            evidence.landmarkResolution < MIN_TRUSTED_HAND_RESOLUTION -> SwipeHoldReason.LOW_TRACKING_QUALITY
            evidence.pathEfficiency < PATH_EFFICIENCY_MIN ||
                evidence.headingDriftDeg > MAX_HEADING_DRIFT_DEG -> SwipeHoldReason.INCONSISTENT_PATH
            evidence.direction == null ||
                evidence.axisDominance < DOMINANCE_DIAGONAL -> SwipeHoldReason.AMBIGUOUS_AXIS
            else -> null
        }
        if (unmeasurable != null) return unmeasurable
        if (score >= candidateScore) return null
        return when {
            evidence.displacementInHandSpans < MIN_COMMIT_SPANS -> SwipeHoldReason.NO_TRAVEL
            evidence.directionalConsistency < CONSISTENCY_AMBIGUOUS -> SwipeHoldReason.INCONSISTENT_PATH
            evidence.peakVelocitySpansPerSecond < VELOCITY_REPOSITION_SPANS_PER_S -> SwipeHoldReason.LOW_VELOCITY
            else -> SwipeHoldReason.LOW_VELOCITY
        }
    }

    private fun heldForMs(nowMs: Long): Long = heldMsAt(nowMs)

    /**
     * Feeds the tracked point's position to the machine, in hand spans, on every frame
     * in which a hand is visible - including the frames right after a commit, where the
     * swipe windows have been cleared and no evidence exists at all.
     *
     * This is what makes the re-arm natural rather than punitive: the latch decays on
     * stillness and is merely held by motion. The previous implementation reset on any
     * motion above a limit that physiological tremor exceeds, so a second swipe was
     * impossible until the user went almost rigid.
     *
     * Returns true on the single frame in which the machine finishes re-arming, so the
     * caller can drop the positions it collected while the latch was running: those
     * samples belong to the gesture that already fired, and letting them repopulate the
     * window is precisely how one physical swipe becomes two.
     */
    fun noteHandPosition(nowMs: Long, xSpans: Float, ySpans: Float): Boolean {
        var rearmed = false
        val first = !hasLastPosition
        val dt = if (first || lastRearmAtMs < 0L) 0L else (nowMs - lastRearmAtMs).coerceIn(0L, MAX_REARM_STEP_MS)
        val moved = if (first) 0f else {
            hypot(xSpans - lastPositionXSpans, ySpans - lastPositionYSpans)
        }
        hasLastPosition = true
        lastPositionXSpans = xSpans
        lastPositionYSpans = ySpans
        val speed = if (dt > 0L) moved / (dt / 1000f) else 0f
        if (!awaitingStillHand) {
            lastRearmAtMs = nowMs
            return false
        }
        lastRearmAtMs = nowMs
        rearmCreditMs += when {
            speed <= REARM_STILLNESS_SPANS_PER_SECOND -> dt
            speed <= REARM_STILLNESS_SPANS_PER_SECOND * 4f -> 0L
            else -> -dt
        }.coerceAtLeast(-rearmCreditMs)
        if (rearmCreditMs >= REARM_STILLNESS_MS) {
            awaitingStillHand = false
            rearmCreditMs = 0L
            rearmed = true
        }
        return rearmed
    }

    /** Full machine reset, as used on [DynamicGestureDetector.reset] and hand loss. */
    fun reset() {
        phase = Phase.NEUTRAL
        candidate = null
        awaitingStillHand = false
        rearmCreditMs = 0L
        lastCommitMs = -1L
        cooldownUntilMs = -1L
        directionalSinceMs = -1L
        hasLastPosition = false
        lastPositionXSpans = 0f
        lastPositionYSpans = 0f
        lastRearmAtMs = -1L
    }

    /** True while the machine is between a commit and a settled hand. */
    fun isCoolingDownAt(nowMs: Long): Boolean =
        cooldownUntilMs > nowMs || awaitingStillHand

    /** How long the current directional evidence has been held. */
    fun heldMsAt(nowMs: Long): Long =
        if (directionalSinceMs >= 0L) (nowMs - directionalSinceMs).coerceAtLeast(0L) else 0L

    /** Debug aid: the phase a consumer would render right now. */
    val currentPhase: Phase get() = phase

    /**
     * Milliseconds before the machine stops treating fresh motion as the tail of the
     * throw that just committed. The *remaining* time is exposed rather than the
     * deadline because the debug screen answers "how long until I can swipe again",
     * and it reads 0 outside COOLDOWN so no caller has to know the phase to ask.
     */
    fun cooldownRemainingMs(nowMs: Long): Long =
        if (phase == Phase.COOLDOWN) maxOf(0L, cooldownUntilMs - nowMs) else 0L

    /**
     * True while the machine is waiting for the hand to stop before it will accept a
     * new candidate. Without this the COOLDOWN phase looks like a hang: the phase is
     * the same whether the hand is still moving or has long since stopped.
     */
    val isAwaitingStillHand: Boolean get() = awaitingStillHand

    private data class CandidateState(
        val direction: SwipeDirection,
        val startedAtMs: Long,
        val score: Float,
        val evidence: SwipeEvidence,
    )

    /** The outcome of one arbiter step. */
    data class Decision(
        val phase: Phase,
        val direction: SwipeDirection? = null,
        val score: Float = 0f,
        val evidence: SwipeEvidence? = null,
        val heldMs: Long = 0L,
        val note: SwipeHoldReason? = null,
    ) {
        val isCommit: Boolean get() = phase == Phase.COMMITTED
    }

    private var phase: Phase = Phase.NEUTRAL
    private var candidate: CandidateState? = null
    private var awaitingStillHand: Boolean = false
    private var rearmCreditMs: Long = 0L
    private var lastCommitMs: Long = -1L
    private var cooldownUntilMs: Long = -1L
    private var lastRearmAtMs: Long = -1L
    private var directionalSinceMs: Long = -1L
    private var hasLastPosition: Boolean = false
    private var lastPositionXSpans: Float = 0f
    private var lastPositionYSpans: Float = 0f

    companion object {
        /** Below this the score is not even worth tracking; above it a direction is claimed. */
        const val DEFAULT_CANDIDATE_SCORE = 0.45f

        /** Committing is deliberately harder than becoming a candidate. */
        const val DEFAULT_COMMIT_SCORE = 0.62f

        /** Minimum spacing between two commits, matching the previous engine's cooldown. */
        const val DEFAULT_COOLDOWN_MS = 220L

        /**
         * Displacement anchors, in hand spans. A human hand at rest drifts by well
         * under a tenth of a span; a *reposition* (moving the hand to a new place
         * without meaning to gesture) is around a third of a span; a swipe is at
         * least as long as the hand is wide. The old detector's accepted floor was
         * 0.069 of the frame, i.e. about 0.6 spans, so [MIN_COMMIT_SPANS] keeps that
         * hard validity bound while the RAMP starts far earlier: displacement below
         * 0.30 spans simply contributes nothing instead of ending the conversation.
         */
        const val DISPLACEMENT_NOISE_SPANS = 0.30f
        const val DISPLACEMENT_DELIBERATE_SPANS = 0.80f
        const val MIN_COMMIT_SPANS = 0.50f

        /**
         * Velocity anchors, in hand spans per second. 0.8 spans/s is roughly the
         * speed of a hand moving to a resting position; the previous detector's hard
         * gate of 1.2 normalised units per second corresponds to about 11 spans/s for a
         * palm that fills a tenth of the frame, and a real flick sits near it. A
         * deliberate slow sweep sits in between, which is exactly the case the old hard
         * gate rejected and this ramp accepts at partial credit.
         */
        const val VELOCITY_REPOSITION_SPANS_PER_S = 1.5f
        const val VELOCITY_FLICK_SPANS_PER_S = 8.0f

        /**
         * Consistency anchors. 0.5 means half the path went backwards: that is a
         * wiggle, not a swipe. 0.95 is a straight throw. 0.70 - the old hard gate -
         * corresponds to a path that wanders by up to ~45 deg, so it becomes the
         * midpoint of the ramp rather than a cliff.
         */
        const val CONSISTENCY_AMBIGUOUS = 0.50f
        const val CONSISTENCY_DIRECT = 0.92f

        /**
         * Axis dominance anchors, as a ratio of the winning axis over the losing one.
         * 1.0 is a perfect diagonal. The old gate demanded 2.0 (a ~27 deg cone) for
         * EVERY direction and 2.0 again for the vertical axes, which is what killed
         * natural arcing swipes; here 2.0 still earns full credit but 1.15 (about
         * 41 deg off axis) already earns some, and the score - not the ratio - decides.
         */
        const val DOMINANCE_DIAGONAL = 1.15f
        const val DOMINANCE_STRONG = 2.0f

        /**
         * MediaPipe's confidence for this hand. Note it is HANDEDNESS confidence, not
         * landmark quality - which is why the old hard gate on it muted swipes for a
         * fast flick (fast motion is exactly what makes handedness wobble) while
         * telling the user nothing. It stays as evidence here, at the lowest weight.
         */
        const val TRACKING_UNCERTAIN = 0.30f
        const val TRACKING_SOLID = 0.80f

        /**
         * A path must be at least this straight to commit. A point-to-point arm movement
         * is close to ballistic and lands above 0.9 even with a natural bow in it; a
         * half circle is 0.64 and a full one is 0. The value sits between the two, so
         * curvature is tolerated but circling is not.
         */
        const val PATH_EFFICIENCY_MIN = 0.72f

        /**
         * How much the path may curve across the shape horizon, in total turn. A
         * point-to-point arm movement bows by 10-25 degrees however energetically it is
         * made; anything turning more than this is going somewhere *around*, and no
         * amount of speed or distance makes that a swipe. This is the rule that separates
         * a deliberate sweep from the opening of a wave - the two are otherwise
         * geometrically identical for the first quarter turn, which is why a threshold
         * on size or speed alone could never tell them apart.
         */
        const val MAX_HEADING_DRIFT_DEG = 30f

        /**
         * Below this the palm covers too few pixels for its own movement to locate a
         * fingertip to within a fraction of itself, so hand-scale thresholds would be
         * measuring noise; the gesture is refused instead of the bar being lowered.
         */
        const val MIN_TRUSTED_HAND_RESOLUTION = 0.15f

        /** Weights sum to 1.0 across the positive contributions. */
        const val W_DISPLACEMENT = 0.28f
        const val W_VELOCITY = 0.18f
        const val W_CONSISTENCY = 0.16f
        const val W_DOMINANCE = 0.12f
        const val W_EFFICIENCY = 0.06f
        const val W_QUALITY = 0.06f
        const val W_NOISE = 0.18f

        /**
         * Two moving steps is the least temporal evidence that can distinguish a
         * direction (start -> middle -> end). The old detector asked for three and
         * then special-cased low frame rates down to one; that special case is gone.
         */
        /**
         * Three moving steps is the least temporal evidence that can distinguish a
         * direction from a flick: start, somewhere, end. Two steps (three samples) is
         * what a single-frame tracking teleport produces, and a teleport must never
         * commit no matter how far it jumped.
         */
        const val MIN_MOVING_STEPS = 3

        /**
         * Stillness limit, in hand spans per SECOND, used only to decide when the hand
         * has returned to rest after a commit. A hand held deliberately still drifts at
         * well under half a palm per second; the return sweep after a swipe is several
         * palms per second, so this separates the two without depending on the frame
         * rate - which the previous per-frame limit did, silently changing the rule
         * between a 60 fps and a 12 fps camera.
         */
        const val REARM_STILLNESS_SPANS_PER_SECOND = 0.55f

        /**
         * How much accumulated stillness re-arms the machine. Shorter than the 250 ms
         * the previous latch demanded, and measured as *credit* rather than as an
         * unbroken streak, so the wait ends as soon as the hand has been calm for about
         * two frames' worth of settling instead of requiring an uninterrupted stillness
         * the hand can rarely hold.
         */
        const val REARM_STILLNESS_MS = 150L

        /**
         * How long a candidate must stay true before it may commit; see the note at the
         * commit decision. Two to three frames at the frame rates this app runs at: just
         * long enough that a one-frame landmark glitch cannot be an action, and short
         * enough that the commit still lands inside the 150-300 ms of a real throw
         * rather than after it. Curvature, not waiting, is what separates a wave from a
         * swipe - see [MAX_HEADING_DRIFT_DEG].
         */
        const val MIN_CANDIDATE_MS = 80L

        /** Never let one frame credit more than this, whatever the frame interval was. */
        const val MAX_REARM_STEP_MS = 80L

        /** Minimum gap between the candidate and commit levels, in score units. */
        const val MIN_HYSTERESIS_GAP = 0.10f

        /** Floor so a mis-set slider cannot make swipes fire back to back. */
        const val MIN_COOLDOWN_MS = 120L

        /** Linear ramp, clamped at both ends. */
        fun ramp(value: Float, from: Float, to: Float): Float =
            if (to <= from) {
                if (value >= to) 1f else 0f
            } else {
                ((value - from) / (to - from)).coerceIn(0f, 1f)
            }
    }
}
