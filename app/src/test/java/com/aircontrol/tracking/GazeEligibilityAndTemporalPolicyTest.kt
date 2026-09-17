package com.aircontrol.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5/6/7 — confidence separation, temporal validity, and debug trace.
 * Safety gates are intentionally fail-closed: malformed or incomplete evidence
 * must never be promoted to an actionable gaze sample.
 */
class GazeEligibilityAndTemporalPolicyTest {

    private fun uncertainty(
        faceDetected: Boolean = true,
        eyeQuality: Float = 0.8f,
        agreement: Float = 1f,
        poseConfidence: Float? = 0.9f,
        headAngleDeg: Float? = 0f,
        poseValid: Boolean = true,
        modelQuality: Float? = null,
        modelAbsent: Boolean = true,
        eyesUsed: Int = 2,
    ) = GazeUncertainty(
        faceDetected = faceDetected,
        eyeQuality = eyeQuality,
        binocularAgreement = agreement,
        poseConfidence = poseConfidence,
        headAngleDeg = headAngleDeg,
        poseValid = poseValid,
        modelQuality = modelQuality,
        modelAbsent = modelAbsent,
        eyesUsed = eyesUsed,
    )

    @Test fun faceLostStopsEverything() {
        val d = GazeEligibilityPolicy.evaluate(uncertainty(faceDetected = false, eyeQuality = 0f))
        assertEquals(GazeEligibility.NOTHING, d.eligibility)
        assertEquals(GazeDiagnostics.RejectionReason.FACE_LOST, d.rejectionReason)
        assertFalse(d.updatesCursor)
        assertFalse(d.showsCursor)
    }

    @Test fun headSlightlyTurnedStillTracksAndStillShows() {
        val d = GazeEligibilityPolicy.evaluate(uncertainty(headAngleDeg = 30f))
        assertTrue("cursor must keep updating at 30 deg of head rotation", d.updatesCursor)
        assertTrue("cursor must stay visible at 30 deg of head rotation", d.showsCursor)
    }

    @Test fun headTurnedFarTracksButCannotAct() {
        val moderate = GazeEligibilityPolicy.evaluate(uncertainty(headAngleDeg = 30f))
        assertEquals(GazeEligibility.ACTIONABLE, moderate.eligibility)
        val extreme = GazeEligibilityPolicy.evaluate(uncertainty(headAngleDeg = 50f))
        assertEquals(GazeEligibility.VISIBLE, extreme.eligibility)
        assertFalse("a tap at a 50 deg head angle is not trustworthy", extreme.canAct)
        assertTrue("but the cursor must keep moving", extreme.updatesCursor)
    }

    @Test fun headTurnedVeryFarStopsShowingButStillHolds() {
        val d = GazeEligibilityPolicy.evaluate(uncertainty(headAngleDeg = 75f))
        assertEquals(GazeEligibility.UPDATE_ONLY, d.eligibility)
        assertTrue("position keeps tracking", d.updatesCursor)
        assertFalse("but nothing is displayed", d.showsCursor)
    }

    @Test fun severeEyeCorruptionRefusesToInventAGaze() {
        val d = GazeEligibilityPolicy.evaluate(uncertainty(eyeQuality = 0.05f))
        assertEquals(GazeEligibility.NOTHING, d.eligibility)
        assertEquals(GazeDiagnostics.RejectionReason.LOW_EYE_QUALITY, d.rejectionReason)
    }

    @Test fun borderlineQualityStillShowsTheCursor() {
        val d = GazeEligibilityPolicy.evaluate(uncertainty(eyeQuality = 0.33f))
        assertTrue("0.33 is above the visibility floor ${GazeEligibilityPolicy.VISIBLE_QUALITY}", d.showsCursor)
        assertFalse("and below the action floor", d.canAct)
        assertEquals(GazeEligibility.VISIBLE, d.eligibility)
    }

