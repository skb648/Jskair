package com.aircontrol.tracking

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadPoseEstimatorTest {

    @Test
    fun neutralPoseFromValidMatrixIsZeroAndUsesMatrix() {
        val frame = syntheticFrame(matrix = rotationMatrix(0f, 0f, 0f))
        val features = EyeFeatureExtractor.extract(frame)
        val pose = HeadPoseEstimator.estimate(frame, features)

        assertTrue(pose.isValid)
        assertEquals(HeadPoseSource.MATRIX, pose.source)
        assertEquals(0f, pose.yawDeg, 1e-4f)
        assertEquals(0f, pose.pitchDeg, 1e-4f)
        assertEquals(0f, pose.rollDeg, 1e-4f)
    }

    @Test
    fun matrixYawPitchRollAreRecovered() {
        val frame = syntheticFrame(matrix = rotationMatrix(20f, -12f, 15f))
        val pose = HeadPoseEstimator.estimate(frame, EyeFeatureExtractor.extract(frame))

        assertEquals(20f, pose.yawDeg, 1e-3f)
        assertEquals(-12f, pose.pitchDeg, 1e-3f)
        assertEquals(15f, pose.rollDeg, 1e-3f)
    }

    @Test
    fun translationAndScaleComeFromStableEyeGeometry() {
        val base = syntheticFrame()
        val moved = transformFrame(base, translateX = 80f, translateY = -45f, scale = 1.5f)
        val basePose = HeadPoseEstimator.estimate(base, EyeFeatureExtractor.extract(base))
        val movedPose = HeadPoseEstimator.estimate(moved, EyeFeatureExtractor.extract(moved))

        assertEquals(0f, basePose.translationXPx, 1e-3f)
        assertEquals(0f, basePose.translationYPx, 1e-3f)
        assertEquals(80f, movedPose.translationXPx, 1e-3f)
        assertEquals(-45f, movedPose.translationYPx, 1e-3f)
        assertEquals(basePose.faceScalePx * 1.5f, movedPose.faceScalePx, 1e-2f)
    }

    @Test
    fun invalidMatrixFallsBackToLandmarkGeometry() {
        val invalid = FloatArray(16) { 0f }
        invalid[0] = Float.NaN
        val frame = syntheticFrame(matrix = invalid)
        val pose = HeadPoseEstimator.estimate(frame, EyeFeatureExtractor.extract(frame))

        assertTrue(pose.isValid)
        assertEquals(HeadPoseSource.LANDMARK_FALLBACK, pose.source)
        assertEquals(0f, pose.yawDeg, 1e-4f)
        assertEquals(0f, pose.pitchDeg, 1e-4f)
        assertEquals(0f, pose.rollDeg, 1e-4f)
    }

    @Test
    fun invalidLandmarksProduceInvalidPoseInsteadOfFabricatedValues() {
        val landmarks = validLandmarks().toMutableList()
        landmarks[CanonicalEyes.LEFT.outerCorner] = FaceLandmark(Float.NaN, 0.5f, 0f)
        val frame = syntheticFrame(landmarks = landmarks, matrix = rotationMatrix(0f, 0f, 0f))
        val features = EyeFeatureExtractor.extract(frame)
        val pose = HeadPoseEstimator.estimate(frame, features)

        assertNull(features.left)
        assertNotNull(features.right)
        assertTrue(!pose.isValid)
        assertEquals(HeadPoseSource.INVALID, pose.source)
        assertEquals(0f, pose.confidence, 0f)
        assertNotNull(pose.reason)
    }

    @Test
    fun degenerateEyeSeparationProducesInvalidPose() {
        val landmarks = validLandmarks().toMutableList()
        val left = CanonicalEyes.LEFT
        val right = CanonicalEyes.RIGHT
        val rightInner = landmarks[right.innerCorner]
        val rightOuter = landmarks[right.outerCorner]
        landmarks[left.innerCorner] = rightInner
        landmarks[left.outerCorner] = rightOuter
        val frame = syntheticFrame(landmarks = landmarks)
        val features = EyeFeatureExtractor.extract(frame)
        val pose = HeadPoseEstimator.estimate(frame, features)

        assertTrue(!pose.isValid)
        assertEquals(HeadPoseSource.INVALID, pose.source)
    }

    @Test
    fun mirroredInputKeepsAnatomicalIdentityAndPoseValidity() {
        val original = syntheticFrame(mirrored = false, matrix = rotationMatrix(10f, 4f, -7f))
        val mirrored = original.copy(
            isFrontCameraMirrored = true,
            landmarks = original.landmarks.map { it.copy(x = 1f - it.x) },
        )
        val aFeatures = EyeFeatureExtractor.extract(original)
        val bFeatures = EyeFeatureExtractor.extract(mirrored)
        val aPose = HeadPoseEstimator.estimate(original, aFeatures)
        val bPose = HeadPoseEstimator.estimate(mirrored, bFeatures)

        assertNotNull(aFeatures.left)
        assertNotNull(bFeatures.left)
        assertNotNull(aFeatures.right)
        assertNotNull(bFeatures.right)
        assertTrue(bFeatures.left!!.eyeCenterX < bFeatures.right!!.eyeCenterX)
        assertEquals(HeadPoseSource.MATRIX, bPose.source)
        assertEquals(aPose.yawDeg, bPose.yawDeg, 1e-4f)
        assertEquals(aPose.pitchDeg, bPose.pitchDeg, 1e-4f)
        assertEquals(aPose.rollDeg, bPose.rollDeg, 1e-4f)
    }

    @Test
    fun headOnlyTranslationAndScaleSubstantiallyReduceNormalizedEyeChange() {
        val base = syntheticFrame(matrix = rotationMatrix(0f, 0f, 0f))
        val moved = transformFrame(base, translateX = 120f, translateY = -60f, scale = 1.8f)

        val baseFeatures = EyeFeatureExtractor.extract(base)
        val movedFeatures = EyeFeatureExtractor.extract(moved)
        val basePose = HeadPoseEstimator.estimate(base, baseFeatures)
        val movedPose = HeadPoseEstimator.estimate(moved, movedFeatures)
        val baseNorm = HeadPoseNormalizer.normalize(baseFeatures, basePose)
        val movedNorm = HeadPoseNormalizer.normalize(movedFeatures, movedPose)

        val rawDelta = distance2D(
            baseFeatures.left!!.eyeCenterX,
            baseFeatures.left!!.eyeCenterY,
            movedFeatures.left!!.eyeCenterX,
            movedFeatures.left!!.eyeCenterY,
        )
        val normalizedDelta = distance2D(
            baseNorm.left!!.eyeCenterX,
            baseNorm.left!!.eyeCenterY,
            movedNorm.left!!.eyeCenterX,
            movedNorm.left!!.eyeCenterY,
        )

        assertTrue(rawDelta > normalizedDelta * 10f)
        assertTrue(normalizedDelta < 1e-3f)
    }

    @Test
    fun invalidPoseIsNotAcceptedByNormalizer() {
        val features = EyeFeatureExtractor.extract(syntheticFrame())
        val normalized = HeadPoseNormalizer.normalize(features, HeadPoseEstimate.invalid("test"))

        assertNull(normalized.left)
        assertNull(normalized.right)
        assertTrue(!normalized.isValid)
    }

    private fun syntheticFrame(
        width: Int = 800,
        height: Int = 600,
        mirrored: Boolean = false,
        matrix: FloatArray? = null,
        landmarks: MutableList<FaceLandmark> = validLandmarks(),
    ): FaceLandmarkFrame = FaceLandmarkFrame(
        frameId = 1L,
        timestampNs = 1_000_000L,
        timestampMs = 1L,
        trackerWidthPx = width,
        trackerHeightPx = height,
        isFrontCameraMirrored = mirrored,
        landmarks = landmarks,
        facialTransformationMatrix = matrix,
    )

    private fun validLandmarks(): MutableList<FaceLandmark> {
        val landmarks = MutableList(478) { FaceLandmark(0.5f, 0.5f, 0f) }
        fillEye(landmarks, CanonicalEyes.LEFT, 0.68f, 0.80f, 0.01f)
        fillEye(landmarks, CanonicalEyes.RIGHT, 0.20f, 0.32f, -0.01f)
        landmarks[FacePoseLandmarks.NOSE_TIP] = FaceLandmark(0.50f, 0.50f, 0f)
        landmarks[FacePoseLandmarks.FOREHEAD] = FaceLandmark(0.50f, 0.25f, 0f)
        landmarks[FacePoseLandmarks.CHIN] = FaceLandmark(0.50f, 0.75f, 0f)
        return landmarks
    }

    private fun fillEye(
        landmarks: MutableList<FaceLandmark>,
        d: EyeLandmarkDefinition,
        outerX: Float,
        innerX: Float,
        irisShift: Float,
    ) {
        val y = 0.50f
        val dx = innerX - outerX
        val irisT = (0.50f + irisShift).coerceIn(0.10f, 0.90f)
        val nx = 0f
        val ny = dx
        fun point(t: Float, normal: Float): FaceLandmark = FaceLandmark(
            outerX + dx * t + nx * normal,
            y + ny * normal,
            0f,
        )
        landmarks[d.outerCorner] = FaceLandmark(outerX, y, 0f)
        landmarks[d.innerCorner] = FaceLandmark(innerX, y, 0f)
        landmarks[d.upperOuter] = point(0.18f, -0.025f)
        landmarks[d.upperInner] = point(0.82f, -0.025f)
        landmarks[d.lowerInner] = point(0.82f, 0.025f)
        landmarks[d.lowerOuter] = point(0.18f, 0.025f)
        landmarks[d.irisCenter] = point(irisT, 0f)
        landmarks[d.irisRing[0]] = point(irisT + 0.018f, 0f)
        landmarks[d.irisRing[1]] = point(irisT, -0.018f)
        landmarks[d.irisRing[2]] = point(irisT - 0.018f, 0f)
        landmarks[d.irisRing[3]] = point(irisT, 0.018f)
    }

    private fun transformFrame(
        frame: FaceLandmarkFrame,
        translateX: Float,
        translateY: Float,
        scale: Float,
    ): FaceLandmarkFrame {
        val cx = frame.trackerWidthPx * 0.5f
        val cy = frame.trackerHeightPx * 0.5f
        val transformed = frame.landmarks.map {
            FaceLandmark(
                x = ((it.x * frame.trackerWidthPx - cx) * scale + cx + translateX) / frame.trackerWidthPx,
                y = ((it.y * frame.trackerHeightPx - cy) * scale + cy + translateY) / frame.trackerHeightPx,
                z = it.z,
            )
        }
        return frame.copy(landmarks = transformed)
    }

    private fun rotationMatrix(yawDeg: Float, pitchDeg: Float, rollDeg: Float): FloatArray {
        val yaw = Math.toRadians(yawDeg.toDouble())
        val pitch = Math.toRadians(pitchDeg.toDouble())
        val roll = Math.toRadians(rollDeg.toDouble())
        val cy = cos(yaw)
        val sy = sin(yaw)
        val cp = cos(pitch)
        val sp = sin(pitch)
        val cr = cos(roll)
        val sr = sin(roll)
        val r00 = cr * cy
        val r01 = cr * sy * sp - sr * cp
        val r02 = cr * sy * cp + sr * sp
        val r10 = sr * cy
        val r11 = sr * sy * sp + cr * cp
        val r12 = sr * sy * cp - cr * sp
        val r20 = -sy
        val r21 = cy * sp
        val r22 = cy * cp
        return floatArrayOf(
            r00.toFloat(), r10.toFloat(), r20.toFloat(), 0f,
            r01.toFloat(), r11.toFloat(), r21.toFloat(), 0f,
            r02.toFloat(), r12.toFloat(), r22.toFloat(), 0f,
            0f, 0f, 0f, 1f,
        )
    }

    private fun distance2D(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }
}
