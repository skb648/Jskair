package com.aircontrol.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue 5 acceptance: velocity-aware smoothing metrics stay primitive-only on
 * the hot path and report what the filter is doing (latency, jitter, teleports).
 */
class GazeSmoothingMetricsTest {

    @Test
    fun `counts samples and reports velocity`() {
        val m = GazeSmoothingMetrics()
        // Move 0.5 → 0.5 of the screen in 100 ms = 5.0 units/sec (fast saccade).
        m.onSample(0.1f, 0.1f, 0.11f, 0.11f, 0L)
        m.onSample(0.6f, 0.1f, 0.5f, 0.1f, 100L)
        m.onSample(0.6f, 0.1f, 0.5f, 0.1f, 200L)

        val s = m.snapshot()
        assertEquals(3L, s.sampleCount)
        assertEquals(1L, s.saccadeCount)
        assertTrue(s.maxRawVelocityNormPerSec >= 5f)
        assertTrue(s.avgRawVelocityNormPerSec > 0f)
    }

    @Test
    fun `still gaze reports jitter not saccades`() {
        val m = GazeSmoothingMetrics()
        var t = 0L
        for (i in 1..200) {
            t += 33L
            // Raw barely drifts (< 0.05 u/s); filter output wobbles by 0.002.
            m.onSample(0.5f, 0.5f, 0.5f + 0.002f * (i % 2), 0.5f, t)
        }
        val s = m.snapshot()
        assertEquals(0L, s.saccadeCount)
        assertTrue(s.restJitterMax > 0f)
        assertTrue(s.restJitterMax < 0.01f)
    }

    @Test
    fun `tracking loss then reacquire records teleport displacement`() {
        val m = GazeSmoothingMetrics()
        m.onSample(0.2f, 0.2f, 0.2f, 0.2f, 0L)
        m.onTrackingLost(0.2f, 0.2f)
        m.onReacquired()
        // Cursor reappears at the opposite corner → large displacement.
        m.onSample(0.9f, 0.9f, 0.9f, 0.9f, 100L)

        val s = m.snapshot()
        assertEquals(1L, s.lossCount)
        assertEquals(1L, s.reacquisitionCount)
        assertTrue(s.lastReacquisitionDisplacement > 0.5f)
    }

    @Test
    fun `raw-to-filtered displacement reflects filter lag`() {
        val m = GazeSmoothingMetrics()
        // Raw leads filter by 0.05 in each axis.
        m.onSample(0.50f, 0.50f, 0.45f, 0.45f, 0L)
        m.onSample(0.55f, 0.55f, 0.50f, 0.50f, 33L)
        val s = m.snapshot()
        assertTrue(s.meanRawToFilteredDisplacement >= 0.07f)
        assertTrue(s.maxRawToFilteredDisplacement >= 0.07f)
    }

    @Test
    fun `reset clears everything`() {
        val m = GazeSmoothingMetrics()
        m.onSample(0.1f, 0.1f, 0.1f, 0.1f, 0L)
        m.onTrackingLost(0.1f, 0.1f)
        m.reset()
        val s = m.snapshot()
        assertEquals(0L, s.sampleCount)
        assertEquals(0L, s.lossCount)
        assertEquals(0L, s.reacquisitionCount)
        assertEquals(0f, s.avgRawVelocityNormPerSec, 0f)
    }
}
