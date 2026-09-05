package com.aircontrol.tracking

/**
 * A single face-landmark observation in tracker-image coordinates.
 *
 * x/y are normalized to the tracker image: x in [0,1] across tracker width and
 * y in [0,1] across tracker height. z is the MediaPipe landmark depth value and
 * is intentionally left in the model's native relative scale.
 */
data class FaceLandmark(
    val x: Float,
    val y: Float,
    val z: Float = 0f,
) {
    fun isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()
}

/**
 * Immutable face-landmark frame used by the eye-tracking pipeline.
 *
 * Landmark IDs remain MediaPipe semantic/anatomical IDs even when the source
 * image was mirrored for a front camera. Consumers must never infer anatomical
 * left/right from x position in the image.
 */
data class FaceLandmarkFrame(
    val frameId: Long,
    val timestampNs: Long,
    val timestampMs: Long,
    val trackerWidthPx: Int,
    val trackerHeightPx: Int,
    val isFrontCameraMirrored: Boolean,
    val landmarks: List<FaceLandmark>,
) {
    init {
        require(frameId >= 0L) { "frameId must be non-negative" }
        require(timestampNs >= 0L) { "timestampNs must be non-negative" }
        require(timestampMs >= 0L) { "timestampMs must be non-negative" }
        require(trackerWidthPx > 0) { "trackerWidthPx must be > 0" }
        require(trackerHeightPx > 0) { "trackerHeightPx must be > 0" }
    }

    fun isLandmarkIndexValid(index: Int): Boolean = index in landmarks.indices

    fun landmark(index: Int): FaceLandmark? = landmarks.getOrNull(index)
}

/**
 * Canonical MediaPipe Face Mesh / Face Landmarker landmark definitions.
 *
 * IMPORTANT: these groups describe ANATOMICAL identity, not screen side.
 * Anatomical LEFT therefore uses MediaPipe's 263/362 eye corners and iris 473;
 * anatomical RIGHT uses 33/133 and iris 468. This remains true for selfie/front
 * camera inputs after horizontal mirroring because the semantic landmark IDs are
 * preserved by the face model.
 */
data class EyeLandmarkDefinition(
    val outerCorner: Int,
    val innerCorner: Int,
    val upperOuter: Int,
    val upperInner: Int,
    val lowerInner: Int,
    val lowerOuter: Int,
    val irisCenter: Int,
    val irisRing: IntArray,
) {
    val earPoints: IntArray
        get() = intArrayOf(
            outerCorner,
            upperOuter,
            upperInner,
            innerCorner,
            lowerInner,
            lowerOuter,
        )

    val requiredIndices: IntArray
        get() = intArrayOf(
            outerCorner,
            innerCorner,
            upperOuter,
            upperInner,
            lowerInner,
            lowerOuter,
            irisCenter,
            *irisRing,
        )
}

object CanonicalEyes {
    /** Anatomical left eye: image-right side for a typical mirrored selfie frame. */
    val LEFT = EyeLandmarkDefinition(
        outerCorner = 263,
        innerCorner = 362,
        upperOuter = 387,
        upperInner = 385,
        lowerInner = 380,
        lowerOuter = 373,
        irisCenter = 473,
        irisRing = intArrayOf(474, 475, 476, 477),
    )

    /** Anatomical right eye: image-left side for a typical mirrored selfie frame. */
    val RIGHT = EyeLandmarkDefinition(
        outerCorner = 33,
        innerCorner = 133,
        upperOuter = 160,
        upperInner = 158,
        lowerInner = 153,
        lowerOuter = 144,
        irisCenter = 468,
        irisRing = intArrayOf(469, 470, 471, 472),
    )

    const val MIN_LANDMARK_COUNT = 478
}
