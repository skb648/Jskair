package com.aircontrol.tracking

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/** Stable face anchors used only by the isolated eye-tracking pose foundation. */
object FacePoseLandmarks {
    const val NOSE_TIP = 1
    const val FOREHEAD = 10
    const val CHIN = 152
}

enum class HeadPoseSource {
    MATRIX,
    LANDMARK_FALLBACK,
    INVALID,
}

/**
 * Head pose and face-frame geometry expressed in tracker coordinates.
 *
 * Angles are degrees. The matrix convention is the MediaPipe-style 4x4
 * column-major layout. Pose orientation is extracted using the ZYX Euler
 * convention (roll around Z, yaw around Y, pitch around X). Translation is the
 * binocular eye-center in tracker pixels relative to the tracker center.
 */
data class HeadPoseEstimate(
    val yawDeg: Float,
    val pitchDeg: Float,
    val rollDeg: Float,
    val translationXPx: Float,
    val translationYPx: Float,
    val frameWidthPx: Int,
    val frameHeightPx: Int,
    val faceScalePx: Float,
    val confidence: Float,
    val source: HeadPoseSource,
    val isValid: Boolean,
    val reason: String? = null,
) {
    companion object {
        fun invalid(reason: String): HeadPoseEstimate = HeadPoseEstimate(
            yawDeg = Float.NaN,
            pitchDeg = Float.NaN,
            rollDeg = Float.NaN,
            translationXPx = Float.NaN,
            translationYPx = Float.NaN,
            frameWidthPx = 0,
            frameHeightPx = 0,
            faceScalePx = Float.NaN,
            confidence = 0f,
            source = HeadPoseSource.INVALID,
            isValid = false,
            reason = reason,
        )
    }
}

/** Pure, isolated head-pose estimator. It has no camera/runtime dependencies. */
object HeadPoseEstimator {
    private const val EPSILON = 1e-6f
    private const val MATRIX_ORTHOGONAL_TOLERANCE = 0.08f
    private const val MATRIX_ANISOTROPY_TOLERANCE = 0.15f
    private const val MAX_FALLBACK_YAW_DEG = 70f
    private const val MAX_FALLBACK_PITCH_DEG = 70f

    fun estimate(frame: FaceLandmarkFrame, features: BinocularEyeFeatures): HeadPoseEstimate {
        val geometry = faceGeometry(frame, features) ?: return HeadPoseEstimate.invalid("degenerate face geometry")
        val matrixPose = frame.facialTransformationMatrix?.let(::extractMatrixRotation)

        if (matrixPose != null) return geometry.toPose(frame, matrixPose, HeadPoseSource.MATRIX)

        val fallback = fallbackRotation(geometry)
            ?: return HeadPoseEstimate.invalid("matrix unavailable/invalid and landmark geometry unreliable")
        return geometry.toPose(frame, fallback, HeadPoseSource.LANDMARK_FALLBACK)
    }

    private data class FaceGeometry(
        val leftEye: Point3,
        val rightEye: Point3,
        val faceCenter: Point3,
        val nose: Point3,
        val forehead: Point3,
        val chin: Point3,
        val faceScalePx: Float,
        val translationXPx: Float,
        val translationYPx: Float,
        val confidence: Float,
    ) {
        fun toPose(frame: FaceLandmarkFrame, rotation: RotationEstimate, source: HeadPoseSource): HeadPoseEstimate =
            HeadPoseEstimate(
                yawDeg = rotation.yawDeg,
                pitchDeg = rotation.pitchDeg,
                rollDeg = rotation.rollDeg,
                translationXPx = translationXPx,
                translationYPx = translationYPx,
                frameWidthPx = frame.trackerWidthPx,
                frameHeightPx = frame.trackerHeightPx,
                faceScalePx = faceScalePx,
                confidence = (confidence * rotation.confidence).coerceIn(0f, 1f),
                source = source,
                isValid = true,
            )
    }

    private data class RotationEstimate(
        val yawDeg: Float,
        val pitchDeg: Float,
        val rollDeg: Float,
        val confidence: Float,
    )

