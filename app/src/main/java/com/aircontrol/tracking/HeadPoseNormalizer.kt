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
 * The correction is explicit: subtract the measured binocular face-center
 * translation, undo head roll, then divide by stable face scale. Eye-local
 * ratios are already scale-normalized and are preserved. Yaw/pitch remain
 * explicit in HeadPoseEstimate because a 2D eye feature cannot honestly remove
 * out-of-plane motion without a 3D eye model.
 */
object HeadPoseNormalizer {
    fun normalize(
        features: BinocularEyeFeatures,
        pose: HeadPoseEstimate,
    ): NormalizedBinocularEyeFeatures {
        if (!pose.isValid || pose.faceScalePx <= 0f || !pose.faceScalePx.isFinite() ||
            pose.frameWidthPx <= 0 || pose.frameHeightPx <= 0
        ) {
            return NormalizedBinocularEyeFeatures(null, null, pose)
        }

        return NormalizedBinocularEyeFeatures(
            left = features.left?.let { normalizeEye(it, pose) },
            right = features.right?.let { normalizeEye(it, pose) },
            pose = pose,
        )
    }

    private fun normalizeEye(eye: EyeFeatures, pose: HeadPoseEstimate): NormalizedEyeFeatures? {
        val eyePoint = rotateIntoFaceFrame(eye.eyeCenterX, eye.eyeCenterY, pose) ?: return null
        val irisPoint = rotateIntoFaceFrame(eye.irisCenterX, eye.irisCenterY, pose) ?: return null
        val scale = pose.faceScalePx

        val normalizedEyeX = eyePoint.first / scale
        val normalizedEyeY = eyePoint.second / scale
        val normalizedIrisX = irisPoint.first / scale
        val normalizedIrisY = irisPoint.second / scale
        if (listOf(normalizedEyeX, normalizedEyeY, normalizedIrisX, normalizedIrisY).any { !it.isFinite() }) return null

        return NormalizedEyeFeatures(
            eyeCenterX = normalizedEyeX,
            eyeCenterY = normalizedEyeY,
            irisCenterX = normalizedIrisX,
            irisCenterY = normalizedIrisY,
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
        val faceCenterX = pose.frameWidthPx * 0.5f + pose.translationXPx
        val faceCenterY = pose.frameHeightPx * 0.5f + pose.translationYPx
        val px = normalizedX * pose.frameWidthPx - faceCenterX
        val py = normalizedY * pose.frameHeightPx - faceCenterY
        if (!px.isFinite() || !py.isFinite()) return null

        val angle = Math.toRadians((-pose.rollDeg).toDouble())
        val c = cos(angle).toFloat()
        val s = sin(angle).toFloat()
        return Pair(
            px * c - py * s,
            px * s + py * c,
        )
    }
}
