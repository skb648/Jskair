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

/** Tests the adaptive frame-rate controller and its safety invariants. */
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

        repeat(100) { i ->
            controller.onHandLost(timestampMs = i * 40L)
            advanceTimeBy(40L)
            runCurrent()
        }
        assertEquals("4s of no hand is not enough", 24, controller.currentFps.value)

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

        controller.onHandLost(7_000L)
        advanceTimeBy(5_100L)
        runCurrent()
        assertEquals(5, controller.currentFps.value)
    }

    @Test
    fun `configured FPS changes apply while full speed`() = runTest {
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
        assertEquals("15 FPS requires a 67 ms ceiling interval", 67L, controller.analysisIntervalMs)
        assertTrue("integer interval must not permit more than 15 FPS", 1000.0 / controller.analysisIntervalMs <= 15.0)
    }

    @Test
    fun `production quantization never exceeds requested FPS`() {
        val cases = mapOf(
            1 to 5,
            5 to 5,
            6 to 5,
            14 to 10,
            15 to 15,
            20 to 15,
            23 to 15,
            24 to 24,
            25 to 24,
            30 to 30,
            120 to 30,
        )
        cases.forEach { (requested, expected) ->
            assertEquals("requested=$requested", expected, AdaptiveFpsController.coerceToSupportedFps(requested))
        }
    }
}
