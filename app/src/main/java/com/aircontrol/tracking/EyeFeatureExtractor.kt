package com.aircontrol.tracking

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/** Independent, aspect-correct feature set for one anatomical eye. */
data class EyeFeatures(
    val eyeCenterX: Float,
    val eyeCenterY: Float,
    val irisCenterX: Float,
    val irisCenterY: Float,
    /** Iris center projected along the eye axis, normalized by eye width. */
    val irisAlongAxis: Float,
    /** Iris center offset perpendicular to the eye axis, normalized by eye width. */
    val irisPerpendicular: Float,
    /** Iris diameter estimate divided by eye width. */
    val irisDiameterOverEyeWidth: Float,
    /** Euclidean eye-corner distance in aspect-correct tracker pixels. */
    val eyeWidthPx: Float,
    /** Vertical lid opening measured perpendicular to the eye axis / eye width. */
    val eyelidOpening: Float,
    /** EAR-like normalized openness metric using the canonical six points. */
    val ear: Float,
    val eyeCenterFromFaceCenterX: Float,
    val eyeCenterFromFaceCenterY: Float,
    val quality: Float,
)

data class FaceCentricEyeGeometry(
    val faceCenterX: Float,
    val faceCenterY: Float,
    val interEyeDistancePx: Float,
)

data class BinocularEyeFeatures(
    val left: EyeFeatures?,
    val right: EyeFeatures?,
    val faceGeometry: FaceCentricEyeGeometry?,
) {
    val isValid: Boolean get() = left != null || right != null
}

/**
 * Pure extractor. It performs no gaze mapping and never averages left/right gaze
 * coordinates. Each anatomical eye remains independently addressable.
 */
object EyeFeatureExtractor {
    fun extract(frame: FaceLandmarkFrame): BinocularEyeFeatures {
        if (frame.trackerWidthPx <= 0 || frame.trackerHeightPx <= 0) {
            return BinocularEyeFeatures(null, null, null)
        }

        val leftGeometry = eyeGeometry(frame, CanonicalEyes.LEFT)
        val rightGeometry = eyeGeometry(frame, CanonicalEyes.RIGHT)

        val validCenters = listOfNotNull(leftGeometry?.eyeCenter, rightGeometry?.eyeCenter)
        if (validCenters.isEmpty()) return BinocularEyeFeatures(null, null, null)

        val faceCenterX = validCenters.map { it.x }.average().toFloat()
        val faceCenterY = validCenters.map { it.y }.average().toFloat()
        val faceCenter = PointPx(faceCenterX, faceCenterY)

        val interEyeDistancePx = if (leftGeometry != null && rightGeometry != null) {
            distance(leftGeometry.eyeCenter, rightGeometry.eyeCenter)
        } else {
            0f
        }

        val faceGeometry = if (interEyeDistancePx > EPSILON && interEyeDistancePx.isFinite()) {
            FaceCentricEyeGeometry(
                faceCenterX = faceCenterX / frame.trackerWidthPx,
                faceCenterY = faceCenterY / frame.trackerHeightPx,
                interEyeDistancePx = interEyeDistancePx,
            )
        } else {
            null
        }

        fun finalize(geometry: EyeGeometry?): EyeFeatures? {
            if (geometry == null) return null
            val scale = if (interEyeDistancePx > EPSILON) interEyeDistancePx else geometry.eyeWidthPx
            if (!scale.isFinite() || scale <= EPSILON) return null
            val eye = geometry.eyeCenter
            val axis = geometry.axis
            val irisDelta = geometry.irisCenter - geometry.innerCorner
            val along = dot(irisDelta, axis) / geometry.eyeWidthPx
            val perpendicular = cross(irisDelta, axis) / geometry.eyeWidthPx
            val irisDiameter = geometry.irisDiameterPx
            val eyeWidth = geometry.eyeWidthPx
            if (!along.isFinite() || !perpendicular.isFinite() ||
                !irisDiameter.isFinite() || !eyeWidth.isFinite() || eyeWidth <= EPSILON
            ) return null

            val relativeX = (eye.x - faceCenter.x) / scale
            val relativeY = (eye.y - faceCenter.y) / scale
            val quality = geometry.quality
            if (!relativeX.isFinite() || !relativeY.isFinite() || !quality.isFinite()) return null

            return EyeFeatures(
                eyeCenterX = eye.x / frame.trackerWidthPx,
                eyeCenterY = eye.y / frame.trackerHeightPx,
                irisCenterX = geometry.irisCenter.x / frame.trackerWidthPx,
                irisCenterY = geometry.irisCenter.y / frame.trackerHeightPx,
                irisAlongAxis = along,
                irisPerpendicular = perpendicular,
                irisDiameterOverEyeWidth = irisDiameter / eyeWidth,
                eyeWidthPx = eyeWidth,
                eyelidOpening = geometry.lidOpening,
                ear = geometry.ear,
                eyeCenterFromFaceCenterX = relativeX,
                eyeCenterFromFaceCenterY = relativeY,
                quality = quality,
            )
        }

        return BinocularEyeFeatures(
            left = finalize(leftGeometry),
            right = finalize(rightGeometry),
            faceGeometry = faceGeometry,
        )
    }

