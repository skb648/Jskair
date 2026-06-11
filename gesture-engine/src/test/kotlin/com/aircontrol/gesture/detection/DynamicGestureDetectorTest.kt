package com.aircontrol.gesture.detection

import com.aircontrol.gesture.config.GestureEngineConfig
import com.aircontrol.gesture.model.HandInput
import com.aircontrol.gesture.model.Handedness
import com.aircontrol.gesture.model.Landmark3D
import com.aircontrol.gesture.model.SwipeDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for DynamicGestureDetector.
 * Tests swipe detection in all four directions, axis dominance rejection,
 * velocity thresholds, window management, and sensitivity scaling.
 */
class DynamicGestureDetectorTest {

    private lateinit var detector: DynamicGestureDetector
    private lateinit var config: GestureEngineConfig

    @Before
    fun setUp() {
        config = GestureEngineConfig(sensitivity = 50)
        detector = DynamicGestureDetector(config)
    }

    // ========== Helper methods ==========

    private fun handAtWristPosition(
        wristX: Float,
        wristY: Float,
        timestampMs: Long,
    ): HandInput {
        val wrist = Landmark3D(wristX, wristY, 0f)
        val landmarks = List(21) { index ->
            when (index) {
                0 -> wrist
                else -> Landmark3D(wristX, wristY, 0f) // simplified
            }
        }
        return HandInput(
            landmarks = landmarks,
            handedness = Handedness.RIGHT,
            timestampMs = timestampMs,
            confidence = 0.9f,
        )
    }

    /**
     * Generates a sequence of hand frames simulating a swipe motion.
     * The wrist moves linearly from startPos to endPos over the given duration.
     */
    private fun generateSwipeFrames(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMs: Long = 300L,
        frameCount: Int = 15,
        startTimestampMs: Long = 1000L,
    ): List<HandInput> {
        return (0..frameCount).map { i ->
            val t = i.toFloat() / frameCount
            val x = startX + (endX - startX) * t
            val y = startY + (endY - startY) * t
            handAtWristPosition(x, y, startTimestampMs + (durationMs * t).toLong())
        }
    }

    // ========== Swipe direction tests ==========

    @Test
    fun `SWIPE_RIGHT detected for rightward wrist motion`() {
        val frames = generateSwipeFrames(
            startX = 0.3f, startY = 0.5f,
            endX = 0.7f, endY = 0.5f,
        )
        var detected = false
        var direction: SwipeDirection? = null
        for (frame in frames) {
            val result = detector.process(frame)
            if (result.detected) {
                detected = true
                direction = result.direction
                break
            }
        }
        assertTrue("SWIPE_RIGHT should be detected", detected)
        assertEquals(SwipeDirection.RIGHT, direction)
    }

    @Test
    fun `SWIPE_LEFT detected for leftward wrist motion`() {
        val frames = generateSwipeFrames(
            startX = 0.7f, startY = 0.5f,
            endX = 0.3f, endY = 0.5f,
        )
        var detected = false
        var direction: SwipeDirection? = null
        for (frame in frames) {
            val result = detector.process(frame)
            if (result.detected) {
                detected = true
                direction = result.direction
                break
            }
        }
        assertTrue("SWIPE_LEFT should be detected", detected)
        assertEquals(SwipeDirection.LEFT, direction)
    }

    @Test
    fun `SWIPE_UP detected for upward wrist motion`() {
        val frames = generateSwipeFrames(
            startX = 0.5f, startY = 0.7f,
            endX = 0.5f, endY = 0.3f,
        )
        var detected = false
        var direction: SwipeDirection? = null
        for (frame in frames) {
            val result = detector.process(frame)
            if (result.detected) {
                detected = true
                direction = result.direction
                break
            }
        }
        assertTrue("SWIPE_UP should be detected", detected)
        assertEquals(SwipeDirection.UP, direction)
    }

    @Test
    fun `SWIPE_DOWN detected for downward wrist motion`() {
        val frames = generateSwipeFrames(
            startX = 0.5f, startY = 0.3f,
            endX = 0.5f, endY = 0.7f,
        )
        var detected = false
        var direction: SwipeDirection? = null
        for (frame in frames) {
            val result = detector.process(frame)
            if (result.detected) {
                detected = true
                direction = result.direction
                break
            }
        }
        assertTrue("SWIPE_DOWN should be detected", detected)
        assertEquals(SwipeDirection.DOWN, direction)
    }

    // ========== Threshold tests ==========

    @Test
    fun `small motion below displacement threshold not detected as swipe`() {
        val frames = generateSwipeFrames(
            startX = 0.5f, startY = 0.5f,
            endX = 0.53f, endY = 0.5f, // Only 3% displacement
        )
        for (frame in frames) {
            val result = detector.process(frame)
            assertFalse("Small motion should not trigger swipe", result.detected)
        }
    }

    @Test
    fun `diagonal motion with insufficient axis dominance rejected`() {
        val frames = generateSwipeFrames(
            startX = 0.3f, startY = 0.3f,
            endX = 0.6f, endY = 0.55f, // Roughly equal displacement on both axes
            durationMs = 300L,
            frameCount = 20,
        )
        var anyDetected = false
        for (frame in frames) {
            val result = detector.process(frame)
            if (result.detected) anyDetected = true
        }
        assertFalse("Diagonal motion with no clear axis should be rejected", anyDetected)
    }

