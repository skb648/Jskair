package com.aircontrol.tracking

import kotlin.math.hypot
import kotlin.math.max

/**
 * DEBUG-ONLY gaze diagnostics (Phase 1): the pipeline is observable without
 * changing what it does.
 *
 * Design constraints that shaped this class:
 *  - zero allocation per frame after construction (fixed arrays, written in
 *    place, overwritten oldest-first), because this sits on the per-frame gaze
 *    path which runs at eye-mode FPS;
 *  - the ring buffer is small and bounded by construction (no unbounded queue,
 *    no log spam): enough to see one saccade + fixation in detail;
 *  - periodic snapshot, not per-frame logging — a reader gets "what is the
 *    pipeline doing right now, and what has it rejected recently" in one line;
 *  - counters are cumulative, so "eye tracking feels broken" becomes a number
 *    attributed to a specific stage instead of an impression.
 *
 * Every field below is a value that already existed inside the pipeline; nothing
 * is computed just for diagnostics.
 */
class GazeDiagnostics(capacity: Int = DEFAULT_CAPACITY) {

    /**
     * Why a frame did not move the visible cursor. One value per frame, so the
     * stages cannot hide each other (the failure mode that made the previous
     * hardening round so hard to diagnose: six independent `return`s that all
     * looked reasonable in isolation).
     */
    enum class RejectionReason {
        /** No face in the frame (or the frame was never produced). */
        FACE_LOST,

        /** Eye landmarks too corrupted to describe gaze at all. */
        LOW_EYE_QUALITY,

        /** Head-pose tier unusable / head turned past what the map can honestally express. */
        LOW_POSE_QUALITY,

        /** Feature vector could not be built (missing eye, non-finite value). */
        INVALID_FEATURE_VECTOR,

        /** Personalized model present but its reliability is below the action floor. */
        MODEL_REJECTED,

        /** Model prediction present but the temporal policy held the output. */
        PREDICTION_SUPPRESSED,

        /** Below the action/visibility confidence floor. */
        CONFIDENCE_REJECTED,

        /** Accepted, but the smoother intentionally held the previous position. */
        SMOOTHING_HELD,

        /** Cursor position updated this frame. */
        CURSOR_UPDATED,

        /** Cursor explicitly hidden (tracking lost, hysteresis crossed). */
        CURSOR_HIDDEN,

        /** Re-acquisition priming: position tracked, visuals deliberately withheld. */
        CURSOR_REACQUIRING,
        ;

        /** True when the frame still moved the cursor (used for the accepted ratio). */
        val movedCursor: Boolean get() = this == CURSOR_UPDATED
    }

    /** One sampled frame, in the order the pipeline produced it. */
    data class Sample(
        val timestampMs: Long,
        val faceDetected: Boolean,
        val leftEyeValid: Boolean,
        val rightEyeValid: Boolean,
        val leftEyeQuality: Float,
        val rightEyeQuality: Float,
        val ear: Float,
        val rawIrisX: Float,
        val rawIrisY: Float,
        val headYawDeg: Float,
        val headPitchDeg: Float,
        val headPoseValid: Boolean,
        val headPoseConfidence: Float,
        val calibrationActive: Boolean,
        val personalizedModelActive: Boolean,
        val personalizedPredictionX: Float,
        val personalizedPredictionY: Float,
        val rawConfidence: Float,
        val finalConfidence: Float,
        val smoothingInputX: Float,
        val smoothingInputY: Float,
        val smoothingOutputX: Float,
        val smoothingOutputY: Float,
        val rejectionReason: RejectionReason,
        val gazeCursorX: Float,
        val gazeCursorY: Float,
    )

    private var lastRecordedTimestampMs = Long.MIN_VALUE
    private var lastRecordedIndex = -1

    private val capacity = capacity.coerceAtLeast(2)
    private val ring = arrayOfNulls<Sample>(capacity)
    private var writeIndex = 0
    private var filled = 0

    // Cumulative counters (one Int per reason — cheap, monotone, and enough to
    // attribute a symptom to a stage).
    private val counters = HashMap<RejectionReason, Int>(RejectionReason.entries.size)
    private var snapshotCount = 0
    private var lastSnapshotAtMs = Long.MIN_VALUE

    /** Raw-to-filtered lag and displacement, measured on the values that really reach the cursor. */
    private var maxSmoothingDisplacement = 0f
    private var sumSmoothingDisplacement = 0.0
    private var displacementSamples = 0

    @Volatile
    private var lastReason: RejectionReason = RejectionReason.CURSOR_UPDATED

    fun record(sample: Sample) {
        synchronized(this) {
            ring[writeIndex] = sample
            lastRecordedIndex = writeIndex
            lastRecordedTimestampMs = sample.timestampMs
            writeIndex = (writeIndex + 1) % capacity
            if (filled < capacity) filled++
            counters[sample.rejectionReason] = (counters[sample.rejectionReason] ?: 0) + 1
            lastReason = sample.rejectionReason
            val dx = sample.smoothingOutputX - sample.smoothingInputX
            val dy = sample.smoothingOutputY - sample.smoothingInputY
            if (sample.rejectionReason.movedCursor || sample.rejectionReason == RejectionReason.SMOOTHING_HELD) {
                val d = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                if (d.isFinite()) {
                    maxSmoothingDisplacement = max(maxSmoothingDisplacement, d)
                    sumSmoothingDisplacement += d
                    displacementSamples++
                }
            }
        }
    }

