package com.aircontrol.tracking

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Temporal validity check for a personalized gaze PREDICTION (Phase 6).
 *
 * What it replaces, and why the thing it replaces broke eye tracking: the
 * previous guard computed `featureDelta` as the sum of |Δ| over all 23 feature
 * dimensions — dominated by head yaw/pitch/roll, face translation and face
 * scale, none of which are eye movement — and `return`ed out of the whole frame
 * when "features barely changed but the prediction leapt". Two independent
 * faults made that an over-rejection machine:
 *
 *  1. a real saccade changes four eye-local dimensions by ~0.1 each (total ≈ 0.4,
 *     under the 0.8 "features are stable" bound) while legitimately moving the
 *     screen prediction by far more than the 0.12 jump bound, so genuine gaze
 *     changes were classified as model noise; and
 *  2. rejection dropped the frame *entirely* — including the raw
 *     [GazeObservation] — so calibration lost samples and the position the next
 *     frame was compared against stayed stale, producing hold-then-teleport.
 *
 * This policy fixes both by being honest about what evidence it has and by never
 * discarding data:
 *
 *  - the arbiter is the MEASURED IRIS MOTION in the eye's own units, which is
 *    exactly the quantity a screen jump must be explained by;
 *  - an unexplained jump is HELD (position frozen, confidence lowered) rather
 *    than dropped, so downstream consumers still see the frame;
 *  - holding is bounded, after [maxHoldFrames] the policy re-primes to the new
 *    value and lets the temporal filter converge. Without that bound a model
 *    that has drifted (lighting change, new distance) freezes the cursor
 *    forever — a livelock, which is the worst possible failure for a real-time
 *    pointer.
 */
