package com.aircontrol.tracking

/**
 * Gaze confidence, split into the uncertainty sources that actually have
 * different meanings, plus one explicit decision per purpose (Phase 5).
 *
 * The pipeline used to compress everything into one scalar compared against one
 * 0.45 floor. That had two structural consequences, both observed on device:
 *
 *  1. the same threshold was compared against two DIFFERENT quantities — the
 *     legacy path produced `0.25 + 0.5*symmetry + 0.25*separation` (typically
 *     ≈ 0.75) while the personalized path produced `min(eye quality, pose
 *     confidence * 0.75)` (starting ≈ 0.56) — so installing a *better* model made
 *     detection more likely to be rejected; and
 *  2. head rotation was multiplied into that same gate, so looking at the edge of
 *     the screen (which turns the head 25-35°) hid the cursor entirely, even with
 *     two perfectly good, fully visible eyes.
 *
 * This type keeps the sources separate and states which decision each one may
 * influence. The rule of thumb: a wrong CURSOR POSITION is self-correcting because
 * the user sees it, so tracking stays alive through mild uncertainty; a wrong TAP
 * is not, so action eligibility is the only place where strictness is justified.
 */
data class GazeUncertainty(
    /** A face was found in this frame at all. */
    val faceDetected: Boolean,
    /** [RawIrisGaze.eyeQuality]: lid/iris landmark consistency. 0 when no usable eye. */
    val eyeQuality: Float,
    /** 1 when both eyes cross-check each other, 0 when they disagree by a full half-aperture. */
    val binocularAgreement: Float,
    /** [HeadPoseEstimate.confidence] when a pose was computed; null when pose is unknown. */
    val poseConfidence: Float?,
    /** max(|yaw|, |pitch|) in degrees, or null when pose is unknown. */
    val headAngleDeg: Float?,
    /** Whether the head-pose tier produced a usable pose at all. */
    val poseValid: Boolean,
    /** Personalized model's own reliability proxy (see [modelAbsent]); null when no model. */
    val modelQuality: Float?,
    /** True when no personalized model is installed, so the raw ratio map is in use. */
    val modelAbsent: Boolean,
) {
    val eyesCorrupted: Boolean get() = eyeQuality < GazeEligibilityPolicy.SEVERE_EYE_CORRUPTION
}

/** Which of the four gaze decisions this frame is good for. */
enum class GazeEligibility {
    /** Nothing usable — face lost or both eyes corrupted. */
    NOTHING,

    /** Update the cursor position but do not show it yet (still warming up). */
    UPDATE_ONLY,

    /** Show the cursor: tracking is trustworthy enough to display. */
    VISIBLE,

    /** Show AND allow click/dwell/blink targets: trustworthy enough to act on. */
    ACTIONABLE,
    ;

    /** Anything but NOTHING: the position may be re-estimated silently (warm-up, held jump). */
    val updatesCursor: Boolean get() = this != NOTHING

    /** Display floor. */
    val showsCursor: Boolean get() = this == VISIBLE || this == ACTIONABLE

    /** Action floor: the only state in which a click, dwell or blink-click may fire. */
    val canAct: Boolean get() = this == ACTIONABLE
}

/**
 * Single decision function for gaze confidence. Deliberately free of smoothing,
 * hysteresis and any per-frame state: given these inputs, the eligibility is a
 * pure function, which makes it testable and makes "why was my gaze rejected"
 * answerable from the numbers alone (see [GazeDiagnostics.RejectionReason]).
 */
object GazeEligibilityPolicy {

    /**
     * Floor for ACTING and for CALIBRATING. 0.45 is not a new number: it is the
     * value the runtime already used (`GazePoint.MIN_GAZE_CONFIDENCE`) and the
     * value `CalibrationSample.MIN_SAMPLE_QUALITY` was aligned to. Keeping it here
     * preserves that alignment instead of drifting two floors apart again.
     */
    const val ACTION_QUALITY = 0.45f

    /**
     * Floor for merely SHOWING the cursor. 2/3 of [ACTION_QUALITY]: the dot is
     * visible feedback, so a slightly noisier frame still informs the user, and
     * the consumer's miss-hysteresis (4 frames) absorbs isolated dips. A
     * one-threshold-fits-all gate is what made the dot flicker at arm's length.
     */
    const val VISIBLE_QUALITY = ACTION_QUALITY * 2f / 3f

    /** Below this the eye landmarks are junk: hold the last position, do not hide. */
    const val SEVERE_EYE_CORRUPTION = 0.15f

