package com.aircontrol.tracking

import kotlin.math.abs
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
 * Cursor-specific filter built on [OneEuroFilter].
 *
 * - One Euro smoothing per axis (low cutoff at rest, opens up with speed).
 * - Ballistic boost: only real, fast sweeps (raw speed above [FAST_SPEED_THRESHOLD]) open the
 *   cutoff further, so sensor noise at rest never disables smoothing.
 * - Optional velocity lead, estimated from the *filtered* signal (not raw noise), suppressed at
 *   low confidence and reset after frame gaps.
 * - Stillness lock with hysteresis: after a few near-still frames the cursor locks in place and
 *   only unlocks once the filtered point leaves a radius larger than the dead zone. This removes
 *   rest tremor without adding lag to normal motion.
 */
class CursorSmoother(
    minCutoff: Float = 1.1f,
    beta: Float = 3.0f,
    leadMs: Float = 0f,
    deadZoneNormalized: Float = DEFAULT_DEAD_ZONE_NORMALIZED,
) {
    private var baseMinCutoff: Float = minCutoff
    private var baseBeta: Float = beta
    private var leadTimeSec: Float = (leadMs / 1000f).coerceIn(0f, MAX_LEAD_TIME_SEC)
    private var activeDeadZone: Float = deadZoneNormalized.coerceIn(MIN_DEAD_ZONE, MAX_DEAD_ZONE)
    private val xFilter = OneEuroFilter(baseMinCutoff, baseBeta)
    private val yFilter = OneEuroFilter(baseMinCutoff, baseBeta)
    private var lastOutputX: Float? = null
    private var lastOutputY: Float? = null
    private var lastRawX: Float? = null
    private var lastRawY: Float? = null
    private var lastBaseX: Float? = null
    private var lastBaseY: Float? = null
    private var velX: Float = 0f
    private var velY: Float = 0f
    private var lastTimestampMs: Long? = null
    private var resting: Boolean = false
    private var stillFrames: Int = 0

    fun filter(
        x: Float,
        y: Float,
        timestampMs: Long,
        confidence: Float = 1f,
    ): Pair<Float, Float> {
        val inputX = x.coerceIn(0f, 1f)
        val inputY = y.coerceIn(0f, 1f)
        val clampedConfidence = confidence.coerceIn(0f, 1f)

        val prevTime = lastTimestampMs
        val prevRawX = lastRawX
        val prevRawY = lastRawY
        var dtSec = 0f
        if (prevTime != null && prevRawX != null && prevRawY != null) {
            val deltaMs = timestampMs - prevTime
            if (deltaMs <= 0L || deltaMs > GAP_RESET_THRESHOLD_MS) {
                // Frame gap or out-of-order sample: never extrapolate across a dropout.
                velX = 0f
                velY = 0f
                lastBaseX = null
                lastBaseY = null
                xFilter.updateParams(baseMinCutoff, baseBeta)
                yFilter.updateParams(baseMinCutoff, baseBeta)
            } else {
                dtSec = (deltaMs / 1000f).coerceIn(0.001f, 0.1f)
                val dx = inputX - prevRawX
                val dy = inputY - prevRawY
                val rawSpeed = sqrt(dx * dx + dy * dy) / dtSec
                val cutoff = if (rawSpeed > FAST_SPEED_THRESHOLD &&
                    clampedConfidence >= MIN_CONFIDENCE_FOR_PREDICTION
                ) {
                    val boost = ((rawSpeed - FAST_SPEED_THRESHOLD) * BALLISTIC_BOOST_FACTOR)
                        .coerceAtMost(MAX_BALLISTIC_BOOST)
                    baseMinCutoff + boost
                } else {
                    baseMinCutoff
                }
                xFilter.updateParams(cutoff, baseBeta)
                yFilter.updateParams(cutoff, baseBeta)
            }
        }
        lastTimestampMs = timestampMs
        lastRawX = inputX
        lastRawY = inputY

        val fxBase = xFilter.filter(inputX, timestampMs)
        val fyBase = yFilter.filter(inputY, timestampMs)

        // Velocity from the filtered signal: far less noisy than raw landmarks, so the lead
        // term no longer amplifies jitter.
        val pbx = lastBaseX
        val pby = lastBaseY
        if (dtSec > 0f && pbx != null && pby != null) {
            val instVx = (fxBase - pbx) / dtSec
            val instVy = (fyBase - pby) / dtSec
            velX = (velX * VELOCITY_EMA_KEEP + instVx * (1f - VELOCITY_EMA_KEEP))
                .coerceIn(-MAX_PREDICT_VELOCITY, MAX_PREDICT_VELOCITY)
            velY = (velY * VELOCITY_EMA_KEEP + instVy * (1f - VELOCITY_EMA_KEEP))
                .coerceIn(-MAX_PREDICT_VELOCITY, MAX_PREDICT_VELOCITY)
        }
        lastBaseX = fxBase
        lastBaseY = fyBase

        val confidenceFactor = ((clampedConfidence - MIN_CONFIDENCE_FOR_PREDICTION) / 0.40f).coerceIn(0f, 1f)
        val speedNorm = sqrt(velX * velX + velY * velY)
        val leadScale = if (leadTimeSec > 0f && speedNorm > LEAD_MIN_SPEED && confidenceFactor > 0f) {
            ((speedNorm - LEAD_MIN_SPEED) / LEAD_FULL_SPEED).coerceIn(0f, 1f) * confidenceFactor
        } else {
            0f
        }
        val leadDx = (velX * leadTimeSec * leadScale).coerceIn(-MAX_LEAD_OFFSET, MAX_LEAD_OFFSET)
        val leadDy = (velY * leadTimeSec * leadScale).coerceIn(-MAX_LEAD_OFFSET, MAX_LEAD_OFFSET)
        val fx = (fxBase + leadDx).coerceIn(0f, 1f)
        val fy = (fyBase + leadDy).coerceIn(0f, 1f)

        val oldX = lastOutputX
        val oldY = lastOutputY
        if (oldX != null && oldY != null) {
            val dx = fx - oldX
            val dy = fy - oldY
            val distance = sqrt(dx * dx + dy * dy)
            if (resting) {
                // Locked: hold until the point clearly leaves the lock radius.
                if (distance < activeDeadZone * REST_EXIT_MULTIPLIER) return oldX to oldY
                resting = false
                stillFrames = 0
            } else if (distance < activeDeadZone) {
                stillFrames++
                if (stillFrames >= REST_ENTER_FRAMES) {
                    resting = true
                    return oldX to oldY
                }
            } else {
                stillFrames = 0
            }
        }

        lastOutputX = fx
        lastOutputY = fy
        return fx to fy
    }

    val lastPosition: Pair<Float, Float>?
        get() {
            val lx = lastOutputX
            val ly = lastOutputY
            return if (lx != null && ly != null) lx to ly else null
        }

    fun reset() {
        xFilter.reset()
        yFilter.reset()
        lastOutputX = null
        lastOutputY = null
        lastRawX = null
        lastRawY = null
        lastBaseX = null
        lastBaseY = null
        velX = 0f
        velY = 0f
        lastTimestampMs = null
        resting = false
        stillFrames = 0
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
        this.activeDeadZone = deadZoneNormalized.coerceIn(MIN_DEAD_ZONE, MAX_DEAD_ZONE)
        xFilter.updateParams(minCutoff, beta)
        yFilter.updateParams(minCutoff, beta)
    }

    companion object {
        private const val DEFAULT_DEAD_ZONE_NORMALIZED = 0.0015f
        private const val MIN_DEAD_ZONE = 0.0001f
        // Gaze needs a much larger fixation lock than the hand cursor.
        private const val MAX_DEAD_ZONE = 0.03f
        // Normalized screens per second. Below this, motion is treated as precise/noise.
        private const val FAST_SPEED_THRESHOLD = 0.6f
        private const val BALLISTIC_BOOST_FACTOR = 4.0f
        private const val MAX_BALLISTIC_BOOST = 8.0f
        private const val VELOCITY_EMA_KEEP = 0.5f
        private const val MAX_LEAD_TIME_SEC = 0.024f
        private const val MAX_PREDICT_VELOCITY = 2.5f
        private const val MAX_LEAD_OFFSET = 0.025f
        private const val LEAD_MIN_SPEED = 0.08f
        private const val LEAD_FULL_SPEED = 0.45f
        private const val GAP_RESET_THRESHOLD_MS = 120L
        private const val MIN_CONFIDENCE_FOR_PREDICTION = 0.45f
        private const val REST_ENTER_FRAMES = 3
        private const val REST_EXIT_MULTIPLIER = 2.0f
    }
}
