package com.aircontrol.tracking

import com.aircontrol.runtime.PerfTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

class LowLatencyAndTelemetryAuditTest {

    @Before
    fun setUp() {
        PerfTelemetry.reset()
    }

    @Test
    fun `CursorSmoother resets velocity on frame gap exceeding 120ms so prediction does not spike`() {
        val smoother = CursorSmoother(minCutoff = 1.5f, beta = 18f, leadMs = 16f, deadZoneNormalized = 0.0003f)
        // Warm up with fast motion to build velocity
        smoother.filter(0.10f, 0.50f, 100L, confidence = 0.95f)
        smoother.filter(0.20f, 0.50f, 116L, confidence = 0.95f)
        val (fastX, _) = smoother.filter(0.30f, 0.50f, 132L, confidence = 0.95f)
        // With positive velocity lead, fastX tracks or slightly leads 0.30
        assertTrue(fastX > 0.25f)

        // Simulate a 300ms tracking dropout/gap followed by a stationary point at 0.31
        val (afterGapX, _) = smoother.filter(0.31f, 0.50f, 432L, confidence = 0.95f)
        // Must not overshoot past 0.31f due to stale pre-gap velocity
        assertTrue("Expected no velocity spike after frame gap, got $afterGapX", afterGapX <= 0.311f)
    }

    @Test
    fun `CursorSmoother suppresses velocity lead extrapolation when landmark confidence is low`() {
        val highConfSmoother = CursorSmoother(minCutoff = 1.5f, beta = 18f, leadMs = 16f, deadZoneNormalized = 0.0003f)
        val lowConfSmoother = CursorSmoother(minCutoff = 1.5f, beta = 18f, leadMs = 16f, deadZoneNormalized = 0.0003f)

        highConfSmoother.filter(0.20f, 0.50f, 100L, confidence = 0.95f)
        lowConfSmoother.filter(0.20f, 0.50f, 100L, confidence = 0.30f)

        val (highX, _) = highConfSmoother.filter(0.40f, 0.50f, 116L, confidence = 0.95f)
        val (lowX, _) = lowConfSmoother.filter(0.40f, 0.50f, 116L, confidence = 0.30f)

        // Low-confidence sample must be more conservatively filtered (less forward excursion) than high-confidence sample
        assertTrue("Expected low-confidence output ($lowX) < high-confidence output ($highX)", lowX < highX)
    }

    @Test
    fun `CalibrationMetrics computes exact 2D Euclidean pixel error and visual angle in degrees`() {
        val metrics = CalibrationMetrics(
            meanNormalizedError = 0.04,
            medianNormalizedError = 0.035,
            p95NormalizedError = 0.08,
            maxNormalizedError = 0.10,
            horizontalMae = 0.03,
            verticalMae = 0.025,
            sampleCount = 90,
            validationSampleCount = 18,
            meanPixelError = 60.0,
            medianPixelError = 52.0,
            p95PixelError = 96.0,
            maxPixelError = 120.0,
        )
        val (meanDeg, p95Deg) = metrics.estimateAngularErrorDeg(
            viewingDistanceMm = 450.0,
            screenWidthMm = 238.0,
            screenHeightMm = 149.0,
        )
        assertTrue("Expected positive mean angular error in degrees, got $meanDeg", meanDeg in 0.5..2.0)
        assertTrue("Expected p95 angular error > mean angular error", p95Deg > meanDeg)
    }

    @Test
    fun `PerfTelemetry defaults delegates to UNINITIALIZED and tracks independent hand and face result FPS`() {
        val initial = PerfTelemetry.snapshot()
        assertEquals("UNINITIALIZED", initial.handDelegate)
        assertEquals("UNINITIALIZED", initial.faceDelegate)
        assertEquals(0f, initial.handResultFps, 0.001f)
        assertEquals(0f, initial.faceResultFps, 0.001f)

        // Record hand results at 33ms intervals (~30.3 FPS) and face results at 50ms intervals (20.0 FPS)
        PerfTelemetry.recordHandInference(durationMs = 14L, nowMs = 1000L)
        PerfTelemetry.recordHandInference(durationMs = 15L, nowMs = 1033L)
        PerfTelemetry.recordHandInference(durationMs = 13L, nowMs = 1066L)

        PerfTelemetry.recordFaceInference(durationMs = 18L, nowMs = 1000L)
        PerfTelemetry.recordFaceInference(durationMs = 19L, nowMs = 1050L)
        PerfTelemetry.recordFaceInference(durationMs = 17L, nowMs = 1100L)

        PerfTelemetry.recordCursorDispatched(frameTimestampMs = 1066L, dispatchNowMs = 1088L)

        val snap = PerfTelemetry.snapshot()
        assertTrue(abs(snap.handResultFps - 30.3f) < 0.5f)
        assertTrue(abs(snap.faceResultFps - 20.0f) < 0.5f)
        assertEquals(22L, snap.cursorDispatchAgeP50Ms)
    }
}