    private data class Point3(val x: Float, val y: Float, val z: Float) {
        operator fun plus(other: Point3): Point3 = Point3(x + other.x, y + other.y, z + other.z)
        operator fun minus(other: Point3): Point3 = Point3(x - other.x, y - other.y, z - other.z)
        operator fun times(scale: Float): Point3 = Point3(x * scale, y * scale, z * scale)
    }

    private fun faceGeometry(frame: FaceLandmarkFrame, features: BinocularEyeFeatures): FaceGeometry? {
        val left = frame.eyeCenter3(CanonicalEyes.LEFT, features.left) ?: return null
        val right = frame.eyeCenter3(CanonicalEyes.RIGHT, features.right) ?: return null
        val nose = frame.landmark3(FacePoseLandmarks.NOSE_TIP) ?: return null
        val forehead = frame.landmark3(FacePoseLandmarks.FOREHEAD) ?: return null
        val chin = frame.landmark3(FacePoseLandmarks.CHIN) ?: return null
        if (!left.isFinite() || !right.isFinite() || !nose.isFinite() || !forehead.isFinite() || !chin.isFinite()) return null

        val interEye = distance2D(left, right)
        val faceVertical = distance3(forehead, chin)
        if (!interEye.isFinite() || !faceVertical.isFinite() || interEye <= EPSILON || faceVertical <= EPSILON) return null

        val faceCenter = (left + right) * 0.5f
        val translationX = faceCenter.x - frame.trackerWidthPx * 0.5f
        val translationY = faceCenter.y - frame.trackerHeightPx * 0.5f
        if (!translationX.isFinite() || !translationY.isFinite()) return null

        // Internal consistency only. Low consistency is rejected rather than
        // converted into a fabricated neutral pose.
        val noseFromEyes = distance3(nose, faceCenter)
        val noseFromVerticalMidpoint = distance3(nose, (forehead + chin) * 0.5f)
        val symmetryError = abs(noseFromEyes - noseFromVerticalMidpoint) / max(faceVertical, EPSILON)
        val confidence = (1f - symmetryError).coerceIn(0f, 1f)
        if (confidence < 0.05f) return null

        return FaceGeometry(left, right, faceCenter, nose, forehead, chin, interEye, translationX, translationY, confidence)
    }

    private fun FaceLandmarkFrame.eyeCenter3(
        definition: EyeLandmarkDefinition,
        feature: EyeFeatures?,
    ): Point3? {
        if (feature == null) return null
        val outer = landmark3(definition.outerCorner) ?: return null
        val inner = landmark3(definition.innerCorner) ?: return null
        return (outer + inner) * 0.5f
    }

    private fun FaceLandmarkFrame.landmark3(index: Int): Point3? {
        val landmark = landmark(index) ?: return null
        if (!landmark.isFinite()) return null
        // MediaPipe z is face-relative and width-normalized; place it in the
        // same scale basis as aspect-correct tracker x/y before 3D geometry use.
        // Fix (A5 wiring + tests): x is first flipped into the canonical
        // unmirrored person view, matching EyeFeatureExtractor, so mirrored and
        // unmirrored frames produce identical geometry.
        val x = if (isFrontCameraMirrored) 1f - landmark.x else landmark.x
        return Point3(
            x * trackerWidthPx,
            landmark.y * trackerHeightPx,
            landmark.z * trackerWidthPx,
        )
    }

