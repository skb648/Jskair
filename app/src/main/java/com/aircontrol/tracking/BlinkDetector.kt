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
    minBlinkMs: Long = 250L,
    maxBlinkMs: Long = 750L,
) {
    // Fix A10: the blink window is user-tunable ("Blink duration" slider in
    // Settings). The old 300–800ms window demanded an unnaturally slow, deliberate
    // blink; now the minimum is adjustable (150–500ms) and the maximum follows
    // at min + 500ms, so a user can pick a natural-feeling blink while still
    // keeping natural (100–250ms) blinks excluded at the default.
    // Fix (audit #7): default lowered 300 → 250ms — "slightly slower than
    // natural" instead of "unnaturally slow", so first-time users are not left
    // wondering why nothing clicks.
    private var minBlinkMs: Long = minBlinkMs
    private var maxBlinkMs: Long = maxBlinkMs

    /**
     * Fix (audit #8): Schmitt-trigger hysteresis. A single threshold lets frame
     * noise right at the boundary flip open/closed/open, which counted phantom
     * blinks or split one blink into several. Once closed, the eyes must open
     * meaningfully wider (18% above the close threshold) before the blink can
     * complete.
     */
    private val openEarThreshold: Float = earThreshold * 1.18f

    /** Updates the blink duration window (clamped to a sane band). */
    fun updateConfig(minBlinkMs: Long, maxBlinkMs: Long) {
        this.minBlinkMs = minBlinkMs.coerceIn(120L, 800L)
        this.maxBlinkMs = maxBlinkMs.coerceIn(this.minBlinkMs + 200L, 2_000L)
    }

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
        // Hysteresis (audit #8): enter "closed" below earThreshold, but only
        // leave it once EAR recovers above openEarThreshold.
        val closed = if (wasClosed) ear < openEarThreshold else ear < earThreshold

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
