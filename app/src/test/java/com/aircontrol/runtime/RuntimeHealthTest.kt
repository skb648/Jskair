package com.aircontrol.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RuntimeHealthTest {
    @Before
    fun reset() {
        RuntimeHealth.reset()
    }

    @Test
    fun off_when_tracking_not_requested() {
        RuntimeHealth.update(
            trackingRequested = false,
            accessibilityConnected = true,
            cameraRunning = true,
            handTrackerReady = true,
            freshFrames = true,
        )
        assertEquals(RuntimeReadiness.OFF, RuntimeHealth.state.value.readiness)
        assertFalse(RuntimeHealth.state.value.isReady)
    }

    @Test
    fun blocked_when_accessibility_is_missing() {
        RuntimeHealth.update(
            trackingRequested = true,
            accessibilityConnected = false,
            cameraRunning = true,
            handTrackerReady = true,
            freshFrames = true,
        )
        assertEquals(RuntimeReadiness.BLOCKED, RuntimeHealth.state.value.readiness)
    }

    @Test
    fun degraded_until_tracker_and_fresh_frame_are_ready() {
        RuntimeHealth.update(
            trackingRequested = true,
            accessibilityConnected = true,
            cameraRunning = true,
            handTrackerReady = true,
            freshFrames = false,
        )
        assertEquals(RuntimeReadiness.DEGRADED, RuntimeHealth.state.value.readiness)

        RuntimeHealth.update(freshFrames = true)
        assertEquals(RuntimeReadiness.READY, RuntimeHealth.state.value.readiness)
        assertTrue(RuntimeHealth.state.value.isReady)
    }

    @Test
    fun paused_is_explicit_and_not_ready() {
        RuntimeHealth.update(
            trackingRequested = true,
            accessibilityConnected = true,
            cameraRunning = false,
            cameraPaused = true,
            handTrackerReady = true,
            freshFrames = false,
            reason = "user-paused",
        )
        assertEquals(RuntimeReadiness.PAUSED, RuntimeHealth.state.value.readiness)
        assertFalse(RuntimeHealth.state.value.isReady)
    }

    @Test
    fun camera_loss_enters_recovery() {
        RuntimeHealth.update(
            trackingRequested = true,
            accessibilityConnected = true,
            cameraRunning = false,
            handTrackerReady = true,
            freshFrames = false,
            reason = "frame-stall",
        )
        assertEquals(RuntimeReadiness.RECOVERING, RuntimeHealth.state.value.readiness)
    }
}