    /**
     * Fills in the temporal-filter and cursor fields for the sample [record] just
     * wrote for the same frame, and returns whether the frame actually moved the
     * visible cursor.
     *
     * The pipeline stages run in the tracker's result callback while the smoother
     * and the overlay run in the service's collector, so the two halves of a frame
     * are produced by different threads. Matching on [timestampMs] is what keeps
     * this honest: if the next frame has already been recorded, this call is a
     * no-op instead of corrupting a newer sample. Phase 7's requirement that the
     * metrics describe the REAL cursor path depends on exactly that — a "latency"
     * number measured before the filter is not a latency the user can feel.
     */
    fun completeCursorSample(
        timestampMs: Long,
        smoothingInputX: Float,
        smoothingInputY: Float,
        smoothingOutputX: Float,
        smoothingOutputY: Float,
        reason: RejectionReason,
    ): Boolean = synchronized(this) {
        if (lastRecordedIndex < 0 || timestampMs != lastRecordedTimestampMs) return false
        val existing = ring[lastRecordedIndex] ?: return false
        ring[lastRecordedIndex] = existing.copy(
            smoothingInputX = smoothingInputX,
            smoothingInputY = smoothingInputY,
            smoothingOutputX = smoothingOutputX,
            smoothingOutputY = smoothingOutputY,
            gazeCursorX = smoothingOutputX,
            gazeCursorY = smoothingOutputY,
            rejectionReason = reason,
        )
        // Move the frame's attribution from the pipeline stage's verdict to the
        // final cursor verdict, so the two writers cannot double count a frame.
        counters[existing.rejectionReason] = ((counters[existing.rejectionReason] ?: 0) - 1).coerceAtLeast(0)
        counters[reason] = (counters[reason] ?: 0) + 1
        lastReason = reason
        true
    }

    fun reasonCount(reason: RejectionReason): Int = synchronized(this) { counters[reason] ?: 0 }

    val totalSamples: Int get() = synchronized(this) { counters.values.sum() }

    val lastRejectionReason: RejectionReason get() = lastReason

    /** Most recent samples, oldest first. Snapshot copy: callers never see mutation. */
    fun recent(count: Int = capacity): List<Sample> = synchronized(this) {
        if (filled == 0) return@synchronized emptyList()
        val n = minOf(count, filled)
        List(n) { i ->
            ring[((writeIndex - n + capacity * 2) + i) % capacity]!!
        }
    }

    /**
     * Periodic one-line snapshot. Returns null when called more than
     * [intervalMs] apart has not yet elapsed, so the caller can invoke it every
     * frame at no cost. Counters make a broken pipeline visible in one line:
     * "0 cursor updates, 412 low-eye-quality" is a different bug from
     * "200 smoothing-held, 3 confidence-rejected".
     */
    fun maybeSnapshot(nowMs: Long, intervalMs: Long = DEFAULT_SNAPSHOT_INTERVAL_MS): String? {
        synchronized(this) {
            if (lastSnapshotAtMs != Long.MIN_VALUE && nowMs - lastSnapshotAtMs < intervalMs) return null
            lastSnapshotAtMs = nowMs
            snapshotCount++
            val accepted = reasonCount(RejectionReason.CURSOR_UPDATED)
            val held = reasonCount(RejectionReason.SMOOTHING_HELD)
            val body = RejectionReason.entries
                .filter { it != RejectionReason.CURSOR_UPDATED }
                .map { "${it.name}=${reasonCount(it)}" }
                .filter { !it.endsWith("=0") }
                .joinToString(" ")
            val avgDisp = if (displacementSamples > 0) sumSmoothingDisplacement / displacementSamples else 0.0
            return "gaze[$snapshotCount] n=$totalSamples accepted=$accepted held=$held " +
                "smoothAvg=%.4f smoothMax=%.4f %s".format(avgDisp, maxSmoothingDisplacement, body)
        }
    }

    /** Reset for a new tracking session; counters are per-session on purpose. */
    fun reset() {
        synchronized(this) {
            ring.fill(null)
            writeIndex = 0
            lastRecordedIndex = -1
            lastRecordedTimestampMs = Long.MIN_VALUE
            filled = 0
            counters.clear()
            maxSmoothingDisplacement = 0f
            sumSmoothingDisplacement = 0.0
            displacementSamples = 0
        }
    }

    companion object {
        /** ~1 s at eye-mode (20 fps) frame rate. Bounded by construction. */
        const val DEFAULT_CAPACITY = 20

        /** 2 s: informative during a debug session, silent in normal use. */
        const val DEFAULT_SNAPSHOT_INTERVAL_MS = 2_000L
    }
}
