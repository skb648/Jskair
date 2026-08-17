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
    private val earThreshold: Float = 0.22f, // Chashma users EAR 0.18, 0.22 still triggers (was 0.20 too low for glasses reflection)
    private val minBlinkMs: Long = 300L,
    private val maxBlinkMs: Long = 800L,
) {
    private var closedStartMs: Long = -1L
    private var wasClosed = false

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
        val closed = ear < earThreshold

        if (closed && !wasClosed) {
            closedStartMs = timestampMs
        }
        if (!closed && wasClosed) {
            val start = closedStartMs
            closedStartMs = -1L
            if (start >= 0L) {
                val duration = timestampMs - start
                wasClosed = false
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
