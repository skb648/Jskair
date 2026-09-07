package com.aircontrol.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Perf audit P18: the telemetry recorder must be deterministic (explicit
 * time), bounded (ring buffers), and its logging strictly rate-limited —
 * it exists to diagnose performance problems and must never become one.
 */
class PerfTelemetryTest {

    @Before
    fun resetTelemetry() {
        PerfTelemetry.reset()
        PerfTelemetry.enableLogging = false
        PerfTelemetry.loggingSink = {}
    }

    @Test
    fun `snapshot on a fresh telemetry is all zeros`() {
        val s = PerfTelemetry.snapshot()
        assertEquals(0L, s.framesProcessed)
        assertEquals(0L, s.intervalP50Ms)
        assertEquals(0f, s.actualFps, 0f)
        assertEquals(0L, s.watchdogActions)
        assertTrue(s.events.isEmpty())
    }

    @Test
    fun `frame intervals are recorded from explicit timestamps`() {
        PerfTelemetry.recordFrameProcessed(0L)
        PerfTelemetry.recordFrameProcessed(40L)
        PerfTelemetry.recordFrameProcessed(90L)
        val s = PerfTelemetry.snapshot()
        assertEquals(3L, s.framesProcessed)
        assertEquals(40L, s.intervalMinMs)
        assertEquals(50L, s.intervalMaxMs)
    }

    @Test
    fun `non-advancing timestamps do not poison the interval window`() {
        // Dropout hardening analogue: an equal/earlier timestamp must not
        // record a zero/negative interval.
        PerfTelemetry.recordFrameProcessed(100L)
        PerfTelemetry.recordFrameProcessed(100L)
        PerfTelemetry.recordFrameProcessed(50L)
        PerfTelemetry.recordFrameProcessed(140L)
        val s = PerfTelemetry.snapshot()
        assertEquals(4L, s.framesProcessed)
        assertEquals(40L, s.intervalMinMs)
        assertEquals(40L, s.intervalMaxMs)
    }

    @Test
    fun `percentiles come from the sorted window`() {
        // 100 intervals: 95×40ms + 5×400ms → p95 lands on a spike, p50 does not.
        var t = 0L
        PerfTelemetry.recordFrameProcessed(t)
        repeat(95) { t += 40; PerfTelemetry.recordFrameProcessed(t) }
        repeat(5) { t += 400; PerfTelemetry.recordFrameProcessed(t) }
        val s = PerfTelemetry.snapshot()
        assertEquals(40L, s.intervalP50Ms)
        assertEquals(400L, s.intervalP95Ms)
        assertEquals(400L, s.intervalMaxMs)
    }

    @Test
    fun `throttle and no-tracker drops are counted separately`() {
        repeat(3) { PerfTelemetry.recordFrameDroppedThrottle() }
        repeat(2) { PerfTelemetry.recordFrameDroppedNoTracker() }
        val s = PerfTelemetry.snapshot()
        assertEquals(3L, s.framesDroppedThrottle)
        assertEquals(2L, s.framesDroppedNoTracker)
    }

    @Test
    fun `inference durations report average and max`() {
        PerfTelemetry.recordHandInference(10)
        PerfTelemetry.recordHandInference(30)
        PerfTelemetry.recordFaceInference(20)
        PerfTelemetry.recordAnalyzerDuration(5)
        val s = PerfTelemetry.snapshot()
        assertEquals(20L, s.handInferAvgMs)
        assertEquals(30L, s.handInferMaxMs)
        assertEquals(20L, s.faceInferAvgMs)
        assertEquals(5L, s.analyzerAvgMs)
    }

    @Test
    fun `summary logging is disabled by default`() {
        var emitted = 0
        PerfTelemetry.loggingSink = { emitted++ }
        assertFalse(PerfTelemetry.maybeLogSummary(0L))
        assertFalse(PerfTelemetry.maybeLogSummary(10 * 60_000L))
        assertEquals(0, emitted)
    }

    @Test
    fun `summary logging is rate-limited to at most once per 30s`() {
        PerfTelemetry.enableLogging = true
        var emitted = 0
        PerfTelemetry.loggingSink = { emitted++ }
        assertTrue(PerfTelemetry.maybeLogSummary(0L))
        // Every 5s watchdog tick inside the 30s window must stay silent.
        repeat(5) { i -> assertFalse(PerfTelemetry.maybeLogSummary(5_000L * (i + 1))) }
        assertEquals(1, emitted)
        // Past the window, the next call emits again.
        assertTrue(PerfTelemetry.maybeLogSummary(31_000L))
        assertEquals(2, emitted)
    }

    @Test
    fun `summary resets duration accumulators but keeps cumulative counters`() {
        PerfTelemetry.enableLogging = true
        PerfTelemetry.loggingSink = {}
        PerfTelemetry.recordFrameProcessed(0L)
        PerfTelemetry.recordFrameProcessed(50L)
        PerfTelemetry.recordHandInference(20)
        PerfTelemetry.maybeLogSummary(0L)
        val s = PerfTelemetry.snapshot()
        assertEquals(0L, s.handInferAvgMs)      // accumulator reset
        assertEquals(2L, s.framesProcessed)    // cumulative counter kept
    }

    @Test
    fun `event ring is bounded`() {
        repeat(200) { i -> PerfTelemetry.recordCameraLifecycle("evt$i", i.toLong()) }
        assertEquals(64, PerfTelemetry.snapshot().events.size)
        // Oldest evicted, newest kept.
        assertTrue(PerfTelemetry.snapshot().events.last().contains("evt199"))
    }

    @Test
    fun `watchdog actions are counted and evented`() {
        PerfTelemetry.recordWatchdogAction("revive", 1_000L)
        PerfTelemetry.recordWatchdogAction("restart-camera", 2_000L)
        val s = PerfTelemetry.snapshot()
        assertEquals(2L, s.watchdogActions)
        assertTrue(s.events.any { it.contains("watchdog:revive") })
        assertTrue(s.events.any { it.contains("watchdog:restart-camera") })
    }

    @Test
    fun `reset clears everything`() {
        PerfTelemetry.recordFrameProcessed(0L)
        PerfTelemetry.recordFrameProcessed(40L)
        PerfTelemetry.recordWatchdogAction("revive", 0L)
        PerfTelemetry.recordConfiguredFps(24)
        PerfTelemetry.reset()
        val s = PerfTelemetry.snapshot()
        assertEquals(0L, s.framesProcessed)
        assertEquals(0, s.configuredFps)
        assertEquals(0L, s.watchdogActions)
        assertTrue(s.events.isEmpty())
    }
}
