package com.aircontrol.tracking

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

    /** Filters a new (x, y) sample and returns the smoothed pair. */
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

    /** Resets the filter to its un-initialized state. */
    fun reset() {
        initialized = false
    }
}
