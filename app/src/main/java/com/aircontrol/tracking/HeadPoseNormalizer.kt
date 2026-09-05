package com.aircontrol.tracking

import kotlin.math.cos
import kotlin.math.sin

/** One anatomical eye after face-frame translation, scale and roll normalization. */
data class NormalizedEyeFeatures(
    val eyeCenterX: Float,
    val eyeCenterY: Float,
    val irisCenterX: Float,
    val irisCenterY: Float,
    val irisAlongAxis: Float,
    val irisPerpendicular: Float,
    val irisDiameterOverEyeWidth: Float,
    val eyelidOpening: Float,
    val ear: Float,
    val eyeCenterFromFaceCenterX: Float,
    val eyeCenterFromFaceCenterY: Float,
    val quality: Float,
) {
    val isValid: Boolean
        get() = listOf(
            eyeCenterX, eyeCenterY, irisCenterX, irisCenterY,
            irisAlongAxis, irisPerpendicular, irisDiameterOverEyeWidth,
            eyelidOpening, ear, eyeCenterFromFaceCenterX, eyeCenterFromFaceCenterY, quality,
        ).all { it.isFinite() }
}

data class NormalizedBinocularEyeFeatures(
    val left: NormalizedEyeFeatures?,
    val right: NormalizedEyeFeatures?,
    val pose: HeadPoseEstimate,
) {
    val isValid: Boolean get() = pose.isValid && (left != null || right != null)
}

/**
 * Converts eye features into a face-fixed 2D frame.
 *
 * The correction is deliberately explicit: subtract the measured binocular
 * face translation, undo head roll, then divide by face/eye scale. Existing
 * local eye-axis ratios are already scale-invariant and are preserved rather
 * than re-derived from a potentially noisier global frame. Yaw/pitch remain in
 * the pose object because a 2D eye feature cannot honestly remove out-of-plane
 * motion without depth or a model of the 3D iris.
 */
object HeadPoseNormalizer {
    fun normalize(
        features: BinocularEyeFeatures,
        pose: HeadPoseEstimate,
    ): NormalizedBinocularEyeFeatures {
        if (!pose.isValid || pose.faceScalePx <= 0f || !pose.faceScalePx.isFinite()) {
            return NormalizedBinocularEyeFeatures(null, null, pose)
        }

        return NormalizedBinocularEyeFeatures(
            left = features.left?.let { normalizeEye(it, pose) },
            right = features.right?.let { normalizeEye(it, pose) },
            pose = pose,
        )
    }

    private fun normalizeEye(eye: EyeFeatures, pose: HeadPoseEstimate): NormalizedEyeFeatures? {
        val eyePoint = rotateIntoFaceFrame(
            eye.eyeCenterX,
            eye.eyeCenterY,
            pose,
        ) ?: return null
        val irisPoint = rotateIntoFaceFrame(
            eye.irisCenterX,
            eye.irisCenterY,
            pose,
        ) ?: return null

        // The face scale is measured in aspect-correct tracker pixels. Convert
        // normalized tracker coordinates back to the same pixel frame first.
        val scale = pose.faceScalePx
        val translationXNorm = pose.translationXPx / poseFrameWidth
        val translationYNorm = pose.translationYPx / poseFrameHeight
        val normalizedEyeX = eyePoint.first / scale
        val normalizedEyeY = eyePoint.second / scale
        val normalizedIrisX = irisPoint.first / scale
        val normalizedIrisY = irisPoint.second / scale

        val correctedRelativeX = normalizedEyeX
        val correctedRelativeY = normalizedEyeY
        val correctedIrisX = normalizedIrisX
        val correctedIrisY = normalizedIrisY
        if (listOf(
                correctedRelativeX, correctedRelativeY, correctedIrisX, correctedIrisY,
                translationXNorm, translationYNorm,
            ).any { !it.isFinite() }
        ) return null

        return NormalizedEyeFeatures(
            eyeCenterX = correctedRelativeX,
            eyeCenterY = correctedRelativeY,
            irisCenterX = correctedIrisX,
            irisCenterY = correctedIrisY,
            irisAlongAxis = eye.irisAlongAxis,
            irisPerpendicular = eye.irisPerpendicular,
            irisDiameterOverEyeWidth = eye.irisDiameterOverEyeWidth,
            eyelidOpening = eye.eyelidOpening,
            ear = eye.ear,
            eyeCenterFromFaceCenterX = eye.eyeCenterFromFaceCenterX,
            eyeCenterFromFaceCenterY = eye.eyeCenterFromFaceCenterY,
            quality = eye.quality,
        )
    }

    private fun rotateIntoFaceFrame(
        normalizedX: Float,
        normalizedY: Float,
        pose: HeadPoseEstimate,
    ): Pair<Float, Float>? {
        if (!normalizedX.isFinite() || !normalizedY.isFinite()) return null
        val px = normalizedX * poseFrameWidth - pose.translationXPx
        val py = normalizedY * poseFrameHeight - pose.translationYPx
        if (!px.isFinite() || !py.isFinite()) return null
        val angle = Math.toRadians((-pose.rollDeg).toDouble())
        val c = cos(angle).toFloat()
        val s = sin(angle).toFloat()
        return Pair(
            px * c - py * s,
            px * s + py * c,
        )
    }

    /** These constants only define the coordinate conversion convention for normalized feature output. */
    private const val poseFrameWidth = 1f
    private const val poseFrameHeight = 1f
}
