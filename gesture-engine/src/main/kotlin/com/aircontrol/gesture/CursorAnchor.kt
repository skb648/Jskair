package com.aircontrol.gesture

import com.aircontrol.gesture.model.HandInput
import com.aircontrol.gesture.model.LandmarkIndex

/** Stable pointer anchor derived from palm landmarks, independent of finger articulation. */
object CursorAnchor {
    fun palm(input: HandInput): Pair<Float, Float>? {
        if (!input.isDetected || input.landmarks.size <= LandmarkIndex.PINKY_MCP) return null
        val wrist = input.landmarks[LandmarkIndex.WRIST]
        val index = input.landmarks[LandmarkIndex.INDEX_MCP]
        val middle = input.landmarks[LandmarkIndex.MIDDLE_MCP]
        val ring = input.landmarks[LandmarkIndex.RING_MCP]
        val pinky = input.landmarks[LandmarkIndex.PINKY_MCP]
        val mcpX = (index.x + middle.x + ring.x + pinky.x) * 0.25f
        val mcpY = (index.y + middle.y + ring.y + pinky.y) * 0.25f
        return ((mcpX * 0.75f + wrist.x * 0.25f).coerceIn(0f, 1f)) to
            ((mcpY * 0.75f + wrist.y * 0.25f).coerceIn(0f, 1f))
    }
}
