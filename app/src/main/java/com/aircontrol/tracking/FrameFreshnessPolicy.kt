package com.aircontrol.tracking

/**
 * Freshness gate for hand frames consumed off the tracker's SharedFlow.
 *
 * The hand transport keeps a 64-deep DROP_OLDEST buffer on purpose (a stalled
 * collector must not eat the frame that completes a pinch). The flip side was
 * never handled: once the collector resumes after a stall (GC pause, main-thread
 * hop, thermal throttle) it replayed *every* buffered frame into the gesture
 * engine as though live — a burst of up to 64 stale positions, i.e. a cursor
 * that rewinds and catches up, and a swipe velocity computed from wall time that
 * no longer matches the landmarks.
 *
 * Rule: a *detected* frame older than [maxAgeMs] is shed. A non-detected
 * ("hand lost") frame is never shed, because the engine relies on it to disarm
 * cleanly, and it is exactly the frame the deep buffer exists to protect.
 * Sheds are counted so the debug screen / telemetry can prove or disprove a
 * stall on a real device; nothing here allocates per frame.
 */
class FrameFreshnessPolicy(private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS) {

    @Volatile var shedCount: Long = 0L
        private set

    /** `true` when the frame should be processed, `false` when it is stale. */
    fun accept(frameTimestampMs: Long, isDetected: Boolean, nowMs: Long): Boolean {
        if (!isDetected) return true
        val age = nowMs - frameTimestampMs
        if (age > maxAgeMs) {
            shedCount++
            return false
        }
        return true
    }

    fun reset() {
        shedCount = 0L
    }

    companion object {
        /**
         * Generous by design: the bound only has to catch stalls, not jitter. At
         * 15–30 fps a healthy pipeline delivers frames well under 100 ms old;
         * anything beyond a quarter second is a replay, not live tracking.
         */
        const val DEFAULT_MAX_AGE_MS = 250L
    }
}
