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
        val speed = abs(filteredDerivative)
        val cutoff = (minCutoff + beta * speed).coerceAtLeast(0.01f)
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
 * movement, eliminates rubber-band lag via velocity-adaptive ballistic cutoff opening,
 * and uses a continuous blend to exit the dead-zone without threshold step artifacts.
 */
class CursorSmoother(
    minCutoff: Float = 1.1f,
    beta: Float = 0.9f,
) {
    private var baseMinCutoff: Float = minCutoff
    private var baseBeta: Float = beta
    private val xFilter = OneEuroFilter(baseMinCutoff, baseBeta)
    private val yFilter = OneEuroFilter(baseMinCutoff, baseBeta)
    private var lastOutputX: Float? = null
    private var lastOutputY: Float? = null
    private var lastTimestampMs: Long? = null

    fun filter(x: Float, y: Float, timestampMs: Long): Pair<Float, Float> {
        val inputX = x.coerceIn(0f, 1f)
        val inputY = y.coerceIn(0f, 1f)

        // Ballistic boost: dynamically scale cutoff during rapid sweeps to eliminate lag
        val prevTime = lastTimestampMs
        val oldX = lastOutputX
        val oldY = lastOutputY
        if (prevTime != null && oldX != null && oldY != null) {
            val dt = ((timestampMs - prevTime).coerceAtLeast(1L) / 1000f).coerceIn(0.001f, 0.1f)
            val dx = inputX - oldX
            val dy = inputY - oldY
            val rawDist = sqrt(dx * dx + dy * dy)
            val speed = rawDist / dt
            if (speed > FAST_SPEED_THRESHOLD) {
                val boost = (speed - FAST_SPEED_THRESHOLD) * BALLISTIC_BOOST_FACTOR
                xFilter.updateParams(baseMinCutoff + boost, baseBeta)
                yFilter.updateParams(baseMinCutoff + boost, baseBeta)
            } else {
                xFilter.updateParams(baseMinCutoff, baseBeta)
                yFilter.updateParams(baseMinCutoff, baseBeta)
            }
        }
        lastTimestampMs = timestampMs

        val fx = xFilter.filter(inputX, timestampMs)
        val fy = yFilter.filter(inputY, timestampMs)

        if (oldX != null && oldY != null) {
            val dx = fx - oldX
            val dy = fy - oldY
            val distance = sqrt(dx * dx + dy * dy)

            // Strict dead zone prevents residual micro-tremor when aiming at a button
            if (distance < DEAD_ZONE_NORMALIZED) return oldX to oldY

            // Smooth continuous blend when breaking out of deadband (no snapping or step artifact)
            val excess = distance - DEAD_ZONE_NORMALIZED
            val blend = (excess / (DEAD_ZONE_NORMALIZED * 2.0f)).coerceIn(0.25f, 1.0f)
            val smoothX = oldX + dx * blend
            val smoothY = oldY + dy * blend
            lastOutputX = smoothX
            lastOutputY = smoothY
            return smoothX to smoothY
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
        lastTimestampMs = null
    }

    fun updateParams(minCutoff: Float, beta: Float) {
        this.baseMinCutoff = minCutoff
        this.baseBeta = beta
        xFilter.updateParams(minCutoff, beta)
        yFilter.updateParams(minCutoff, beta)
    }

    companion object {
        // ~3.5 px on a 1080p display. Eliminates hand physiological micro-tremor
        // while the blend ensures zero threshold stutter.
        private const val DEAD_ZONE_NORMALIZED = 0.0032f
        private const val FAST_SPEED_THRESHOLD = 0.7f
        private const val BALLISTIC_BOOST_FACTOR = 12.0f
    }
}