class GazeJumpPolicy(
    private val jumpLimit: Float = SCREEN_JUMP_LIMIT,
    private val maxHoldFrames: Int = MAX_HOLD_FRAMES,
    private val maxHoldMs: Long = MAX_HOLD_MS,
    /**
     * How far a held cursor may keep travelling on its own measured velocity. Bounded by both time
     * and the disputed sample itself (see [Decision.continued]), so this can glide toward the
     * evidence but can never fly past it.
     */
    private val continuationMaxMs: Long = CONTINUATION_MAX_MS,
) {

    /**
     * What to do with a freshly computed screen-space prediction.
     *
     * [heldPosition] is the position the cursor should keep when the outcome is
     * [JumpOutcome.HOLD] (null on the very first frame, where there is nothing to
     * hold and any value would be a guess).
     */
    enum class JumpOutcome { ACCEPT, HOLD, REPRIME }

    private var lastAcceptedX = Float.NaN
    private var lastAcceptedY = Float.NaN
    private var lastIrisX = Float.NaN
    private var lastIrisY = Float.NaN
    private var heldFrames = 0
    private var heldCount = 0L
    private var continuedCount = 0L

    // Timestamps of the accepted position (and of the accepted sample before it), which is what
    // makes a *measured* velocity possible. NO_CLOCK means "the caller has no sample clock", in
    // which case timing rules fall back to frame counting.
    private var lastAcceptedTs = NO_CLOCK
    private var previousAcceptedTs = NO_CLOCK
    private var previousAcceptedX = Float.NaN
    private var previousAcceptedY = Float.NaN

    val hasBaseline: Boolean get() = lastAcceptedX.isFinite()

    /** How many frames were held since construction — exposed through [GazeDiagnostics] counters. */
    fun heldPredictionCount(): Int = heldCount.toInt()

    /**
     * @param timestampMs the sample's own monotonic timestamp (`elapsedRealtime`-compatible). When
     *   omitted, the policy has no clock and bounds a hold by frames alone — the pre-recovery
     *   behaviour, kept working for callers that cannot supply a timestamp and for tests.
     */
    fun evaluate(
        predictedX: Float,
        predictedY: Float,
        irisX: Float,
        irisY: Float,
        timestampMs: Long = NO_CLOCK,
    ): Decision {
        if (!predictedX.isFinite() || !predictedY.isFinite()) {
            return Decision(JumpOutcome.ACCEPT, predictedX, predictedY, null)
        }
        // First frame, or a stale baseline after reset: nothing to compare against.
        if (!hasBaseline || !irisX.isFinite() || !irisY.isFinite()) {
            accept(predictedX, predictedY, irisX, irisY, timestampMs)
            return Decision(JumpOutcome.ACCEPT, predictedX, predictedY, null)
        }

        val jump = hypot(
            (predictedX - lastAcceptedX).toDouble(),
            (predictedY - lastAcceptedY).toDouble(),
        ).toFloat()
        // The eyes' own motion, in the same half-aperture units on both axes, is
        // the only honest explanation for a jump in predicted screen position.
        val irisMotion = max(abs(irisX - lastIrisX), abs(irisY - lastIrisY))
        // One rule, not a stack of them: accept when the jump is small enough to be
        // harmless, or when the iris moved far enough that the calibrated map says it
        // SHOULD land somewhere new. Anything else is the model disagreeing with the
        // eye, which is what the hold is for.
        val motionExplainsJump = irisMotion * SCREEN_TRAVEL_PER_IRIS_UNIT * JUMP_TOLERANCE >= jump

        if (jump <= jumpLimit || motionExplainsJump) {
            accept(predictedX, predictedY, irisX, irisY, timestampMs)
            return Decision(JumpOutcome.ACCEPT, predictedX, predictedY, null)
        }

        // P0-4: a hold must be bounded in TIME, not in frames. `heldFrames >= 10` was 0.5 s of a
        // frozen cursor at 20 fps, 1 s at 10 fps and 2 s at 5 fps — the exact regime where the frame
        // rate drops, so the old bound made the *worst* case track the *slowest* device. The frame
        // ceiling stays as a safety net for a caller with no clock or a stalled timestamp, because
        // "a clock that never advances must not freeze the pointer forever" is a real hazard.
        val holdAgeMs = if (lastAcceptedTs == NO_CLOCK || timestampMs == NO_CLOCK) {
            -1L
        } else {
            (timestampMs - lastAcceptedTs).coerceAtLeast(0L)
        }
        if (holdAgeMs >= maxHoldMs || heldFrames >= maxHoldFrames) {
            // The world genuinely changed (new distance, lighting, head position):
            // re-prime instead of holding forever. The cursor's own temporal filter
            // turns this into a fast glide rather than a cut.
            heldFrames = 0
            accept(predictedX, predictedY, irisX, irisY, timestampMs)
            return Decision(JumpOutcome.REPRIME, predictedX, predictedY, null)
        }

        heldFrames++
        heldCount++
        // Latest-valid + short bounded interpolation (Rule 5): the position the cursor KEEPS is
        // frozen, but it is not necessarily *still* — while the disputed sample is being watched,
        // the accepted velocity carries the cursor for at most continuationMaxMs and never past the
        // disputed position. Stopping dead for half a second is what users describe as "the eye
        // tracking is broken"; a bounded glide keeps the pointer alive without trusting the jump.
        val continuation = heldPosition(lastAcceptedX, lastAcceptedY, timestampMs, predictedX, predictedY)
        if (continuation.continued) continuedCount++
        return Decision(
            JumpOutcome.HOLD,
            continuation.x,
            continuation.y,
            jump to irisMotion,
            continued = continuation.continued,
        )    }

    private fun accept(x: Float, y: Float, irisX: Float, irisY: Float, timestampMs: Long = NO_CLOCK) {
        previousAcceptedX = lastAcceptedX
        previousAcceptedY = lastAcceptedY
        previousAcceptedTs = lastAcceptedTs
        lastAcceptedX = x
        lastAcceptedY = y
        lastIrisX = irisX
        lastIrisY = irisY
        lastAcceptedTs = timestampMs
        heldFrames = 0
    }

    /**
     * Where the cursor sits while a disputed sample is being held: the accepted position, advanced
     * along the velocity the tracker itself measured on the last two accepted samples.
     *
     * Bounded twice on purpose — by [continuationMaxMs] (a saccade is over in tens of ms; carrying
     * a velocity beyond that is invention) and by the disputed position itself (the extrapolation
     * may close the distance to it but never pass it). With no clock, no second sample, no
     * measurable velocity, or a still gaze, the position is held exactly as before: this feature can
     * only reduce a freeze, it can never introduce motion that the evidence does not support.
     */
    private fun heldPosition(
        x: Float,
        y: Float,
        timestampMs: Long,
        disputedX: Float,
        disputedY: Float,
    ): Continuation {
        if (timestampMs == NO_CLOCK || lastAcceptedTs == NO_CLOCK || previousAcceptedTs == NO_CLOCK) {
            return Continuation(x, y, continued = false)
        }
        if (!previousAcceptedX.isFinite() || !previousAcceptedY.isFinite()) return Continuation(x, y, continued = false)
        val dtMs = (timestampMs - lastAcceptedTs).coerceAtLeast(0L)
        val intervalMs = (lastAcceptedTs - previousAcceptedTs).coerceAtLeast(1L)
        val vx = (x - previousAcceptedX) / intervalMs
        val vy = (y - previousAcceptedY) / intervalMs
        // Below one pixel of travel per interval (0.001 of a 1080-wide screen ≈ 1 px) there is
        // nothing to continue, and inventing motion in a still gaze is worse than holding.
        if (hypot(vx.toDouble(), vy.toDouble()) * intervalMs < MIN_CONTINUATION_TRAVEL) {
            return Continuation(x, y, continued = false)
        }
        val spanMs = dtMs.coerceAtMost(continuationMaxMs)
        var nx = x + vx * spanMs
        var ny = y + vy * spanMs
        // Never past the disputed position, and never further than the span allows on either axis.
        if (nx.isNaN() || ny.isNaN()) return Continuation(x, y, continued = false)
        nx = clampBetween(nx, x, disputedX)
        ny = clampBetween(ny, y, disputedY)
        if (nx == x && ny == y) return Continuation(x, y, continued = false)
        return Continuation(nx, ny, continued = true)
    }

    private fun clampBetween(value: Float, from: Float, toward: Float): Float = when {
        toward >= from -> value.coerceIn(from, toward)
        else -> value.coerceIn(toward, from)
    }

    private class Continuation(val x: Float, val y: Float, val continued: Boolean)

    fun reset() {
        lastAcceptedX = Float.NaN
        lastAcceptedY = Float.NaN
        lastIrisX = Float.NaN
        lastIrisY = Float.NaN
        lastAcceptedTs = NO_CLOCK
        previousAcceptedTs = NO_CLOCK
        previousAcceptedX = Float.NaN
        previousAcceptedY = Float.NaN
        heldFrames = 0
    }

    /** How many held frames kept the cursor moving on measured velocity instead of freezing it. */
    fun continuedFrameCount(): Long = continuedCount

    data class Decision(
        val outcome: JumpOutcome,
        val x: Float,
        val y: Float,
        /** (jump, irisMotion) that caused a hold — goes straight into diagnostics. */
        val evidence: Pair<Float, Float>?,
        /** True when a HOLD advanced the otherwise-frozen position along the measured velocity. */
        val continued: Boolean = false,
    ) {
        /** Whether the emitted prediction should be trusted for CLICKING this frame. */
        val actionConfidenceFactor: Float
            get() = when (outcome) {
                JumpOutcome.ACCEPT -> 1f
                JumpOutcome.REPRIME -> 0.7f
                JumpOutcome.HOLD -> 0.3f
            }
    }

    companion object {
        /**
         * Screen-space jump considered "large" in one frame: 0.15 of the display
         * (≈160 px on a 1080p-wide screen). Slightly above the previous 0.12 so a
         * fast saccade (which really does move the cursor a long way in 50 ms)
         * passes on its own merit instead of relying on the iris-motion escape.
         */
        const val SCREEN_JUMP_LIMIT = 0.15f

        /**
         * Frame ceiling on a hold. Since P0-4 this is a *safety net*, not the normal exit: a hold
         * ends after [MAX_HOLD_MS] whenever the caller supplies timestamps, which makes the visible
         * freeze independent of the frame rate. It still matters for a caller without a clock, and
         * for a stalled timestamp, because "held forever" is a livelock.
         */
        const val MAX_HOLD_FRAMES = 10

        /**
         * How long a disputed sample may freeze the cursor before the policy re-primes to it, in
         * milliseconds of pipeline time (measured from the sample timestamps, not wall clock).
         *
         * 120 ms is a measured bound rather than a guess at a nicer number: a real saccade lasts
         * 20-80 ms and the cursor's One-Euro filter needs two samples to converge on a new target,
         * so holding past that stabilises nothing — it only withholds a position the tracker has
         * already computed. At the eye-mode rate (20 fps) this is ~2 frames, where the old
         * frame-counted hold was 10 frames (0.5 s), and it is the same 120 ms at 5 fps instead of
         * the 2 s the old bound produced exactly when the device was most overloaded.
         */
        const val MAX_HOLD_MS = 120L

        /** Ceiling on how long one hold may carry the last measured velocity (see [heldPosition]). */
        const val CONTINUATION_MAX_MS = 60L

        /** Below this travel per sample interval, a gaze is "still" and must not be extrapolated. */
        const val MIN_CONTINUATION_TRAVEL = 0.001f

        /** Sentinel for "this caller has no sample clock"; timing rules then fall back to frames. */
        const val NO_CLOCK = Long.MIN_VALUE

        /**
         * Screen distance travelled per unit of iris motion for a 9-point fit: the
         * targets sit at 0.1..0.9 (0.8 of the screen) while the iris sweeps roughly
         * ±1 half-aperture, i.e. ≈0.4 screen per unit. This ties the acceptance rule
         * to the calibration's own scale rather than a second magic number.
         */
        const val SCREEN_TRAVEL_PER_IRIS_UNIT = 0.4f

        /**
         * Slack on the ratio above: the map is nonlinear and landmarks quantize, so
         * "explained within 50 %" counts as explained. Without slack a legitimate
         * saccade that overshoots the linear estimate by any amount gets held.
         */
        const val JUMP_TOLERANCE = 1.5f
    }
}
