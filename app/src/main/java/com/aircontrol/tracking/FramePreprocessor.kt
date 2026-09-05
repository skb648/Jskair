package com.aircontrol.tracking

import androidx.camera.core.ImageProxy
import java.util.concurrent.atomic.AtomicLong

/**
 * CameraX-facing ownership/metadata gate. Pixel interpretation is described by
 * CoordinateTransform; this class does not create a second camera pipeline.
 * Rotation and mirroring are represented once in the immutable frame contract so
 * downstream stages cannot silently apply a second transform.
 */
class FramePreprocessor {
    private val nextFrameId = AtomicLong(0L)

    fun prepare(
        image: ImageProxy,
        mirroredHorizontally: Boolean,
        crop: CropRect = CropRect(0, 0, image.width, image.height),
    ): PreparedFrame {
        val rotation = Rotation.fromDegrees(image.imageInfo.rotationDegrees)
        val transform = CoordinateTransform(
            sourceWidthPx = image.width,
            sourceHeightPx = image.height,
            crop = crop,
            rotation = rotation,
            mirroredHorizontally = mirroredHorizontally,
        )
        val timestampNs = image.imageInfo.timestamp
        require(timestampNs >= 0L) { "CameraX timestamp must be non-negative" }
        val timestampMs = timestampNs / NANOS_PER_MILLISECOND
        val frame = transform.toAnalysisFrame(
            frameId = nextFrameId.getAndIncrement(),
            timestampNs = timestampNs,
            timestampMs = timestampMs,
        )
        val lease = TaskInputLease(image) { image.close() }
        return PreparedFrame(frame, lease)
    }

    data class PreparedFrame(
        val frame: AnalysisFrame,
        val input: TaskInputLease<ImageProxy>,
    ) : AutoCloseable {
        override fun close() = input.close()
    }

    companion object {
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
