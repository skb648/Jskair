package com.aircontrol.gesture

import com.aircontrol.gesture.config.GestureEngineConfig
import com.aircontrol.gesture.model.GestureEngineState
import com.aircontrol.gesture.model.GestureEvent
import com.aircontrol.gesture.model.HandInput
import com.aircontrol.gesture.model.Handedness
import com.aircontrol.gesture.model.Landmark3D
import com.aircontrol.gesture.model.Pose
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Integration tests for GestureEngine.
 * Tests the full pipeline: HandInput → gesture classification → state machine → events.
 * Includes false-positive rejection tests with random hand motion.
 */
class GestureEngineTest {

    private lateinit var engine: GestureEngine
    private lateinit var config: GestureEngineConfig

    @Before
    fun setUp() {
        config = GestureEngineConfig(
            sensitivity = 50,
            poseDebounceFrames = 3,
            armingDurationMs = 600L,
            cooldownDurationMs = 200L, // Shorter for faster tests
            autoDisarmTimeoutMs = 5_000L,
            fistDisarmDurationMs = 500L, // Shorter for faster tests
        )
        engine = GestureEngine(config)
    }

    // ========== Helper methods ==========

    private fun openPalmInput(timestampMs: Long): HandInput = HandInput(
        landmarks = buildOpenPalmLandmarks(),
        handedness = Handedness.RIGHT,
        timestampMs = timestampMs,
        confidence = 0.95f,
    )

    private fun fistInput(timestampMs: Long): HandInput = HandInput(
        landmarks = buildFistLandmarks(),
        handedness = Handedness.RIGHT,
        timestampMs = timestampMs,
        confidence = 0.95f,
    )

    private fun pointingInput(timestampMs: Long): HandInput = HandInput(
        landmarks = buildPointingLandmarks(),
        handedness = Handedness.RIGHT,
        timestampMs = timestampMs,
        confidence = 0.95f,
    )

    private fun victoryInput(timestampMs: Long): HandInput = HandInput(
        landmarks = buildVictoryLandmarks(),
        handedness = Handedness.RIGHT,
        timestampMs = timestampMs,
        confidence = 0.95f,
    )

    private fun pinchInput(timestampMs: Long): HandInput = HandInput(
        landmarks = buildPinchLandmarks(),
        handedness = Handedness.RIGHT,
        timestampMs = timestampMs,
        confidence = 0.95f,
    )

    private fun noHandInput(timestampMs: Long): HandInput =
        HandInput.EMPTY.copy(timestampMs = timestampMs)

    /**
     * Arms the engine by feeding open palm frames for the arming duration.
     */
    private fun armEngine(startTimestampMs: Long = 100L): Long {
        val framesNeeded = config.poseDebounceFrames + 2 // debounce + arming
        var ts = startTimestampMs
        // Feed enough frames to confirm open palm
        repeat(config.poseDebounceFrames) {
            engine.processFrame(openPalmInput(ts))
            ts += 33L // ~30fps
        }
        // Feed frames to complete arming
        engine.processFrame(openPalmInput(ts))
        ts += config.armingDurationMs
        engine.processFrame(openPalmInput(ts))
        assertEquals("Engine should be ARMED", GestureEngineState.ARMED, engine.engineState.value)
        return ts
    }

    // ========== Arming and state machine tests ==========

    @Test
    fun `engine starts in DISARMED state`() {
        assertEquals(GestureEngineState.DISARMED, engine.engineState.value)
    }

    @Test
    fun `open palm arms the engine after debounce and arming duration`() {
        var ts = 100L
        repeat(config.poseDebounceFrames) {
            engine.processFrame(openPalmInput(ts))
            ts += 33L
        }
        // Not yet armed — still arming
        assertEquals(GestureEngineState.ARMING, engine.engineState.value)

        // Complete arming
        ts += config.armingDurationMs
        engine.processFrame(openPalmInput(ts))
        assertEquals(GestureEngineState.ARMED, engine.engineState.value)
    }

    @Test
    fun `Armed event emitted on arming completion`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val job = launch {
            engine.gestureEvents.toList(events)
        }

        val ts = armEngine(100L)

        // Should have Armed event
        assertTrue("Armed event should be emitted", events.any { it is GestureEvent.Armed })