    /** Validate and decompose a 4x4 column-major rotation block without trusting it blindly. */
    private fun extractMatrixRotation(values: FloatArray): RotationEstimate? {
        if (values.size != 16 || values.any { !it.isFinite() }) return null

        val c0 = Point3(values[0], values[1], values[2])
        val c1 = Point3(values[4], values[5], values[6])
        val c2 = Point3(values[8], values[9], values[10])
        val s0 = norm3(c0)
        val s1 = norm3(c1)
        val s2 = norm3(c2)
        if (minOf(s0, s1, s2) <= EPSILON) return null
        val scaleMean = (s0 + s1 + s2) / 3f
        val anisotropy = maxOf(abs(s0 - scaleMean), abs(s1 - scaleMean), abs(s2 - scaleMean)) / scaleMean
        if (anisotropy > MATRIX_ANISOTROPY_TOLERANCE) return null

        val r0 = c0 * (1f / s0)
        val r1 = c1 * (1f / s1)
        val r2 = c2 * (1f / s2)
        if (maxOf(abs(dot3(r0, r1)), abs(dot3(r0, r2)), abs(dot3(r1, r2))) > MATRIX_ORTHOGONAL_TOLERANCE) return null

        val determinant = dot3(r0, cross3(r1, r2))
        if (!determinant.isFinite() || determinant <= 0.90f) return null

        // ZYX Euler decomposition: R = Rz(roll) * Ry(yaw) * Rx(pitch).
        // Fix (dead-code bug): the decomposition must read the ROW elements of R.
        // With column-major storage the row-major element m_rc lives at
        // values[c*4 + r], so m20 = values[2], m21 = values[6], m22 = values[10],
        // m10 = values[1], m00 = values[0]. (The old code decomposed the columns
        // with ad-hoc signs, which returned -yaw/interchanged angles.)
        // yaw = asin(-m20), pitch = atan2(m21, m22), roll = atan2(m10, m00).
        val yaw = Math.toDegrees(asin((-values[2]).coerceIn(-1f, 1f).toDouble())).toFloat()
        val pitch = Math.toDegrees(atan2(values[6].toDouble(), values[10].toDouble())).toFloat()
        val roll = Math.toDegrees(atan2(values[1].toDouble(), values[0].toDouble())).toFloat()
        if (!yaw.isFinite() || !pitch.isFinite() || !roll.isFinite()) return null
        return RotationEstimate(yaw, pitch, roll, 0.98f)
    }

    /** Conservative landmark-only orientation fallback from eye line, face normal, and vertical axis. */
    private fun fallbackRotation(geometry: FaceGeometry): RotationEstimate? {
        val right = normalize3(geometry.rightEye - geometry.leftEye) ?: return null
        val upRaw = normalize3(geometry.forehead - geometry.chin) ?: return null
        val upOrtho = normalize3(upRaw - right * dot3(upRaw, right)) ?: return null
        val forward = normalize3(cross3(right, upOrtho)) ?: return null
        if (abs(forward.z) < 0.20f) return null

        // Coordinates here are the canonical unmirrored person view
        // (x: image right, y: down, z: toward the viewer), so the neutral axes
        // are right = (-1, 0, 0), up = (0, -1, 0), forward = (0, 0, 1).
        // Fix (dead-code bug): the old formulas measured roll from +x and yaw/
        // pitch against -z, which turned every neutral face into yaw = pitch =
        // roll = 180° and made the fallback permanently fail its ±70° gate.
        val roll = Math.toDegrees(atan2(right.y.toDouble(), (-right.x).toDouble())).toFloat()
        val yaw = Math.toDegrees(atan2(forward.x.toDouble(), forward.z.toDouble())).toFloat()
        val pitch = Math.toDegrees(atan2(forward.y.toDouble(), forward.z.toDouble())).toFloat()
        if (!roll.isFinite() || !yaw.isFinite() || !pitch.isFinite()) return null
        if (abs(yaw) > MAX_FALLBACK_YAW_DEG || abs(pitch) > MAX_FALLBACK_PITCH_DEG) return null
        return RotationEstimate(yaw, pitch, roll, 0.75f)
    }

    private fun distance2D(a: Point3, b: Point3): Float = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()

    /**
     * Fix (compile): Point3 is a plain container — it never had a finiteness
     * check, so `left.isFinite()` below did not resolve and this whole
     * (previously unwired) file failed to compile.
     */
    private fun Point3.isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()

    private fun distance3(a: Point3, b: Point3): Float = norm3(a - b)
    private fun norm3(a: Point3): Float = sqrt((a.x * a.x + a.y * a.y + a.z * a.z).toDouble()).toFloat()
    private fun dot3(a: Point3, b: Point3): Float = a.x * b.x + a.y * b.y + a.z * b.z
    private fun cross3(a: Point3, b: Point3): Point3 = Point3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)

    private fun normalize3(a: Point3): Point3? {
        val n = norm3(a)
        return if (n.isFinite() && n > EPSILON) a * (1f / n) else null
    }
}
