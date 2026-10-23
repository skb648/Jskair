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
 * A single tracked hand within a [HandFrame], preserving its stable [trackId],
 * [handedness], [confidence], independent [landmarkQuality], and 21 3D [landmarks].
 */
data class TrackedHand(
    val trackId: Int,
    val landmarks: List<Landmark3D>,
    val handedness: Handedness,
    val confidence: Float,
    val timestampMs: Long,
    val frameAspectRatio: Float = 1f,
    val landmarkQuality: Float = HandFrame.computeLandmarkQuality(landmarks),
    val isAmbiguous: Boolean = false,
) {
    /**
     * A valid hand must not be flagged as crossing-ambiguous, and must have a complete
     * 21-landmark set, usable geometry, and minimum classifier confidence.
     */
    val isValid: Boolean
        get() = !isAmbiguous &&
            landmarks.size == HandFrame.LANDMARK_COUNT &&
            confidence.isFinite() && confidence >= HandFrame.MIN_CONFIDENCE &&
            landmarkQuality >= HandFrame.MIN_LANDMARK_QUALITY

    val isDetected: Boolean
        get() = isValid
}

/**
 * Multi-hand MediaPipe frame result.
 *
 * Holds up to two simultaneous [TrackedHand] instances in [hands] while exposing
 * primary-hand fields ([landmarks], [handedness], [confidence], [landmarkQuality])
 * for compatibility with single-hand consumers.
 */
data class HandFrame(
    val landmarks: List<Landmark3D>,
    val handedness: Handedness,
    val timestampMs: Long,
    val confidence: Float,
    val frameAspectRatio: Float = 1f,
    val landmarkQuality: Float = computeLandmarkQuality(landmarks),
    val hands: List<TrackedHand> = defaultHandsList(
        landmarks = landmarks,
        handedness = handedness,
        timestampMs = timestampMs,
        confidence = confidence,
        frameAspectRatio = frameAspectRatio,
        landmarkQuality = landmarkQuality,
    ),
    val primaryTrackId: Int? = hands.firstOrNull()?.trackId,
) {
    /**
     * True when the primary hand (or at least one valid non-ambiguous hand when
     * primary fields are populated) passes all landmark, confidence, and quality gates.
     */
    val isDetected: Boolean
        get() = landmarks.size == LANDMARK_COUNT &&
            confidence.isFinite() && confidence >= MIN_CONFIDENCE &&
            landmarkQuality >= MIN_LANDMARK_QUALITY &&
            (hands.isEmpty() || hands.firstOrNull { it.trackId == primaryTrackId }?.isAmbiguous != true)

    /** All hands in this frame that pass geometric, confidence, and ambiguity checks. */
    val validHands: List<TrackedHand>
        get() = hands.filter { it.isValid }

    /** True when both left and right (or two distinct valid hands) are simultaneously tracked. */
    val isDualHandDetected: Boolean
        get() = validHands.size >= 2

    /** Convenience lookup for a valid left hand in this frame. */
    val leftHand: TrackedHand?
        get() = hands.firstOrNull { it.handedness == Handedness.LEFT && it.isValid }

    /** Convenience lookup for a valid right hand in this frame. */
    val rightHand: TrackedHand?
        get() = hands.firstOrNull { it.handedness == Handedness.RIGHT && it.isValid }

    /** Lookup a tracked hand by its temporal [trackId]. */
    fun handByTrackId(trackId: Int): TrackedHand? =
        hands.firstOrNull { it.trackId == trackId }

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
            hands = emptyList(),
            primaryTrackId = null,
        )

        private fun defaultHandsList(
            landmarks: List<Landmark3D>,
            handedness: Handedness,
            timestampMs: Long,
            confidence: Float,
            frameAspectRatio: Float,
            landmarkQuality: Float,
        ): List<TrackedHand> {
            if (landmarks.isEmpty()) return emptyList()
            val defaultTrackId = when (handedness) {
                Handedness.LEFT -> 1
                Handedness.RIGHT -> 2
                Handedness.UNKNOWN -> 1
            }
            return listOf(
                TrackedHand(
                    trackId = defaultTrackId,
                    landmarks = landmarks,
                    handedness = handedness,
                    confidence = confidence,
                    timestampMs = timestampMs,
                    frameAspectRatio = frameAspectRatio,
                    landmarkQuality = landmarkQuality,
                ),
            )
        }

        /**
         * Builds a [HandFrame] from up to two simultaneously tracked hands.
         *
         * All detected hands are preserved in [hands] (for HUD rendering and multi-hand
         * gesture processing), while the top-level primary fields reflect [primaryHand]
         * (or empty if no hand satisfies the active hand preference / ambiguity gate).
         */
        fun fromTrackedHands(
            hands: List<TrackedHand>,
            primaryHand: TrackedHand?,
            timestampMs: Long,
            frameAspectRatio: Float = 1f,
        ): HandFrame {
            val orderedHands = if (primaryHand != null) {
                listOf(primaryHand) + hands.filter { it.trackId != primaryHand.trackId }
            } else {
                hands
            }
            return if (primaryHand != null && primaryHand.isValid) {
                HandFrame(
                    landmarks = primaryHand.landmarks,
                    handedness = primaryHand.handedness,
                    timestampMs = timestampMs,
                    confidence = primaryHand.confidence,
                    frameAspectRatio = frameAspectRatio,
                    landmarkQuality = primaryHand.landmarkQuality,
                    hands = orderedHands,
                    primaryTrackId = primaryHand.trackId,
                )
            } else {
                HandFrame(
                    landmarks = emptyList(),
                    handedness = Handedness.UNKNOWN,
                    timestampMs = timestampMs,
                    confidence = 0f,
                    frameAspectRatio = frameAspectRatio,
                    landmarkQuality = 0f,
                    hands = orderedHands,
                    primaryTrackId = null,
                )
            }
        }

        /**
         * Lightweight geometric quality score, independent from MediaPipe
         * handedness. It rejects non-finite/out-of-range landmarks and collapsed
         * hand geometry while allowing normal near-edge poses.
         */
        fun computeLandmarkQuality(landmarks: List<Landmark3D>): Float {
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