    /**
     * Head rotation the raw ratio map can tolerate before accuracy degrades.
     * Chosen from the geometry, not tuned: the iris-ratio map has no out-of-plane
     * compensation, and the projected iris travel shrinks as the eye turns away,
     * roughly with cos(yaw). At 45° the horizontal scale is ~0.71 (usable,
     * mildly compressed); past ~60° it is < 0.5 and the far eye's iris starts
     * disappearing behind its own corner. So 45° downgrades ACTION (a tap at a
     * compressed position is wrong) while 60° is where even showing stops being
     * honest.
     */
    const val ACTION_HEAD_LIMIT_DEG = 45f
    const val VISIBLE_HEAD_LIMIT_DEG = 60f

    /** Same reasoning for the model path: the fit was sampled near-neutral, so extrapolating is unwise. */
    const val MODEL_QUALITY_FLOOR = 0.35f

    fun evaluate(u: GazeUncertainty): Decision {
        if (!u.faceDetected) return Decision(GazeEligibility.NOTHING, GazeDiagnostics.RejectionReason.FACE_LOST)
        if (u.eyesCorrupted) {
            return Decision(GazeEligibility.NOTHING, GazeDiagnostics.RejectionReason.LOW_EYE_QUALITY)
        }
        val canShow = u.eyeQuality >= VISIBLE_QUALITY &&
            (u.headAngleDeg == null || u.headAngleDeg!! <= VISIBLE_HEAD_LIMIT_DEG)
        if (!canShow) {
            return Decision(
                GazeEligibility.UPDATE_ONLY,
                if (u.eyeQuality < VISIBLE_QUALITY) {
                    GazeDiagnostics.RejectionReason.LOW_EYE_QUALITY
                } else {
                    GazeDiagnostics.RejectionReason.LOW_POSE_QUALITY
                },
            )
        }
        val headTooTurnedToAct = u.headAngleDeg != null && u.headAngleDeg!! > ACTION_HEAD_LIMIT_DEG
        val modelUnreliable = !u.modelAbsent && (u.modelQuality ?: 0f) < MODEL_QUALITY_FLOOR
        val disagreement = u.binocularAgreement < BINOCULAR_DISAGREEMENT_FLOOR
        val canAct = u.eyeQuality >= ACTION_QUALITY && !headTooTurnedToAct && !modelUnreliable && !disagreement
        return if (canAct) {
            Decision(GazeEligibility.ACTIONABLE, null)
        } else {
            Decision(
                GazeEligibility.VISIBLE,
                when {
                    u.eyeQuality < ACTION_QUALITY -> GazeDiagnostics.RejectionReason.CONFIDENCE_REJECTED
                    headTooTurnedToAct -> GazeDiagnostics.RejectionReason.LOW_POSE_QUALITY
                    modelUnreliable -> GazeDiagnostics.RejectionReason.MODEL_REJECTED
                    else -> GazeDiagnostics.RejectionReason.INVALID_FEATURE_VECTOR
                },
            )
        }
    }

    /**
     * Calibration must be fed only by clearly trustworthy frames — a frame that is
     * good enough to *act* on is good enough to *train* on, and the previous audit
     * round established that training below the runtime floor teaches the model
     * frames the runtime would throw away. Pose is mandatory here (unlike showing)
     * because the feature vector is defined in the face frame.
     */
    fun calibrationEligible(u: GazeUncertainty, eyeOpenness: Float): Boolean =
        u.faceDetected && u.poseValid && u.eyeQuality >= ACTION_QUALITY &&
            eyeOpenness > CALIBRATION_MIN_EAR && u.binocularAgreement >= CALIBRATION_AGREEMENT

    /**
     * A single frame's disagreement is noise, not a verdict — the value that
     * matters is how often it happens, which is what
     * [GazeDiagnostics] counts. 0.25 rejects "one eye's iris is a full aperture
     * away from the other" while admitting the small asymmetry every real face
     * has.
     */
    const val BINOCULAR_DISAGREEMENT_FLOOR = 0.25f

    /** Matches `CalibrationSample.MIN_OPEN_EAR`: eyes must be meaningfully open. */
    const val CALIBRATION_MIN_EAR = 0.16f

    /** Calibration is stricter about cross-eye agreement than acting is. */
    const val CALIBRATION_AGREEMENT = 0.5f

    /**
     * The verdict for one frame. The three display/action predicates live on
     * [GazeEligibility] itself — one home for the meaning — and are delegated
     * here so callers holding a `Decision` and callers holding only a published
     * `GazePoint` (which carries the enum, not the Decision) ask the same
     * question and get the same answer.
     */
    data class Decision(val eligibility: GazeEligibility, val rejectionReason: GazeDiagnostics.RejectionReason?) {
        val updatesCursor: Boolean get() = eligibility.updatesCursor
        val showsCursor: Boolean get() = eligibility.showsCursor
        val canAct: Boolean get() = eligibility.canAct
    }
}
