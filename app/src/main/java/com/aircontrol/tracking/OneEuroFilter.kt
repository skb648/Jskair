package com.aircontrol.tracking

import kotlin.math.abs
import kotlin.math.pow

/**
 * Implementation of the One Euro Filter for smoothing noisy signals.
 *
 * Reference: Casiez, G., Roussel, N., Vogel, D. (2012).
 * "1€ Filter: A Simple Speed-based Low-pass Filter for Noisy Input in Interactive Systems"
 *
 * KEY TUNING FOR AIR GESTURES:
 * - minCutoff=0.8: Strong smoothing when hand is still (kills micro-jitter from tremor)
 * - beta=0.08: Fast adaptation when hand moves intentionally (no lag during motion)
 * - dCutoff=1.0: Derivative smoothing frequency
 *
 * The filter is asymmetric-aware: it applies stronger smoothing to small movements
 * (tremor range: 1-3px) while preserving intentional large movements.
 *
 * @param minCutoff Minimum cutoff frequency (lower = more smoothing at low speed)
 * @param beta Speed coefficient (higher = less smoothing when moving fast)
 * @param dCutoff Cutoff frequency for the derivative computation
 */
class OneEuroFilter(
    private var minCutoff: Float = 0.8f,
    private var beta: Float = 0.08f,
    private var dCutoff: Float = 1.0f,
) {
    private var prevValue: Float? = null
    private var prevTimestampMs: Long? = null

    // Low-pass filters for value and derivative
    private val valueFilter = LowPassFilter()
    private val dValueFilter = LowPassFilter()

    fun filter(value: Float, timestampMs: Long): Float {
        val prevTs = prevTimestampMs
        if (prevTs == null) {
            // First sample: initialize
            prevTimestampMs = timestampMs
            prevValue = value
            valueFilter.initialize(value)
            dValueFilter.initialize(0f)
            return value
        }

        // Keep Double precision during computation; only convert to Float at the final step
        // to avoid premature loss of precision in dt calculation.
        val dt = ((timestampMs - prevTs) / 1000.0).toFloat().coerceAtLeast(MIN_DT)
        prevTimestampMs = timestampMs

        // Estimate derivative
        val dValue = if (prevValue != null) {
            (value - prevValue!!) / dt
        } else {
            0f
        }
        prevValue = value

        // Smooth derivative
        val edValue = dValueFilter.filter(dValue, alpha(dt, dCutoff))

        // Compute adaptive cutoff — the core of the One Euro Filter
        // Low speed (tremor) → low cutoff → heavy smoothing → kills jitter
        // High speed (intentional move) → high cutoff → light smoothing → no lag
        val cutoff = minCutoff + beta * abs(edValue)

        // Smooth value
        val filteredValue = valueFilter.filter(value, alpha(dt, cutoff))

        return filteredValue
    }

    fun reset() {
        prevValue = null
        prevTimestampMs = null
        valueFilter.reset()
        dValueFilter.reset()
    }

    fun updateParams(minCutoff: Float, beta: Float) {
        this.minCutoff = minCutoff
        this.beta = beta
    }

    private fun alpha(dt: Float, cutoff: Float): Float {
        val tau = 1.0f / (2.0f * Math.PI.toFloat() * cutoff)
        return 1.0f / (1.0f + tau / dt)
    }

    private class LowPassFilter {
        private var hatY: Float? = null

        fun initialize(value: Float) {
            hatY = value
        }

        fun filter(value: Float, alpha: Float): Float {
            val current = hatY
            val result = if (current != null) {
                alpha * value + (1.0f - alpha) * current
            } else {
                value
            }
            hatY = result
            return result
        }

        fun reset() {
            hatY = null
        }
    }

    companion object {
        private const val MIN_DT = 0.0001f
    }
}

/**
 * Applies One Euro Filter independently to x, y, z components of Landmark3D.
 */
class LandmarkFilter(
    minCutoff: Float = 0.8f,
    beta: Float = 0.08f,
) {
    private val xFilter = OneEuroFilter(minCutoff, beta)
    private val yFilter = OneEuroFilter(minCutoff, beta)
    private val zFilter = OneEuroFilter(minCutoff, beta)

    fun filter(landmark: Landmark3D, timestampMs: Long): Landmark3D {
        return Landmark3D(
            x = xFilter.filter(landmark.x, timestampMs),
            y = yFilter.filter(landmark.y, timestampMs),
            z = zFilter.filter(landmark.z, timestampMs),
        )
    }

    fun reset() {
        xFilter.reset()
        yFilter.reset()
        zFilter.reset()
    }

    fun updateParams(minCutoff: Float, beta: Float) {
        xFilter.updateParams(minCutoff, beta)
        yFilter.updateParams(minCutoff, beta)
        zFilter.updateParams(minCutoff, beta)
    }
}

