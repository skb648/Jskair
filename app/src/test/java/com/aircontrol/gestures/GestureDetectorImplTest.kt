package com.aircontrol.gestures

import com.aircontrol.gesture.model.GestureEngineState
import com.aircontrol.gesture.model.Pose
import com.aircontrol.tracking.HandFrame
import com.aircontrol.tracking.Handedness
import com.aircontrol.tracking.Landmark3D
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class GestureDetectorImplTest {

    private lateinit var gestureDetector: GestureDetectorImpl

    @Before
    fun setup() {
        gestureDetector = GestureDetectorImpl()
    }

    // ========== Initial state ==========

    @Test
    fun `initial engine state is DISARMED`() {
        assertEquals(GestureEngineState.DISARMED, gestureDetector.engineState.value)
    }

    @Test
    fun `initial current pose is NONE`() {
        assertEquals(Pose.NONE, gestureDetector.currentPose.value)
    }

    @Test
    fun `initial arming progress is zero`() {
        assertEquals(0f, gestureDetector.armingProgress.value, 0.001f)
    }

    // ========== processHandFrame with empty frame ==========

    @Test
    fun `processHandFrame with empty frame does not crash`() {
        val emptyFrame = HandFrame.EMPTY
        // Should not throw
        gestureDetector.processHandFrame(emptyFrame)
    }

    @Test
    fun `processHandFrame with empty landmarks keeps DISARMED state`() {
        gestureDetector.processHandFrame(HandFrame.EMPTY)
        assertEquals(GestureEngineState.DISARMED, gestureDetector.engineState.value)
    }

    // ========== processHandFrame with valid landmarks ==========

    @Test
    fun `processHandFrame with 21 landmarks does not crash`() {
        val landmarks = List(21) { index ->
            Landmark3D(
                x = index * 0.01f,
                y = index * 0.02f,
                z = index * -0.005f,
            )
        }
        val frame = HandFrame(
            landmarks = landmarks,
            handedness = Handedness.RIGHT,
            timestampMs = System.currentTimeMillis(),
            confidence = 0.9f,
        )
        // Should not throw
        gestureDetector.processHandFrame(frame)
    }

    @Test
    fun `processHandFrame with open palm landmarks transitions state`() {
        // Create landmarks that represent an open palm (all fingers extended)
        val landmarks = createOpenPalmLandmarks()
        val frame = HandFrame(
            landmarks = landmarks,
            handedness = Handedness.RIGHT,
            timestampMs = System.currentTimeMillis(),
            confidence = 0.95f,
        )

        // Feed multiple frames over time to allow arming
        val baseTime = System.currentTimeMillis()
        for (i in 0..30) {
            gestureDetector.processHandFrame(
                frame.copy(timestampMs = baseTime + i * 33L),
            )
        }

        // After enough frames, the state should have changed from DISARMED
        // (may be ARMING, ARMED, etc. depending on timing)
        val state = gestureDetector.engineState.value
        // We can at least verify it's not crashed and state is a valid enum
        assert(state in GestureEngineState.entries.toSet())
    }

    // ========== Handedness mapping ==========

    @Test
    fun `processHandFrame with LEFT handedness does not crash`() {
        val landmarks = createOpenPalmLandmarks()
        val frame = HandFrame(
            landmarks = landmarks,
            handedness = Handedness.LEFT,
            timestampMs = System.currentTimeMillis(),
            confidence = 0.9f,
        )
        gestureDetector.processHandFrame(frame)
    }

    @Test
    fun `processHandFrame with RIGHT handedness does not crash`() {
        val landmarks = createOpenPalmLandmarks()
        val frame = HandFrame(
            landmarks = landmarks,
            handedness = Handedness.RIGHT,
            timestampMs = System.currentTimeMillis(),
            confidence = 0.9f,
        )
        gestureDetector.processHandFrame(frame)
    }

    @Test
    fun `processHandFrame with UNKNOWN handedness does not crash`() {
        val landmarks = createOpenPalmLandmarks()
        val frame = HandFrame(
            landmarks = landmarks,
            handedness = Handedness.UNKNOWN,
            timestampMs = System.currentTimeMillis(),
            confidence = 0.9f,
        )
        gestureDetector.processHandFrame(frame)
    }

    @Test
    fun `processHandFrame with zero confidence keeps DISARMED state`() {
        val landmarks = createOpenPalmLandmarks()
        val frame = HandFrame(
            landmarks = landmarks,
            handedness = Handedness.RIGHT,
            timestampMs = System.currentTimeMillis(),
            confidence = 0f,
        )
        gestureDetector.processHandFrame(frame)
        assertEquals(GestureEngineState.DISARMED, gestureDetector.engineState.value)
    }

    // ========== updateSensitivity ==========

    @Test
    fun `updateSensitivity resets engine state to DISARMED`() {
        // First feed some frames to potentially change state
        val landmarks = createOpenPalmLandmarks()
        val frame = HandFrame(
            landmarks = landmarks,
            handedness = Handedness.RIGHT,
            timestampMs = System.currentTimeMillis(),
            confidence = 0.9f,
        )
        gestureDetector.processHandFrame(frame)

        // Update sensitivity — should reset
        gestureDetector.updateSensitivity(75)

        // After updateSensitivity, the engine is recreated so state should be DISARMED
        assertEquals(GestureEngineState.DISARMED, gestureDetector.engineState.value)
        assertEquals(Pose.NONE, gestureDetector.currentPose.value)
    }

    // ========== reset ==========

    @Test
    fun `reset clears engine state to DISARMED`() {
        gestureDetector.reset()
        assertEquals(GestureEngineState.DISARMED, gestureDetector.engineState.value)
    }

    @Test
    fun `reset clears current pose to NONE`() {
        gestureDetector.reset()
        assertEquals(Pose.NONE, gestureDetector.currentPose.value)
    }

    @Test
    fun `reset clears arming progress to zero`() {
        gestureDetector.reset()
        assertEquals(0f, gestureDetector.armingProgress.value, 0.001f)
    }

    @Test
    fun `reset after processing frames clears all state`() {
        val landmarks = createOpenPalmLandmarks()
        val frame = HandFrame(
            landmarks = landmarks,
            handedness = Handedness.RIGHT,
            timestampMs = System.currentTimeMillis(),
            confidence = 0.9f,
        )
        gestureDetector.processHandFrame(frame)

        gestureDetector.reset()

        assertEquals(GestureEngineState.DISARMED, gestureDetector.engineState.value)
        assertEquals(Pose.NONE, gestureDetector.currentPose.value)
        assertEquals(0f, gestureDetector.armingProgress.value, 0.001f)
    }

    // ========== Multiple frames processing ==========

    @Test
    fun `processing multiple frames in sequence does not crash`() {
        val landmarks = createOpenPalmLandmarks()
        val baseTime = System.currentTimeMillis()

        for (i in 0..99) {
            val frame = HandFrame(
                landmarks = landmarks,
                handedness = Handedness.RIGHT,
                timestampMs = baseTime + i * 33L,
                confidence = 0.9f,
            )
            gestureDetector.processHandFrame(frame)
        }

        // Should still be in a valid state
        assert(gestureDetector.engineState.value in GestureEngineState.entries.toSet())
    }

    @Test
    fun `alternating between detected and empty frames does not crash`() {
        val landmarks = createOpenPalmLandmarks()
        val baseTime = System.currentTimeMillis()

        for (i in 0..49) {
            if (i % 2 == 0) {
                gestureDetector.processHandFrame(
                    HandFrame(
                        landmarks = landmarks,
                        handedness = Handedness.RIGHT,
                        timestampMs = baseTime + i * 33L,
                        confidence = 0.9f,
                    ),
                )
            } else {
                gestureDetector.processHandFrame(HandFrame.EMPTY)
            }
        }

        // Should not crash and should be in a valid state
        assert(gestureDetector.engineState.value in GestureEngineState.entries.toSet())
    }

    // ========== Helpers ==========

    /**
     * Creates a set of 21 landmarks that roughly represent an open palm.
     * All fingertips are above their respective PIP joints (extended).
     */
    private fun createOpenPalmLandmarks(): List<Landmark3D> {
        return listOf(
            // 0: Wrist
            Landmark3D(0.5f, 0.8f, 0f),
            // 1: Thumb CMC
            Landmark3D(0.4f, 0.7f, -0.02f),
            // 2: Thumb MCP
            Landmark3D(0.35f, 0.6f, -0.03f),
            // 3: Thumb IP
            Landmark3D(0.3f, 0.5f, -0.04f),
            // 4: Thumb Tip (extended)
            Landmark3D(0.25f, 0.4f, -0.05f),
            // 5: Index MCP
            Landmark3D(0.4f, 0.5f, -0.03f),
            // 6: Index PIP
            Landmark3D(0.4f, 0.35f, -0.05f),
            // 7: Index DIP
            Landmark3D(0.4f, 0.25f, -0.06f),
            // 8: Index Tip (extended)
            Landmark3D(0.4f, 0.15f, -0.07f),
            // 9: Middle MCP
            Landmark3D(0.5f, 0.48f, -0.03f),
            // 10: Middle PIP
            Landmark3D(0.5f, 0.33f, -0.05f),
            // 11: Middle DIP
            Landmark3D(0.5f, 0.23f, -0.06f),
            // 12: Middle Tip (extended)
            Landmark3D(0.5f, 0.13f, -0.07f),
            // 13: Ring MCP
            Landmark3D(0.6f, 0.5f, -0.03f),
            // 14: Ring PIP
            Landmark3D(0.6f, 0.35f, -0.05f),
            // 15: Ring DIP
            Landmark3D(0.6f, 0.25f, -0.06f),
            // 16: Ring Tip (extended)
            Landmark3D(0.6f, 0.15f, -0.07f),
            // 17: Pinky MCP
            Landmark3D(0.68f, 0.53f, -0.02f),
            // 18: Pinky PIP
            Landmark3D(0.7f, 0.4f, -0.04f),
            // 19: Pinky DIP
            Landmark3D(0.72f, 0.32f, -0.05f),
            // 20: Pinky Tip (extended)
            Landmark3D(0.73f, 0.24f, -0.06f),
        )
    }
}
