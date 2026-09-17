package com.aircontrol.tracking

import kotlin.math.abs
import kotlin.math.max

/** The authoritative coordinate-space contract for the gaze pipeline. */
object GazeCoordinateContract {
    /** Analysis bitmap is mirrored once; the landmark pipeline compensates once. */
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
    /** Cross-eye agreement in [0,1], using BOTH horizontal and vertical geometry. */
    val binocularAgreement: Float = 0f,
    val featureVector: CalibrationFeatureVector?,
    val timestampMs: Long,
    val faceDetected: Boolean,
)

/**
 * Converts per-eye iris geometry into a shared viewer-space gaze signal.
 *
 * The two eyes are first expressed in the same canonical coordinate system. The
 * cross-eye consistency score now checks horizontal AND vertical disagreement;
 * using only X could let a vertically corrupted eye pass the action gate.
 */
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

object RawIrisGazeExtractor {
    private const val HORIZONTAL_HALF_APERTURE = 0.5f
    private const val MIN_EYE_QUALITY = 0.05f
    private const val MIN_VERTICAL_HALF_APERTURE = 0.02f
    private const val SPREAD_FULL_REJECT = 1.0f

    fun from(features: BinocularEyeFeatures): RawIrisGaze? {
        val left = features.left?.takeIf { isUsable(it) }
        val right = features.right?.takeIf { isUsable(it) }
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
            // Both axes must agree. min() is intentionally conservative:
            // a single badly estimated axis cannot hide behind a good axis.
            minOf(agreementX, agreementY)
        } else {
            // Monocular gaze can move the cursor, but it has no cross-eye proof.
            // GazeEligibilityPolicy separately makes this non-actionable.
            0.5f
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
