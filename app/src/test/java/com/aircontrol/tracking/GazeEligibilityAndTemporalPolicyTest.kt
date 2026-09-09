package com.aircontrol.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5/6/7 — confidence separation, the temporal validity policy, and the
 * debug trace. All three are pure functions/classes now, which is what makes the
 * "why did my gaze not move the cursor" question answerable at all.
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
    ) = GazeUncertainty(
        faceDetected = faceDetected,
        eyeQuality = eyeQuality,
        binocularAgreement = agreement,
        poseConfidence = poseConfidence,
        headAngleDeg = headAngleDeg,
        poseValid = poseValid,
        modelQuality = modelQuality,
        modelAbsent = modelAbsent,
    )

    // --- Phase 5: each uncertainty source may only veto what it invalidates ---

    @Test fun faceLostStopsEverything() {
        val d = GazeEligibilityPolicy.evaluate(uncertainty(faceDetected = false, eyeQuality = 0f))
        assertEquals(GazeEligibility.NOTHING, d.eligibility)
        assertEquals(GazeDiagnostics.RejectionReason.FACE_LOST, d.rejectionReason)
        assertFalse(d.updatesCursor)
        assertFalse(d.showsCursor)
    }

    @Test fun headSlightlyTurnedStillTracksAndStillShows() {
        // "face good + eyes good + head slightly rotated -> gaze can still continue"
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
        // The single 0.45-everything gate is what made the dot flicker at normal
        // holding distances; showing must be looser than acting.
        val d = GazeEligibilityPolicy.evaluate(uncertainty(eyeQuality = 0.33f))
        assertTrue("0.33 is above the visibility floor ${GazeEligibilityPolicy.VISIBLE_QUALITY}", d.showsCursor)
        assertFalse("and below the action floor", d.canAct)
        assertEquals(GazeEligibility.VISIBLE, d.eligibility)
    }

    @Test fun validGazeSurvivesHeadPoseUnknown() {
        // Pose is optional for the cursor: losing the pose tier must not destroy
        // valid gaze (it only removes the head-angle information).
        val d = GazeEligibilityPolicy.evaluate(
            uncertainty(poseConfidence = null, headAngleDeg = null, poseValid = false),
        )
        assertEquals(GazeEligibility.ACTIONABLE, d.eligibility)
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
        val u = uncertainty(eyeQuality = 0.9f, poseValid = true)
        assertTrue(GazeEligibilityPolicy.calibrationEligible(u, eyeOpenness = 0.3f))
        assertFalse(GazeEligibilityPolicy.calibrationEligible(u.copy(poseValid = false), 0.3f))
        assertFalse(GazeEligibilityPolicy.calibrationEligible(u, eyeOpenness = 0.1f))
        assertFalse(GazeEligibilityPolicy.calibrationEligible(u.copy(eyeQuality = 0.3f), 0.3f))
        assertFalse(GazeEligibilityPolicy.calibrationEligible(u.copy(binocularAgreement = 0.1f), 0.3f))
    }

    // --- Phase 6/7: one temporal policy that never destroys the frame --------

    @Test fun smallJumpIsAccepted() {
        val policy = GazeJumpPolicy()
        val first = policy.evaluate(0.5f, 0.5f, 0f, 0f)
        assertEquals(GazeJumpPolicy.JumpOutcome.ACCEPT, first.outcome)
        val second = policy.evaluate(0.53f, 0.51f, 0.02f, 0.01f)
        assertEquals(GazeJumpPolicy.JumpOutcome.ACCEPT, second.outcome)
        assertEquals(0.53f, second.x, 1e-6f)
    }

    @Test fun realIrisMotionExplainsALargeJump() {
        // The old code dropped exactly this frame: a genuine saccade moves the
        // screen prediction a long way while most feature dims stay put.
        val policy = GazeJumpPolicy()
        policy.evaluate(0.5f, 0.5f, 0f, 0f)
        val saccade = policy.evaluate(0.9f, 0.5f, 0.9f, 0f)
        assertEquals(GazeJumpPolicy.JumpOutcome.ACCEPT, saccade.outcome)
        assertEquals(0.9f, saccade.x, 1e-6f)
    }

    @Test fun jumpWithoutEyeMotionIsHeldNotDropped() {
        val policy = GazeJumpPolicy()
        policy.evaluate(0.5f, 0.5f, 0f, 0f)
        val held = policy.evaluate(0.95f, 0.5f, 0.0f, 0.0f)
        assertEquals(GazeJumpPolicy.JumpOutcome.HOLD, held.outcome)
        assertEquals("the frame still carries a usable position", 0.5f, held.x, 1e-6f)
        assertEquals(1, policy.heldPredictionCount())
        assertTrue("a held frame must not be used to click", held.actionConfidenceFactor < 1f)
    }

    @Test fun holdingIsBoundedSoTheCursorCannotFreezeForever() {
        val policy = GazeJumpPolicy(maxHoldFrames = 3)
        policy.evaluate(0.5f, 0.5f, 0f, 0f)
        repeat(3) {
            assertEquals(GazeJumpPolicy.JumpOutcome.HOLD, policy.evaluate(0.95f, 0.5f, 0f, 0f).outcome)
        }
        val reprime = policy.evaluate(0.95f, 0.5f, 0f, 0f)
        assertEquals(GazeJumpPolicy.JumpOutcome.REPRIME, reprime.outcome)
        assertEquals(0.95f, reprime.x, 1e-6f)
    }

    @Test fun nonFinitePredictionNeverMovesTheCursor() {
        val policy = GazeJumpPolicy()
        val d = policy.evaluate(Float.NaN, 0.5f, 0f, 0f)
        assertTrue(d.x.isNaN() || d.outcome == GazeJumpPolicy.JumpOutcome.ACCEPT)
    }

    @Test fun resetClearsTheBaseline() {
        val policy = GazeJumpPolicy()
        policy.evaluate(0.5f, 0.5f, 0f, 0f)
        assertTrue(policy.hasBaseline)
        policy.reset()
        assertFalse(policy.hasBaseline)
        assertEquals(GazeJumpPolicy.JumpOutcome.ACCEPT, policy.evaluate(0.1f, 0.9f, 0f, 0f).outcome)
    }

    // --- Phase 1: the trace -------------------------------------------------

    @Test fun ringBufferIsBoundedAndOverwritesOldest() {
        val d = GazeDiagnostics(capacity = 4)
        repeat(10) { i -> d.record(sample(timestampMs = i.toLong(), reason = GazeDiagnostics.RejectionReason.CURSOR_UPDATED)) }
        val recent = d.recent()
        assertEquals(4, recent.size)
        assertEquals(6L, recent.first().timestampMs)
        assertEquals(9L, recent.last().timestampMs)
        assertEquals(10, d.reasonCount(GazeDiagnostics.RejectionReason.CURSOR_UPDATED))
    }

    @Test fun cursorCompletionOnlyPatchesTheMatchingFrame() {
        val d = GazeDiagnostics(capacity = 4)
        d.record(sample(timestampMs = 100L, reason = GazeDiagnostics.RejectionReason.CONFIDENCE_REJECTED))
        assertTrue(d.completeCursorSample(100L, 0.2f, 0.2f, 0.25f, 0.25f, GazeDiagnostics.RejectionReason.CURSOR_UPDATED))
        val s = d.recent().single()
        assertEquals(GazeDiagnostics.RejectionReason.CURSOR_UPDATED, s.rejectionReason)
        assertEquals(0.25f, s.gazeCursorX, 1e-6f)
        // A stale completion (already superseded frame) must be a no-op.
        d.record(sample(timestampMs = 150L, reason = GazeDiagnostics.RejectionReason.FACE_LOST))
        assertFalse(d.completeCursorSample(100L, 0f, 0f, 0f, 0f, GazeDiagnostics.RejectionReason.CURSOR_UPDATED))
        // And the reason counters must not double count.
        assertEquals(1, d.reasonCount(GazeDiagnostics.RejectionReason.CURSOR_UPDATED))
    }

    @Test fun snapshotIsRateLimitedAndNamesTheFailingStage() {
        val d = GazeDiagnostics(capacity = 8)
        repeat(5) { d.record(sample(it.toLong(), GazeDiagnostics.RejectionReason.LOW_POSE_QUALITY)) }
        val first = d.maybeSnapshot(nowMs = 1_000L)
        assertNotNull("first call must produce a snapshot", first)
        assertTrue(first!!.contains("LOW_POSE_QUALITY=5"))
        assertNull(d.maybeSnapshot(nowMs = 1_500L))
        assertNotNull(d.maybeSnapshot(nowMs = 4_000L))
    }

    @Test fun resetClearsCountersAndBuffer() {
        val d = GazeDiagnostics(capacity = 4)
        d.record(sample(1L, GazeDiagnostics.RejectionReason.CURSOR_UPDATED))
        d.reset()
        assertEquals(0, d.totalSamples)
        assertTrue(d.recent().isEmpty())
    }

    /** The bug, pinned as a test: averaging per-eye temporal-positive ratios cancels. */
    @Test fun oldHorizontalAveragingWasCancelledByConstruction() {
        val features = com.aircontrol.tracking.EyeFeatureExtractor.extract(neutralFrameLikeFixture())
        val l = features.left!!
        val r = features.right!!
        val oldStyle = (l.irisAlongAxis + r.irisAlongAxis) / 2f
        // Whatever the gaze direction, the old mean sits at 0.5 -> no horizontal
        // response. This assertion documents WHY the new viewer-frame step exists.
        assertEquals(0.5f, oldStyle, 1e-3f)
        val corrected = RawIrisGazeExtractor.from(features)!!
        assertTrue("and the corrected value does move", abs(corrected.h - 0.5f) > 0.05f)
    }

    private fun abs(v: Float) = if (v < 0f) -v else v

    private fun neutralFrameLikeFixture(): FaceLandmarkFrame {
        // Minimal binocular fixture with the LEFT eye looking right and the RIGHT
        // eye looking right (i.e. both irises shifted toward the viewer's right).
        val n = CanonicalEyes.MIN_LANDMARK_COUNT
        val lm = Array(n) { floatArrayOf(0.5f, 0.5f, 0f) }
        // left eye (canonical x 0.62), iris at +0.35 half-widths
        lm[263] = floatArrayOf(0.65f, 0.40f, 0f) // outer (temporal)
        lm[362] = floatArrayOf(0.59f, 0.40f, 0f) // inner (nasal)
        lm[387] = floatArrayOf(0.635f, 0.393f, 0f)
        lm[385] = floatArrayOf(0.605f, 0.393f, 0f)
        lm[380] = floatArrayOf(0.605f, 0.407f, 0f)
        lm[373] = floatArrayOf(0.635f, 0.407f, 0f)
        lm[473] = floatArrayOf(0.641f, 0.40f, 0f)
        intArrayOf(474, 475, 476, 477).forEach { lm[it] = floatArrayOf(0.645f, 0.404f, 0f) }
        // right eye (canonical x 0.38): temporal corner on the LEFT side
        lm[33] = floatArrayOf(0.35f, 0.40f, 0f) // outer (temporal)
        lm[133] = floatArrayOf(0.41f, 0.40f, 0f) // inner (nasal)
        lm[160] = floatArrayOf(0.365f, 0.393f, 0f)
        lm[158] = floatArrayOf(0.395f, 0.393f, 0f)
        lm[153] = floatArrayOf(0.395f, 0.407f, 0f)
        lm[144] = floatArrayOf(0.365f, 0.407f, 0f)
        lm[468] = floatArrayOf(0.401f, 0.40f, 0f) // iris shifted right => toward the nose
        intArrayOf(469, 470, 471, 472).forEach { lm[it] = floatArrayOf(0.405f, 0.404f, 0f) }
        return FaceLandmarkFrame(
            frameId = 7L,
            timestampNs = 7_000_000L,
            timestampMs = 7L,
            trackerWidthPx = 640,
            trackerHeightPx = 480,
            isFrontCameraMirrored = false, // fixture already canonical
            landmarks = lm.map { FaceLandmark(it[0], it[1], it[2]) },
        )
    }

    private fun sample(timestampMs: Long, reason: GazeDiagnostics.RejectionReason) = GazeDiagnostics.Sample(
        timestampMs = timestampMs,
        faceDetected = true,
        leftEyeValid = true,
        rightEyeValid = true,
        leftEyeQuality = 0.8f,
        rightEyeQuality = 0.8f,
        ear = 0.3f,
        rawIrisX = 0.5f,
        rawIrisY = 0.5f,
        headYawDeg = 0f,
        headPitchDeg = 0f,
        headPoseValid = true,
        headPoseConfidence = 0.9f,
        calibrationActive = false,
        personalizedModelActive = false,
        personalizedPredictionX = Float.NaN,
        personalizedPredictionY = Float.NaN,
        rawConfidence = 0.8f,
        finalConfidence = 0.8f,
        smoothingInputX = 0.5f,
        smoothingInputY = 0.5f,
        smoothingOutputX = 0.5f,
        smoothingOutputY = 0.5f,
        rejectionReason = reason,
        gazeCursorX = 0.5f,
        gazeCursorY = 0.5f,
    )
}
