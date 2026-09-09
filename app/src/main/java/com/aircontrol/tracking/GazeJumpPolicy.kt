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

    val hasBaseline: Boolean get() = lastAcceptedX.isFinite()

    /** How many frames were held since construction — exposed through [GazeDiagnostics] counters. */
    fun heldPredictionCount(): Int = heldCount.toInt()

    fun evaluate(predictedX: Float, predictedY: Float, irisX: Float, irisY: Float): Decision {
        if (!predictedX.isFinite() || !predictedY.isFinite()) {
            return Decision(JumpOutcome.ACCEPT, predictedX, predictedY, null)
        }
        // First frame, or a stale baseline after reset: nothing to compare against.
        if (!hasBaseline || !irisX.isFinite() || !irisY.isFinite()) {
            accept(predictedX, predictedY, irisX, irisY)
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
            accept(predictedX, predictedY, irisX, irisY)
            return Decision(JumpOutcome.ACCEPT, predictedX, predictedY, null)
        }

        if (heldFrames >= maxHoldFrames) {
            // The world genuinely changed (new distance, lighting, head position):
            // re-prime instead of holding forever. The cursor's own temporal filter
            // turns this into a fast glide rather than a cut.
            heldFrames = 0
            accept(predictedX, predictedY, irisX, irisY)
            return Decision(JumpOutcome.REPRIME, predictedX, predictedY, null)
        }

        heldFrames++
        heldCount++
        return Decision(JumpOutcome.HOLD, lastAcceptedX, lastAcceptedY, jump to irisMotion)
    }

    private fun accept(x: Float, y: Float, irisX: Float, irisY: Float) {
        lastAcceptedX = x
        lastAcceptedY = y
        lastIrisX = irisX
        lastIrisY = irisY
        heldFrames = 0
    }

    fun reset() {
        lastAcceptedX = Float.NaN
        lastAcceptedY = Float.NaN
        lastIrisX = Float.NaN
        lastIrisY = Float.NaN
        heldFrames = 0
    }

    data class Decision(
        val outcome: JumpOutcome,
        val x: Float,
        val y: Float,
        /** (jump, irisMotion) that caused a hold — goes straight into diagnostics. */
        val evidence: Pair<Float, Float>?,
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

        /** Frames spent holding before re-priming: ~0.5 s at eye-mode 20 fps. */
        const val MAX_HOLD_FRAMES = 10

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
