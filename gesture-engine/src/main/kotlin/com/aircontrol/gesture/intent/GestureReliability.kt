package com.aircontrol.gesture.intent

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Central, deterministic reliability policy for pinch, double-click, drag and swipe.
 * This class never dispatches system actions; it only decides whether a candidate
 * gesture is stable enough to be executed.
 */
class GestureReliability(
    private val pinchStartHoldMs: Long = 80L,
    private val pinchReleaseHoldMs: Long = 80L,
    private val doubleClickWindowMs: Long = 350L,
    private val doubleClickMaxDistance: Float = 0.05f,
    private val swipeSuppressionAfterPinchMs: Long = 350L,
) {
    init {
        require(pinchStartHoldMs >= 0)
        require(pinchReleaseHoldMs >= 0)
        require(doubleClickWindowMs > 0)
        require(doubleClickMaxDistance > 0f)
        require(swipeSuppressionAfterPinchMs >= 0)
    }

    private var pinchCandidateSinceMs = Long.MIN_VALUE
    private var pinchReleaseSinceMs = Long.MIN_VALUE
    private var lastPinchEndMs = Long.MIN_VALUE
    private var lastClickMs = Long.MIN_VALUE
    private var lastClickX = Float.NaN
    private var lastClickY = Float.NaN

    fun reset() {
        pinchCandidateSinceMs = Long.MIN_VALUE
        pinchReleaseSinceMs = Long.MIN_VALUE
        lastPinchEndMs = Long.MIN_VALUE
        lastClickMs = Long.MIN_VALUE
        lastClickX = Float.NaN
        lastClickY = Float.NaN
    }

    fun pinchStartStable(distanceRatio: Float, threshold: Float, timestampMs: Long): Boolean {
        if (distanceRatio >= threshold) {
            pinchCandidateSinceMs = Long.MIN_VALUE
            return false
        }
        if (pinchCandidateSinceMs == Long.MIN_VALUE) pinchCandidateSinceMs = timestampMs
        return timestampMs - pinchCandidateSinceMs >= pinchStartHoldMs
    }

    fun pinchReleaseStable(distanceRatio: Float, threshold: Float, timestampMs: Long): Boolean {
        if (distanceRatio <= threshold) {
            pinchReleaseSinceMs = Long.MIN_VALUE
            return false
        }
        if (pinchReleaseSinceMs == Long.MIN_VALUE) pinchReleaseSinceMs = timestampMs
        return timestampMs - pinchReleaseSinceMs >= pinchReleaseHoldMs
    }

    fun markPinchEnd(timestampMs: Long) {
        lastPinchEndMs = timestampMs
    }

    fun shouldSuppressSwipe(timestampMs: Long): Boolean =
        lastPinchEndMs != Long.MIN_VALUE &&
            timestampMs - lastPinchEndMs < swipeSuppressionAfterPinchMs

    fun registerClick(timestampMs: Long, x: Float, y: Float): Boolean {
        val doubleClick = lastClickMs != Long.MIN_VALUE &&
            timestampMs - lastClickMs in 1L..doubleClickWindowMs &&
            distance(x, y, lastClickX, lastClickY) <= doubleClickMaxDistance
        lastClickMs = timestampMs
        lastClickX = x.coerceIn(0f, 1f)
        lastClickY = y.coerceIn(0f, 1f)
        return doubleClick
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        if (x2.isNaN() || y2.isNaN()) return Float.POSITIVE_INFINITY
        return sqrt((x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2))
    }
}
