package com.aircontrol.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression: after a collector stall the 64-deep hand transport buffer replayed
 * stale frames into the gesture engine as if live. The policy must shed only
 * detected frames past the age bound and must never shed the "hand lost" frame.
 */
class FrameFreshnessPolicyTest {

    @Test
    fun `fresh detected frames are accepted`() {
        val p = FrameFreshnessPolicy(maxAgeMs = 250L)
        assertTrue(p.accept(frameTimestampMs = 10_000L, isDetected = true, nowMs = 10_040L))
        assertTrue(p.accept(frameTimestampMs = 10_000L, isDetected = true, nowMs = 10_250L))
        assertEquals(0L, p.shedCount)
    }

    @Test
    fun `stale detected frames are shed and counted`() {
        val p = FrameFreshnessPolicy(maxAgeMs = 250L)
        assertFalse(p.accept(frameTimestampMs = 10_000L, isDetected = true, nowMs = 10_251L))
        assertFalse(p.accept(frameTimestampMs = 10_033L, isDetected = true, nowMs = 10_600L))
        assertEquals(2L, p.shedCount)
    }

    @Test
    fun `hand-lost frame is never shed however old`() {
        val p = FrameFreshnessPolicy(maxAgeMs = 250L)
        assertTrue(p.accept(frameTimestampMs = 0L, isDetected = false, nowMs = 60_000L))
        assertEquals(0L, p.shedCount)
    }

    @Test
    fun `replay burst after stall keeps only the frames that are still live`() {
        val p = FrameFreshnessPolicy(maxAgeMs = 250L)
        // 20 frames at 33 ms were buffered while the collector was stalled; the
        // collector resumes at t=1000 and drains them all "instantly".
        val now = 1_000L
        val accepted = (0 until 20).count { i -> p.accept(frameTimestampMs = 340L + i * 33L, isDetected = true, nowMs = now) }
        // Frames with ts >= 750 (age <= 250) survive: i >= 13 → 7 frames.
        assertEquals(7, accepted)
        assertEquals(13L, p.shedCount)
    }

    @Test
    fun `reset clears the counter`() {
        val p = FrameFreshnessPolicy(maxAgeMs = 1L)
        p.accept(0L, true, 100L)
        p.reset()
        assertEquals(0L, p.shedCount)
    }
}
