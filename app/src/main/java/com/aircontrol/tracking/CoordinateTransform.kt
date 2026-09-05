package com.aircontrol.tracking

import java.security.MessageDigest
import java.util.Locale

/** Explicit source -> crop -> clockwise rotation -> horizontal mirror transform. */
data class TransformPoint(val x: Float, val y: Float)

class CoordinateTransform(
    val sourceWidthPx: Int,
    val sourceHeightPx: Int,
    val crop: CropRect,
    val rotation: Rotation,
    val mirroredHorizontally: Boolean,
    val transformVersion: Int = VERSION,
) {
    val transformedWidthPx: Int = rotation.outputWidth(crop.width, crop.height)
    val transformedHeightPx: Int = rotation.outputHeight(crop.width, crop.height)
    val coordinateConvention: CoordinateConvention = CoordinateConvention.X_RIGHT_Y_DOWN
    val signature: String = buildSignature()

    init {
        require(sourceWidthPx > 0 && sourceHeightPx > 0) { "source dimensions must be positive" }
        require(crop.isWithin(sourceWidthPx, sourceHeightPx)) { "crop must be within source dimensions" }
        require(transformVersion == VERSION) { "unsupported transform contract version" }
    }

    fun toTracker(point: TransformPoint): TransformPoint {
        requireSourcePoint(point)
        val cx = point.x - crop.leftPx
        val cy = point.y - crop.topPx
        val rotated = rotateClockwise(cx, cy)
        val x = if (mirroredHorizontally) transformedWidthPx - rotated.x else rotated.x
        val result = TransformPoint(x, rotated.y)
        requireTransformedPoint(result)
        return result
    }

    fun fromTracker(point: TransformPoint): TransformPoint {
        requireTransformedPoint(point)
        val unmirroredX = if (mirroredHorizontally) transformedWidthPx - point.x else point.x
        val cropped = inverseRotateClockwise(unmirroredX, point.y)
        val result = TransformPoint(cropped.x + crop.leftPx, cropped.y + crop.topPx)
        requireSourcePoint(result)
        return result
    }

    fun sourceToNormalized(point: TransformPoint): TransformPoint {
        val tracker = toTracker(point)
        return TransformPoint(tracker.x / transformedWidthPx, tracker.y / transformedHeightPx)
    }

    fun normalizedToSource(point: TransformPoint): TransformPoint {
        require(point.x.isFinite() && point.y.isFinite()) { "normalized point must be finite" }
        require(point.x in 0f..1f && point.y in 0f..1f) { "normalized point must be within [0,1]" }
        return fromTracker(TransformPoint(point.x * transformedWidthPx, point.y * transformedHeightPx))
    }

    fun toAnalysisFrame(frameId: Long, timestampNs: Long, timestampMs: Long): AnalysisFrame = AnalysisFrame(
        frameId = frameId,
        timestampNs = timestampNs,
        timestampMs = timestampMs,
        sourceWidthPx = sourceWidthPx,
        sourceHeightPx = sourceHeightPx,
        crop = crop,
        rotation = rotation,
        mirroredHorizontally = mirroredHorizontally,
        transformedWidthPx = transformedWidthPx,
        transformedHeightPx = transformedHeightPx,
        coordinateConvention = coordinateConvention,
        transformSignature = signature,
    )

    private fun rotateClockwise(x: Float, y: Float): TransformPoint = when (rotation) {
        Rotation.DEG_0 -> TransformPoint(x, y)
        Rotation.DEG_90 -> TransformPoint(crop.height - y, x)
        Rotation.DEG_180 -> TransformPoint(crop.width - x, crop.height - y)
        Rotation.DEG_270 -> TransformPoint(y, crop.width - x)
    }

    private fun inverseRotateClockwise(x: Float, y: Float): TransformPoint = when (rotation) {
        Rotation.DEG_0 -> TransformPoint(x, y)
        Rotation.DEG_90 -> TransformPoint(y, crop.height - x)
        Rotation.DEG_180 -> TransformPoint(crop.width - x, crop.height - y)
        Rotation.DEG_270 -> TransformPoint(crop.width - y, x)
    }

    private fun requireSourcePoint(point: TransformPoint) {
        require(point.x.isFinite() && point.y.isFinite()) { "source point must be finite" }
        require(point.x in 0f..sourceWidthPx.toFloat() && point.y in 0f..sourceHeightPx.toFloat()) {
            "source point outside source bounds"
        }
        require(point.x + TOLERANCE >= crop.leftPx && point.x <= crop.leftPx + crop.width + TOLERANCE) {
            "source point outside crop x bounds"
        }
        require(point.y + TOLERANCE >= crop.topPx && point.y <= crop.topPx + crop.height + TOLERANCE) {
            "source point outside crop y bounds"
        }
    }

    private fun requireTransformedPoint(point: TransformPoint) {
        require(point.x.isFinite() && point.y.isFinite()) { "tracker point must be finite" }
        require(point.x in 0f..transformedWidthPx.toFloat() && point.y in 0f..transformedHeightPx.toFloat()) {
            "tracker point outside transformed bounds"
        }
    }

    private fun buildSignature(): String {
        val canonical = listOf(
            "version=$transformVersion",
            "convention=${coordinateConvention.id}",
            "source=${sourceWidthPx}x$sourceHeightPx",
            "crop=${crop.leftPx},${crop.topPx},${crop.width},${crop.height}",
            "rotation=${rotation.degrees}",
            "mirror=$mirroredHorizontally",
            "transformed=${transformedWidthPx}x$transformedHeightPx",
            "order=source>crop>clockwise-rotation>horizontal-mirror>tracker",
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return "ct-v$transformVersion:" + digest.joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    companion object {
        const val VERSION = 1
        private const val TOLERANCE = 1e-4f
    }
}
