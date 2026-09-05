package com.aircontrol.tracking

import kotlin.math.abs

object GazeCalibrationFeatureSchema {
    const val VERSION = 1

    /**
     * Fix A5: canonical signature of the feature pipeline that produced the
     * calibration samples (model → extraction → head pose → normalization →
     * this feature vector). Persisted models are only deserialized when the
     * signature matches, so a code change to any stage invalidates stale
     * models instead of silently predicting garbage.
     */
    const val TRANSFORM_SIGNATURE = "facelandmarker-478:eyefeatures-v1:headpose-v1:normalizer-v1:featvec-v1"

    val NAMES: List<String> = listOf(
        "left_iris_along","left_iris_perpendicular","left_iris_diameter_over_eye_width",
        "left_eyelid_opening","left_ear","left_eye_center_from_face_x","left_eye_center_from_face_y",
        "right_iris_along","right_iris_perpendicular","right_iris_diameter_over_eye_width",
        "right_eyelid_opening","right_ear","right_eye_center_from_face_x","right_eye_center_from_face_y",
        "binocular_iris_along_agreement","binocular_iris_perpendicular_agreement","binocular_quality_min",
        "head_yaw_deg","head_pitch_deg","head_roll_deg","face_translation_x_norm",
        "face_translation_y_norm","face_scale_norm",
    )
    const val DIMENSION = 23
    init { require(NAMES.size == DIMENSION) }
}

data class CalibrationFeatureVector(val schemaVersion: Int, val values: FloatArray) {
    init {
        require(schemaVersion == GazeCalibrationFeatureSchema.VERSION)
        require(values.size == GazeCalibrationFeatureSchema.DIMENSION)
        require(values.all { it.isFinite() })
    }
    override fun equals(other: Any?): Boolean = other is CalibrationFeatureVector && schemaVersion == other.schemaVersion && values.contentEquals(other.values)
    override fun hashCode(): Int = 31 * schemaVersion + values.contentHashCode()
    fun copyValues(): FloatArray = values.copyOf()
}

/** Builds the stable calibration vector from the exact Stage 3 normalized feature API. */
object GazeCalibrationFeatureVectorBuilder {
    fun from(normalized: NormalizedBinocularEyeFeatures): CalibrationFeatureVector? {
        if (!normalized.pose.isValid) return null
        val left = normalized.left ?: return null
        val right = normalized.right ?: return null
        val pose = normalized.pose
        if (pose.frameWidthPx <= 0 || pose.frameHeightPx <= 0 || pose.faceScalePx <= 0f || !pose.faceScalePx.isFinite()) return null
        val values = floatArrayOf(
            left.irisAlongAxis, left.irisPerpendicular, left.irisDiameterOverEyeWidth,
            left.eyelidOpening, left.ear, left.eyeCenterFromFaceCenterX, left.eyeCenterFromFaceCenterY,
            right.irisAlongAxis, right.irisPerpendicular, right.irisDiameterOverEyeWidth,
            right.eyelidOpening, right.ear, right.eyeCenterFromFaceCenterX, right.eyeCenterFromFaceCenterY,
            abs(left.irisAlongAxis - right.irisAlongAxis), abs(left.irisPerpendicular - right.irisPerpendicular),
            minOf(left.quality, right.quality), pose.yawDeg, pose.pitchDeg, pose.rollDeg,
            pose.translationXPx / pose.frameWidthPx, pose.translationYPx / pose.frameHeightPx,
            pose.faceScalePx / minOf(pose.frameWidthPx, pose.frameHeightPx),
        )
        return if (values.all { it.isFinite() }) CalibrationFeatureVector(GazeCalibrationFeatureSchema.VERSION, values) else null
    }
}
