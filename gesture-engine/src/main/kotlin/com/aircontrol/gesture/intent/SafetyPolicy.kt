package com.aircontrol.gesture.intent

/**
 * Central safety policy for executable gesture intent.
 *
 * False positives are more damaging than missed actions, so ambiguous input is
 * deliberately rejected. All thresholds are expressed in normalized camera
 * coordinates and monotonic milliseconds.
 */
object SafetyPolicy {
    const val MIN_CLICK_CONFIDENCE = 0.72f
    const val MIN_DOUBLE_CLICK_CONFIDENCE = 0.80f
    const val MIN_CUSTOM_GESTURE_CONFIDENCE = 0.82f

    const val CLICK_SETTLE_MS = 90L
    const val CLICK_MAX_TRAVEL = 0.018f
    const val CLICK_COOLDOWN_MS = 180L

    const val DOUBLE_CLICK_WINDOW_MS = 320L
    const val DOUBLE_CLICK_MAX_DISTANCE_SQUARED = 0.0020f

    const val CUSTOM_GESTURE_ARMING_MS = 160L
    const val CUSTOM_GESTURE_RELEASE_MS = 140L

    fun isClickSafe(
        confidence: Float,
        stationaryMs: Long,
        travel: Float,
        nowMs: Long,
        lastClickMs: Long,
    ): Boolean =
        confidence >= MIN_CLICK_CONFIDENCE &&
            stationaryMs >= CLICK_SETTLE_MS &&
            travel <= CLICK_MAX_TRAVEL &&
            (lastClickMs == Long.MIN_VALUE || nowMs - lastClickMs >= CLICK_COOLDOWN_MS)

    fun isDoubleClickCandidate(
        confidence: Float,
        nowMs: Long,
        lastClickMs: Long,
        distanceSquared: Float,
    ): Boolean =
        confidence >= MIN_DOUBLE_CLICK_CONFIDENCE &&
            lastClickMs != Long.MIN_VALUE &&
            nowMs - lastClickMs in 1L..DOUBLE_CLICK_WINDOW_MS &&
            distanceSquared <= DOUBLE_CLICK_MAX_DISTANCE_SQUARED

    fun isCustomGestureSafe(confidence: Float, heldMs: Long): Boolean =
        confidence >= MIN_CUSTOM_GESTURE_CONFIDENCE && heldMs >= CUSTOM_GESTURE_ARMING_MS
}
