package com.aircontrol.tracking

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Independent, aspect-correct feature set for one anatomical eye.
 *
 * ## Coordinate contract (Phase 3 — see also [GazeCoordinateContract])
 *
 * | field | space | normalization | axis convention | range |
 * |---|---|---|---|---|
 * | [eyeCenterX], [eyeCenterY], [irisCenterX], [irisCenterY] | canonical tracker image (front-camera mirror ALREADY undone by [EyeFeatureExtractor]) | divided by tracker width / height respectively | +x = viewer right, +y = down | ~[0,1] |
 * | [irisAlongAxis], [irisPerpendicular] | EYE-local frame, per-eye | divided by that eye's own width | **+axis = this eye's temporal corner** (nasal = 0) | along ~[0,1] |
 * | [irisViewerX], [irisViewerY] | canonical VIEWER frame | divided by that eye's own width | +x = viewer right, +y = down | ~[-1,1] |
 * | [eyeCenterFromFaceCenterX/Y] | face-centred | divided by inter-eye distance (or eye width for a monocular frame) | +x = viewer right, +y = down | ~[-0.5,0.5] |
 *
 * The distinction between [irisAlongAxis] and [irisViewerX] is the whole reason eye
 * tracking used to be broken: `irisAlongAxis` counts *away from each eye's own nose*,
 * so the left and right eyes move in OPPOSITE directions for the same gaze shift, and
 * averaging it cancels horizontal gaze. [axisSign] is derived from the landmark
 * geometry (never hardcoded) and converts one into the other. Nothing downstream may
 * treat [irisAlongAxis] as a screen direction.
 */
data class EyeFeatures(
    val eyeCenterX: Float,
    val eyeCenterY: Float,
    val irisCenterX: Float,
    val irisCenterY: Float,
    /** Iris center projected along the eye axis, normalized by eye width. Per-eye convention: 0 = nasal corner, 1 = temporal corner. */
    val irisAlongAxis: Float,
    /** Iris center offset perpendicular to the eye axis, normalized by eye width. Per-eye convention, see [irisViewerY]. */
    val irisPerpendicular: Float,
    /**
     * +1 when this eye's temporal corner lies to the viewer's right, -1 otherwise.
     * Derived from the (un-mirrored) landmark geometry, so it is correct for any
     * camera/eye/left-right combination and for head roll.
     */
    val axisSign: Float,
    /** Horizontal iris offset from eye center in the VIEWER frame, / eye width. + = looking to the viewer's right. */
    val irisViewerX: Float,
    /** Vertical iris offset from eye center in the VIEWER frame, / eye width. + = looking down. */
    val irisViewerY: Float,
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

            // Phase 2 (single intentional mirror/sign interpretation): the
            // per-eye `along` axis points from this eye's NASAL corner to its
            // TEMPORAL corner, so for the two eyes it points in opposite viewer
            // directions. Deriving the sign from the geometry itself (instead of
            // hardcoding +1/-1 per eye) keeps it correct under any camera
            // mirroring, any left/right naming convention and any head roll.
            val axisSign = if (axis.x >= 0f) 1f else -1f
            // Offsets from the eye center in units of this eye's own width.
            // `eyeCenter` is the midpoint of the two corners and therefore lies
            // ON the axis, so (along - 0.5) is exactly the along-axis offset from
            // the center and `perpendicular` is already measured from it.
            val viewerX = axisSign * (along - 0.5f)
            val viewerY = -axisSign * perpendicular
            if (!viewerX.isFinite() || !viewerY.isFinite() || !axisSign.isFinite()) return null

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
                axisSign = axisSign,
                irisViewerX = viewerX,
                irisViewerY = viewerY,
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
        // P0-5: read the frame's primitive buffer directly. This used to build a
        // `Map<Int, FaceLandmark?>` of 11 boxed lookups per eye and then re-read it per call to
        // `p(index)` — allocation whose only purpose was to move three floats into an object.
        // Validity is checked up front exactly as before (present AND finite), so a corrupt frame
        // still yields a null geometry rather than a partial one.
        val required = definition.requiredIndices
        var i = 0
        while (i < required.size) {
            val index = required[i]
            if (!frame.isLandmarkIndexValid(index)) return null
            if (!frame.xOf(index).isFinite() || !frame.yOf(index).isFinite() || !frame.zOf(index).isFinite()) {
                return null
            }
            i++
        }

        fun p(index: Int): PointPx {
            // Fix (A5 wiring + tests): normalized coordinates are converted into a
            // canonical unmirrored "person view" — for a mirrored front-camera
            // frame the x axis is flipped back so anatomical identity and all
            // downstream features are frame-convention independent.
            val x = if (frame.isFrontCameraMirrored) 1f - frame.xOf(index) else frame.xOf(index)
            return PointPx(
                x * frame.trackerWidthPx,
                frame.yOf(index) * frame.trackerHeightPx,
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

        // The iris ring is four points, always. Walking it in place (and accumulating
        // max/min/sum) removes two intermediate Lists and their boxed floats per eye per frame,
        // while keeping the arithmetic bit-identical: average() over a List<Float> is a Double sum
        // divided by the size, which is exactly what is computed here.
        val ring = definition.irisRing
        if (ring.isEmpty()) return null
        var radiusSum = 0.0
        var radiusMin = Float.POSITIVE_INFINITY
        var radiusMax = Float.NEGATIVE_INFINITY
        for (ringIndex in ring.indices) {
            val radius = distance(p(ring[ringIndex]), iris)
            if (!radius.isFinite()) return null
            radiusSum += radius.toDouble()
            if (radius < radiusMin) radiusMin = radius
            if (radius > radiusMax) radiusMax = radius
        }
        val irisRadiusAverage = (radiusSum / ring.size).toFloat()
        val irisDiameter = irisRadiusAverage * 2f

        val upperMid = midpoint(upperOuter, upperInner)
        val lowerMid = midpoint(lowerInner, lowerOuter)
        val lidOpening = abs(dot(lowerMid - upperMid, perp)) * invWidth

        val vertical1 = distance(upperOuter, lowerOuter)
        val vertical2 = distance(upperInner, lowerInner)
        val ear = (vertical1 + vertical2) / (2f * eyeWidth)

        if (!lidOpening.isFinite() || !ear.isFinite() || !irisDiameter.isFinite()) return null

        val geometryConsistency = 1f - abs(vertical1 - vertical2) / max(vertical1 + vertical2, EPSILON)
        val irisConsistency = 1f - (radiusMax - radiusMin) / max(irisRadiusAverage, EPSILON)
        // Fix (audit #31): geometry consistency alone calls a far-away, tiny face
        // "high quality" even though the iris is a handful of pixels. Derate
        // quality when the eye is small in frame (< 6% of the tracker width):
        // full quality at >= 6%, down to 0.6x at ~2.4%.
        val resolutionFactor = (eyeWidth / (frame.trackerWidthPx * 0.06f))
            .coerceIn(0.6f, 1f)
        val quality = ((geometryConsistency + irisConsistency) * 0.5f * resolutionFactor).coerceIn(0f, 1f)

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
