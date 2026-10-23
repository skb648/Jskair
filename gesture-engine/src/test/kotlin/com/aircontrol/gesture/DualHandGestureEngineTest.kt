package com.aircontrol.gesture

import com.aircontrol.gesture.config.GestureEngineConfig
import com.aircontrol.gesture.model.GestureEngineState
import com.aircontrol.gesture.model.GestureEvent
import com.aircontrol.gesture.model.HandInput
import com.aircontrol.gesture.model.Handedness
import com.aircontrol.gesture.model.Landmark3D
import com.aircontrol.gesture.model.MultiHandInput
import com.aircontrol.gesture.model.PinchPhase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DualHandGestureEngineTest {

    private fun buildOpenPalmLandmarks(offsetX: Float = 0f): List<Landmark3D> {
        fun shift(x: Float) = (x + offsetX).coerceIn(0.02f, 0.98f)
        return listOf(
            Landmark3D(shift(0.50f), 0.80f, 0f), // WRIST
            Landmark3D(shift(0.40f), 0.75f, 0f), // THUMB_CMC
            Landmark3D(shift(0.32f), 0.68f, 0f), // THUMB_MCP
            Landmark3D(shift(0.24f), 0.62f, 0f), // THUMB_IP
            Landmark3D(shift(0.15f), 0.55f, 0f), // THUMB_TIP
            Landmark3D(shift(0.42f), 0.68f, 0f), // INDEX_MCP
            Landmark3D(shift(0.40f), 0.55f, 0f), // INDEX_PIP
            Landmark3D(shift(0.39f), 0.42f, 0f), // INDEX_DIP
            Landmark3D(shift(0.38f), 0.30f, 0f), // INDEX_TIP
            Landmark3D(shift(0.50f), 0.66f, 0f), // MIDDLE_MCP
            Landmark3D(shift(0.50f), 0.52f, 0f), // MIDDLE_PIP
            Landmark3D(shift(0.50f), 0.38f, 0f), // MIDDLE_DIP
            Landmark3D(shift(0.50f), 0.25f, 0f), // MIDDLE_TIP
            Landmark3D(shift(0.57f), 0.68f, 0f), // RING_MCP
            Landmark3D(shift(0.58f), 0.54f, 0f), // RING_PIP
            Landmark3D(shift(0.59f), 0.40f, 0f), // RING_DIP
            Landmark3D(shift(0.60f), 0.28f, 0f), // RING_TIP
            Landmark3D(shift(0.64f), 0.72f, 0f), // PINKY_MCP
            Landmark3D(shift(0.66f), 0.60f, 0f), // PINKY_PIP
            Landmark3D(shift(0.68f), 0.48f, 0f), // PINKY_DIP
            Landmark3D(shift(0.70f), 0.38f, 0f), // PINKY_TIP
        )
    }

    private fun buildPinchLandmarks(offsetX: Float = 0f): List<Landmark3D> {
        fun shift(x: Float) = (x + offsetX).coerceIn(0.02f, 0.98f)
        return listOf(
            Landmark3D(shift(0.50f), 0.80f, 0f), // WRIST
            Landmark3D(shift(0.40f), 0.75f, 0f), // THUMB_CMC
            Landmark3D(shift(0.35f), 0.68f, 0f), // THUMB_MCP
            Landmark3D(shift(0.36f), 0.60f, 0f), // THUMB_IP
            Landmark3D(shift(0.392f), 0.502f, 0f), // THUMB_TIP (close to index)
            Landmark3D(shift(0.42f), 0.68f, 0f), // INDEX_MCP
            Landmark3D(shift(0.41f), 0.58f, 0f), // INDEX_PIP
            Landmark3D(shift(0.40f), 0.52f, 0f), // INDEX_DIP
            Landmark3D(shift(0.39f), 0.50f, 0f), // INDEX_TIP
            Landmark3D(shift(0.50f), 0.66f, 0f), // MIDDLE_MCP
            Landmark3D(shift(0.50f), 0.60f, 0f), // MIDDLE_PIP
            Landmark3D(shift(0.50f), 0.64f, 0f), // MIDDLE_DIP
            Landmark3D(shift(0.50f), 0.67f, 0f), // MIDDLE_TIP
            Landmark3D(shift(0.57f), 0.68f, 0f), // RING_MCP
            Landmark3D(shift(0.57f), 0.62f, 0f), // RING_PIP
            Landmark3D(shift(0.56f), 0.66f, 0f), // RING_DIP
            Landmark3D(shift(0.55f), 0.69f, 0f), // RING_TIP
            Landmark3D(shift(0.64f), 0.72f, 0f), // PINKY_MCP
            Landmark3D(shift(0.65f), 0.68f, 0f), // PINKY_PIP
            Landmark3D(shift(0.64f), 0.72f, 0f), // PINKY_DIP
            Landmark3D(shift(0.63f), 0.74f, 0f), // PINKY_TIP
        )
    }

    @Test
    fun `simultaneous dual hands maintain independent perHandStates and losing secondary hand keeps primary armed`() = runTest {
        val config = GestureEngineConfig(
            sensitivity = 50,
            poseDebounceFrames = 3,
            armingDurationMs = 150L,
            cooldownDurationMs = 200L,
            autoDisarmTimeoutMs = 5_000L,
            fistDisarmDurationMs = 500L,
        )
        val engine = GestureEngine(config)

        var t = 1000L
        // Arm both hands over debounce + arming duration
        repeat(8) {
            t += 40L
            val h1 = HandInput(
                landmarks = buildOpenPalmLandmarks(offsetX = -0.15f),
                handedness = Handedness.LEFT,
                confidence = 0.92f,
                timestampMs = t,
                trackId = 1,
            )
            val h2 = HandInput(
                landmarks = buildOpenPalmLandmarks(offsetX = 0.15f),
                handedness = Handedness.RIGHT,
                confidence = 0.94f,
                timestampMs = t,
                trackId = 2,
            )
            engine.processMultiHandFrameSuspending(
                MultiHandInput(hands = listOf(h1, h2), primaryTrackId = 2, timestampMs = t),
            )
        }

        assertEquals(GestureEngineState.ARMED, engine.engineState.value)
        val states = engine.perHandStates.value
        assertEquals(2, states.size)
        assertEquals(GestureEngineState.ARMED, states[1]?.engineState)
        assertEquals(GestureEngineState.ARMED, states[2]?.engineState)
        assertTrue(states[2]?.isPrimaryPointer == true)

        // Now hand 1 leaves frame completely; hand 2 remains visible
        t += 40L
        val h2StillThere = HandInput(
            landmarks = buildOpenPalmLandmarks(offsetX = 0.15f),
            handedness = Handedness.RIGHT,
            confidence = 0.94f,
            timestampMs = t,
            trackId = 2,
        )
        engine.processMultiHandFrameSuspending(
            MultiHandInput(hands = listOf(h2StillThere), primaryTrackId = 2, timestampMs = t),
        )

        // Primary hand 2 must still be ARMED without interruption
        assertEquals(GestureEngineState.ARMED, engine.engineState.value)
        assertEquals(GestureEngineState.ARMED, engine.perHandStates.value[2]?.engineState)
    }

    @Test
    fun `ambiguous overlapping hand frame suppresses accidental discrete pinch trigger`() = runTest {
        val config = GestureEngineConfig(
            sensitivity = 50,
            poseDebounceFrames = 3,
            armingDurationMs = 150L,
            cooldownDurationMs = 200L,
            autoDisarmTimeoutMs = 5_000L,
            fistDisarmDurationMs = 500L,
        )
        val engine = GestureEngine(config)
        val events = mutableListOf<GestureEvent>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            engine.gestureEvents.collect { events.add(it) }
        }

        var t = 1000L
        repeat(8) {
            t += 40L
            val h1 = HandInput(
                landmarks = buildOpenPalmLandmarks(),
                handedness = Handedness.RIGHT,
                confidence = 0.95f,
                timestampMs = t,
                trackId = 1,
            )
            engine.processMultiHandFrameSuspending(
                MultiHandInput(hands = listOf(h1), primaryTrackId = 1, timestampMs = t),
            )
        }
        assertEquals(GestureEngineState.ARMED, engine.engineState.value)
        events.clear()

        // Feed ambiguous pinch frames (as when two hands cross/overlap)
        repeat(5) {
            t += 40L
            val ambiguousPinch = HandInput(
                landmarks = buildPinchLandmarks(),
                handedness = Handedness.RIGHT,
                confidence = 0.90f,
                timestampMs = t,
                trackId = 1,
                isAmbiguous = true,
            )
            engine.processMultiHandFrameSuspending(
                MultiHandInput(hands = listOf(ambiguousPinch), primaryTrackId = 1, timestampMs = t),
            )
        }

        assertTrue(
            "Ambiguous overlapping hand must not start a pinch click",
            events.none { it is GestureEvent.Pinch && it.phase == PinchPhase.START },
        )
        job.cancel()
    }
}
