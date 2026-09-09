package com.aircontrol.gesture.model

/**
 * Which hand was detected.
 */
enum class Handedness {
    LEFT,
    RIGHT,
    UNKNOWN,
}

/**
 * Input to the gesture engine — a single frame of hand tracking data.
 * This is the pure-Kotlin equivalent of the tracking module's HandFrame,
 * decoupled from any Android or MediaPipe dependencies.
 */
data class HandInput(
    val landmarks: List<Landmark3D>,
    val handedness: Handedness,
    val timestampMs: Long,
    val confidence: Float,
    /**
     * Width / height of the image the normalised landmark coordinates come from.
     *
     * MediaPipe normalises x by the image WIDTH and y by its HEIGHT, so a raw x and a
     * raw y are not the same physical distance and must never be compared directly.
     * Motion maths that wants a real-world ratio multiplies x by this value first.
     * Defaults to 1.0 (square pixels, no correction) so callers that do not know the
     * frame geometry behave exactly as before.
     */
    val frameAspectRatio: Float = 1f,
) {
    val isDetected: Boolean get() = landmarks.size == LANDMARK_COUNT && confidence > 0f

    companion object {
        const val LANDMARK_COUNT = 21

        val EMPTY = HandInput(
            landmarks = emptyList(),
            handedness = Handedness.UNKNOWN,
            timestampMs = 0L,
            confidence = 0f,
        )
    }
}
