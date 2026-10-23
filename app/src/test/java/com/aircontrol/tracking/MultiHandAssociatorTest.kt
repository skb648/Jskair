package com.aircontrol.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiHandAssociatorTest {

    private fun makeHandLandmarks(centerX: Float, centerY: Float, spread: Float = 0.14f): List<Landmark3D> {
        return List(21) { idx ->
            val ring = (idx % 5) - 2
            val depth = idx / 5
            Landmark3D(
                x = (centerX + ring * spread * 0.25f).coerceIn(0.01f, 0.99f),
                y = (centerY - depth * spread * 0.35f).coerceIn(0.01f, 0.99f),
                z = 0f,
            )
        }
    }

    @Test
    fun `simultaneous left and right hands preserve both tracks and 21 landmarks`() {
        val associator = MultiHandAssociator()
        val leftLandmarks = makeHandLandmarks(0.25f, 0.65f)
        val rightLandmarks = makeHandLandmarks(0.75f, 0.65f)

        val frame1 = associator.associate(
            rawDetections = listOf(
                RawHandDetection(leftLandmarks, Handedness.LEFT, 0.92f),
                RawHandDetection(rightLandmarks, Handedness.RIGHT, 0.95f),
            ),
            timestampMs = 100L,
            frameAspectRatio = 1.333f,
            preferredHand = Handedness.UNKNOWN,
        )

        assertTrue(frame1.isDetected)
        assertTrue(frame1.isDualHandDetected)
        assertEquals(2, frame1.hands.size)
        val left = frame1.leftHand
        val right = frame1.rightHand
        assertNotNull(left)
        assertNotNull(right)
        assertEquals(21, left!!.landmarks.size)
        assertEquals(21, right!!.landmarks.size)
        assertNotEquals(left.trackId, right.trackId)
        assertFalse(left.isAmbiguous)
        assertFalse(right.isAmbiguous)
    }

    @Test
    fun `track IDs remain stable across consecutive frames even if MediaPipe reorders detections`() {
        val associator = MultiHandAssociator()
        val f1 = associator.associate(
            rawDetections = listOf(
                RawHandDetection(makeHandLandmarks(0.25f, 0.65f), Handedness.LEFT, 0.90f),
                RawHandDetection(makeHandLandmarks(0.75f, 0.65f), Handedness.RIGHT, 0.91f),
            ),
            timestampMs = 100L,
            frameAspectRatio = 1.333f,
            preferredHand = Handedness.UNKNOWN,
        )
        val leftTrackId = f1.leftHand!!.trackId
        val rightTrackId = f1.rightHand!!.trackId

        // Next frame: MediaPipe returns RIGHT first and LEFT second, with slight movement
        val f2 = associator.associate(
            rawDetections = listOf(
                RawHandDetection(makeHandLandmarks(0.76f, 0.66f), Handedness.RIGHT, 0.89f),
                RawHandDetection(makeHandLandmarks(0.26f, 0.66f), Handedness.LEFT, 0.93f),
            ),
            timestampMs = 133L,
            frameAspectRatio = 1.333f,
            preferredHand = Handedness.UNKNOWN,
        )

        assertEquals(leftTrackId, f2.leftHand!!.trackId)
        assertEquals(rightTrackId, f2.rightHand!!.trackId)
    }

    @Test
    fun `one hand leaving frame preserves the remaining hand trackId without reset`() {
        val associator = MultiHandAssociator()
        val f1 = associator.associate(
            rawDetections = listOf(
                RawHandDetection(makeHandLandmarks(0.25f, 0.65f), Handedness.LEFT, 0.90f),
                RawHandDetection(makeHandLandmarks(0.75f, 0.65f), Handedness.RIGHT, 0.91f),
            ),
            timestampMs = 100L,
            frameAspectRatio = 1.333f,
            preferredHand = Handedness.UNKNOWN,
        )
        val rightTrackId = f1.rightHand!!.trackId

        // Left hand leaves; only right hand remains
        val f2 = associator.associate(
            rawDetections = listOf(
                RawHandDetection(makeHandLandmarks(0.76f, 0.65f), Handedness.RIGHT, 0.92f),
            ),
            timestampMs = 133L,
            frameAspectRatio = 1.333f,
            preferredHand = Handedness.UNKNOWN,
        )

        assertEquals(1, f2.hands.size)
        assertEquals(rightTrackId, f2.hands.first().trackId)
        assertEquals(rightTrackId, f2.primaryTrackId)
    }

    @Test
    fun `overlapping or crossing hands are marked ambiguous to suppress accidental gestures`() {
        val associator = MultiHandAssociator()
        // Establish two distinct tracks first
        associator.associate(
            rawDetections = listOf(
                RawHandDetection(makeHandLandmarks(0.35f, 0.65f), Handedness.LEFT, 0.90f),
                RawHandDetection(makeHandLandmarks(0.65f, 0.65f), Handedness.RIGHT, 0.90f),
            ),
            timestampMs = 100L,
            frameAspectRatio = 1.0f,
            preferredHand = Handedness.UNKNOWN,
        )

        // Hands move very close together (palm distance ~0.08, well below CROSSING_AMBIGUITY_DIST=0.11 but above DUPLICATE_PALM_DIST=0.055)
        val crossingFrame = associator.associate(
            rawDetections = listOf(
                RawHandDetection(makeHandLandmarks(0.46f, 0.65f), Handedness.LEFT, 0.88f),
                RawHandDetection(makeHandLandmarks(0.54f, 0.65f), Handedness.RIGHT, 0.88f),
            ),
            timestampMs = 133L,
            frameAspectRatio = 1.0f,
            preferredHand = Handedness.UNKNOWN,
        )

        assertEquals(2, crossingFrame.hands.size)
        assertTrue(crossingFrame.hands.all { it.isAmbiguous })
    }

    @Test
    fun `single-hand handedness flip hysteresis resists transient 1-frame classification flip`() {
        val associator = MultiHandAssociator()
        val f1 = associator.associate(
            rawDetections = listOf(
                RawHandDetection(makeHandLandmarks(0.50f, 0.65f), Handedness.RIGHT, 0.90f),
            ),
            timestampMs = 100L,
            frameAspectRatio = 1.0f,
            preferredHand = Handedness.UNKNOWN,
        )
        assertEquals(Handedness.RIGHT, f1.handedness)

        // Single-frame transient flip to LEFT at the exact same position
        val f2 = associator.associate(
            rawDetections = listOf(
                RawHandDetection(makeHandLandmarks(0.51f, 0.65f), Handedness.LEFT, 0.85f),
            ),
            timestampMs = 133L,
            frameAspectRatio = 1.0f,
            preferredHand = Handedness.UNKNOWN,
        )
        assertEquals(Handedness.RIGHT, f2.handedness)
    }
}
