package com.aircontrol.tracking

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandFrameQualityTest {
    @Test fun goodGeometryCanBeDetectedWithoutHighHandednessScore() {
        val frame = HandFrame(
            landmarks = normalLandmarks(),
            handedness = Handedness.UNKNOWN,
            timestampMs = 1L,
            confidence = 0.20f,
        )
        assertTrue(frame.landmarkQuality >= HandFrame.MIN_LANDMARK_QUALITY)
        assertTrue(frame.isDetected)
    }

    @Test fun nonFiniteLandmarkCannotBecomeDetected() {
        val points = normalLandmarks().toMutableList()
        points[8] = points[8].copy(x = Float.NaN)
        val frame = HandFrame(points, Handedness.RIGHT, 1L, 0.99f)
        assertFalse(frame.isDetected)
        assertTrue(frame.landmarkQuality == 0f)
    }

    @Test fun collapsedHandGeometryCannotBecomeDetected() {
        val points = List(HandFrame.LANDMARK_COUNT) { Landmark3D(0.5f, 0.5f, 0f) }
        val frame = HandFrame(points, Handedness.RIGHT, 1L, 0.99f)
        assertFalse(frame.isDetected)
    }

    private fun normalLandmarks(): List<Landmark3D> = List(HandFrame.LANDMARK_COUNT) { i ->
        Landmark3D(
            x = 0.35f + (i % 5) * 0.04f,
            y = 0.25f + (i / 5) * 0.07f,
            z = 0f,
        )
    }
}
