package com.aircontrol.tracking

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EyeFeatureExtractorTest {

    @Test
    fun mirroredInputPreservesAnatomicalEyeIdentity() {
        val original = syntheticFrame(mirrored = false)
        val mirrored = mirrorFrame(original)

        val a = EyeFeatureExtractor.extract(original)
        val b = EyeFeatureExtractor.extract(mirrored)

        assertNotNull(a.left)
        assertNotNull(a.right)
        assertNotNull(b.left)
        assertNotNull(b.right)

        // Anatomical identity is tied to MediaPipe semantic IDs, never x ordering.
        assertEquals(a.left!!.irisAlongAxis, b.left!!.irisAlongAxis, 1e-6f)
        assertEquals(a.left.irisPerpendicular, -b.left.irisPerpendicular, 1e-6f)
        assertEquals(a.right!!.irisAlongAxis, b.right!!.irisAlongAxis, 1e-6f)
        assertEquals(a.right.irisPerpendicular, -b.right.irisPerpendicular, 1e-6f)
    }

    @Test
    fun aspectCorrectCoordinatesUseTrackerWidthAndHeight() {
        val frame = syntheticFrame(
            width = 1000,
            height = 500,
            leftCorners = 0.70f to 0.50f to 0.82f to 0.54f,
            rightCorners = 0.18f to 0.50f to 0.30f to 0.54f,
        )

        val features = EyeFeatureExtractor.extract(frame)
        val left = features.left
        assertNotNull(left)

        // Corner delta is (120 px, 20 px), so aspect-correct Euclidean width is
        // sqrt(120^2 + 20^2), not sqrt(.12^2 + .04^2) in raw normalized space.
        val expected = kotlin.math.hypot(120.0, 20.0).toFloat()
        assertEquals(expected, left!!.eyeWidthPx, 1e-3f)
    }

    @Test
    fun unequalEyesRemainIndependentAndAreNotAveraged() {
        val frame = syntheticFrame(
            leftIrisShift = 0.08f,
            rightIrisShift = -0.03f,
        )
        val features = EyeFeatureExtractor.extract(frame)

        assertNotNull(features.left)
        assertNotNull(features.right)
        assertTrue(abs(features.left!!.irisAlongAxis - features.right!!.irisAlongAxis) > 0.05f)
    }

    @Test
    fun oneEyeDegradationLeavesOtherEyeValid() {
        val landmarks = syntheticLandmarks().toMutableList()
        val bad = CanonicalEyes.LEFT.irisCenter
        landmarks[bad] = FaceLandmark(Float.NaN, 0.5f, 0f)

        val features = EyeFeatureExtractor.extract(
            syntheticFrame(landmarks = landmarks),
        )

        assertNull(features.left)
        assertNotNull(features.right)
        assertTrue(features.isValid)
    }

    @Test
    fun invalidNonFiniteLandmarksProduceNoEyeFeature() {
        val landmarks = syntheticLandmarks().toMutableList()
        landmarks[CanonicalEyes.RIGHT.innerCorner] = FaceLandmark(Float.POSITIVE_INFINITY, 0.4f, 0f)
        landmarks[CanonicalEyes.LEFT.innerCorner] = FaceLandmark(0.8f, Float.NaN, 0f)

        val features = EyeFeatureExtractor.extract(syntheticFrame(landmarks = landmarks))

        assertNull(features.left)
        assertNull(features.right)
        assertFalse(features.isValid)
    }

    @Test
    fun degenerateEyeGeometryRemainsInvalid() {
        val landmarks = syntheticLandmarks().toMutableList()
        val left = CanonicalEyes.LEFT
        val x = landmarks[left.innerCorner].x
        val y = landmarks[left.innerCorner].y
        landmarks[left.outerCorner] = FaceLandmark(x, y, 0f)

        val features = EyeFeatureExtractor.extract(syntheticFrame(landmarks = landmarks))
        assertNull(features.left)
        assertNotNull(features.right)
    }

    @Test
    fun scaleInvariantLocalFeaturesRemainStable() {
        val base = syntheticFrame(width = 800, height = 600)
        val scaled = syntheticFrame(width = 1600, height = 1200)
        val a = EyeFeatureExtractor.extract(base)
        val b = EyeFeatureExtractor.extract(scaled)

        assertNotNull(a.left)
        assertNotNull(b.left)
        val la = a.left!!
        val lb = b.left!!
        assertEquals(la.irisAlongAxis, lb.irisAlongAxis, 1e-5f)
        assertEquals(la.irisPerpendicular, lb.irisPerpendicular, 1e-5f)
        assertEquals(la.irisDiameterOverEyeWidth, lb.irisDiameterOverEyeWidth, 1e-5f)
        assertEquals(la.eyelidOpening, lb.eyelidOpening, 1e-5f)
        assertEquals(la.ear, lb.ear, 1e-5f)
        assertEquals(la.eyeCenterFromFaceCenterX, lb.eyeCenterFromFaceCenterX, 1e-5f)
        assertEquals(la.eyeCenterFromFaceCenterY, lb.eyeCenterFromFaceCenterY, 1e-5f)
        assertEquals(la.eyeWidthPx * 2f, lb.eyeWidthPx, 1e-3f)
    }

    private fun mirrorFrame(frame: FaceLandmarkFrame): FaceLandmarkFrame =
        frame.copy(
            isFrontCameraMirrored = true,
            landmarks = frame.landmarks.map { it.copy(x = 1f - it.x) },
        )

    private fun syntheticFrame(
        width: Int = 800,
        height: Int = 600,
        mirrored: Boolean = false,
        leftIrisShift: Float = 0.01f,
        rightIrisShift: Float = -0.01f,
        leftCorners: FloatQuad? = null,
        rightCorners: FloatQuad? = null,
        landmarks: MutableList<FaceLandmark>? = null,
    ): FaceLandmarkFrame {
        val base = landmarks ?: syntheticLandmarks(
            leftIrisShift = leftIrisShift,
            rightIrisShift = rightIrisShift,
            leftCorners = leftCorners,
            rightCorners = rightCorners,
        )
        return FaceLandmarkFrame(
            frameId = 1L,
            timestampNs = 1_000_000L,
            timestampMs = 1L,
            trackerWidthPx = width,
            trackerHeightPx = height,
            isFrontCameraMirrored = mirrored,
            landmarks = base,
        )
    }

    private fun syntheticLandmarks(
        leftIrisShift: Float = 0.01f,
        rightIrisShift: Float = -0.01f,
        leftCorners: FloatQuad? = null,
        rightCorners: FloatQuad? = null,
    ): MutableList<FaceLandmark> {
        val landmarks = MutableList(478) { FaceLandmark(0.5f, 0.5f, 0f) }
        fillEye(landmarks, CanonicalEyes.LEFT, leftCorners ?: (0.68f to 0.50f to 0.80f to 0.50f), leftIrisShift)
        fillEye(landmarks, CanonicalEyes.RIGHT, rightCorners ?: (0.20f to 0.50f to 0.32f to 0.50f), rightIrisShift)
        return landmarks
    }

    private fun fillEye(
        landmarks: MutableList<FaceLandmark>,
        d: EyeLandmarkDefinition,
        corners: FloatQuad,
        irisShift: Float,
    ) {
        val outer = FaceLandmark(corners.x1, corners.y1, 0f)
        val inner = FaceLandmark(corners.x2, corners.y2, 0f)
        val dx = corners.x2 - corners.x1
        val dy = corners.y2 - corners.y1
        val nx = -dy
        val ny = dx
        fun point(t: Float, normal: Float): FaceLandmark =
            FaceLandmark(
                corners.x1 + dx * t + nx * normal,
                corners.y1 + dy * t + ny * normal,
                0f,
            )

        landmarks[d.outerCorner] = outer
        landmarks[d.innerCorner] = inner
        landmarks[d.upperOuter] = point(0.18f, -0.025f)
        landmarks[d.upperInner] = point(0.82f, -0.025f)
        landmarks[d.lowerInner] = point(0.82f, 0.025f)
        landmarks[d.lowerOuter] = point(0.18f, 0.025f)

        val irisT = (0.50f + irisShift).coerceIn(0.10f, 0.90f)
        val irisCenter = point(irisT, 0f)
        landmarks[d.irisCenter] = irisCenter
        val irisRadius = 0.018f
        landmarks[d.irisRing[0]] = point(irisT + irisRadius, 0f)
        landmarks[d.irisRing[1]] = point(irisT, -irisRadius)
        landmarks[d.irisRing[2]] = point(irisT - irisRadius, 0f)
        landmarks[d.irisRing[3]] = point(irisT, irisRadius)
    }

    private data class FloatQuad(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
    )

    private infix fun Float.to(other: Float): FloatPair = FloatPair(this, other)
    private data class FloatPair(val x: Float, val y: Float) {
        infix fun to(otherX: Float): FloatTriple = FloatTriple(x, y, otherX)
    }
    private data class FloatTriple(val x1: Float, val y1: Float, val x2: Float) {
        infix fun to(otherY: Float): FloatQuad = FloatQuad(x1, y1, x2, otherY)
    }
}
