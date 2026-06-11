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