/**
 * Manages filtering of all 21 hand landmarks.
 * Creates 21 independent LandmarkFilters and applies them per-frame.
 *
 * CRITICAL: Filter tuning for Iron Man-level smoothness:
 * - minCutoff=0.8: Aggressive jitter suppression when hand is still
 * - beta=0.08: Fast release when hand moves (no perceptible lag)
 * - These values are calibrated for 24fps camera input with ~16ms frame gaps
 */
class HandFrameFilter(
    minCutoff: Float = 0.8f,
    beta: Float = 0.08f,
) {
    private val landmarkFilters = List(21) { LandmarkFilter(minCutoff, beta) }

    fun filter(frame: HandFrame): HandFrame {
        if (frame.landmarks.isEmpty()) return frame

        val filteredLandmarks = frame.landmarks.mapIndexed { index, landmark ->
            if (index < landmarkFilters.size) {
                landmarkFilters[index].filter(landmark, frame.timestampMs)
            } else {
                landmark
            }
        }

        return frame.copy(landmarks = filteredLandmarks)
    }

    fun reset() {
        landmarkFilters.forEach { it.reset() }
    }

    fun updateParams(minCutoff: Float, beta: Float) {
        landmarkFilters.forEach { it.updateParams(minCutoff, beta) }
    }
}

/**
 * Dedicated cursor-level smoothing filter.
 *
 * This is SEPARATE from the landmark filter because cursor coordinates need
 * different tuning than raw landmarks:
 * - Cursor only needs X/Y (not Z)
 * - Cursor benefits from slightly MORE aggressive smoothing (user sees the dot)
 * - Cursor needs dead-zone filtering (ignore sub-pixel jitter)
 *
 * The dead-zone eliminates residual micro-jitter that passes through the One Euro
 * filter when the hand is perfectly still. If the filtered displacement from the
 * last output position is below DEAD_ZONE_PX (screen pixels), the position is
 * not updated. This produces a rock-steady cursor when the hand is still.
 */
class CursorSmoother(
    minCutoff: Float = 0.45f,
    beta: Float = 0.15f,
) {
    private val xFilter = OneEuroFilter(minCutoff, beta)
    private val yFilter = OneEuroFilter(minCutoff, beta)

    /** Last output position (screen-normalized). */
    private var lastOutputX: Float? = null
    private var lastOutputY: Float? = null

    /**
     * Filters cursor coordinates with dead-zone rejection.
     * @param x Normalized X [0,1]
     * @param y Normalized Y [0,1]
     * @param timestampMs Frame timestamp
     * @return Filtered (x, y) pair
     */
    fun filter(x: Float, y: Float, timestampMs: Long): Pair<Float, Float> {
        val fx = xFilter.filter(x, timestampMs)
        val fy = yFilter.filter(y, timestampMs)

        // Dead-zone: if displacement from last output is tiny, keep last output
        val lastX = lastOutputX
        val lastY = lastOutputY
        if (lastX != null && lastY != null) {
            val dx = fx - lastX
            val dy = fy - lastY
            val displacement = kotlin.math.sqrt(dx * dx + dy * dy)
            if (displacement < DEAD_ZONE_NORMALIZED) {
                return Pair(lastX, lastY)
            }
        }

        lastOutputX = fx
        lastOutputY = fy
        return Pair(fx, fy)
    }

    fun reset() {
        xFilter.reset()
        yFilter.reset()
        lastOutputX = null
        lastOutputY = null
    }

    fun updateParams(minCutoff: Float, beta: Float) {
        xFilter.updateParams(minCutoff, beta)
        yFilter.updateParams(minCutoff, beta)
    }

    companion object {
        // Dead-zone in normalized coordinates.
        // 0.004 ≈ 4px on a 1080p screen.
        //
        // (Bug #6 & #7 Fix): Increased from 0.001 to 0.004 to better suppress
        // residual hand tremor that survives the One Euro Filter. Since the
        // landmark-level filter has been removed from HandTracker, CursorSmoother
        // is now the sole smoothing stage; a slightly larger dead zone compensates
        // for the raw input noise. Combined with the 3dp dead zone in CursorOverlay,
        // the cursor is rock-steady when the hand is still.
        private const val DEAD_ZONE_NORMALIZED = 0.004f
    }
}
