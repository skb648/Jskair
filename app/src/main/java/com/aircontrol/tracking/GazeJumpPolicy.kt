package com.aircontrol.tracking

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** Temporal validity check for personalized gaze predictions. */
class GazeJumpPolicy(
    private val jumpLimit: Float = SCREEN_JUMP_LIMIT,
    private val maxHoldFrames: Int = MAX_HOLD_FRAMES,
    private val maxHoldMs: Long = MAX_HOLD_MS,
    private val continuationMaxMs: Long = CONTINUATION_MAX_MS,
) {
    enum class JumpOutcome { ACCEPT, HOLD, REPRIME }

    private var lastAcceptedX = Float.NaN
    private var lastAcceptedY = Float.NaN
    private var lastIrisX = Float.NaN
    private var lastIrisY = Float.NaN
    private var heldFrames = 0
    private var heldCount = 0L
    private var continuedCount = 0L
    private var lastAcceptedTs = NO_CLOCK
    private var previousAcceptedTs = NO_CLOCK
    private var previousAcceptedX = Float.NaN
    private var previousAcceptedY = Float.NaN

    val hasBaseline: Boolean get() = lastAcceptedX.isFinite() && lastAcceptedY.isFinite()
    fun heldPredictionCount(): Int = heldCount.toInt()

    fun evaluate(
        predictedX: Float,
        predictedY: Float,
        irisX: Float,
        irisY: Float,
        timestampMs: Long = NO_CLOCK,
    ): Decision {
        // Invalid numeric predictions are never accepted. With a known baseline
        // hold it; otherwise use a neutral safe position with action confidence
        // suppressed by HOLD semantics. This prevents an upstream NaN/Infinity
        // from being treated as a valid cursor target.
        if (!predictedX.isFinite() || !predictedY.isFinite()) {
            val safeX = lastAcceptedX.takeIf { it.isFinite() } ?: 0.5f
            val safeY = lastAcceptedY.takeIf { it.isFinite() } ?: 0.5f
            heldCount++
            return Decision(JumpOutcome.HOLD, safeX, safeY, evidence = null, continued = false)
        }

        if (!hasBaseline || !irisX.isFinite() || !irisY.isFinite()) {
            accept(predictedX, predictedY, irisX, irisY, timestampMs)
            return Decision(JumpOutcome.ACCEPT, predictedX, predictedY, null)
        }

        val jump = hypot(
            (predictedX - lastAcceptedX).toDouble(),
            (predictedY - lastAcceptedY).toDouble(),
        ).toFloat()
        val irisMotion = max(abs(irisX - lastIrisX), abs(irisY - lastIrisY))
        val motionExplainsJump = irisMotion * SCREEN_TRAVEL_PER_IRIS_UNIT * JUMP_TOLERANCE >= jump

        if (jump <= jumpLimit || motionExplainsJump) {
            accept(predictedX, predictedY, irisX, irisY, timestampMs)
            return Decision(JumpOutcome.ACCEPT, predictedX, predictedY, null)
        }

        val holdAgeMs = if (lastAcceptedTs == NO_CLOCK || timestampMs == NO_CLOCK) -1L
        else (timestampMs - lastAcceptedTs).coerceAtLeast(0L)
        if (holdAgeMs >= maxHoldMs || heldFrames >= maxHoldFrames) {
            heldFrames = 0
            accept(predictedX, predictedY, irisX, irisY, timestampMs)
            return Decision(JumpOutcome.REPRIME, predictedX, predictedY, null)
        }

        heldFrames++
        heldCount++
        val continuation = heldPosition(lastAcceptedX, lastAcceptedY, timestampMs, predictedX, predictedY)
        if (continuation.continued) continuedCount++
        return Decision(
            JumpOutcome.HOLD,
            continuation.x,
            continuation.y,
            jump to irisMotion,
            continuation.continued,
        )
    }

    private fun accept(x: Float, y: Float, irisX: Float, irisY: Float, timestampMs: Long) {
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

    private fun heldPosition(x: Float, y: Float, timestampMs: Long, disputedX: Float, disputedY: Float): Continuation {
        if (timestampMs == NO_CLOCK || lastAcceptedTs == NO_CLOCK || previousAcceptedTs == NO_CLOCK) return Continuation(x, y, false)
        if (!previousAcceptedX.isFinite() || !previousAcceptedY.isFinite()) return Continuation(x, y, false)
        val dtMs = (timestampMs - lastAcceptedTs).coerceAtLeast(0L)
        val intervalMs = (lastAcceptedTs - previousAcceptedTs).coerceAtLeast(1L)
        val vx = (x - previousAcceptedX) / intervalMs
        val vy = (y - previousAcceptedY) / intervalMs
        if (hypot(vx.toDouble(), vy.toDouble()) * intervalMs < MIN_CONTINUATION_TRAVEL) return Continuation(x, y, false)
        val spanMs = dtMs.coerceAtMost(continuationMaxMs)
        var nx = x + vx * spanMs
        var ny = y + vy * spanMs
        if (!nx.isFinite() || !ny.isFinite()) return Continuation(x, y, false)
        nx = clampBetween(nx, x, disputedX)
        ny = clampBetween(ny, y, disputedY)
        if (nx == x && ny == y) return Continuation(x, y, false)
        return Continuation(nx, ny, true)
    }

    private fun clampBetween(value: Float, from: Float, toward: Float): Float =
        if (toward >= from) value.coerceIn(from, toward) else value.coerceIn(toward, from)

    private data class Continuation(val x: Float, val y: Float, val continued: Boolean)

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
        heldCount = 0L
        continuedCount = 0L
    }

    fun continuedFrameCount(): Long = continuedCount

    data class Decision(
        val outcome: JumpOutcome,
        val x: Float,
        val y: Float,
        val evidence: Pair<Float, Float>?,
        val continued: Boolean = false,
    ) {
        val actionConfidenceFactor: Float
            get() = when (outcome) {
                JumpOutcome.ACCEPT -> 1f
                JumpOutcome.REPRIME -> 0.7f
                JumpOutcome.HOLD -> 0.3f
            }
    }

    companion object {
        const val SCREEN_JUMP_LIMIT = 0.15f
        const val MAX_HOLD_FRAMES = 10
        const val MAX_HOLD_MS = 120L
        const val CONTINUATION_MAX_MS = 60L
        const val MIN_CONTINUATION_TRAVEL = 0.001f
        const val NO_CLOCK = Long.MIN_VALUE
        const val SCREEN_TRAVEL_PER_IRIS_UNIT = 0.4f
        const val JUMP_TOLERANCE = 1.5f
    }
}
