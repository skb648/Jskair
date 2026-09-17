package com.aircontrol.tracking

import kotlin.math.abs
import kotlin.math.max

/** The authoritative coordinate-space contract for the gaze pipeline. */
object GazeCoordinateContract {
    const val ANALYSIS_MIRRORED_HORIZONTALLY = true
}

enum class GazePointSpace {
    CAMERA_RAW,
    SCREEN_NORMALIZED,
}

data class GazePoint(
    val x: Float,
    val y: Float,
    val ear: Float = 1f,
    val confidence: Float,
    val actionConfidence: Float = confidence,
    val eligibility: GazeEligibility = GazeEligibility.VISIBLE,
    val timestampMs: Long = 0L,
    val space: GazePointSpace = GazePointSpace.CAMERA_RAW,
) {
    val isDetected: Boolean get() = eligibility.showsCursor
    val isActionable: Boolean get() = eligibility.canAct && actionConfidence >= ACTION_CONFIDENCE_FLOOR
    val personalized: Boolean get() = space == GazePointSpace.SCREEN_NORMALIZED

    companion object {
        const val ACTION_CONFIDENCE_FLOOR = GazeEligibilityPolicy.ACTION_QUALITY
        const val MIN_GAZE_CONFIDENCE = GazeEligibilityPolicy.ACTION_QUALITY
        val EMPTY = GazePoint(0.5f, 0.5f, confidence = 0f, eligibility = GazeEligibility.NOTHING)
    }
}

data class GazeObservation(
    val rawX: Float,
    val rawY: Float,
    val ear: Float,
    val quality: Float,
    val poseValid: Boolean,
    val poseConfidence: Float = 0f,
    val binocularAgreement: Float = 0f,
    val featureVector: CalibrationFeatureVector?,
    val timestampMs: Long,
    val faceDetected: Boolean,
)

data class RawIrisGaze(
    val h: Float,
    val v: Float,
    val signedTravelX: Float,
    val signedTravelY: Float,
    val eyeOpenness: Float,
    val eyeQuality: Float,
    val binocularAgreement: Float,
    val eyesUsed: Int,
) {
    val isValid: Boolean
        get() = h.isFinite() && v.isFinite() && eyesUsed in 1..2
}

/**
 * Converts per-eye iris geometry into a shared viewer-space gaze signal.
 * Cross-eye consistency checks horizontal AND vertical disagreement.
 */
object RawIrisGazeExtractor {
    private const val HORIZONTAL_HALF_APERTURE = 0.5f
    private const val MIN_EYE_QUALITY = 0.05f
    private const val MIN_VERTICAL_HALF_APERTURE = 0.02f
    private const val SPREAD_FULL_REJECT = 1.0f

    fun from(features: BinocularEyeFeatures): RawIrisGaze? {
        val left = features.left?.takeIf(::isUsable)
        val right = features.right?.takeIf(::isUsable)
        if (left == null && right == null) return null

        var weightedX = 0f
        var weightedY = 0f
        var totalWeight = 0f
        var sumQuality = 0f
        var sumEar = 0f
        var count = 0

        fun addEye(eye: EyeFeatures) {
            val verticalHalf = max(eye.eyelidOpening * 0.5f, MIN_VERTICAL_HALF_APERTURE)
            val posX = eye.irisViewerX / HORIZONTAL_HALF_APERTURE
            val posY = eye.irisViewerY / verticalHalf
            val weight = eye.quality.coerceAtLeast(0.01f)
            weightedX += posX * weight
            weightedY += posY * weight
            totalWeight += weight
            sumQuality += eye.quality
            sumEar += eye.ear
            count++
        }

        left?.let(::addEye)
        right?.let(::addEye)

        val meanX = if (totalWeight > 0f) weightedX / totalWeight else 0f
        val meanY = if (totalWeight > 0f) weightedY / totalWeight else 0f

        val agreement = if (left != null && right != null) {
            val leftX = left.irisViewerX / HORIZONTAL_HALF_APERTURE
            val rightX = right.irisViewerX / HORIZONTAL_HALF_APERTURE
            val leftY = left.irisViewerY / max(left.eyelidOpening * 0.5f, MIN_VERTICAL_HALF_APERTURE)
            val rightY = right.irisViewerY / max(right.eyelidOpening * 0.5f, MIN_VERTICAL_HALF_APERTURE)
            val agreementX = 1f - (abs(leftX - rightX) / SPREAD_FULL_REJECT).coerceIn(0f, 1f)
            val agreementY = 1f - (abs(leftY - rightY) / SPREAD_FULL_REJECT).coerceIn(0f, 1f)
            minOf(agreementX, agreementY)
        } else {
            // 0 is an explicit "no binocular cross-check" sentinel. Monocular
            // gaze may move the cursor but can never satisfy an action/calibration
            // gate that requires bilateral evidence.
            0f
        }

        return RawIrisGaze(
            h = (0.5f + meanX * 0.5f).coerceIn(0f, 1f),
            v = (0.5f + meanY * 0.5f).coerceIn(0f, 1f),
            signedTravelX = meanX,
            signedTravelY = meanY,
            eyeOpenness = sumEar / count,
            eyeQuality = (sumQuality / count).coerceIn(0f, 1f),
            binocularAgreement = agreement.coerceIn(0f, 1f),
            eyesUsed = count,
        )
    }

    private fun isUsable(eye: EyeFeatures): Boolean =
        eye.irisViewerX.isFinite() &&
            eye.irisViewerY.isFinite() &&
            eye.quality > MIN_EYE_QUALITY
}
