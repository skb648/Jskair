package com.aircontrol.tracking

import kotlin.math.abs

object GazeCalibrationFeatureSchema {
    const val VERSION = 1
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

object GazeCalibrationFeatureVectorBuilder {
    fun from(features: BinocularEyeFeatures, pose: HeadPoseEstimate): CalibrationFeatureVector? {
        if (!pose.isValid || pose.frameWidthPx <= 0 || pose.frameHeightPx <= 0) return null
        val left = features.left ?: return null
        val right = features.right ?: return null
        val faceGeometry = features.faceGeometry ?: return null
        if (!faceGeometry.interEyeDistancePx.isFinite() || faceGeometry.interEyeDistancePx <= 0f) return null
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
