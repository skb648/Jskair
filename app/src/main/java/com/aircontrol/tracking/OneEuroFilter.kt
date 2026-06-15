package com.aircontrol.tracking

import kotlin.math.abs

/**
 * Implementation of the One Euro Filter for smoothing noisy signals.
 *
 * Reference: Casiez, G., Roussel, N., Vogel, D. (2012).
 * "1€ Filter: A Simple Speed-based Low-pass Filter for Noisy Input in Interactive Systems"
 *
 * Android 17 optimization: Adaptive beta coefficient based on motion speed
 * to reduce jitter during fast movements while maintaining smoothness at rest.
 *
 * @param minCutoff Minimum cutoff frequency (lower = more smoothing at low speed)
 * @param beta Speed coefficient (higher = less smoothing when moving fast)
 * @param dCutoff Cutoff frequency for the derivative computation
 */
class OneEuroFilter(
    private var minCutoff: Float = 1.0f,
    private var beta: Float = 0.05f, // Increased from 0.007 for better fast-motion response
    private var dCutoff: Float = 1.0f,
) {
    private var prevValue: Float? = null
    private var prevDValue: Float? = null
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
            prevDValue = 0f
            valueFilter.initialize(value)
            dValueFilter.initialize(0f)
            return value
        }

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

        // Compute adaptive cutoff
        val cutoff = minCutoff + beta * abs(edValue)

        // Smooth value
        val filteredValue = valueFilter.filter(value, alpha(dt, cutoff))

        return filteredValue
    }

    fun reset() {
        prevValue = null
        prevDValue = null
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
    minCutoff: Float = 1.0f,
    beta: Float = 0.007f,
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
 */
class HandFrameFilter(
    minCutoff: Float = 1.0f,
    beta: Float = 0.007f,
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
