package com.aircontrol.nativeinput

import com.aircontrol.tracking.HandFrame
import com.aircontrol.tracking.Handedness
import com.aircontrol.tracking.Landmark3D
import kotlin.math.abs

/**
 * Phase 1 MVP adapter: existing hand-tracking output → relative HID mouse
 * deltas. Completely isolated — it only READS HandFrames; it never touches
 * CursorController, the One Euro filter, or any accessibility path, so HID
 * tuning can never affect the existing cursor.
 *
 * Anchor: the palm centre (four MCP joints + wrist), the same stable region
 * the existing cursor uses conceptually. The calculation here is an
 * independent read-only copy — no shared state with the cursor pipeline.
 *
 * Relative semantics: dx/dy in normalized camera units are scaled to HID
 * "counts" (what a physical mouse reports). Sub-count remainders are carried
 * between frames so slow drift still accumulates instead of vanishing.
 */
class NativeHidInputAdapter(
    private val input: NativeMouseInput,
    /** Optional rate-limited movement logger (inject Timber in production; null in tests). */
    private val debug: ((String) -> Unit)? = null,
) {
    private var hasPrev = false
    private var prevX = 0f
    private var prevY = 0f
    private var residualX = 0f
    private var residualY = 0f

    /** Rate-limited movement logging (never per-frame spam). */
    private var lastDebugLogMs = 0L

    fun onHandFrame(frame: HandFrame) {
        if (!frame.isDetected || frame.landmarks.size < HandFrame.LANDMARK_COUNT) {
            // Hand lost: the next detection must re-prime the anchor, otherwise
            // the jump would be reported as a huge mouse fling.
            reset()
            return
        }
        val lm = frame.landmarks
        val palmX = (lm[5].x + lm[9].x + lm[13].x + lm[17].x + lm[0].x) / 5f
        val palmY = (lm[5].y + lm[9].y + lm[13].y + lm[17].y + lm[0].y) / 5f

        if (!hasPrev) {
            prevX = palmX
            prevY = palmY
            hasPrev = true
            return
        }

        val dxN = palmX - prevX
        val dyN = palmY - prevY
        prevX = palmX
        prevY = palmY

        // Dead zone: suppress tracker jitter while the hand is essentially
        // still, so the receiver's cursor does not creep.
        if (abs(dxN) < DEAD_ZONE && abs(dyN) < DEAD_ZONE) return

        residualX += dxN * GAIN
        residualY += dyN * GAIN
        val outX = residualX.toInt().coerceIn(-127, 127)
        val outY = residualY.toInt().coerceIn(-127, 127)
        // Keep only the unsent remainder.
        residualX -= outX
        residualY -= outY

        if (outX != 0 || outY != 0) {
            input.move(outX, outY)
            if (debug != null && frame.timestampMs - lastDebugLogMs > DEBUG_LOG_INTERVAL_MS) {
                lastDebugLogMs = frame.timestampMs
                debug.invoke("move dx=$outX dy=$outY (dN=%.4f,%.4f)".format(dxN, dyN))
            }
        }
    }

    fun reset() {
        hasPrev = false
        residualX = 0f
        residualY = 0f
    }

    companion object {
        /**
         * Camera-normalized units → HID counts. A full camera-width hand sweep
         * (1.0) ≈ 1600 counts ≈ roughly one screen of cursor travel on a
         * default-sensitivity Android host.
         */
        const val GAIN = 1_600f

        /** Normalized units; tracker jitter at rest is typically below this. */
        const val DEAD_ZONE = 0.0015f

        private const val DEBUG_LOG_INTERVAL_MS = 2_000L
    }
}

/** Convenience for tests/debugging: a uniform-landmark synthetic hand frame. */
fun syntheticHandFrame(
    x: Float,
    y: Float,
    timestampMs: Long,
    confidence: Float = 0.9f,
): HandFrame = HandFrame(
    landmarks = List(HandFrame.LANDMARK_COUNT) { Landmark3D(x, y, 0f) },
    handedness = Handedness.RIGHT,
    timestampMs = timestampMs,
    confidence = confidence,
)
