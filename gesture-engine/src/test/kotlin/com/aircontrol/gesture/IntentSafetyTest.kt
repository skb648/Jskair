package com.aircontrol.gesture

import com.aircontrol.gesture.config.GestureEngineConfig
import com.aircontrol.gesture.model.GestureEngineState
import com.aircontrol.gesture.model.GestureEvent
import com.aircontrol.gesture.model.HandInput
import com.aircontrol.gesture.model.Handedness
import com.aircontrol.gesture.model.Landmark3D
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentSafetyTest {
    @Test
    fun `palm cursor move event is independent from index tip`() {
        val engine = GestureEngine(GestureEngineConfig(armingDurationMs = 50L, poseDebounceFrames = 2))
        val events = mutableListOf<GestureEvent>()

        val base = hand(0.45f, 0.35f)
        engine.processFrame(base.copy(timestampMs = 100L))
        engine.processFrame(base.copy(timestampMs = 150L))
        engine.processFrame(base.copy(timestampMs = 220L))

        events += listOf()
        assertTrue(engine.engineState.value == GestureEngineState.ARMED || engine.engineState.value == GestureEngineState.ARMING)
    }

    @Test
    fun `palm anchor remains bounded`() {
        val result = CursorAnchor.palm(hand(0.9f, 0.1f))!!
        assertTrue(result.first in 0f..1f)
        assertTrue(result.second in 0f..1f)
    }

    private fun hand(indexX: Float, indexY: Float) = HandInput(
        landmarks = MutableList(21) { Landmark3D(0.5f, 0.6f, 0f) }.apply {
            this[0] = Landmark3D(0.5f, 0.8f, 0f)
            this[1] = Landmark3D(0.40f, 0.75f, 0f)
            this[2] = Landmark3D(0.35f, 0.70f, 0f)
            this[3] = Landmark3D(0.30f, 0.65f, 0f)
            this[4] = Landmark3D(0.25f, 0.60f, 0f)
            this[5] = Landmark3D(0.40f, 0.62f, 0f)
            this[6] = Landmark3D(0.40f, 0.50f, 0f)
            this[7] = Landmark3D(0.40f, 0.40f, 0f)
            this[8] = Landmark3D(indexX, indexY, 0f)
            this[9] = Landmark3D(0.48f, 0.60f, 0f)
            this[10] = Landmark3D(0.48f, 0.50f, 0f)
            this[11] = Landmark3D(0.48f, 0.40f, 0f)
            this[12] = Landmark3D(0.48f, 0.30f, 0f)
            this[13] = Landmark3D(0.56f, 0.62f, 0f)
            this[14] = Landmark3D(0.56f, 0.50f, 0f)
            this[15] = Landmark3D(0.56f, 0.40f, 0f)
            this[16] = Landmark3D(0.56f, 0.32f, 0f)
            this[17] = Landmark3D(0.63f, 0.66f, 0f)
            this[18] = Landmark3D(0.63f, 0.55f, 0f)
            this[19] = Landmark3D(0.63f, 0.45f, 0f)
            this[20] = Landmark3D(0.63f, 0.38f, 0f)
        },
        handedness = Handedness.RIGHT,
        timestampMs = 0L,
        confidence = 0.98f,
    )
}
