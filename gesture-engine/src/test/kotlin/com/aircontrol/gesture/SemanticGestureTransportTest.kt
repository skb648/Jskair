package com.aircontrol.gesture

import com.aircontrol.gesture.config.GestureEngineConfig
import com.aircontrol.gesture.model.GestureEvent
import com.aircontrol.gesture.model.GestureEngineState
import com.aircontrol.gesture.model.HandInput
import com.aircontrol.gesture.model.Handedness
import com.aircontrol.gesture.model.Landmark3D
import com.aircontrol.gesture.model.PinchPhase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the semantic/cursor transport split.
 *
 * CursorMoved is allowed to be latest-wins. Semantic events are not: even with
 * a deliberately slow consumer, a committed pinch must preserve START and END.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SemanticGestureTransportTest {

    @Test
    fun `slow consumer cannot lose pinch end after a large move burst`() = runTest {
        val engine = GestureEngine(GestureEngineConfig())
        val events = mutableListOf<GestureEvent>()
        val collector = launch {
            engine.semanticEvents.collect { event ->
                events += event
                // Deliberately slower than the producer. The cursor stream is much
                // higher-rate, but it must not consume semantic capacity.
                delay(50L)
            }
        }
        runCurrent()

        var ts = 1_000L
        repeat(12) {
            engine.processFrameSuspending(hand(ts))
            ts += 40L
            if (engine.engineState.value == GestureEngineState.ARMED) return@repeat
        }
        var guard = 0
        while (engine.engineState.value != GestureEngineState.ARMED && guard++ < 20) {
            engine.processFrameSuspending(hand(ts))
            ts += 40L
        }
        assertEquals(GestureEngineState.ARMED, engine.engineState.value)

        // Flood the continuous cursor path BEFORE the semantic pinch.
        // These frames must not enter the semantic transport at all.
        repeat(1_000) {
            engine.processFrameSuspending(hand(ts))
            ts += 16L
        }
        repeat(8) {
            engine.processFrameSuspending(hand(ts, pinchGap = 0.08f))
            ts += 40L
        }
        repeat(8) {
            engine.processFrameSuspending(hand(ts, pinchGap = 0.60f))
            ts += 40L
        }
        advanceUntilIdle()

        assertEquals(1, events.count { it is GestureEvent.Pinch && it.phase == PinchPhase.START })
        assertEquals(1, events.count { it is GestureEvent.Pinch && it.phase == PinchPhase.END })
        assertTrue(events.indexOfFirst { it is GestureEvent.Pinch && it.phase == PinchPhase.START } <
            events.indexOfFirst { it is GestureEvent.Pinch && it.phase == PinchPhase.END })

        collector.cancel()
    }

    private fun hand(
        timestampMs: Long,
        offsetX: Float = 0f,
        scale: Float = 0.35f,
        index: Boolean = true,
        middle: Boolean = true,
        ring: Boolean = true,
        pinky: Boolean = true,
        thumbOut: Boolean = true,
        pinchGap: Float? = null,
        confidence: Float = 0.95f,
    ): HandInput {
        val wx = 0.5f + offsetX
        val wy = 0.75f
        val s = scale
        fun finger(xOffset: Float, extended: Boolean): List<Landmark3D> {
            val mcp = Landmark3D(wx + xOffset * s, wy - 1.0f * s, 0f)
            val pipY = if (extended) wy - 1.5f * s else wy - 1.3f * s
            val dipY = if (extended) wy - 1.8f * s else wy - 1.1f * s
            val tipY = if (extended) wy - 2.1f * s else wy - 0.95f * s
            val x = wx + xOffset * s
            return listOf(mcp, Landmark3D(x, pipY, 0f), Landmark3D(x, dipY, 0f), Landmark3D(x, tipY, 0f))
        }
        val wrist = Landmark3D(wx, wy, 0f)
        val thumbTip = if (pinchGap != null) {
            Landmark3D(wx - 0.15f * s + pinchGap * s, wy - 2.1f * s, 0f)
        } else if (thumbOut) {
            Landmark3D(wx - 0.68f * s, wy - 0.68f * s, 0f)
        } else {
            Landmark3D(wx - 0.35f * s, wy - 0.45f * s, 0f)
        }
        val landmarks = mutableListOf(wrist)
        landmarks += listOf(
            Landmark3D(wx - 0.2f * s, wy - 0.2f * s, 0f),
            Landmark3D(wx - 0.3f * s, wy - 0.3f * s, 0f),
            Landmark3D(wx - 0.5f * s, wy - 0.5f * s, 0f),
            thumbTip,
        )
        landmarks += finger(-0.15f, index)
        landmarks += finger(0f, middle)
        landmarks += finger(0.15f, ring)
        landmarks += finger(0.30f, pinky)
        assertEquals(21, landmarks.size)
        return HandInput(landmarks, Handedness.RIGHT, timestampMs, confidence)
    }


}
