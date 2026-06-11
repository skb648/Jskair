package com.aircontrol.gesture.detection

import com.aircontrol.gesture.config.GestureEngineConfig
import com.aircontrol.gesture.model.HandInput
import com.aircontrol.gesture.model.Handedness
import com.aircontrol.gesture.model.Landmark3D
import com.aircontrol.gesture.model.Pose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for StaticPoseClassifier.
 * Tests all 7 poses, debounce behavior, pinch with hand-size scaling,
 * and pose priority ordering.
 */
class StaticPoseClassifierTest {

    private lateinit var classifier: StaticPoseClassifier
    private lateinit var config: GestureEngineConfig

    @Before
    fun setUp() {
        config = GestureEngineConfig(sensitivity = 50, poseDebounceFrames = 3)
        classifier = StaticPoseClassifier(config)
    }

    // ========== Landmark builders for each pose ==========

    private fun openPalmLandmarks(): List<Landmark3D> = buildLandmarks(
        thumbExtended = true, indexExtended = true, middleExtended = true,
        ringExtended = true, pinkyExtended = true,
    )

    private fun fistLandmarks(): List<Landmark3D> = buildLandmarks(
        thumbExtended = false, indexExtended = false, middleExtended = false,
        ringExtended = false, pinkyExtended = false,
    )

    private fun pointingLandmarks(): List<Landmark3D> = buildLandmarks(
        thumbExtended = false, indexExtended = true, middleExtended = false,
        ringExtended = false, pinkyExtended = false,
    )

    private fun victoryLandmarks(): List<Landmark3D> = buildLandmarks(
        thumbExtended = false, indexExtended = true, middleExtended = true,
        ringExtended = false, pinkyExtended = false,
    )

    private fun thumbUpLandmarks(): List<Landmark3D> = buildLandmarks(
        thumbExtended = true, indexExtended = false, middleExtended = false,
        ringExtended = false, pinkyExtended = false,
        thumbUp = true,
    )

    private fun thumbDownLandmarks(): List<Landmark3D> = buildLandmarks(
        thumbExtended = true, indexExtended = false, middleExtended = false,
        ringExtended = false, pinkyExtended = false,
        thumbUp = false,
    )

    private fun pinchLandmarks(): List<Landmark3D> = buildPinchLandmarks()

    private fun noHandInput(timestampMs: Long = System.currentTimeMillis()): HandInput =
        HandInput.EMPTY.copy(timestampMs = timestampMs)

    // ========== Debounce tests ==========

    @Test
    fun `pose not confirmed before debounce frames reached`() {
        val input = handInput(openPalmLandmarks())
        // Only 1 frame → not confirmed
        assertEquals(Pose.NONE, classifier.classify(input))
    }

    @Test
    fun `pose confirmed after required debounce frames`() {
        val input = handInput(openPalmLandmarks())
        // Feed N-1 frames (still not confirmed)
        repeat(config.poseDebounceFrames - 1) {
            classifier.classify(input)
        }
        // Feed Nth frame → should confirm
        assertEquals(Pose.OPEN_PALM, classifier.classify(input))
    }

    @Test
    fun `debounce resets when pose changes mid-stream`() {
        val openPalm = handInput(openPalmLandmarks())
        val fist = handInput(fistLandmarks())

        // Build up 2 frames of open palm
        repeat(2) { classifier.classify(openPalm) }

        // Switch to fist → resets debounce
        classifier.classify(fist)
        assertEquals(Pose.NONE, classifier.confirmedPose)

        // Now build up fist for debounce frames
        repeat(config.poseDebounceFrames) { classifier.classify(fist) }
        assertEquals(Pose.FIST, classifier.confirmedPose)
    }

    @Test
    fun `no hand resets confirmed pose`() {
        val input = handInput(openPalmLandmarks())
        repeat(config.poseDebounceFrames) { classifier.classify(input) }
        assertEquals(Pose.OPEN_PALM, classifier.confirmedPose)

        // No hand → should reset
        classifier.classify(noHandInput())
        assertEquals(Pose.NONE, classifier.confirmedPose)
    }

    // ========== Individual pose tests ==========

    @Test
    fun `OPEN_PALM detected when all fingers extended`() {
        val input = handInput(openPalmLandmarks())
        repeat(config.poseDebounceFrames) { classifier.classify(input) }
        assertEquals(Pose.OPEN_PALM, classifier.confirmedPose)
    }

    @Test
    fun `FIST detected when no fingers extended`() {
        val input = handInput(fistLandmarks())
        repeat(config.poseDebounceFrames) { classifier.classify(input) }
        assertEquals(Pose.FIST, classifier.confirmedPose)
    }

    @Test
    fun `POINTING detected when only index extended`() {
        val input = handInput(pointingLandmarks())
        repeat(config.poseDebounceFrames) { classifier.classify(input) }
        assertEquals(Pose.POINTING, classifier.confirmedPose)
    }

