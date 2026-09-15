package com.aircontrol.runtime

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression tests for the release-visible startup trail.
 *
 * Confirmed bug: every startup stage of the accessibility/camera services was
 * logged with Timber.d/i, which the release build strips (R8
 * -assumenosideeffects) or filters (ReleaseTree WARN+). A signed build whose
 * service stalled in any stage therefore produced *no* logcat output. StageLog
 * must (1) emit through a sink that carries success and failure with component,
 * stage, exception class and message, and (2) never depend on Timber.d.
 */
class StageLogTest {

    private val captured = mutableListOf<Pair<StageLog.Record, Throwable?>>()
    private lateinit var originalSink: (StageLog.Record, Throwable?) -> Unit
    private lateinit var originalClock: () -> Long

    @Before
    fun setUp() {
        originalSink = StageLog.sink
        originalClock = StageLog.clock
        StageLog.clearForTest()
        StageLog.sink = { record, error -> captured += record to error }
        StageLog.clock = { 1234L }
    }

    @After
    fun tearDown() {
        StageLog.sink = originalSink
        StageLog.clock = originalClock
        StageLog.clearForTest()
    }

    @Test
    fun `success record carries stage component and detail`() {
        StageLog.success(StageLog.Stage.SERVICE_CONNECTED, "GestureControlAccessibilityService", "onServiceConnected")

        assertEquals(1, captured.size)
        val (record, error) = captured.single()
        assertNull(error)
        assertTrue(record.ok)
        assertEquals("SERVICE_CONNECTED", record.stage)
        assertEquals("GestureControlAccessibilityService", record.component)
        assertEquals(1234L, record.elapsedRealtimeMs)
        assertEquals(
            "SERVICE_CONNECTED OK component=GestureControlAccessibilityService detail=onServiceConnected",
            record.format(),
        )
    }

    @Test
    fun `failure record captures exception class and message`() {
        val boom = IllegalStateException("Hilt component not ready")
        StageLog.failure(StageLog.Stage.DI_INIT, "CameraService", "attempt=2", boom)

        val (record, error) = captured.single()
        assertFalse(record.ok)
        assertEquals(boom, error)
        assertEquals("java.lang.IllegalStateException", record.errorClass)
        assertEquals("Hilt component not ready", record.errorMessage)
        assertEquals(
            "DI_INIT FAIL component=CameraService detail=attempt=2 error=java.lang.IllegalStateException: Hilt component not ready",
            record.format(),
        )
    }

    @Test
    fun `failure without throwable still formats as FAIL`() {
        StageLog.failure(StageLog.Stage.HAND_TRACKER_INIT, "HandTrackerImpl", "hand_landmarker.task not readable from assets")

        val record = captured.single().first
        assertFalse(record.ok)
        assertNull(record.errorClass)
        assertEquals(
            "HAND_TRACKER_INIT FAIL component=HandTrackerImpl detail=hand_landmarker.task not readable from assets",
            record.format(),
        )
    }

    @Test
    fun `recent ring is newest first and bounded`() {
        repeat(60) { StageLog.success(StageLog.Stage.CAMERA_INIT_RUNNING, "CameraService", "n=$it") }

        val recent = StageLog.recentRecords
        assertEquals(48, recent.size)
        assertEquals("n=59", recent.first().detail)
        assertEquals("n=12", recent.last().detail)
    }

    @Test
    fun `all required diagnostics stages are defined`() {
        val required = listOf(
            "SERVICE_CREATE", "SERVICE_CONNECTED", "DI_INIT", "OVERLAY_INIT",
            "CAMERA_INIT_FOREGROUND", "CAMERA_INIT_BIND", "CAMERA_INIT_RUNNING",
            "FACE_TRACKER_INIT", "HAND_TRACKER_INIT",
        )
        val defined = listOf(
            StageLog.Stage.SERVICE_CREATE, StageLog.Stage.SERVICE_CONNECTED, StageLog.Stage.DI_INIT,
            StageLog.Stage.OVERLAY_INIT, StageLog.Stage.CAMERA_INIT_FOREGROUND, StageLog.Stage.CAMERA_INIT_BIND,
            StageLog.Stage.CAMERA_INIT_RUNNING, StageLog.Stage.FACE_TRACKER_INIT, StageLog.Stage.HAND_TRACKER_INIT,
        )
        assertEquals(required, defined)
    }
}
