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
 * compensates for camera + inference latency via clamped kinematic velocity lead prediction,
 * and uses a continuous blend to exit the dead-zone without threshold step artifacts.
 */
class CursorSmoother(
    minCutoff: Float = 1.1f,
    beta: Float = 12.0f,
    leadMs: Float = 0f,
    deadZoneNormalized: Float = DEFAULT_DEAD_ZONE_NORMALIZED,
) {
    private var baseMinCutoff: Float = minCutoff
    private var baseBeta: Float = beta
    private var leadTimeSec: Float = (leadMs / 1000f).coerceIn(0f, MAX_LEAD_TIME_SEC)
    private var activeDeadZone: Float = deadZoneNormalized.coerceIn(0.0001f, 0.005f)
    private val xFilter = OneEuroFilter(baseMinCutoff, baseBeta)
    private val yFilter = OneEuroFilter(baseMinCutoff, baseBeta)
    private var lastOutputX: Float? = null
    private var lastOutputY: Float? = null
    private var lastRawX: Float? = null
    private var lastRawY: Float? = null
    private var velX: Float = 0f
    private var velY: Float = 0f
    private var lastTimestampMs: Long? = null

    fun filter(x: Float, y: Float, timestampMs: Long): Pair<Float, Float> {
        val inputX = x.coerceIn(0f, 1f)
        val inputY = y.coerceIn(0f, 1f)

        // Ballistic boost + kinematic velocity estimation
        val prevTime = lastTimestampMs
        val oldX = lastOutputX
        val oldY = lastOutputY
        val prevRawX = lastRawX
        val prevRawY = lastRawY
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

            if (prevRawX != null && prevRawY != null) {
                val instVx = (inputX - prevRawX) / dt
                val instVy = (inputY - prevRawY) / dt
                // Smooth velocity estimate with fast EMA and clamp to prevent overshoot on deceleration
                velX = (velX * 0.35f + instVx * 0.65f).coerceIn(-MAX_PREDICT_VELOCITY, MAX_PREDICT_VELOCITY)
                velY = (velY * 0.35f + instVy * 0.65f).coerceIn(-MAX_PREDICT_VELOCITY, MAX_PREDICT_VELOCITY)
                // Zero out velocity if direction reverses (prevents overshoot when stopping)
                if (instVx * velX <= 0f) velX = 0f
                if (instVy * velY <= 0f) velY = 0f
            }
        }
        lastTimestampMs = timestampMs
        lastRawX = inputX
        lastRawY = inputY

        val fxBase = xFilter.filter(inputX, timestampMs)
        val fyBase = yFilter.filter(inputY, timestampMs)

        // Apply kinematic velocity lead compensation only when moving above stillness threshold
        val speedNorm = sqrt(velX * velX + velY * velY)
        val leadScale = if (leadTimeSec > 0f && speedNorm > LEAD_MIN_SPEED) {
            ((speedNorm - LEAD_MIN_SPEED) / LEAD_FULL_SPEED).coerceIn(0f, 1f)
        } else {
            0f
        }
        val leadDx = (velX * leadTimeSec * leadScale).coerceIn(-MAX_LEAD_OFFSET, MAX_LEAD_OFFSET)
        val leadDy = (velY * leadTimeSec * leadScale).coerceIn(-MAX_LEAD_OFFSET, MAX_LEAD_OFFSET)
        val fx = (fxBase + leadDx).coerceIn(0f, 1f)
        val fy = (fyBase + leadDy).coerceIn(0f, 1f)

        if (oldX != null && oldY != null) {
            val dx = fx - oldX
            val dy = fy - oldY
            val distance = sqrt(dx * dx + dy * dy)

            // Ultra-tight micro dead zone clamps sensor noise at pure rest
            if (distance < activeDeadZone) return oldX to oldY

            // Instant and responsive breakout: blend across a micro transition margin to prevent
            // single-frame step artifacts, then track filter output at 100% speed without drag.
            val excess = distance - activeDeadZone
            val blend = (excess / (activeDeadZone * 0.5f)).coerceIn(0.0f, 1.0f)
            val smoothX = (oldX + dx * blend).coerceIn(0f, 1f)
            val smoothY = (oldY + dy * blend).coerceIn(0f, 1f)
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
        lastRawX = null
        lastRawY = null
        velX = 0f
        velY = 0f
        lastTimestampMs = null
    }

    fun updateParams(
        minCutoff: Float,
        beta: Float,
        leadMs: Float = this.leadTimeSec * 1000f,
        deadZoneNormalized: Float = this.activeDeadZone,
    ) {
        this.baseMinCutoff = minCutoff
        this.baseBeta = beta
        this.leadTimeSec = (leadMs / 1000f).coerceIn(0f, MAX_LEAD_TIME_SEC)
        this.activeDeadZone = deadZoneNormalized.coerceIn(0.0001f, 0.005f)
        xFilter.updateParams(minCutoff, beta)
        yFilter.updateParams(minCutoff, beta)
    }

    companion object {
        // Micro-deadzone ~0.6 px on a 1080p display: clamps stillness tremor without slowing motion
        private const val DEFAULT_DEAD_ZONE_NORMALIZED = 0.0006f
        private const val FAST_SPEED_THRESHOLD = 0.05f
        private const val BALLISTIC_BOOST_FACTOR = 28.0f
        private const val MAX_LEAD_TIME_SEC = 0.024f
        private const val MAX_PREDICT_VELOCITY = 2.5f
        private const val MAX_LEAD_OFFSET = 0.025f
        private const val LEAD_MIN_SPEED = 0.08f
        private const val LEAD_FULL_SPEED = 0.45f
    }
}
