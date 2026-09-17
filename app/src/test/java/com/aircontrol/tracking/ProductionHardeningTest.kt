package com.aircontrol.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionHardeningTest {
    private fun goodBilateralUncertainty(eyesUsed: Int = 2) = GazeUncertainty(
        faceDetected = true,
        eyeQuality = 0.95f,
        binocularAgreement = 0.95f,
        poseConfidence = 0.90f,
        headAngleDeg = 5f,
        poseValid = true,
        modelQuality = null,
        modelAbsent = true,
        eyesUsed = eyesUsed,
    )

    @Test
    fun monocularGazeCannotBecomeActionable() {
        val decision = GazeEligibilityPolicy.evaluate(goodBilateralUncertainty(eyesUsed = 1))
        assertFalse(decision.canAct)
        assertEquals(GazeEligibility.VISIBLE, decision.eligibility)
    }

    @Test
    fun zeroEyeGazeCannotBecomeActionable() {
        val decision = GazeEligibilityPolicy.evaluate(goodBilateralUncertainty(eyesUsed = 0))
        assertFalse(decision.canAct)
    }

    @Test
    fun invalidEyeCountCannotBecomeActionable() {
        val decision = GazeEligibilityPolicy.evaluate(goodBilateralUncertainty(eyesUsed = 3))
        assertFalse(decision.canAct)
    }

    @Test
    fun lowConfidencePoseCannotBecomeActionable() {
        val decision = GazeEligibilityPolicy.evaluate(
            goodBilateralUncertainty().copy(poseConfidence = 0.40f),
        )
        assertFalse(decision.canAct)
    }

    @Test
    fun invalidPoseCannotBecomeActionable() {
        val decision = GazeEligibilityPolicy.evaluate(
            goodBilateralUncertainty().copy(poseConfidence = null, headAngleDeg = null, poseValid = false),
        )
        assertFalse(decision.canAct)
    }

    @Test
    fun nanBinocularAgreementCannotBecomeActionable() {
        val decision = GazeEligibilityPolicy.evaluate(
            goodBilateralUncertainty().copy(binocularAgreement = Float.NaN),
        )
        assertFalse(decision.canAct)
        assertEquals(GazeEligibility.NOTHING, decision.eligibility)
    }

    @Test
    fun infiniteHeadAngleCannotBecomeActionable() {
        val decision = GazeEligibilityPolicy.evaluate(
            goodBilateralUncertainty().copy(headAngleDeg = Float.POSITIVE_INFINITY),
        )
        assertFalse(decision.canAct)
    }

    @Test
    fun nanPoseConfidenceCannotBecomeActionable() {
        val decision = GazeEligibilityPolicy.evaluate(
            goodBilateralUncertainty().copy(poseConfidence = Float.NaN),
        )
        assertFalse(decision.canAct)
    }

    @Test
    fun nanModelQualityCannotBecomeActionable() {
        val decision = GazeEligibilityPolicy.evaluate(
            goodBilateralUncertainty().copy(modelAbsent = false, modelQuality = Float.NaN),
        )
        assertFalse(decision.canAct)
    }

    @Test
    fun bilateralGazeWithGoodPoseCanBecomeActionable() {
        val decision = GazeEligibilityPolicy.evaluate(goodBilateralUncertainty())
        assertTrue(decision.canAct)
        assertEquals(GazeEligibility.ACTIONABLE, decision.eligibility)
    }

    @Test
    fun productionFpsQuantizationNeverRoundsCapUp() {
        assertEquals(5, AdaptiveFpsController.coerceToSupportedFps(1))
        assertEquals(5, AdaptiveFpsController.coerceToSupportedFps(5))
        assertEquals(5, AdaptiveFpsController.coerceToSupportedFps(8))
        assertEquals(10, AdaptiveFpsController.coerceToSupportedFps(14))
        assertEquals(15, AdaptiveFpsController.coerceToSupportedFps(15))
        assertEquals(15, AdaptiveFpsController.coerceToSupportedFps(20))
        assertEquals(15, AdaptiveFpsController.coerceToSupportedFps(23))
        assertEquals(24, AdaptiveFpsController.coerceToSupportedFps(24))
        assertEquals(24, AdaptiveFpsController.coerceToSupportedFps(25))
        assertEquals(30, AdaptiveFpsController.coerceToSupportedFps(30))
        assertEquals(30, AdaptiveFpsController.coerceToSupportedFps(120))
    }

    @Test
    fun calibrationRequiresExactlyTwoEyes() {
        assertFalse(
            GazeEligibilityPolicy.calibrationEligible(
                goodBilateralUncertainty(eyesUsed = 1),
                eyeOpenness = 0.8f,
            ),
        )
        assertTrue(
            GazeEligibilityPolicy.calibrationEligible(
                goodBilateralUncertainty(eyesUsed = 2),
                eyeOpenness = 0.8f,
            ),
        )
    }
}