    @Test
    fun `VICTORY detected when index and middle extended`() {
        val input = handInput(victoryLandmarks())
        repeat(config.poseDebounceFrames) { classifier.classify(input) }
        assertEquals(Pose.VICTORY, classifier.confirmedPose)
    }

    @Test
    fun `THUMB_UP detected when only thumb extended and tip above MCP`() {
        val input = handInput(thumbUpLandmarks())
        repeat(config.poseDebounceFrames) { classifier.classify(input) }
        assertEquals(Pose.THUMB_UP, classifier.confirmedPose)
    }

    @Test
    fun `THUMB_DOWN detected when only thumb extended and tip below MCP`() {
        val input = handInput(thumbDownLandmarks())
        repeat(config.poseDebounceFrames) { classifier.classify(input) }
        assertEquals(Pose.THUMB_DOWN, classifier.confirmedPose)
    }

    @Test
    fun `PINCH detected when thumb and index tips are close`() {
        val input = handInput(pinchLandmarks())
        repeat(config.poseDebounceFrames) { classifier.classify(input) }
        assertEquals(Pose.PINCH, classifier.confirmedPose)
    }

    @Test
    fun `PINCH takes priority over FIST`() {
        // In a pinch, the finger extension state may show no fingers extended
        // but the distance-based pinch check should take priority
        val pinchClassifier = StaticPoseClassifier(config)
        val input = handInput(pinchLandmarks())
        val fingerState = pinchClassifier.getFingerState(input)
        // Even if fingers appear not extended, pinch should be detected
        val rawPose = pinchClassifier.classifyRaw(input, fingerState)
        assertEquals(Pose.PINCH, rawPose)
    }

    // ========== NONE pose ==========

    @Test
    fun `NONE returned for ambiguous finger states`() {
        // Three fingers extended — doesn't match any defined pose
        val ambiguous = buildLandmarks(
            thumbExtended = false, indexExtended = true,
            middleExtended = true, ringExtended = true, pinkyExtended = false,
        )
        val input = handInput(ambiguous)
        val rawPose = classifier.classifyRaw(input, classifier.getFingerState(input))
        assertEquals(Pose.NONE, rawPose)
    }

    // ========== Hand-size scaling for pinch ==========

    @Test
    fun `pinch threshold scales with hand size`() {
        // Build pinch landmarks where thumb and index are close
        val landmarks = pinchLandmarks()
        val wrist = landmarks[0]
        val middleMcp = landmarks[9]
        val thumbTip = landmarks[4]
        val indexTip = landmarks[8]

        val pinchDist = classifier.distance2D(thumbTip, indexTip)
        val handSize = classifier.distance2D(wrist, middleMcp)
        val ratio = pinchDist / handSize

        // Verify the ratio is below the threshold
        assertTrue(ratio < config.scaledPinchDistanceRatio())
    }

    @Test
    fun `isPinch returns false when fingers are far apart`() {
        // Use open palm landmarks — thumb and index should be far apart
        val landmarks = openPalmLandmarks()
        val fingerState = classifier.getFingerState(handInput(landmarks))
        assertFalse(classifier.isPinch(landmarks, fingerState))
    }

    // ========== Reset ==========

    @Test
    fun `reset clears confirmed pose and history`() {
        val input = handInput(openPalmLandmarks())
        repeat(config.poseDebounceFrames) { classifier.classify(input) }
        assertEquals(Pose.OPEN_PALM, classifier.confirmedPose)

        classifier.reset()
        assertEquals(Pose.NONE, classifier.confirmedPose)
    }

    // ========== Helper methods ==========

    private fun handInput(landmarks: List<Landmark3D>, timestampMs: Long = System.currentTimeMillis()): HandInput {
        return HandInput(
            landmarks = landmarks,
            handedness = Handedness.RIGHT,
            timestampMs = timestampMs,
            confidence = 0.95f,
        )
    }