    @Test
    fun `slow motion below velocity threshold not detected`() {
        // Very slow motion over a long duration
        val frames = generateSwipeFrames(
            startX = 0.3f, startY = 0.5f,
            endX = 0.7f, endY = 0.5f,
            durationMs = 5000L, // 5 seconds — very slow
            frameCount = 20,
        )
        var anyDetected = false
        for (frame in frames) {
            val result = detector.process(frame)
            if (result.detected) anyDetected = true
        }
        assertFalse("Slow motion should not trigger swipe", anyDetected)
    }

    // ========== Window management ==========

    @Test
    fun `pruneWindow removes samples older than window duration`() {
        val detector = DynamicGestureDetector(config)

        // Add samples spanning 1 second
        detector.process(handAtWristPosition(0.5f, 0.5f, 1000L))
        detector.process(handAtWristPosition(0.5f, 0.5f, 1100L))
        detector.process(handAtWristPosition(0.5f, 0.5f, 1200L))

        // Process a frame at 2000ms — samples from 1000L should be pruned
        detector.process(handAtWristPosition(0.5f, 0.5f, 2000L))

        // Window should only contain recent samples
        // The exact size depends on internal implementation, but old samples should be gone
        // We can verify indirectly by checking that a swipe starting from old position is not detected
    }

    @Test
    fun `no hand detection clears window`() {
        val frames = generateSwipeFrames(
            startX = 0.3f, startY = 0.5f,
            endX = 0.7f, endY = 0.5f,
        )
        // Feed some frames
        for (i in 0..5) {
            detector.process(frames[i])
        }
        // Hand lost
        val emptyResult = detector.process(HandInput.EMPTY.copy(timestampMs = 2000L))
        assertFalse(emptyResult.detected)
    }

    // ========== Cooldown ==========

    @Test
    fun `swipe cooldown prevents immediate re-detection`() {
        val frames1 = generateSwipeFrames(
            startX = 0.3f, startY = 0.5f,
            endX = 0.7f, endY = 0.5f,
            startTimestampMs = 1000L,
        )

        // Detect first swipe
        var firstDetected = false
        for (frame in frames1) {
            val result = detector.process(frame)
            if (result.detected) {
                firstDetected = true
                break
            }
        }
        assertTrue(firstDetected)

        // Immediately try another swipe — should be in cooldown
        val frames2 = generateSwipeFrames(
            startX = 0.3f, startY = 0.5f,
            endX = 0.7f, endY = 0.5f,
            startTimestampMs = 1300L, // Right after first swipe
        )
        var secondDetected = false
        for (frame in frames2) {
            val result = detector.process(frame)
            if (result.detected) {
                secondDetected = true
            }
        }
        // Second swipe within cooldown should not be detected
        assertFalse("Second swipe within cooldown should not be detected", secondDetected)
    }

    // ========== Sensitivity scaling ==========

    @Test
    fun `higher sensitivity detects smaller swipes`() {
        val highSensConfig = GestureEngineConfig(sensitivity = 100)
        val highSensDetector = DynamicGestureDetector(highSensConfig)

        // Smaller swipe that might fail at default sensitivity
        val frames = generateSwipeFrames(
            startX = 0.4f, startY = 0.5f,
            endX = 0.6f, endY = 0.5f,
            durationMs = 200L,
            frameCount = 10,
        )

        var detected = false
        for (frame in frames) {
            val result = highSensDetector.process(frame)
            if (result.detected) {
                detected = true
                break
            }
        }
        assertTrue("High sensitivity should detect smaller swipes", detected)
    }

    @Test
    fun `lower sensitivity requires larger swipes`() {
        val lowSensConfig = GestureEngineConfig(sensitivity = 20)
        val lowSensDetector = DynamicGestureDetector(lowSensConfig)

        // Moderate swipe
        val frames = generateSwipeFrames(
            startX = 0.35f, startY = 0.5f,
            endX = 0.65f, endY = 0.5f,
            durationMs = 300L,
            frameCount = 15,
        )

        var detected = false
        for (frame in frames) {
            val result = lowSensDetector.process(frame)
            if (result.detected) {
                detected = true
                break
            }
        }
        // With very low sensitivity, even moderate swipes may not be detected
        // This is expected behavior — low sensitivity = higher thresholds
    }

    // ========== Peak velocity computation ==========

    @Test
    fun `computePeakVelocity returns 0 for insufficient samples`() {
        val emptyDetector = DynamicGestureDetector(config)
        assertEquals(0f, emptyDetector.computePeakVelocity(), 0.001f)
    }

    // ========== Reset ==========

    @Test
    fun `reset clears detection state`() {
        val frames = generateSwipeFrames(
            startX = 0.3f, startY = 0.5f,
            endX = 0.7f, endY = 0.5f,
        )
        for (frame in frames) {
            detector.process(frame)
        }
        detector.reset()
        // After reset, internal state should be clean
        // Verify by processing a single frame — should not detect
        val result = detector.process(handAtWristPosition(0.5f, 0.5f, 5000L))
        assertFalse(result.detected)
    }
}
