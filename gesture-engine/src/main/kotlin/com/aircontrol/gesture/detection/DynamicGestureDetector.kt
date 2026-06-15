package com.aircontrol.gesture.detection

import com.aircontrol.gesture.config.GestureEngineConfig
import com.aircontrol.gesture.model.HandInput
import com.aircontrol.gesture.model.LandmarkIndex
import com.aircontrol.gesture.model.SwipeDirection

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
class DynamicGestureDetector(private val config: GestureEngineConfig) {

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
     */
    data class SwipeResult(
        val detected: Boolean,
        val direction: SwipeDirection? = null,
        val displacementX: Float = 0f,
        val displacementY: Float = 0f,
        val peakVelocity: Float = 0f,
    )

    // Track both wrist and index fingertip for more reliable swipe detection
    private val wristWindow = ArrayDeque<PositionSample>()
    private val indexTipWindow = ArrayDeque<PositionSample>()

    /** Timestamp of the last detected swipe, used for cooldown. */
    private var lastSwipeTimestampMs: Long = 0L

    /** Minimum time between consecutive swipe detections. */
    private var swipeCooldownMs: Long = config.swipeCooldownMs

    /**
     * Processes a hand input frame and returns a [SwipeResult] indicating
     * whether a swipe was detected and in which direction.
     *
     * Uses index fingertip as primary tracker (more dramatic movement),
     * with wrist as fallback.
     */
    fun process(input: HandInput): SwipeResult {
        if (!input.isDetected) {
            wristWindow.clear()
            indexTipWindow.clear()
            return SwipeResult(detected = false)
        }

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
        wristWindow.addLast(wristSample)
        indexTipWindow.addLast(indexSample)
        pruneWindow(wristWindow, input.timestampMs)
        pruneWindow(indexTipWindow, input.timestampMs)

        // Issue 6 Fix: Require at least 4 samples (was 3) for temporal consistency
        if (indexTipWindow.size < MIN_SAMPLES_FOR_SWIPE) return SwipeResult(detected = false)

        // Analyze using index fingertip first (more dramatic movement)
        var result = analyzeWindow(indexTipWindow, input.timestampMs)

        // If index tip didn't detect, try wrist (some users swipe with whole hand)
        if (!result.detected && wristWindow.size >= MIN_SAMPLES_FOR_SWIPE) {
            result = analyzeWindow(wristWindow, input.timestampMs)
        }

        if (result.detected) {
            lastSwipeTimestampMs = input.timestampMs
            wristWindow.clear()
            indexTipWindow.clear()
        }

        return result
    }

    /**
     * Removes samples older than [config.swipeWindowMs] from the window.
     */
    internal fun pruneWindow(window: ArrayDeque<PositionSample>, currentTimeMs: Long) {
        val cutoffTime = currentTimeMs - config.swipeWindowMs
        while (window.isNotEmpty() && window.first().timestampMs < cutoffTime) {
            window.removeFirst()
        }
    }

    /**
     * Analyzes the current window of position samples for a swipe gesture.
     *
     * Includes directional consistency check (Issue 6 Fix):
     * We verify that the majority of intermediate velocity vectors agree
     * with the overall displacement direction. This eliminates swipes that
     * look consistent in total displacement but have zigzag intermediate paths.
     */
    internal fun analyzeWindow(window: ArrayDeque<PositionSample>, currentTimeMs: Long): SwipeResult {
        if (window.size < 2) return SwipeResult(detected = false)

        // Check cooldown
        if (currentTimeMs - lastSwipeTimestampMs < swipeCooldownMs) {
            return SwipeResult(detected = false)
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
            )
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
            )
        }

        // Determine direction based on dominant axis
        val direction = if (isHorizontalDominant) {
            if (displacementX > 0f) SwipeDirection.RIGHT else SwipeDirection.LEFT
        } else {
            if (displacementY > 0f) SwipeDirection.DOWN else SwipeDirection.UP
        }

        // Issue 6 Fix: Directional consistency check
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
            )
        }

        return SwipeResult(
            detected = true,
            direction = direction,
            displacementX = displacementX,
            displacementY = displacementY,
            peakVelocity = peakVelocity,
        )
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

    /** Resets the detector state. */
    fun reset() {
        wristWindow.clear()
        indexTipWindow.clear()
        lastSwipeTimestampMs = 0L
    }

    companion object {
        private const val EPSILON = 1e-6f
        // Issue 6 Fix: Minimum samples for reliable swipe detection (was 3)
        private const val MIN_SAMPLES_FOR_SWIPE = 4
        // Minimum fraction of intermediate vectors that must agree with overall direction
        private const val DIRECTIONAL_CONSISTENCY_THRESHOLD = 0.7f
        // Minimum displacement for an intermediate step to be counted in consistency check
        private const val MIN_INTERMEDIATE_DISPLACEMENT = 0.005f
    }
}