    private data class PointPx(val x: Float, val y: Float) {
        operator fun minus(other: PointPx): PointPx = PointPx(x - other.x, y - other.y)
    }

    private data class EyeGeometry(
        val eyeCenter: PointPx,
        val irisCenter: PointPx,
        val irisDiameterPx: Float,
        val eyeWidthPx: Float,
        val lidOpening: Float,
        val ear: Float,
        val axis: PointPx,
        val innerCorner: PointPx,
        val quality: Float,
    )

    private fun eyeGeometry(frame: FaceLandmarkFrame, definition: EyeLandmarkDefinition): EyeGeometry? {
        val points = definition.requiredIndices.associateWith { frame.landmark(it) }
        if (points.values.any { it == null || !it.isFinite() }) return null

        fun p(index: Int): PointPx {
            val landmark = points.getValue(index)!!
            return PointPx(
                landmark.x * frame.trackerWidthPx,
                landmark.y * frame.trackerHeightPx,
            )
        }

        val outer = p(definition.outerCorner)
        val inner = p(definition.innerCorner)
        val upperOuter = p(definition.upperOuter)
        val upperInner = p(definition.upperInner)
        val lowerInner = p(definition.lowerInner)
        val lowerOuter = p(definition.lowerOuter)
        val iris = p(definition.irisCenter)

        val cornerDelta = outer - inner
        val eyeWidth = hypot(cornerDelta.x.toDouble(), cornerDelta.y.toDouble()).toFloat()
        if (!eyeWidth.isFinite() || eyeWidth <= EPSILON) return null

        val invWidth = 1f / eyeWidth
        val axis = PointPx(cornerDelta.x * invWidth, cornerDelta.y * invWidth)
        val perp = PointPx(-axis.y, axis.x)
        val eyeCenter = midpoint(inner, outer)

        val ringPoints = definition.irisRing.map(::p)
        val irisRadii = ringPoints.map { distance(it, iris) }
        if (irisRadii.any { !it.isFinite() } || irisRadii.isEmpty()) return null
        val irisDiameter = irisRadii.average().toFloat() * 2f

        val upperMid = midpoint(upperOuter, upperInner)
        val lowerMid = midpoint(lowerInner, lowerOuter)
        val lidOpening = abs(dot(lowerMid - upperMid, perp)) * invWidth

        val vertical1 = distance(upperOuter, lowerOuter)
        val vertical2 = distance(upperInner, lowerInner)
        val ear = (vertical1 + vertical2) / (2f * eyeWidth)

        if (!lidOpening.isFinite() || !ear.isFinite() || !irisDiameter.isFinite()) return null

        val geometryConsistency = 1f - abs(vertical1 - vertical2) / max(vertical1 + vertical2, EPSILON)
        val irisConsistency = 1f - ((irisRadii.maxOrNull() ?: 0f) - (irisRadii.minOrNull() ?: 0f)) /
            max(irisRadii.average().toFloat(), EPSILON)
        val quality = ((geometryConsistency + irisConsistency) * 0.5f).coerceIn(0f, 1f)

        return EyeGeometry(
            eyeCenter = eyeCenter,
            irisCenter = iris,
            irisDiameterPx = irisDiameter,
            eyeWidthPx = eyeWidth,
            lidOpening = lidOpening,
            ear = ear,
            axis = axis,
            innerCorner = inner,
            quality = quality,
        )
    }

    private fun midpoint(a: PointPx, b: PointPx): PointPx =
        PointPx((a.x + b.x) * 0.5f, (a.y + b.y) * 0.5f)

    private fun distance(a: PointPx, b: PointPx): Float =
        hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()

    private fun dot(a: PointPx, b: PointPx): Float = a.x * b.x + a.y * b.y

    private fun cross(a: PointPx, b: PointPx): Float = a.x * b.y - a.y * b.x

    private const val EPSILON = 1e-6f
}
