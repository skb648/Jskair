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
 * When the window is full, computes the displacement vector and peak velocity.
 * A swipe is recognized when:
 *   - Displacement > 15% of frame dimension (sensitivity-scaled)
 *   - Peak velocity > threshold (sensitivity-scaled)
 *   - Dominant axis ratio > 2:1 (rejects diagonal ambiguity)
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

        // Need at least 3 samples to compute velocity
        if (indexTipWindow.size < 3) return SwipeResult(detected = false)

        // Analyze using index fingertip first (more dramatic movement)
        var result = analyzeWindow(indexTipWindow, input.timestampMs)

        // If index tip didn't detect, try wrist (some users swipe with whole hand)
        if (!result.detected && wristWindow.size >= 3) {
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
        // Note: Since camera image is already mirrored in CameraService, the coordinates
        // are in selfie-view. So positive X displacement in camera = right on screen.
        val direction = if (isHorizontalDominant) {
            if (displacementX > 0f) SwipeDirection.RIGHT else SwipeDirection.LEFT
        } else {
            if (displacementY > 0f) SwipeDirection.DOWN else SwipeDirection.UP
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

    /** Resets the detector state. */
    fun reset() {
        wristWindow.clear()
        indexTipWindow.clear()
        lastSwipeTimestampMs = 0L
    }

    companion object {
        private const val EPSILON = 1e-6f
    }
}
