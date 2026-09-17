package com.aircontrol.tracking

/** Blink detection via the Eye Aspect Ratio (EAR). */
class BlinkDetector(
    private val earThreshold: Float = 0.170f,
    minBlinkMs: Long = 180L,
    maxBlinkMs: Long = 650L,
) {
    private var minBlinkMs: Long = minBlinkMs
    private var maxBlinkMs: Long = maxBlinkMs
    private var baselineOpenEar: Float = Float.NaN

    private fun closureThreshold(): Float {
        if (baselineOpenEar.isNaN()) return earThreshold
        return (baselineOpenEar * 0.72f).coerceIn(0.10f, 0.22f)
    }

    private fun openThreshold(): Float = closureThreshold() * 1.15f

    fun updateConfig(minBlinkMs: Long, maxBlinkMs: Long) {
        this.minBlinkMs = minBlinkMs.coerceIn(120L, 800L)
        this.maxBlinkMs = maxBlinkMs.coerceIn(this.minBlinkMs + 180L, 2_000L)
    }

    private var closedStartMs: Long = -1L
    private var wasClosed = false
    private var minEarDuringClosure = 1.0f
    var lastBlinkClosureStartMs: Long = -1L
        private set

    fun update(ear: Float, timestampMs: Long): BlinkResult {
        // CV failures are never click evidence. Abort an in-progress closure so
        // a later valid sample cannot turn a tracking gap into a click.
        if (!ear.isFinite() || ear < 0f) {
            abortInProgressBlink()
            return BlinkResult.NONE
        }

        val closeAt = closureThreshold()
        val openAt = openThreshold()
        val closed = if (wasClosed) ear < openAt else ear < closeAt

        // Adapt only from confidently open eyes; never learn from a closure.
        if (!closed && ear > earThreshold * 1.25f) {
            baselineOpenEar = if (baselineOpenEar.isNaN()) ear
            else baselineOpenEar + (ear - baselineOpenEar) * 0.01f
        }

        if (closed && !wasClosed) {
            closedStartMs = timestampMs
            lastBlinkClosureStartMs = timestampMs
            minEarDuringClosure = ear
        } else if (closed) {
            if (ear < minEarDuringClosure) minEarDuringClosure = ear
        }

        if (!closed && wasClosed) {
            val start = closedStartMs
            val minEar = minEarDuringClosure
            closedStartMs = -1L
            minEarDuringClosure = 1.0f
            wasClosed = false
            if (start >= 0L) {
                val duration = timestampMs - start
                if (minEar > closeAt) return BlinkResult.NONE
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

    /** Reset transient state and relearn the open-eye baseline. */
    fun reset() {
        closedStartMs = -1L
        wasClosed = false
        minEarDuringClosure = 1.0f
        lastBlinkClosureStartMs = -1L
        baselineOpenEar = Float.NaN
    }

    val hasInProgressBlink: Boolean
        get() = wasClosed

    private var abortedBlinkCount = 0
    fun abortedBlinkCount(): Int = abortedBlinkCount

    fun abortInProgressBlink() {
        if (wasClosed || closedStartMs >= 0L) {
            closedStartMs = -1L
            wasClosed = false
            minEarDuringClosure = 1.0f
            lastBlinkClosureStartMs = -1L
            abortedBlinkCount++
        }
    }

    fun isClosed(): Boolean = wasClosed
}

enum class BlinkResult { NONE, CLICK, TOO_SHORT, TOO_LONG }
