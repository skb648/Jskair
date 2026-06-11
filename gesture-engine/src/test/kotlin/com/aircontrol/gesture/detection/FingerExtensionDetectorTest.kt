package com.aircontrol.gesture.detection

import com.aircontrol.gesture.config.GestureEngineConfig
import com.aircontrol.gesture.model.FingerExtensionState
import com.aircontrol.gesture.model.HandInput
import com.aircontrol.gesture.model.Handedness
import com.aircontrol.gesture.model.Landmark3D
import com.aircontrol.gesture.model.LandmarkIndex
import com.aircontrol.gesture.model.Pose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.sqrt

/**
 * Unit tests for FingerExtensionDetector.
 * Tests distance-based finger extension detection and angle-based thumb detection
 * with synthetic landmark data.
 */
class FingerExtensionDetectorTest {

    private lateinit var detector: FingerExtensionDetector
    private lateinit var config: GestureEngineConfig

    @Before
    fun setUp() {
        config = GestureEngineConfig(sensitivity = 50)
        detector = FingerExtensionDetector(config)
    }

    // ========== Helper: Build landmark list ==========

    /**
     * Builds a 21-landmark list with explicit finger extension states.
     * All landmarks start at the wrist (0,0,0) and we construct
     * realistic positions for extended vs curled fingers.
     *
     * Coordinate system: y increases downward (screen coordinates).
     * Extended fingers have tips farther from wrist than PIP joints.
     * Curled fingers have tips closer to wrist than PIP joints.
     */
    private fun buildLandmarks(
        thumbExtended: Boolean = false,
        indexExtended: Boolean = false,
        middleExtended: Boolean = false,
        ringExtended: Boolean = false,
        pinkyExtended: Boolean = false,
    ): List<Landmark3D> {
        val wrist = Landmark3D(0.5f, 0.8f, 0f)

        // Thumb: angle-based, so we position landmarks to produce
        // an extended or curled angle at the IP joint
        val thumbCmc = Landmark3D(0.4f, 0.75f, 0f)
        val thumbMcp = Landmark3D(0.32f, 0.68f, 0f)
        val thumbIp: Landmark3D
        val thumbTip: Landmark3D
        if (thumbExtended) {
            // Extended: IP and tip continue outward (left and up)
            thumbIp = Landmark3D(0.24f, 0.62f, 0f)
            thumbTip = Landmark3D(0.15f, 0.58f, 0f)
        } else {
            // Curled: IP and tip bend inward (toward palm)
            thumbIp = Landmark3D(0.34f, 0.72f, 0f)
            thumbTip = Landmark3D(0.38f, 0.78f, 0f)
        }

        // Index finger
        val indexMcp = Landmark3D(0.42f, 0.68f, 0f)
        val indexPip = Landmark3D(0.40f, 0.55f, 0f)
        val indexDip: Landmark3D
        val indexTip: Landmark3D
        if (indexExtended) {
            indexDip = Landmark3D(0.39f, 0.42f, 0f)
            indexTip = Landmark3D(0.38f, 0.30f, 0f) // far from wrist
        } else {
            indexDip = Landmark3D(0.41f, 0.62f, 0f)
            indexTip = Landmark3D(0.42f, 0.66f, 0f) // close to wrist
        }

        // Middle finger
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

        // Ring finger
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

        // Pinky finger
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
            wrist,       // 0
            thumbCmc,    // 1
            thumbMcp,    // 2
            thumbIp,     // 3
            thumbTip,    // 4
            indexMcp,    // 5
            indexPip,    // 6
            indexDip,    // 7
            indexTip,    // 8
            middleMcp,   // 9
            middlePip,   // 10
            middleDip,   // 11
            middleTip,   // 12
            ringMcp,     // 13
            ringPip,     // 14
            ringDip,     // 15
            ringTip,     // 16
            pinkyMcp,    // 17
            pinkyPip,    // 18
            pinkyDip,    // 19
            pinkyTip,    // 20
        )
    }

    private fun buildInput(
        thumbExtended: Boolean = false,
        indexExtended: Boolean = false,
        middleExtended: Boolean = false,
        ringExtended: Boolean = false,
        pinkyExtended: Boolean = false,
        timestampMs: Long = System.currentTimeMillis(),
    ): HandInput {
        return HandInput(
            landmarks = buildLandmarks(thumbExtended, indexExtended, middleExtended, ringExtended, pinkyExtended),
            handedness = Handedness.RIGHT,
            timestampMs = timestampMs,
            confidence = 0.95f,
        )
    }

    // ========== Distance-based finger extension tests ==========

    @Test
    fun `isFingerExtended returns true when tip is farther from wrist than PIP`() {
        val wrist = Landmark3D(0.5f, 0.8f, 0f)
        val pip = Landmark3D(0.5f, 0.5f, 0f) // 0.3 from wrist
        val tip = Landmark3D(0.5f, 0.2f, 0f)  // 0.6 from wrist → extended
        assertTrue(detector.isFingerExtended(tip, pip, wrist, 1.0f))
    }

    @Test
    fun `isFingerExtended returns false when tip is closer to wrist than PIP`() {
        val wrist = Landmark3D(0.5f, 0.8f, 0f)
        val pip = Landmark3D(0.5f, 0.5f, 0f) // 0.3 from wrist
        val tip = Landmark3D(0.5f, 0.7f, 0f)  // 0.1 from wrist → curled
        assertFalse(detector.isFingerExtended(tip, pip, wrist, 1.0f))
    }

    @Test
    fun `isFingerExtended with threshold greater than 1 requires larger extension`() {
        val wrist = Landmark3D(0.5f, 0.8f, 0f)
        val pip = Landmark3D(0.5f, 0.5f, 0f) // 0.3 from wrist
        val tip = Landmark3D(0.5f, 0.25f, 0f) // 0.55 from wrist
        // tip/pip ratio = 0.55/0.3 = 1.83
        assertTrue(detector.isFingerExtended(tip, pip, wrist, 1.5f))
        assertFalse(detector.isFingerExtended(tip, pip, wrist, 2.0f))
    }

    @Test
    fun `isFingerExtended returns false for zero-length PIP distance`() {
        val wrist = Landmark3D(0.5f, 0.8f, 0f)
        val pip = Landmark3D(0.5f, 0.8f, 0f) // Same as wrist → degenerate
        val tip = Landmark3D(0.5f, 0.2f, 0f)
        assertFalse(detector.isFingerExtended(tip, pip, wrist, 1.0f))
    }

    // ========== Angle-based thumb detection tests ==========

    @Test
    fun `isThumbExtended returns true for straight thumb`() {
        val input = buildInput(thumbExtended = true)
        assertTrue(detector.isThumbExtended(input.landmarks))
    }

    @Test
    fun `isThumbExtended returns false for curled thumb`() {
        val input = buildInput(thumbExtended = false)
        assertFalse(detector.isThumbExtended(input.landmarks))
    }

    @Test
    fun `angleAtVertex returns 180 for collinear points`() {
        val a = Landmark3D(0f, 0f, 0f)
        val vertex = Landmark3D(0.5f, 0f, 0f)
        val c = Landmark3D(1f, 0f, 0f)
        val angle = detector.angleAtVertex(vertex, a, c)
        assertEquals(180f, angle, 1f)
    }

    @Test
    fun `angleAtVertex returns 90 for perpendicular vectors`() {
        val a = Landmark3D(1f, 0f, 0f)
        val vertex = Landmark3D(0f, 0f, 0f)
        val c = Landmark3D(0f, 1f, 0f)
        val angle = detector.angleAtVertex(vertex, a, c)
        assertEquals(90f, angle, 1f)
    }

    @Test
    fun `angleAtVertex returns 0 for coincident points`() {
        val a = Landmark3D(1f, 1f, 1f)
        val vertex = Landmark3D(0f, 0f, 0f)
        val c = Landmark3D(0f, 0f, 0f) // Same as vertex
        val angle = detector.angleAtVertex(vertex, a, c)
        assertEquals(0f, angle, 1f)
    }

    // ========== Full detection tests ==========

    @Test
    fun `detect returns all extended for open palm`() {
        val input = buildInput(
            thumbExtended = true,
            indexExtended = true,
            middleExtended = true,
            ringExtended = true,
            pinkyExtended = true,
        )
        val state = detector.detect(input)
        assertTrue(state.thumb)
        assertTrue(state.index)
        assertTrue(state.middle)
        assertTrue(state.ring)
        assertTrue(state.pinky)
        assertEquals(5, state.totalExtendedCount)
    }

    @Test
    fun `detect returns none extended for fist`() {
        val input = buildInput(
            thumbExtended = false,
            indexExtended = false,
            middleExtended = false,
            ringExtended = false,
            pinkyExtended = false,
        )
        val state = detector.detect(input)
        assertFalse(state.thumb)
        assertFalse(state.index)
        assertFalse(state.middle)
        assertFalse(state.ring)
        assertFalse(state.pinky)
        assertEquals(0, state.totalExtendedCount)
    }

    @Test
    fun `detect returns empty state for undetected hand`() {
        val input = HandInput.EMPTY
        val state = detector.detect(input)
        assertEquals(FingerExtensionState(), state)
    }

    @Test
    fun `detect with high sensitivity lowers thresholds`() {
        val highSensitivityConfig = GestureEngineConfig(sensitivity = 100)
        val highDet = FingerExtensionDetector(highSensitivityConfig)

        // Build a marginally extended finger that would fail at default sensitivity
        val wrist = Landmark3D(0.5f, 0.8f, 0f)
        val pip = Landmark3D(0.5f, 0.5f, 0f)
        val tip = Landmark3D(0.5f, 0.49f, 0f) // Just barely past PIP

        // At default sensitivity (threshold=1.0), this should fail
        assertFalse(detector.isFingerExtended(tip, pip, wrist, 1.0f))
        // At high sensitivity (threshold ≈ 0.67), this should pass
        assertTrue(highDet.isFingerExtended(tip, pip, wrist, highSensitivityConfig.scaledFingerExtensionThreshold()))
    }

    @Test
    fun `detect with low sensitivity raises thresholds`() {
        val lowSensitivityConfig = GestureEngineConfig(sensitivity = 20)
        val lowDet = FingerExtensionDetector(lowSensitivityConfig)

        val wrist = Landmark3D(0.5f, 0.8f, 0f)
        val pip = Landmark3D(0.5f, 0.5f, 0f)
        val tip = Landmark3D(0.5f, 0.2f, 0f) // Clearly extended

        // At default sensitivity, this should pass
        assertTrue(detector.isFingerExtended(tip, pip, wrist, 1.0f))
        // At low sensitivity, threshold is higher but should still pass for clearly extended
        val threshold = lowSensitivityConfig.scaledFingerExtensionThreshold()
        assertTrue(tip.let { t ->
            val tipDist = lowDet.distance3D(t, wrist)
            val pipDist = lowDet.distance3D(pip, wrist)
            tipDist > pipDist * threshold
        })
    }

    @Test
    fun `distance3D computes correct Euclidean distance`() {
        val a = Landmark3D(0f, 0f, 0f)
        val b = Landmark3D(3f, 4f, 0f)
        assertEquals(5f, detector.distance3D(a, b), 0.001f)
    }

    @Test
    fun `distance3D computes 3D distance correctly`() {
        val a = Landmark3D(1f, 2f, 3f)
        val b = Landmark3D(4f, 6f, 3f)
        val expected = sqrt((3f * 3f + 4f * 4f).toDouble()).toFloat()
        assertEquals(expected, detector.distance3D(a, b), 0.001f)
    }
}
