package com.aircontrol.tracking

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * One Euro filter for interactive normalized coordinates.
 */
class OneEuroFilter(
    private var minCutoff: Float = 1.0f,
    private var beta: Float = 0.007f,
    private var dCutoff: Float = 1.0f,
) {
    private var prevValue: Float? = null
    private var prevTimestampMs: Long? = null
    private val valueFilter = LowPassFilter()
    private val dValueFilter = LowPassFilter()

    fun filter(value: Float, timestampMs: Long): Float {
        val previousTimestamp = prevTimestampMs
        if (previousTimestamp == null) {
            prevTimestampMs = timestampMs
            prevValue = value
            valueFilter.initialize(value)
            dValueFilter.initialize(0f)
            return value
        }

        val dt = ((timestampMs - previousTimestamp).coerceAtLeast(1L) / 1000.0)
            .toFloat().coerceIn(MIN_DT, MAX_DT)
        prevTimestampMs = timestampMs

        val previousValue = prevValue ?: value
        val derivative = (value - previousValue) / dt
        prevValue = value

        val filteredDerivative = dValueFilter.filter(derivative, alpha(dt, dCutoff))
        val cutoff = (minCutoff + beta * abs(filteredDerivative)).coerceAtLeast(0.01f)
        return valueFilter.filter(value, alpha(dt, cutoff))
    }

    fun reset() {
        prevValue = null
        prevTimestampMs = null
        valueFilter.reset()
        dValueFilter.reset()
    }

    fun updateParams(minCutoff: Float, beta: Float) {
        this.minCutoff = minCutoff.coerceAtLeast(0.05f)
        this.beta = beta.coerceAtLeast(0f)
    }

    private fun alpha(dt: Float, cutoff: Float): Float {
        val tau = 1.0f / (2.0f * Math.PI.toFloat() * cutoff)
        return (1.0f / (1.0f + tau / dt)).coerceIn(0.01f, 1.0f)
    }

    private class LowPassFilter {
        private var hatY: Float? = null

        fun initialize(value: Float) {
            hatY = value
        }

        fun filter(value: Float, alpha: Float): Float {
            val current = hatY
            val result = if (current == null) value else alpha * value + (1.0f - alpha) * current
            hatY = result
            return result
        }

        fun reset() {
            hatY = null
        }
    }

    companion object {
        private const val MIN_DT = 0.008f
        private const val MAX_DT = 0.100f
    }
}

/**
 * Cursor-specific filter. It suppresses micro jitter while preserving intentional
 * movement and avoids an additional UI-layer smoothing stage.
 */
class CursorSmoother(
    minCutoff: Float = 1.1f,
    beta: Float = 0.9f,
) {
    private val xFilter = OneEuroFilter(minCutoff, beta)
    private val yFilter = OneEuroFilter(minCutoff, beta)
    private var lastOutputX: Float? = null
    private var lastOutputY: Float? = null

    fun filter(x: Float, y: Float, timestampMs: Long): Pair<Float, Float> {
        val inputX = x.coerceIn(0f, 1f)
        val inputY = y.coerceIn(0f, 1f)
        val fx = xFilter.filter(inputX, timestampMs)
        val fy = yFilter.filter(inputY, timestampMs)

        val oldX = lastOutputX
        val oldY = lastOutputY
        if (oldX != null && oldY != null) {
            val dx = fx - oldX
            val dy = fy - oldY
            val distance = sqrt(dx * dx + dy * dy)

            // A tiny stationary band prevents hand tremor from becoming visible,
            // while larger motion is never clipped to the dead-zone boundary.
            if (distance < DEAD_ZONE_NORMALIZED) return oldX to oldY
        }

        lastOutputX = fx
        lastOutputY = fy
        return fx to fy
    }

    val lastPosition: Pair<Float, Float>?
        get() = if (lastOutputX != null && lastOutputY != null) lastOutputX!! to lastOutputY!! else null

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
        // ~1.6 px on a 1080p display. Small enough for precision pointing,
        // large enough to reject residual tremor from a steady hand.
        private const val DEAD_ZONE_NORMALIZED = 0.0015f
    }
}
