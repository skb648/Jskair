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
    val isDetected: Boolean get() = landmarks.isNotEmpty() && confidence > 0f

    companion object {
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
