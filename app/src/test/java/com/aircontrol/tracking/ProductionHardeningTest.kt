package com.aircontrol.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionHardeningTest {
    @Test
    fun monocularGazeCannotBecomeActionable() {
        val decision = GazeEligibilityPolicy.evaluate(
            GazeUncertainty(
                faceDetected = true,
                eyeQuality = 0.95f,
                binocularAgreement = 0f,
                poseConfidence = 0.9f,
                headAngleDeg = 5f,
                poseValid = true,
                modelQuality = null,
                modelAbsent = true,
            ),
        )
        assertFalse(decision.canAct)
        assertEquals(GazeEligibility.VISIBLE, decision.eligibility)
    }

    @Test
    fun lowConfidencePoseCannotBecomeActionable() {
        val decision = GazeEligibilityPolicy.evaluate(
            GazeUncertainty(
                faceDetected = true,
                eyeQuality = 0.95f,
                binocularAgreement = 0.95f,
                poseConfidence = 0.40f,
                headAngleDeg = 5f,
                poseValid = true,
                modelQuality = null,
                modelAbsent = true,
            ),
        )
        assertFalse(decision.canAct)
    }

    @Test
    fun invalidPoseCannotBecomeActionable() {
        val decision = GazeEligibilityPolicy.evaluate(
            GazeUncertainty(
                faceDetected = true,
                eyeQuality = 0.95f,
                binocularAgreement = 0.95f,
                poseConfidence = null,
                headAngleDeg = null,
                poseValid = false,
                modelQuality = null,
                modelAbsent = true,
            ),
        )
        assertFalse(decision.canAct)
    }

    @Test
    fun bilateralGazeWithGoodPoseCanBecomeActionable() {
        val decision = GazeEligibilityPolicy.evaluate(
            GazeUncertainty(
                faceDetected = true,
                eyeQuality = 0.90f,
                binocularAgreement = 0.90f,
                poseConfidence = 0.80f,
                headAngleDeg = 10f,
                poseValid = true,
                modelQuality = null,
                modelAbsent = true,
            ),
        )
        assertTrue(decision.canAct)
        assertEquals(GazeEligibility.ACTIONABLE, decision.eligibility)
    }

    @Test
    fun supportedFpsNeverRoundsThermalCapUp() {
        assertEquals(15, coerceToSupported(20))
        assertEquals(24, coerceToSupported(24))
        assertEquals(30, coerceToSupported(30))
        assertEquals(5, coerceToSupported(8))
    }

    private fun coerceToSupported(requested: Int): Int {
        val supported = intArrayOf(5, 10, 15, 24, 30)
        if (requested <= supported.first()) return supported.first()
        return supported.lastOrNull { it <= requested } ?: supported.first()
    }
}
