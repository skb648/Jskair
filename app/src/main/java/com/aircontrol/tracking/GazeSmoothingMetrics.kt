package com.aircontrol.tracking

import kotlin.math.hypot

/**
 * Lightweight, hot-path-safe gaze metrics (Issue 5: responsiveness vs stability).
 *
 * The eye cursor is already velocity-adaptive (One Euro, see [CursorSmoother]),
 * so this class does NOT add another smoothing layer. It measures what the
 * smoothing is doing so that tuning and regressions can be judged numerically
 * instead of by feel, and so a debug diagnostics surface can show:
 *
 *  - raw gaze velocity (intentional saccades vs micro noise),
 *  - raw-to-filtered displacement (how far the filter holds the dot from the
 *    raw signal — jitter suppression when staring, lag when moving),
 *  - micro-jitter magnitude at rest,
 *  - re-acquisition displacement (cursor teleport after the face returns),
 *  - gaze loss / re-acquisition counts.
 *
 * Recording is primitive-only and allocates nothing per sample (safe on the
 * per-frame hot path); [snapshot] allocates one immutable summary. Debug-only
 * consumers decide how often to read/log it — never log per frame.
 */
data class GazeSmoothingSnapshot(
    val sampleCount: Long,
    val lossCount: Long,
    val reacquisitionCount: Long,
    val saccadeCount: Long,
    val lastRawVelocityNormPerSec: Float,
    val avgRawVelocityNormPerSec: Float,
    val maxRawVelocityNormPerSec: Float,
    val meanRawToFilteredDisplacement: Float,
    val maxRawToFilteredDisplacement: Float,
    val restJitterMax: Float,
    val lastReacquisitionDisplacement: Float,
)

class GazeSmoothingMetrics {

    private var sampleCount = 0L
    private var lossCount = 0L
    private var reacquisitionCount = 0L
    private var saccadeCount = 0L

    private var lastRawX = 0f
    private var lastRawY = 0f
    private var lastFilteredX = 0f
    private var lastFilteredY = 0f
    private var lastTimestampMs = Long.MIN_VALUE

    // Raw velocity accumulator (normalized units per second).
    private var rawVelocityAccum = 0.0
    private var lastRawVelocity = 0f
    private var maxRawVelocity = 0f

    // Raw-to-filtered displacement accumulator (normalized units).
    private var rawToFilteredAccum = 0.0
    private var maxRawToFiltered = 0f

    // Rest-jitter: max filtered displacement between consecutive samples while
    // the raw gaze was essentially still.
    private var restJitterMax = 0f

    // Re-acquisition teleport tracking.
    private var filteredBeforeLossX = 0.5f
    private var filteredBeforeLossY = 0.5f
    private var pendingReacquisition = false
    private var lastReacquisitionDisplacement = 0f

    /** A detected raw-gaze sample (raw + filtered, both normalized [0,1]). */
    fun onSample(rawX: Float, rawY: Float, filteredX: Float, filteredY: Float, timestampMs: Long) {
        if (lastTimestampMs != Long.MIN_VALUE && timestampMs > lastTimestampMs) {
            val dtSec = (timestampMs - lastTimestampMs).toFloat() / 1000f
            if (dtSec > 0f) {
                val rawVelocity = hypot(rawX - lastRawX, rawY - lastRawY) / dtSec
                lastRawVelocity = rawVelocity
                rawVelocityAccum += rawVelocity
                if (rawVelocity > maxRawVelocity) maxRawVelocity = rawVelocity
                if (rawVelocity >= SACCADE_VELOCITY_NORM_PER_SEC) saccadeCount++

                // Micro-jitter magnitude while the raw gaze is basically still.
                if (rawVelocity < STILL_RAW_VELOCITY_NORM_PER_SEC) {
                    val filteredDelta = hypot(filteredX - lastFilteredX, filteredY - lastFilteredY)
                    if (filteredDelta > restJitterMax) restJitterMax = filteredDelta
                }
            }
        }

        val rawToFiltered = hypot(rawX - filteredX, rawY - filteredY)
        rawToFilteredAccum += rawToFiltered
        if (rawToFiltered > maxRawToFiltered) maxRawToFiltered = rawToFiltered

        lastRawX = rawX
        lastRawY = rawY
        lastFilteredX = filteredX
        lastFilteredY = filteredY
        lastTimestampMs = timestampMs
        sampleCount++

        // First sample after a loss measures the re-acquisition displacement:
        // how far the cursor jumped between what the user last saw and where
        // the cursor reappears.
        if (pendingReacquisition && sampleCount > 1) {
            pendingReacquisition = false
            lastReacquisitionDisplacement = hypot(
                filteredX - filteredBeforeLossX,
                filteredY - filteredBeforeLossY,
            )
        }
    }

    /** The face/eyes left the frame (the cursor stopped being updated). */
    fun onTrackingLost(filteredX: Float, filteredY: Float) {
        lossCount++
        filteredBeforeLossX = filteredX
        filteredBeforeLossY = filteredY
        pendingReacquisition = true
    }

    /** The face came back and the cursor will be re-positioned. */
    fun onReacquired() {
        reacquisitionCount++
        // The displacement is measured on the first onSample after the loss.
    }

    fun snapshot(): GazeSmoothingSnapshot {
        val n = sampleCount
        return GazeSmoothingSnapshot(
            sampleCount = n,
            lossCount = lossCount,
            reacquisitionCount = reacquisitionCount,
            saccadeCount = saccadeCount,
            lastRawVelocityNormPerSec = lastRawVelocity,
            avgRawVelocityNormPerSec = if (n == 0L) 0f else (rawVelocityAccum / n).toFloat(),
            maxRawVelocityNormPerSec = maxRawVelocity,
            meanRawToFilteredDisplacement = if (n == 0L) 0f else (rawToFilteredAccum / n).toFloat(),
            maxRawToFilteredDisplacement = maxRawToFiltered,
            restJitterMax = restJitterMax,
            lastReacquisitionDisplacement = lastReacquisitionDisplacement,
        )
    }

    fun reset() {
        sampleCount = 0L
        lossCount = 0L
        reacquisitionCount = 0L
        saccadeCount = 0L
        rawVelocityAccum = 0.0
        lastRawVelocity = 0f
        maxRawVelocity = 0f
        rawToFilteredAccum = 0.0
        maxRawToFiltered = 0f
        restJitterMax = 0f
        lastTimestampMs = Long.MIN_VALUE
        pendingReacquisition = false
        lastReacquisitionDisplacement = 0f
    }

    companion object {
        /**
         * Raw gaze faster than this (normalized screen units/second) counts as a
         * deliberate saccade (a fast look across a phone screen is ~1–3 u/s).
         */
        const val SACCADE_VELOCITY_NORM_PER_SEC = 0.6f

        /** Raw gaze slower than this is "staring" — residual motion is jitter. */
        const val STILL_RAW_VELOCITY_NORM_PER_SEC = 0.05f
    }
}
