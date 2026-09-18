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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
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
        repeat(20) {
            engine.processFrameSuspending(hand(ts))
            ts += 40L
        }
        assertEquals(GestureEngineState.ARMED, engine.engineState.value)

        repeat(8) {
            engine.processFrameSuspending(hand(ts, pinchGap = 0.08f))
            ts += 40L
        }
        // Flood the continuous cursor path. These frames must not enter the
        // semantic transport at all.
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
        runCurrent()

        assertEquals(1, events.count { it is GestureEvent.Pinch && it.phase == PinchPhase.START })
        assertEquals(1, events.count { it is GestureEvent.Pinch && it.phase == PinchPhase.END })
        assertTrue(events.indexOfFirst { it is GestureEvent.Pinch && it.phase == PinchPhase.START } <
            events.indexOfFirst { it is GestureEvent.Pinch && it.phase == PinchPhase.END })

        collector.cancel()
    }

    private fun hand(timestampMs: Long, pinchGap: Float? = null): HandInput {
        val wx = 0.5f
        val wy = 0.75f
        val s = 0.35f
        fun finger(xOffset: Float): List<Landmark3D> {
            val x = wx + xOffset * s
            return listOf(
                Landmark3D(x, wy - 1.0f * s, 0f),
                Landmark3D(x, wy - 1.5f * s, 0f),
                Landmark3D(x, wy - 1.8f * s, 0f),
                Landmark3D(x, wy - 2.1f * s, 0f),
            )
        }
        val wrist = Landmark3D(wx, wy, 0f)
        val thumb = if (pinchGap == null) {
            Landmark3D(wx - 0.68f * s, wy - 0.68f * s, 0f)
        } else {
            Landmark3D(wx - 0.15f * s + pinchGap * s, wy - 2.1f * s, 0f)
        }
        val landmarks = mutableListOf(
            wrist,
            Landmark3D(wx - 0.2f * s, wy - 0.2f * s, 0f),
            Landmark3D(wx - 0.3f * s, wy - 0.3f * s, 0f),
            Landmark3D(wx - 0.5f * s, wy - 0.5f * s, 0f),
            thumb,
        )
        landmarks += finger(-0.15f)
        landmarks += finger(0f)
        landmarks += finger(0.15f)
        landmarks += finger(0.30f)
        return HandInput(landmarks, Handedness.RIGHT, timestampMs, 0.95f)
    }
}
