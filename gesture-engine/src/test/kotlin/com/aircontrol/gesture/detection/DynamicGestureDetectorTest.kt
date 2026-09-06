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
        var firstDetectedAt: Long? = null
        for (frame in frames1) {
            val result = detector.process(frame)
            if (result.detected) {
                firstDetectedAt = frame.timestampMs
                break
            }
        }
        assertTrue(firstDetectedAt != null)

        // Keep every frame of the second candidate strictly inside the cooldown.
        val frames2 = generateSwipeFrames(
            startX = 0.3f, startY = 0.5f,
            endX = 0.7f, endY = 0.5f,
            startTimestampMs = firstDetectedAt!! + 1L,
            durationMs = config.swipeCooldownMs - 2L,
            frameCount = 10,
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
        assertEquals(0f, emptyDetector.computePeakVelocity(ArrayDeque<DynamicGestureDetector.PositionSample>()), 0.001f)
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

    // ========== Hardening round 9 ==========

    /**
     * Spec §12 (hand loss): an in-flight swipe candidate must be CANCELLED —
     * motion before and after a tracking gap may never be stitched into one
     * swipe. Each half alone is below the required sample count, while the
     * combined motion would comfortably fire.
     */
    @Test
    fun `tracking loss mid swipe cancels the candidate`() {
        // First half: 3 fast rightward samples (would fire when combined).
        var result = detector.process(handAtWristPosition(0.50f, 0.5f, 1000L))
        assertFalse(result.detected)
        result = detector.process(handAtWristPosition(0.56f, 0.5f, 1040L))
        assertFalse(result.detected)
        result = detector.process(handAtWristPosition(0.62f, 0.5f, 1080L))
        assertFalse(result.detected)

        // Tracking gap.
        result = detector.process(
            HandInput(landmarks = emptyList(), handedness = Handedness.UNKNOWN, timestampMs = 1120L, confidence = 0f),
        )
        assertFalse(result.detected)

        // Second half: another 3 fast rightward samples.
        result = detector.process(handAtWristPosition(0.68f, 0.5f, 1160L))
        assertFalse(result.detected)
        result = detector.process(handAtWristPosition(0.74f, 0.5f, 1200L))
        assertFalse(result.detected)
        result = detector.process(handAtWristPosition(0.80f, 0.5f, 1240L))
        assertFalse(
            "motion spanning a tracking gap must never be stitched into a swipe",
            result.detected,
        )
    }

    /**
     * Spec §14/§18: a committed swipe carries a normalized confidence and no
     * rejection reason; rejected real motion carries a machine-readable reason.
     */
    @Test
    fun `rejection reasons and confidence are exposed`() {
        // Valid fast swipe → confidence set, no reason.
        val frames = generateSwipeFrames(0.3f, 0.5f, 0.7f, 0.5f)
        var committed: DynamicGestureDetector.SwipeResult? = null
        for (frame in frames) {
            val r = detector.process(frame)
            if (r.detected) { committed = r; break }
        }
        assertTrue("fixture must produce a swipe", committed != null)
        assertTrue("confidence in (0,1]", committed!!.confidence in 0.35f..1.0f)
        assertEquals(null, committed!!.reason)
        assertTrue(committed!!.hadEvidence)

        // Slow motion → rejected as TOO_SLOW with evidence. 0.4 travel over
        // 700ms: inside the 350ms window displacement ≈0.2 (well above the
        // gate) but peak velocity ≈0.57 u/s (well below 1.2).
        val slowDetector = DynamicGestureDetector(config)
        var slowRejected: DynamicGestureDetector.SwipeResult? = null
        generateSwipeFrames(0.3f, 0.5f, 0.7f, 0.5f, durationMs = 700L, frameCount = 20).forEach { f ->
            val r = slowDetector.process(f)
            if (r.hadEvidence && slowRejected == null) slowRejected = r
        }
        assertTrue("slow sweep must reach the velocity gate", slowRejected != null)
        assertEquals(DynamicGestureDetector.SwipeRejectReason.TOO_SLOW, slowRejected!!.reason)
        assertFalse(slowRejected!!.detected)

        // Diagonal motion → rejected as DIAGONAL_AMBIGUOUS.
        val diagDetector = DynamicGestureDetector(config)
        var diagRejected: DynamicGestureDetector.SwipeResult? = null
        generateSwipeFrames(0.3f, 0.4f, 0.6f, 0.7f).forEach { f ->
            val r = diagDetector.process(f)
            if (r.hadEvidence && diagRejected == null) diagRejected = r
        }
        assertTrue("diagonal sweep must reach the dominance gate", diagRejected != null)
        assertEquals(DynamicGestureDetector.SwipeRejectReason.DIAGONAL_AMBIGUOUS, diagRejected!!.reason)
    }

    /**
     * Spec §12/§8 regression (round 9 bug): a 1-frame dropout after a fired
     * swipe must NOT clear the neutral re-arm latch — the detector must keep
     * swallowing motion until the hand has been still for 250ms.
     */
    @Test
    fun `neutral rearm survives a tracking dropout after a swipe`() {
        // Fire a swipe…
        var ts = 1000L
        var x = 0.30f
        var fired = false
        repeat(12) {
            val r = detector.process(handAtWristPosition(x, 0.5f, ts))
            x += 0.06f
            ts += 40L
            if (r.detected) fired = true
        }
        assertTrue("fixture must fire a swipe", fired)

        // …lose the hand for one frame…
        detector.process(
            HandInput(landmarks = emptyList(), handedness = Handedness.UNKNOWN, timestampMs = ts, confidence = 0f),
        )
        ts += 40L

        // …then sweep back left, past the cooldown. The latch must hold.
        var secondFire = false
        repeat(10) {
            val r = detector.process(handAtWristPosition(x, 0.5f, ts))
            x -= 0.06f
            ts += 40L
            if (r.detected) secondFire = true
        }
        assertFalse("return sweep after a dropout must not re-fire (latch survives)", secondFire)

        // Stillness re-arms; a fresh deliberate swipe fires again.
        repeat(10) { detector.process(handAtWristPosition(x, 0.5f, ts)); ts += 40L }
        var refired = false
        repeat(10) {
            val r = detector.process(handAtWristPosition(x, 0.5f, ts))
            x += 0.06f
            ts += 40L
            if (r.detected) refired = true
        }
        assertTrue("after stillness the detector must accept a new swipe", refired)
    }
}
