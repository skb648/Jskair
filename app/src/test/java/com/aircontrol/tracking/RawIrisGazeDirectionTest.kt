package com.aircontrol.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawIrisGazeDirectionTest {
    @Test fun centerGazeStaysCentered() {
        val gaze = extract(0f, 0f)
        assertEquals(0.5f, gaze.h, 1e-5f)
        assertEquals(0.5f, gaze.v, 1e-5f)
        assertEquals(2, gaze.eyesUsed)
    }

    @Test fun lookingRightMovesCursorRight() {
        val gaze = extract(horizontal = 0.20f, vertical = 0f)
        assertTrue(gaze.h > 0.5f)
    }

    @Test fun lookingLeftMovesCursorLeft() {
        val gaze = extract(horizontal = -0.20f, vertical = 0f)
        assertTrue(gaze.h < 0.5f)
    }

    @Test fun lookingDownMovesCursorDown() {
        val gaze = extract(horizontal = 0f, vertical = 0.10f)
        assertTrue(gaze.v > 0.5f)
    }

    @Test fun lookingUpMovesCursorUp() {
        val gaze = extract(horizontal = 0f, vertical = -0.10f)
        assertTrue(gaze.v < 0.5f)
    }

    @Test fun verticalDisagreementReducesBinocularAgreement() {
        val left = eye(viewerX = 0f, viewerY = -0.15f)
        val right = eye(viewerX = 0f, viewerY = 0.15f)
        val gaze = RawIrisGazeExtractor.from(BinocularEyeFeatures(left, right, null))!!
        assertTrue(gaze.binocularAgreement < 0.25f)
    }

    private fun extract(horizontal: Float, vertical: Float): RawIrisGaze =
        RawIrisGazeExtractor.from(
            BinocularEyeFeatures(
                left = eye(horizontal, vertical),
                right = eye(horizontal, vertical),
                faceGeometry = null,
            ),
        )!!

    private fun eye(viewerX: Float, viewerY: Float): EyeFeatures = EyeFeatures(
        eyeCenterX = 0.3f,
        eyeCenterY = 0.5f,
        irisCenterX = 0.3f,
        irisCenterY = 0.5f,
        irisAlongAxis = 0.5f,
        irisPerpendicular = 0f,
        axisSign = 1f,
        irisViewerX = viewerX,
        irisViewerY = viewerY,
        irisDiameterOverEyeWidth = 0.3f,
        eyeWidthPx = 100f,
        eyelidOpening = 0.4f,
        ear = 0.4f,
        eyeCenterFromFaceCenterX = 0f,
        eyeCenterFromFaceCenterY = 0f,
        quality = 0.95f,
    )
}
