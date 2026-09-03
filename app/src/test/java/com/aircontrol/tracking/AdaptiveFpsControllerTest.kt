package com.aircontrol.tracking

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the adaptive frame-rate controller.
 *
 * These pin the fix for "AirControl says it idles at 5 FPS but the battery still
 * drains": [AdaptiveFpsController.onHandLost] used to cancel and re-launch the
 * downgrade timer on *every* frame without a hand. Frames keep arriving while the
 * camera runs, so each one pushed the deadline 5 s into the future and scan mode
 * never engaged - unless you covered the camera completely.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdaptiveFpsControllerTest {

    @Test
    fun `scan mode engages even while handless frames keep arriving`() = runTest {
        val controllerScope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val controller = AdaptiveFpsController(
            scope = controllerScope,
            configuredFps = 24,
            scanFps = 5,
            noHandTimeoutMs = 5_000L,
        )
        runCurrent()
        assertEquals("starts at the configured rate", 24, controller.currentFps.value)

        // 100 frames without a hand, 40 ms apart: 4 seconds of "no hand" so far.
        repeat(100) { i ->
            controller.onHandLost(timestampMs = i * 40L)
            advanceTimeBy(40L)
            runCurrent()
        }
        assertEquals("4s of no hand is not enough", 24, controller.currentFps.value)

        // Crossing the 5s mark from the *first* lost frame must downgrade, even
        // though more lost frames keep arriving (each one used to push the
        // deadline forward, so this is the regression guard).
        repeat(40) { i ->
            controller.onHandLost(timestampMs = 4_000L + i * 40L)
            advanceTimeBy(40L)
            runCurrent()
        }
        assertEquals("scan mode engaged", 5, controller.currentFps.value)
        assertTrue(controller.isHandDetected.value.not())
    }

    @Test
    fun `a hand coming back restores full speed and disarms the downgrade`() = runTest {
        val controllerScope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val controller = AdaptiveFpsController(
            scope = controllerScope,
            configuredFps = 24,
            scanFps = 5,
            noHandTimeoutMs = 5_000L,
        )
        runCurrent()

        controller.onHandLost(0L)
        advanceTimeBy(3_000L)
        runCurrent()

        repeat(60) { i ->
            controller.onHandDetected(timestampMs = 3_000L + i * 40L)
            advanceTimeBy(40L)
            runCurrent()
        }
        assertEquals("hand back: full rate", 24, controller.currentFps.value)
        assertTrue(controller.isHandDetected.value)

        // ...and staying still for another 10s must not drop the rate.
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(24, controller.currentFps.value)
    }

    @Test
    fun `reset returns to full speed and clears detection`() = runTest {
        val controllerScope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val controller = AdaptiveFpsController(
            scope = controllerScope,
            configuredFps = 24,
            scanFps = 5,
            noHandTimeoutMs = 5_000L,
        )
        runCurrent()
        controller.onHandLost(0L)
        advanceTimeBy(6_000L)
        runCurrent()
        assertEquals(5, controller.currentFps.value)

        controller.reset()
        runCurrent()
        assertEquals(24, controller.currentFps.value)
        assertFalse(controller.isHandDetected.value)

        // After a reset the next handless frame re-arms the timer (it must not be
        // permanently disarmed by the reset).
        controller.onHandLost(7_000L)
        advanceTimeBy(5_100L)
        runCurrent()
        assertEquals(5, controller.currentFps.value)
    }

    @Test
    fun `battery saver style fps changes apply while idle`() = runTest {
        val controllerScope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val controller = AdaptiveFpsController(
            scope = controllerScope,
            configuredFps = 24,
            scanFps = 5,
            noHandTimeoutMs = 5_000L,
        )
        runCurrent()
        controller.updateConfiguredFps(15)
        runCurrent()
        assertEquals("at full speed, a new cap applies immediately", 15, controller.currentFps.value)
        assertEquals("interval follows the rate", 1000L / 15, controller.analysisIntervalMs)
    }
}
