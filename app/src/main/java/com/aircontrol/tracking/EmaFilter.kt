package com.aircontrol.tracking

import kotlin.math.pow

/**
 * Exponential Moving Average filter for 1D/2D gaze smoothing.
 *
 * Eliminates saccadic eye jitter: a lower [alpha] means heavier smoothing (more
 * lag), a higher alpha tracks fast saccades more closely. The 0.15–0.25 range is
 * recommended for gaze (the default 0.2 is used for the eye cursor).
 *
 * This complements the existing One Euro [CursorSmoother] — EMA is simpler and
 * stateless-per-axis, ideal for the iris-landmark signal.
 */
class EmaFilter(
    private val alpha: Float = 0.2f,
) {
    private var initialized = false
    private var valueX = 0f
    private var valueY = 0f
    private var lastTimestampMs: Long = Long.MIN_VALUE

    /**
     * Filters a new (x, y) sample and returns the smoothed pair.
     *
     * Fix D8: the filter used a fixed alpha regardless of the frame interval,
     * so the amount of smoothing silently changed with the analysis FPS setting
     * (and with adaptive scan mode): at 5 fps the same alpha smoothed far more
     * wall-clock time than at 30 fps, and the gaze cursor felt inconsistent.
     * [filterWithTimestamp] makes the smoothing time-constant-stable; this
     * timestamp-less overload keeps the legacy time-independent behaviour for
     * callers that have no clock.
     */
    fun filter(x: Float, y: Float): Pair<Float, Float> {
        if (!initialized) {
            valueX = x
            valueY = y
            initialized = true
            return valueX to valueY
        }
        valueX = alpha * x + (1f - alpha) * valueX
        valueY = alpha * y + (1f - alpha) * valueY
        return valueX to valueY
    }

    /**
     * Timestamp-aware variant: scales the effective alpha with the measured
     * frame interval so the cursor feels the same at 5 fps and 30 fps.
     * `referenceFrameIntervalMs` is the interval the base [alpha] was tuned for.
     */
    fun filterWithTimestamp(x: Float, y: Float, timestampMs: Long): Pair<Float, Float> {
        if (!initialized) {
            valueX = x
            valueY = y
            initialized = true
            lastTimestampMs = timestampMs
            return valueX to valueY
        }
        var effectiveAlpha = alpha
        if (lastTimestampMs != Long.MIN_VALUE) {
            val dt = (timestampMs - lastTimestampMs).coerceIn(1L, 500L)
            // alpha_dt = 1 - (1 - alpha)^(dt / reference), clamped to (0, 1].
            val exponent = dt.toDouble() / REFERENCE_FRAME_INTERVAL_MS.toDouble()
            effectiveAlpha = (1.0 - alpha.toDouble()).pow(exponent).toFloat()
                .coerceIn(0.02f, 1f)
        }
        lastTimestampMs = timestampMs
        valueX = effectiveAlpha * x + (1f - effectiveAlpha) * valueX
        valueY = effectiveAlpha * y + (1f - effectiveAlpha) * valueY
        return valueX to valueY
    }

    /** Resets the filter to its un-initialized state. */
    fun reset() {
        initialized = false
        lastTimestampMs = Long.MIN_VALUE
    }

    private companion object {
        /** The 24 fps analysis default the base alpha was tuned against. */
        private const val REFERENCE_FRAME_INTERVAL_MS = 1000L / 24L
    }
}
