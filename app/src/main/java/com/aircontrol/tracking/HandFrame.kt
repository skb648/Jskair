package com.aircontrol.tracking

/**
 * Represents a single 3D landmark point from MediaPipe hand detection.
 * Coordinates are normalized [0,1] relative to the image dimensions,
 * with z representing depth relative to the wrist.
 */
data class Landmark3D(
    val x: Float,
    val y: Float,
    val z: Float,
)

/**
 * Which hand was detected.
 */
enum class Handedness {
    LEFT,
    RIGHT,
    UNKNOWN,
}

/**
 * A single frame of hand tracking output, emitted by HandTracker.
 * Contains all 21 hand landmarks, handedness, timing, and confidence.
 */
data class HandFrame(
    val landmarks: List<Landmark3D>,
    val handedness: Handedness,
    val timestampMs: Long,
    val confidence: Float,
) {
    /**
     * Fix C-5: "a hand is visible" must mean the same thing on both sides of the
     * pipeline. It used to be `landmarks.isNotEmpty() && confidence > 0f`, so a
     * single noise landmark with score 0.01 counted as a hand: the adaptive frame
     * rate stayed high (draining the battery on the sofa) and the "show your open
     * palm" hint never appeared, because the app believed a hand was already
     * tracked. Requiring the full 21-landmark set and a floor score aligns it
     * with the engine's own [com.aircontrol.gesture.model.HandInput.isDetected]
     * while staying deliberately below the engine's 0.7 low-confidence threshold,
     * so the engine - not the tracker - decides what to do with a shaky frame.
     */
    val isDetected: Boolean get() = landmarks.size == LANDMARK_COUNT && confidence >= MIN_CONFIDENCE

    companion object {
        const val LANDMARK_COUNT = 21

        /** Below this, MediaPipe is guessing; treat as no hand at all. */
        const val MIN_CONFIDENCE = 0.15f

        val EMPTY = HandFrame(
            landmarks = emptyList(),
            handedness = Handedness.UNKNOWN,
            timestampMs = -1L,
            confidence = 0f,
        )
    }
}

/**
 * Standard hand landmark connections for skeleton visualization.
 * Each pair represents a bone connection between two landmark indices.
 * Based on MediaPipe HAND_CONNECTIONS.
 */
object HandConnections {
    val CONNECTIONS: List<Pair<Int, Int>> = listOf(
        // Thumb
        0 to 1, 1 to 2, 2 to 3, 3 to 4,
        // Index finger
        0 to 5, 5 to 6, 6 to 7, 7 to 8,
        // Middle finger
        0 to 9, 9 to 10, 10 to 11, 11 to 12,
        // Ring finger
        0 to 13, 13 to 14, 14 to 15, 15 to 16,
        // Pinky
        0 to 17, 17 to 18, 18 to 19, 19 to 20,
        // Palm
        5 to 9, 9 to 13, 13 to 17,
    )
}
