package com.aircontrol.tracking

/**
 * Blink detection via the Eye Aspect Ratio (EAR).
 *
 * EAR = (||p2-p6|| + ||p3-p5||) / (2 * ||p1-p4||)
 *
 * When both eyes are open EAR is typically > 0.22 (was 0.20); when closed it drops toward
 * ~0.10. A deliberate blink (both eyes closed for [minBlinkMs]..[maxBlinkMs])
 * emits a single click event via [update].
 */
class BlinkDetector(
    private val earThreshold: Float = 0.185f, // Distinguishes true eye closure from smiles/squints (~0.21) and glasses reflection
    minBlinkMs: Long = 200L,
    maxBlinkMs: Long = 650L,
) {
    // Tunable blink window: 200ms discriminates natural involuntary eye flutters (<180ms)
    // from intentional click gestures while remaining crisp and instant.
    private var minBlinkMs: Long = minBlinkMs
    private var maxBlinkMs: Long = maxBlinkMs

    /**
     * Schmitt-trigger hysteresis: enter closed below earThreshold, but only
     * leave it once EAR recovers meaningfully above openEarThreshold (25% higher).
     */
    private val openEarThreshold: Float = earThreshold * 1.25f

    /** Updates the blink duration window (clamped to a sane band). */
    fun updateConfig(minBlinkMs: Long, maxBlinkMs: Long) {
        this.minBlinkMs = minBlinkMs.coerceIn(120L, 800L)
        this.maxBlinkMs = maxBlinkMs.coerceIn(this.minBlinkMs + 180L, 2_000L)
    }

    private var closedStartMs: Long = -1L
    private var wasClosed = false
    private var minEarDuringClosure = 1.0f
    var lastBlinkClosureStartMs: Long = -1L
        private set

    /**
     * Feeds the current average EAR. Returns the blink outcome exactly once, when
     * a blink (eyes closed then reopened) completes:
     *  - [BlinkResult.CLICK] for a valid blink within the duration window,
     *  - [BlinkResult.TOO_SHORT] if the closure was too brief,
     *  - [BlinkResult.TOO_LONG] if the closure was too long (e.g. eyes closed for
     *    rest — no click, but distinguishable from a normal blink).
     *  - [BlinkResult.NONE] otherwise (still open / still closed).
     */
    fun update(ear: Float, timestampMs: Long): BlinkResult {
        // Hysteresis: enter "closed" below earThreshold, but only
        // leave it once EAR recovers above openEarThreshold.
        val closed = if (wasClosed) ear < openEarThreshold else ear < earThreshold

        if (closed && !wasClosed) {
            closedStartMs = timestampMs
            lastBlinkClosureStartMs = timestampMs
            minEarDuringClosure = ear
        } else if (closed && wasClosed) {
            if (ear < minEarDuringClosure) {
                minEarDuringClosure = ear
            }
        }

        if (!closed && wasClosed) {
            val start = closedStartMs
            val minEar = minEarDuringClosure
            closedStartMs = -1L
            minEarDuringClosure = 1.0f
            if (start >= 0L) {
                val duration = timestampMs - start
                wasClosed = false
                // Ensure the closure actually reached full closure depth
                if (minEar > earThreshold) {
                    return BlinkResult.NONE
                }
                return when {
                    duration < minBlinkMs -> BlinkResult.TOO_SHORT
                    duration > maxBlinkMs -> BlinkResult.TOO_LONG
                    else -> BlinkResult.CLICK
                }
            }
        }
        wasClosed = closed
        return BlinkResult.NONE
    }

    fun reset() {
        closedStartMs = -1L
        wasClosed = false
        minEarDuringClosure = 1.0f
        lastBlinkClosureStartMs = -1L
    }

    /** True while a blink (eye closure) is in progress and not yet completed. */
    val hasInProgressBlink: Boolean
        get() = wasClosed

    private var abortedBlinkCount = 0

    /** How many in-progress blinks were aborted (Issue 4 metrics). */
    fun abortedBlinkCount(): Int = abortedBlinkCount

    /**
     * Issue 4: aborts an in-progress blink because the eyes stopped being
     * observed (face lost / occlusion / tracking gap). A closure that was
     * interrupted can no longer be assumed continuous, so it must NEVER
     * complete into a click after re-acquisition. This is a no-op when no
     * blink is in progress, so transient uncertain frames that occur while
     * the eyes are open do not disturb the detector at all.
     */
    fun abortInProgressBlink() {
        if (wasClosed || closedStartMs >= 0L) {
            closedStartMs = -1L
            wasClosed = false
            abortedBlinkCount++
        }
    }

    /**
     * True while the eyes are currently detected as closed. Consumers should
     * freeze the cursor while this is true — the iris landmarks are unreliable
     * when the eyelids cover them.
     */
    fun isClosed(): Boolean = wasClosed
}

/** Outcome of a completed blink (see [BlinkDetector.update]). */
enum class BlinkResult { NONE, CLICK, TOO_SHORT, TOO_LONG }