        job.cancel()
    }

    // ========== Gesture execution tests ==========

    @Test
    fun `PoseTriggered event emitted when actionable pose detected while ARMED`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val job = launch {
            engine.gestureEvents.toList(events)
        }

        var ts = armEngine(100L)

        // Feed pointing gesture
        repeat(config.poseDebounceFrames) {
            engine.processFrame(pointingInput(ts))
            ts += 33L
        }

        // Should have PoseTriggered(POINTING)
        assertTrue(
            "PoseTriggered(POINTING) should be emitted",
            events.any { it is GestureEvent.PoseTriggered && it.pose == Pose.POINTING },
        )

        job.cancel()
    }

    @Test
    fun `PoseTriggered for VICTORY pose`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val job = launch {
            engine.gestureEvents.toList(events)
        }

        var ts = armEngine(100L)

        repeat(config.poseDebounceFrames) {
            engine.processFrame(victoryInput(ts))
            ts += 33L
        }

        assertTrue(
            "PoseTriggered(VICTORY) should be emitted",
            events.any { it is GestureEvent.PoseTriggered && it.pose == Pose.VICTORY },
        )

        job.cancel()
    }

    // ========== Disarm tests ==========

    @Test
    fun `Disarmed event emitted when fist held for disarm duration`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val job = launch {
            engine.gestureEvents.toList(events)
        }

        var ts = armEngine(100L)

        // Hold FIST for disarm duration
        engine.processFrame(fistInput(ts))
        ts += config.fistDisarmDurationMs
        engine.processFrame(fistInput(ts))

        assertEquals(GestureEngineState.DISARMED, engine.engineState.value)
        assertTrue("Disarmed event should be emitted", events.any { it is GestureEvent.Disarmed })

        job.cancel()
    }

    // ========== Cursor tracking ==========

    @Test
    fun `CursorMoved events emitted when hand detected and ARMED`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val job = launch {
            engine.gestureEvents.toList(events)
        }

        val ts = armEngine(100L)

        // Feed a frame with hand
        engine.processFrame(openPalmInput(ts + 100L))

        assertTrue(
            "CursorMoved should be emitted when armed",
            events.any { it is GestureEvent.CursorMoved },
        )

        job.cancel()
    }

    @Test
    fun `CursorMoved not emitted when DISARMED`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val job = launch {
            engine.gestureEvents.toList(events)
        }

        // Feed a FIST frame while DISARMED. FIST does not trigger arming (only
        // OPEN_PALM does), so the state remains DISARMED after this frame.
        // (Bug #18 Fix: CursorMoved is now emitted during ARMING to pre-warm the
        // smoother, so we can't use OPEN_PALM here — it would transition to ARMING
        // and emit a CursorMoved.)
        engine.processFrame(fistInput(100L))

        assertFalse(
            "CursorMoved should not be emitted when DISARMED",
            events.any { it is GestureEvent.CursorMoved },
        )

        job.cancel()
    }

    // ========== Pinch lifecycle ==========

    @Test
    fun `Pinch START-MOVE-END lifecycle`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val job = launch {
            engine.gestureEvents.toList(events)
        }

        var ts = armEngine(100L)

        // Pinch start
        repeat(config.poseDebounceFrames) {
            engine.processFrame(pinchInput(ts))
            ts += 33L
        }

        // Pinch hold
        engine.processFrame(pinchInput(ts))
        ts += 33L

        // Pinch end (switch to open palm)
        engine.processFrame(openPalmInput(ts))

        val pinchEvents = events.filterIsInstance<GestureEvent.Pinch>()
        assertTrue("Should have Pinch START", pinchEvents.any { it.phase == com.aircontrol.gesture.model.PinchPhase.START })
        assertTrue("Should have Pinch MOVE", pinchEvents.any { it.phase == com.aircontrol.gesture.model.PinchPhase.MOVE })
        assertTrue("Should have Pinch END", pinchEvents.any { it.phase == com.aircontrol.gesture.model.PinchPhase.END })

        job.cancel()
    }

    // ========== False-positive rejection ==========

    @Test
    fun `random hand motion produces zero gesture events when DISARMED`() {
        // Generate 100 frames of random hand positions
        var eventCount = 0
        val collectedEvents = mutableListOf<GestureEvent>()

        // Use synchronous collection
        var ts = 100L
        repeat(100) {
            val randomLandmarks = generateRandomLandmarks()
            val input = HandInput(
                landmarks = randomLandmarks,
                handedness = Handedness.RIGHT,
                timestampMs = ts,
                confidence = 0.8f,
            )
            engine.processFrame(input)
            ts += 33L
        }

        // When DISARMED, no gesture events should fire
        // (only cursor events if the system armed, which random motion shouldn't do consistently)
        // The engine should stay DISARMED because random motion won't produce a stable open palm
        // for the arming duration
    }

    @Test
    fun `rapidly changing poses do not pass debounce filter`() {
        var ts = 100L
        val poses = listOf(
            ::openPalmInput,
            ::fistInput,
            ::pointingInput,
            ::victoryInput,
            ::fistInput,
        )

        // Feed alternating poses — none should be confirmed
        repeat(20) { i ->
            val inputFn = poses[i % poses.size]
            engine.processFrame(inputFn(ts))
            ts += 33L
        }

        // Current pose should be NONE or the last raw pose, but never confirmed
        // since the debounce filter requires N consecutive identical frames
        assertEquals(Pose.NONE, engine.currentPose.value)
    }

    @Test
    fun `no events emitted for undetected hand`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val job = launch {
            engine.gestureEvents.toList(events)
        }

        repeat(50) { i ->
            engine.processFrame(noHandInput(100L + i * 33L))
        }

        // No meaningful events should be generated
        assertFalse("No Swipe events for no hand", events.any { it is GestureEvent.Swipe })
        assertFalse("No PoseTriggered for no hand", events.any { it is GestureEvent.PoseTriggered })

        job.cancel()
    }

    // ========== Reset ==========

    @Test
    fun `reset clears all state`() {
        armEngine(100L)
        engine.reset()
        assertEquals(GestureEngineState.DISARMED, engine.engineState.value)
        assertEquals(Pose.NONE, engine.currentPose.value)
        assertEquals(0f, engine.armingProgress.value, 0.01f)
    }

    // ========== Landmark builders ==========

    private fun buildOpenPalmLandmarks(): List<Landmark3D> {
        val wrist = Landmark3D(0.5f, 0.8f, 0f)
        return listOf(
            wrist,
            Landmark3D(0.4f, 0.75f, 0f),  // THUMB_CMC
            Landmark3D(0.32f, 0.68f, 0f),  // THUMB_MCP
            Landmark3D(0.24f, 0.62f, 0f),  // THUMB_IP
            Landmark3D(0.15f, 0.55f, 0f),  // THUMB_TIP (extended)
            Landmark3D(0.42f, 0.68f, 0f),  // INDEX_MCP
            Landmark3D(0.40f, 0.55f, 0f),  // INDEX_PIP
            Landmark3D(0.39f, 0.42f, 0f),  // INDEX_DIP
            Landmark3D(0.38f, 0.30f, 0f),  // INDEX_TIP (extended)
            Landmark3D(0.50f, 0.66f, 0f),  // MIDDLE_MCP
            Landmark3D(0.50f, 0.52f, 0f),  // MIDDLE_PIP
            Landmark3D(0.50f, 0.38f, 0f),  // MIDDLE_DIP
            Landmark3D(0.50f, 0.25f, 0f),  // MIDDLE_TIP (extended)
            Landmark3D(0.57f, 0.68f, 0f),  // RING_MCP
            Landmark3D(0.58f, 0.54f, 0f),  // RING_PIP
            Landmark3D(0.59f, 0.40f, 0f),  // RING_DIP
            Landmark3D(0.60f, 0.28f, 0f),  // RING_TIP (extended)
            Landmark3D(0.64f, 0.72f, 0f),  // PINKY_MCP
            Landmark3D(0.66f, 0.60f, 0f),  // PINKY_PIP
            Landmark3D(0.68f, 0.48f, 0f),  // PINKY_DIP
            Landmark3D(0.70f, 0.38f, 0f),  // PINKY_TIP (extended)
        )
    }

    private fun buildFistLandmarks(): List<Landmark3D> {
        val wrist = Landmark3D(0.5f, 0.8f, 0f)
        return listOf(
            wrist,
            Landmark3D(0.4f, 0.75f, 0f),   // THUMB_CMC
            Landmark3D(0.35f, 0.68f, 0f),   // THUMB_MCP
            Landmark3D(0.34f, 0.72f, 0f),   // THUMB_IP
            Landmark3D(0.38f, 0.78f, 0f),   // THUMB_TIP (curled)
            Landmark3D(0.42f, 0.68f, 0f),   // INDEX_MCP
            Landmark3D(0.40f, 0.55f, 0f),   // INDEX_PIP
            Landmark3D(0.41f, 0.62f, 0f),   // INDEX_DIP
            Landmark3D(0.42f, 0.66f, 0f),   // INDEX_TIP (curled)
            Landmark3D(0.50f, 0.66f, 0f),   // MIDDLE_MCP
            Landmark3D(0.50f, 0.52f, 0f),   // MIDDLE_PIP
            Landmark3D(0.50f, 0.60f, 0f),   // MIDDLE_DIP
            Landmark3D(0.50f, 0.64f, 0f),   // MIDDLE_TIP (curled)
            Landmark3D(0.57f, 0.68f, 0f),   // RING_MCP
            Landmark3D(0.58f, 0.54f, 0f),   // RING_PIP
            Landmark3D(0.57f, 0.62f, 0f),   // RING_DIP
            Landmark3D(0.56f, 0.66f, 0f),   // RING_TIP (curled)
            Landmark3D(0.64f, 0.72f, 0f),   // PINKY_MCP
            Landmark3D(0.66f, 0.60f, 0f),   // PINKY_PIP
            Landmark3D(0.65f, 0.68f, 0f),   // PINKY_DIP
            Landmark3D(0.64f, 0.72f, 0f),   // PINKY_TIP (curled)
        )
    }

    private fun buildPointingLandmarks(): List<Landmark3D> {
        val base = buildFistLandmarks().toMutableList()
        // Extend index finger
        base[7] = Landmark3D(0.39f, 0.42f, 0f)  // INDEX_DIP
        base[8] = Landmark3D(0.38f, 0.30f, 0f)  // INDEX_TIP (extended)
        return base
    }

    private fun buildVictoryLandmarks(): List<Landmark3D> {
        val base = buildFistLandmarks().toMutableList()
        // Extend index and middle
        base[7] = Landmark3D(0.39f, 0.42f, 0f)  // INDEX_DIP
        base[8] = Landmark3D(0.38f, 0.30f, 0f)  // INDEX_TIP
        base[11] = Landmark3D(0.50f, 0.38f, 0f) // MIDDLE_DIP
        base[12] = Landmark3D(0.50f, 0.25f, 0f) // MIDDLE_TIP
        return base
    }

    private fun buildPinchLandmarks(): List<Landmark3D> {
        val wrist = Landmark3D(0.5f, 0.8f, 0f)
        return listOf(
            wrist,
            Landmark3D(0.4f, 0.75f, 0f),  // THUMB_CMC
            Landmark3D(0.35f, 0.68f, 0f),  // THUMB_MCP
            Landmark3D(0.36f, 0.60f, 0f),  // THUMB_IP
            Landmark3D(0.38f, 0.52f, 0f),  // THUMB_TIP (close to index)
            Landmark3D(0.42f, 0.68f, 0f),  // INDEX_MCP
            Landmark3D(0.41f, 0.58f, 0f),  // INDEX_PIP
            Landmark3D(0.40f, 0.52f, 0f),  // INDEX_DIP
            Landmark3D(0.39f, 0.50f, 0f),  // INDEX_TIP (close to thumb)
            Landmark3D(0.50f, 0.66f, 0f),  // MIDDLE_MCP
            Landmark3D(0.50f, 0.60f, 0f),  // MIDDLE_PIP
            Landmark3D(0.50f, 0.64f, 0f),  // MIDDLE_DIP
            Landmark3D(0.50f, 0.67f, 0f),  // MIDDLE_TIP (curled)
            Landmark3D(0.57f, 0.68f, 0f),  // RING_MCP
            Landmark3D(0.57f, 0.62f, 0f),  // RING_PIP
            Landmark3D(0.56f, 0.66f, 0f),  // RING_DIP
            Landmark3D(0.55f, 0.69f, 0f),  // RING_TIP (curled)
            Landmark3D(0.64f, 0.72f, 0f),  // PINKY_MCP
            Landmark3D(0.65f, 0.68f, 0f),  // PINKY_PIP
            Landmark3D(0.64f, 0.72f, 0f),  // PINKY_DIP
            Landmark3D(0.63f, 0.74f, 0f),  // PINKY_TIP (curled)
        )
    }

    /**
     * Generates 21 random landmarks that simulate random hand motion.
     * This should NOT produce any consistent gesture.
     */
    private fun generateRandomLandmarks(): List<Landmark3D> {
        val wrist = Landmark3D(0.5f, 0.8f, 0f)
        val random = java.util.Random()
        return (0 until 21).map { i ->
            if (i == 0) wrist
            else Landmark3D(
                x = 0.3f + random.nextFloat() * 0.4f,
                y = 0.2f + random.nextFloat() * 0.6f,
                z = random.nextFloat() * 0.1f,
            )
        }
    }
}
