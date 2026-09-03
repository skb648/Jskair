package com.aircontrol.tracking

import kotlin.math.abs
import kotlin.math.pow

/**
 * Implementation of the One Euro Filter for smoothing noisy signals.
 *
 * Reference: Casiez, G., Roussel, N., Vogel, D. (2012).
 * "1€ Filter: A Simple Speed-based Low-pass Filter for Noisy Input in Interactive Systems"
 *
 * APPLE VISION PRO TUNING (Layer 1):
 * - minCutoff=1.0 Hz: Lower lag, eliminates high-frequency micro-jitters
 * - beta=0.007: Lower high-speed delay, minimal filtering during fast movements
 * - dCutoff=1.0: Derivative smoothing frequency
 *
 * The filter dynamically adapts:
 * - Low velocity → fc drops toward fc_min (1.0 Hz) → heavy filtering (zero jitter)
 * - High velocity → fc rises rapidly → minimal filtering (near-zero latency)
 *
 * @param minCutoff Minimum cutoff frequency (fc_min) - Apple Vision Pro: 1.0 Hz
 * @param beta Speed coefficient - Apple Vision Pro: 0.007
 * @param dCutoff Cutoff frequency for the derivative computation
 */
class OneEuroFilter(
    private var minCutoff: Float = 1.0f,  // Apple Vision Pro: 1.0 Hz
    private var beta: Float = 0.007f,      // Apple Vision Pro: 0.007
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
        val previousValue = prevValue
        val dValue = if (previousValue != null) {
            (value - previousValue) / dt
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
        private const val MIN_DT = 0.008f // ~120fps max, clamp duplicate timestamps to avoid alpha spike
    }
}

// H-07 Fix: Removed LandmarkFilter and HandFrameFilter classes.
// These were previously used for landmark-level One Euro filtering in HandTracker,
// but that filtering was removed to eliminate double-filtering latency.
// The landmark-level filter was adding ~2 frames of lag on top of the cursor-side
// CursorSmoother filter, making the cursor feel sluggish.
//
// If you need landmark-level filtering in the future, restore these classes from
// git history (commit before H-07 fix) or implement a lighter-weight approach.
//
// The OneEuroFilter class itself is kept because CursorSmoother uses it.

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
 *
 * APPLE VISION PRO TUNING:
 * - minCutoff=1.0 Hz: Lower lag, better jitter elimination
 * - beta=0.007: Lower high-speed delay, minimal filtering during fast movements
 */
class CursorSmoother(
    minCutoff: Float = 1.1f,
    // Fix B-5: beta is multiplied by velocity in *normalized units per second*
    // (~1-3 for a hand sweep). 0.007 made the adaptive term ~0.02, i.e. the
    // filter never opened up and every move took ~400ms to settle. 0.9 gives a
    // cutoff of ~2-4 Hz during motion (near-zero lag) while still resting at
    // minCutoff for tremor suppression.
    beta: Float = 0.9f,
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

    /**
     * The last position actually emitted to the overlay. The click target reads
     * this so a tap lands on the visible dot rather than on the raw hand
     * landmark (Fix A-9).
     */
    val lastPosition: Pair<Float, Float>?
        get() {
            val x = lastOutputX ?: return null
            val y = lastOutputY ?: return null
            return x to y
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
        // Dead-zone in normalized coordinates. Fix B-5: there is now exactly ONE
        // dead zone in the cursor path. This one (≈1.5px on a 1080p screen) kills
        // tremor at rest; the 4dp zone that used to sit on top of it in
        // CursorOverlay ate small precise nudges, so small targets became tiring
        // to hit and the dot looked "stuck" before jumping.
        private const val DEAD_ZONE_NORMALIZED = 0.0015f
    }
}