    private fun buildLandmarks(
        thumbExtended: Boolean,
        indexExtended: Boolean,
        middleExtended: Boolean,
        ringExtended: Boolean,
        pinkyExtended: Boolean,
        thumbUp: Boolean = true,
    ): List<Landmark3D> {
        val wrist = Landmark3D(0.5f, 0.8f, 0f)

        // Thumb
        val thumbCmc = Landmark3D(0.4f, 0.75f, 0f)
        val thumbMcp = Landmark3D(0.32f, 0.68f, 0f)
        val thumbIp: Landmark3D
        val thumbTip: Landmark3D
        if (thumbExtended) {
            thumbIp = Landmark3D(0.24f, 0.62f, 0f)
            if (thumbUp) {
                thumbTip = Landmark3D(0.15f, 0.55f, 0f) // Above MCP (lower y)
            } else {
                thumbTip = Landmark3D(0.15f, 0.75f, 0f) // Below MCP (higher y)
            }
        } else {
            thumbIp = Landmark3D(0.34f, 0.72f, 0f)
            thumbTip = Landmark3D(0.38f, 0.78f, 0f)
        }

        val indexMcp = Landmark3D(0.42f, 0.68f, 0f)
        val indexPip = Landmark3D(0.40f, 0.55f, 0f)
        val indexDip: Landmark3D
        val indexTip: Landmark3D
        if (indexExtended) {
            indexDip = Landmark3D(0.39f, 0.42f, 0f)
            indexTip = Landmark3D(0.38f, 0.30f, 0f)
        } else {
            indexDip = Landmark3D(0.41f, 0.62f, 0f)
            indexTip = Landmark3D(0.42f, 0.66f, 0f)
        }

        val middleMcp = Landmark3D(0.50f, 0.66f, 0f)
        val middlePip = Landmark3D(0.50f, 0.52f, 0f)
        val middleDip: Landmark3D
        val middleTip: Landmark3D
        if (middleExtended) {
            middleDip = Landmark3D(0.50f, 0.38f, 0f)
            middleTip = Landmark3D(0.50f, 0.25f, 0f)
        } else {
            middleDip = Landmark3D(0.50f, 0.60f, 0f)
            middleTip = Landmark3D(0.50f, 0.64f, 0f)
        }

        val ringMcp = Landmark3D(0.57f, 0.68f, 0f)
        val ringPip = Landmark3D(0.58f, 0.54f, 0f)
        val ringDip: Landmark3D
        val ringTip: Landmark3D
        if (ringExtended) {
            ringDip = Landmark3D(0.59f, 0.40f, 0f)
            ringTip = Landmark3D(0.60f, 0.28f, 0f)
        } else {
            ringDip = Landmark3D(0.57f, 0.62f, 0f)
            ringTip = Landmark3D(0.56f, 0.66f, 0f)
        }

        val pinkyMcp = Landmark3D(0.64f, 0.72f, 0f)
        val pinkyPip = Landmark3D(0.66f, 0.60f, 0f)
        val pinkyDip: Landmark3D
        val pinkyTip: Landmark3D
        if (pinkyExtended) {
            pinkyDip = Landmark3D(0.68f, 0.48f, 0f)
            pinkyTip = Landmark3D(0.70f, 0.38f, 0f)
        } else {
            pinkyDip = Landmark3D(0.65f, 0.68f, 0f)
            pinkyTip = Landmark3D(0.64f, 0.72f, 0f)
        }

        return listOf(
            wrist, thumbCmc, thumbMcp, thumbIp, thumbTip,
            indexMcp, indexPip, indexDip, indexTip,
            middleMcp, middlePip, middleDip, middleTip,
            ringMcp, ringPip, ringDip, ringTip,
            pinkyMcp, pinkyPip, pinkyDip, pinkyTip,
        )
    }

    private fun buildPinchLandmarks(): List<Landmark3D> {
        val wrist = Landmark3D(0.5f, 0.8f, 0f)
        val thumbCmc = Landmark3D(0.4f, 0.75f, 0f)
        val thumbMcp = Landmark3D(0.35f, 0.68f, 0f)
        val thumbIp = Landmark3D(0.36f, 0.60f, 0f)
        val thumbTip = Landmark3D(0.38f, 0.52f, 0f) // Close to index tip

        val indexMcp = Landmark3D(0.42f, 0.68f, 0f)
        val indexPip = Landmark3D(0.41f, 0.58f, 0f)
        val indexDip = Landmark3D(0.40f, 0.52f, 0f)
        val indexTip = Landmark3D(0.39f, 0.50f, 0f) // Close to thumb tip

        // Other fingers curled
        val middleMcp = Landmark3D(0.50f, 0.66f, 0f)
        val middlePip = Landmark3D(0.50f, 0.60f, 0f)
        val middleDip = Landmark3D(0.50f, 0.64f, 0f)
        val middleTip = Landmark3D(0.50f, 0.67f, 0f)

        val ringMcp = Landmark3D(0.57f, 0.68f, 0f)
        val ringPip = Landmark3D(0.57f, 0.62f, 0f)
        val ringDip = Landmark3D(0.56f, 0.66f, 0f)
        val ringTip = Landmark3D(0.55f, 0.69f, 0f)

        val pinkyMcp = Landmark3D(0.64f, 0.72f, 0f)
        val pinkyPip = Landmark3D(0.65f, 0.68f, 0f)
        val pinkyDip = Landmark3D(0.64f, 0.72f, 0f)
        val pinkyTip = Landmark3D(0.63f, 0.74f, 0f)

        return listOf(
            wrist, thumbCmc, thumbMcp, thumbIp, thumbTip,
            indexMcp, indexPip, indexDip, indexTip,
            middleMcp, middlePip, middleDip, middleTip,
            ringMcp, ringPip, ringDip, ringTip,
            pinkyMcp, pinkyPip, pinkyDip, pinkyTip,
        )
    }
}