    @Test fun validGazeWithoutHeadPoseTracksButCannotAct() {
        // Head pose is optional for cursor movement, but the production safety
        // contract requires valid pose evidence for any actionable gaze.
        val d = GazeEligibilityPolicy.evaluate(
            uncertainty(poseConfidence = null, headAngleDeg = null, poseValid = false),
        )
        assertEquals(GazeEligibility.VISIBLE, d.eligibility)
        assertTrue(d.updatesCursor)
        assertFalse("missing head pose must never permit an action", d.canAct)
    }

    @Test fun unreliableModelDowngradesActionNotTracking() {
        val d = GazeEligibilityPolicy.evaluate(
            uncertainty(modelAbsent = false, modelQuality = 0.1f),
        )
        assertTrue(d.showsCursor)
        assertFalse(d.canAct)
        assertEquals(GazeDiagnostics.RejectionReason.MODEL_REJECTED, d.rejectionReason)
    }

    @Test fun goodModelDoesNotLosePointsToNoModel() {
        val without = GazeEligibilityPolicy.evaluate(uncertainty())
        val with = GazeEligibilityPolicy.evaluate(uncertainty(modelAbsent = false, modelQuality = 0.95f))
        assertEquals(without.eligibility, with.eligibility)
    }

    @Test fun binocularDisagreementBlocksActionOnly() {
        val d = GazeEligibilityPolicy.evaluate(uncertainty(agreement = 0.1f))
        assertTrue(d.showsCursor)
        assertFalse(d.canAct)
    }

    @Test fun calibrationNeedsPoseAndBothEyesAndOpenLids() {
        val u = uncertainty(eyeQuality = 0.9f, poseValid = true, eyesUsed = 2)
        assertTrue(GazeEligibilityPolicy.calibrationEligible(u, eyeOpenness = 0.3f))
        assertFalse(GazeEligibilityPolicy.calibrationEligible(u.copy(poseValid = false), 0.3f))
        assertFalse(GazeEligibilityPolicy.calibrationEligible(u.copy(eyesUsed = 1), 0.3f))
        assertFalse(GazeEligibilityPolicy.calibrationEligible(u, eyeOpenness = 0.1f))
        assertFalse(GazeEligibilityPolicy.calibrationEligible(u.copy(eyeQuality = 0.3f), 0.3f))
        assertFalse(GazeEligibilityPolicy.calibrationEligible(u.copy(binocularAgreement = 0.1f), 0.3f))
    }

    @Test fun smallJumpIsAccepted() {
        val policy = GazeJumpPolicy()
        val first = policy.evaluate(0.5f, 0.5f, 0f, 0f)
        assertEquals(GazeJumpPolicy.JumpOutcome.ACCEPT, first.outcome)
        val second = policy.evaluate(0.53f, 0.51f, 0.02f, 0.01f)
        assertEquals(GazeJumpPolicy.JumpOutcome.ACCEPT, second.outcome)
        assertEquals(0.53f, second.x, 1e-6f)
    }

    @Test fun realIrisMotionExplainsALargeJump() {
        val policy = GazeJumpPolicy()
        policy.evaluate(0.5f, 0.5f, 0f, 0f)
        val saccade = policy.evaluate(0.9f, 0.5f, 0.9f, 0f)
        assertEquals(GazeJumpPolicy.JumpOutcome.ACCEPT, saccade.outcome)
        assertEquals(0.9f, saccade.x, 1e-6f)
    }

    @Test fun jumpWithoutEyeMotionIsHeldNotDropped() {
        val policy = GazeJumpPolicy()
        policy.evaluate(0.5f, 0.5f, 0f, 0f)
        val jump = policy.evaluate(0.9f, 0.5f, 0f, 0f)
        assertEquals(GazeJumpPolicy.JumpOutcome.HOLD, jump.outcome)
        assertEquals(0.5f, jump.x, 1e-6f)
    }
}
