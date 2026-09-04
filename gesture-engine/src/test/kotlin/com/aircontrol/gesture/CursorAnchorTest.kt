package com.aircontrol.gesture

import com.aircontrol.gesture.model.HandInput
import com.aircontrol.gesture.model.Handedness
import com.aircontrol.gesture.model.Landmark3D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CursorAnchorTest {
    @Test
    fun `palm anchor ignores index fingertip movement`() {
        val base = landmarks(indexTipX = 0.45f, indexTipY = 0.35f)
        val movedFinger = landmarks(indexTipX = 0.90f, indexTipY = 0.05f)
        val a = CursorAnchor.palm(input(base))!!
        val b = CursorAnchor.palm(input(movedFinger))!!

        assertEquals(a.first, b.first, 0.0001f)
        assertEquals(a.second, b.second, 0.0001f)
    }

    @Test
    fun `palm anchor moves when palm moves`() {
        val a = CursorAnchor.palm(input(landmarks(palmOffsetX = 0f, palmOffsetY = 0f)))!!
        val b = CursorAnchor.palm(input(landmarks(palmOffsetX = 0.15f, palmOffsetY = -0.10f)))!!

        assertTrue(kotlin.math.abs(a.first - b.first) > 0.05f)
        assertTrue(kotlin.math.abs(a.second - b.second) > 0.03f)
    }

    private fun input(lm: List<Landmark3D>) = HandInput(
        landmarks = lm,
        handedness = Handedness.RIGHT,
        timestampMs = 1000L,
        confidence = 0.99f,
    )

    private fun landmarks(
        indexTipX: Float = 0.45f,
        indexTipY: Float = 0.35f,
        palmOffsetX: Float = 0f,
        palmOffsetY: Float = 0f,
    ): List<Landmark3D> {
        val points = MutableList(21) { Landmark3D(0.5f, 0.6f, 0f) }
        points[0] = Landmark3D(0.5f + palmOffsetX, 0.8f + palmOffsetY, 0f)
        points[5] = Landmark3D(0.40f + palmOffsetX, 0.62f + palmOffsetY, 0f)
        points[9] = Landmark3D(0.48f + palmOffsetX, 0.60f + palmOffsetY, 0f)
        points[13] = Landmark3D(0.56f + palmOffsetX, 0.62f + palmOffsetY, 0f)
        points[17] = Landmark3D(0.63f + palmOffsetX, 0.66f + palmOffsetY, 0f)
        points[8] = Landmark3D(indexTipX, indexTipY, 0f)
        return points
    }
}
