package com.aircontrol.tracking

import kotlin.math.hypot

/** A single normalized 3D hand landmark. */
data class Landmark3D(
    val x: Float,
    val y: Float,
    val z: Float,
)

enum class Handedness {
    LEFT,
    RIGHT,
    UNKNOWN,
}

/**
 * One MediaPipe hand result. [confidence] is the handedness classifier score;
 * [landmarkQuality] is an independent geometric sanity score and must not be
 * interpreted as handedness confidence.
 */
data class HandFrame(
    val landmarks: List<Landmark3D>,
    val handedness: Handedness,
    val timestampMs: Long,
    val confidence: Float,
    val frameAspectRatio: Float = 1f,
    val landmarkQuality: Float = computeLandmarkQuality(landmarks),
) {
    /**
     * A detected hand must have a complete landmark set, usable geometry and a
     * minimum classifier score. This prevents a high handedness score from making
     * corrupt landmark geometry actionable.
     */
    val isDetected: Boolean
        get() = landmarks.size == LANDMARK_COUNT &&
            confidence.isFinite() && confidence >= MIN_CONFIDENCE &&
            landmarkQuality >= MIN_LANDMARK_QUALITY

    companion object {
        const val LANDMARK_COUNT = 21
        const val MIN_CONFIDENCE = 0.15f
        const val MIN_LANDMARK_QUALITY = 0.45f

        val EMPTY = HandFrame(
            landmarks = emptyList(),
            handedness = Handedness.UNKNOWN,
            timestampMs = -1L,
            confidence = 0f,
            landmarkQuality = 0f,
        )

        /**
         * Lightweight geometric quality score, independent from MediaPipe
         * handedness. It rejects non-finite/out-of-range landmarks and collapsed
         * hand geometry while allowing normal near-edge poses.
         */
        private fun computeLandmarkQuality(landmarks: List<Landmark3D>): Float {
            if (landmarks.size != LANDMARK_COUNT) return 0f
            var minX = 1f
            var maxX = 0f
            var minY = 1f
            var maxY = 0f
            for (lm in landmarks) {
                if (!lm.x.isFinite() || !lm.y.isFinite() || !lm.z.isFinite()) return 0f
                if (lm.x !in 0f..1f || lm.y !in 0f..1f) return 0f
                minX = minOf(minX, lm.x)
                maxX = maxOf(maxX, lm.x)
                minY = minOf(minY, lm.y)
                maxY = maxOf(maxY, lm.y)
            }

            val wrist = landmarks[0]
            val middleMcp = landmarks[9]
            val palmScale = hypot(
                (middleMcp.x - wrist.x) * 1f,
                middleMcp.y - wrist.y,
            )
            if (!palmScale.isFinite() || palmScale < MIN_PALM_SCALE) return 0f

            val span = maxOf(maxX - minX, maxY - minY)
            if (!span.isFinite() || span < MIN_VISIBLE_SPAN) return 0f

            // Quality remains close to 1 for ordinary geometry; it falls smoothly
            // toward zero only as the hand becomes implausibly tiny at the detector.
            val scaleQuality = ((palmScale - MIN_PALM_SCALE) / 0.12f).coerceIn(0f, 1f)
            val spanQuality = ((span - MIN_VISIBLE_SPAN) / 0.20f).coerceIn(0f, 1f)
            return minOf(1f, 0.5f + 0.5f * minOf(scaleQuality, spanQuality))
        }

        private const val MIN_PALM_SCALE = 0.035f
        private const val MIN_VISIBLE_SPAN = 0.08f
    }
}

object HandConnections {
    val CONNECTIONS: List<Pair<Int, Int>> = listOf(
        0 to 1, 1 to 2, 2 to 3, 3 to 4,
        0 to 5, 5 to 6, 6 to 7, 7 to 8,
        0 to 9, 9 to 10, 10 to 11, 11 to 12,
        0 to 13, 13 to 14, 14 to 15, 15 to 16,
        0 to 17, 17 to 18, 18 to 19, 19 to 20,
        5 to 9, 9 to 13, 13 to 17,
    )
}
