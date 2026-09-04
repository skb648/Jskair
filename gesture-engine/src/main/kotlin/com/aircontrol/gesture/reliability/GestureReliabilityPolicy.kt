package com.aircontrol.gesture.reliability

/** Conservative arbitration policy for real-time gesture execution. */
object GestureReliabilityPolicy {
    const val PINCH_MIN_HOLD_MS = 80L
    const val DOUBLE_TAP_MAX_GAP_MS = 300L
    const val DRAG_MAX_STEP_FRACTION = 0.10f
    const val SWIPE_COOLDOWN_MS = 350L
    const val SWIPE_SUPPRESSION_AFTER_PINCH_MS = 250L
    const val LOW_CONFIDENCE_THRESHOLD = 0.70f

    fun allowSwipeAfterPinch(nowMs: Long, lastPinchEndMs: Long): Boolean =
        lastPinchEndMs <= 0L || nowMs - lastPinchEndMs >= SWIPE_SUPPRESSION_AFTER_PINCH_MS

    fun withinDoubleTapWindow(nowMs: Long, previousTapMs: Long): Boolean =
        previousTapMs > 0L && nowMs - previousTapMs <= DOUBLE_TAP_MAX_GAP_MS
}
