package com.aircontrol.tracking

/** Inputs used by the gaze eligibility policy. */
data class GazeUncertainty(
    val faceDetected: Boolean,
    val eyeQuality: Float,
    val binocularAgreement: Float,
    val poseConfidence: Float?,
    val headAngleDeg: Float?,
    val poseValid: Boolean,
    val modelQuality: Float?,
    val modelAbsent: Boolean,
    val eyesUsed: Int = 2,
) {
    val eyesCorrupted: Boolean get() = eyeQuality < GazeEligibilityPolicy.SEVERE_EYE_CORRUPTION
}

enum class GazeEligibility {
    NOTHING,
    UPDATE_ONLY,
    VISIBLE,
    ACTIONABLE,
    ;

    val updatesCursor: Boolean get() = this != NOTHING
    val showsCursor: Boolean get() = this == VISIBLE || this == ACTIONABLE
    val canAct: Boolean get() = this == ACTIONABLE
}

/**
 * Separates cursor visibility from action safety. A frame may be useful for
 * positioning the cursor without being trustworthy enough to click.
 */
object GazeEligibilityPolicy {
    const val ACTION_QUALITY = 0.45f
    const val VISIBLE_QUALITY = ACTION_QUALITY * 2f / 3f
    const val SEVERE_EYE_CORRUPTION = 0.15f
    const val ACTION_HEAD_LIMIT_DEG = 45f
    const val VISIBLE_HEAD_LIMIT_DEG = 60f
    const val MODEL_QUALITY_FLOOR = 0.35f
    const val BINOCULAR_DISAGREEMENT_FLOOR = 0.25f
    const val CALIBRATION_MIN_EAR = 0.16f
    const val CALIBRATION_AGREEMENT = 0.5f
    /** A pose that cannot meet this floor is display-only, never actionable. */
    const val ACTION_POSE_CONFIDENCE_FLOOR = 0.55f

    fun evaluate(u: GazeUncertainty): Decision {
        if (!u.faceDetected) return Decision(GazeEligibility.NOTHING, GazeDiagnostics.RejectionReason.FACE_LOST)
        if (u.eyesCorrupted) return Decision(GazeEligibility.NOTHING, GazeDiagnostics.RejectionReason.LOW_EYE_QUALITY)

        val canShow = u.eyeQuality >= VISIBLE_QUALITY &&
            (u.headAngleDeg == null || u.headAngleDeg <= VISIBLE_HEAD_LIMIT_DEG)
        if (!canShow) {
            return Decision(
                GazeEligibility.UPDATE_ONLY,
                if (u.eyeQuality < VISIBLE_QUALITY) GazeDiagnostics.RejectionReason.LOW_EYE_QUALITY
                else GazeDiagnostics.RejectionReason.LOW_POSE_QUALITY,
            )
        }

        val headTooTurnedToAct = u.headAngleDeg != null && u.headAngleDeg > ACTION_HEAD_LIMIT_DEG
        val modelUnreliable = !u.modelAbsent && (u.modelQuality ?: 0f) < MODEL_QUALITY_FLOOR
        val disagreement = u.binocularAgreement < BINOCULAR_DISAGREEMENT_FLOOR
        val poseUnreliable = !u.poseValid || (u.poseConfidence ?: 0f) < ACTION_POSE_CONFIDENCE_FLOOR
        // One-eye gaze is useful for cursor movement but never sufficient evidence
        // for a blind action. It must be confirmed by both eyes.
        val monocular = u.eyesUsed < 2
        val canAct = u.eyeQuality >= ACTION_QUALITY &&
            !headTooTurnedToAct &&
            !modelUnreliable &&
            !disagreement &&
            !poseUnreliable &&
            !monocular

        return if (canAct) {
            Decision(GazeEligibility.ACTIONABLE, null)
        } else {
            Decision(
                GazeEligibility.VISIBLE,
                when {
                    u.eyeQuality < ACTION_QUALITY -> GazeDiagnostics.RejectionReason.CONFIDENCE_REJECTED
                    poseUnreliable || headTooTurnedToAct -> GazeDiagnostics.RejectionReason.LOW_POSE_QUALITY
                    modelUnreliable -> GazeDiagnostics.RejectionReason.MODEL_REJECTED
                    disagreement || monocular -> GazeDiagnostics.RejectionReason.INVALID_FEATURE_VECTOR
                    else -> GazeDiagnostics.RejectionReason.INVALID_FEATURE_VECTOR
                },
            )
        }
    }

    fun calibrationEligible(u: GazeUncertainty, eyeOpenness: Float): Boolean =
        u.faceDetected && u.poseValid &&
            u.eyeQuality >= ACTION_QUALITY &&
            eyeOpenness > CALIBRATION_MIN_EAR &&
            u.binocularAgreement >= CALIBRATION_AGREEMENT &&
            u.eyesUsed >= 2

    data class Decision(
        val eligibility: GazeEligibility,
        val rejectionReason: GazeDiagnostics.RejectionReason?,
    ) {
        val updatesCursor: Boolean get() = eligibility.updatesCursor
        val showsCursor: Boolean get() = eligibility.showsCursor
        val canAct: Boolean get() = eligibility.canAct
    }
}
