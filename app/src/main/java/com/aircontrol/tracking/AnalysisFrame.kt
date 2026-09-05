package com.aircontrol.tracking

/** Immutable metadata contract for one analysis frame. */
data class AnalysisFrame(
    val frameId: Long,
    val timestampNs: Long,
    val timestampMs: Long,
    val sourceWidthPx: Int,
    val sourceHeightPx: Int,
    val crop: CropRect,
    val rotation: Rotation,
    val mirroredHorizontally: Boolean,
    val transformedWidthPx: Int,
    val transformedHeightPx: Int,
    val coordinateConvention: CoordinateConvention,
    val transformSignature: String,
) {
    init {
        require(frameId >= 0L) { "frameId must be non-negative" }
        require(timestampNs >= 0L) { "timestampNs must be non-negative" }
        require(timestampMs >= 0L) { "timestampMs must be non-negative" }
        require(sourceWidthPx > 0 && sourceHeightPx > 0) { "source dimensions must be positive" }
        require(crop.isWithin(sourceWidthPx, sourceHeightPx)) { "crop must be within source dimensions" }
        require(transformedWidthPx > 0 && transformedHeightPx > 0) { "transformed dimensions must be positive" }
        require(coordinateConvention == CoordinateConvention.X_RIGHT_Y_DOWN) { "unsupported coordinate convention" }
        require(transformSignature.isNotBlank()) { "transform signature is required" }
        require(transformedWidthPx == rotation.outputWidth(crop.width) && transformedHeightPx == rotation.outputHeight(crop.height)) {
            "transformed dimensions do not match crop/rotation"
        }
    }
}

data class CropRect(val leftPx: Int, val topPx: Int, val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "crop width/height must be positive" }
        require(leftPx >= 0 && topPx >= 0) { "crop origin must be non-negative" }
    }

    fun isWithin(sourceWidthPx: Int, sourceHeightPx: Int): Boolean =
        sourceWidthPx > 0 && sourceHeightPx > 0 &&
            leftPx.toLong() + width <= sourceWidthPx.toLong() &&
            topPx.toLong() + height <= sourceHeightPx.toLong()
}

enum class Rotation(val degrees: Int) {
    DEG_0(0), DEG_90(90), DEG_180(180), DEG_270(270);

    fun outputWidth(cropWidth: Int, cropHeight: Int = cropWidth): Int =
        if (this == DEG_90 || this == DEG_270) cropHeight else cropWidth

    fun outputHeight(cropWidth: Int, cropHeight: Int = cropWidth): Int =
        if (this == DEG_90 || this == DEG_270) cropWidth else cropHeight

    companion object {
        fun fromDegrees(degrees: Int): Rotation = when (((degrees % 360) + 360) % 360) {
            0 -> DEG_0
            90 -> DEG_90
            180 -> DEG_180
            270 -> DEG_270
            else -> throw IllegalArgumentException("rotation must be one of 0, 90, 180, 270 degrees")
        }
    }
}

enum class CoordinateConvention(val id: String) {
    X_RIGHT_Y_DOWN("x-right-y-down")
}
