package com.aircontrol.data.model

import com.aircontrol.accessibility.GestureAction

/**
 * Represents a user-defined custom gesture.
 *
 * Custom gestures are defined by a combination of:
 * - A trigger pose (required): The hand pose that activates the gesture
 * - An optional directional component: Whether the gesture requires movement direction
 * - A mapped action: What system action to perform
 *
 * Custom gestures are persisted in DataStore and can be created/modified/deleted
 * by the user through the Custom Gesture screen.
 */
data class CustomGesture(
    val id: String,
    val name: String,
    val description: String,
    val triggerPose: CustomGestureTrigger,
    val action: GestureAction,
    val isEnabled: Boolean = true,
    val createdAtMs: Long = System.currentTimeMillis(),
)

/**
 * Defines what triggers a custom gesture.
 * Can be a static pose, a pose with direction, or a finger count pattern.
 */
sealed class CustomGestureTrigger {
    /** A specific hand pose combined with an optional direction. */
    data class PoseWithDirection(
        val pose: CustomGesturePose,
        val direction: CustomGestureDirection = CustomGestureDirection.NONE,
    ) : CustomGestureTrigger()

    /** A finger count pattern (e.g., 2 fingers, 3 fingers). */
    data class FingerCount(
        val extendedFingers: Int,
        val whichFingers: Set<FingerType> = emptySet(),
    ) : CustomGestureTrigger()
}

/**
 * Poses available for custom gesture triggers.
 * These are simpler than the full gesture engine poses to make it user-friendly.
 */
enum class CustomGesturePose {
    OPEN_PALM,
    FIST,
    PINCH,
    POINTING,     // Index finger extended only
    VICTORY,      // Index + middle extended (peace sign)
    THUMB_UP,     // Thumb extended only
    THUMB_DOWN,   // Thumb down
    THREE_FINGERS, // Index + middle + ring extended
    FOUR_FINGERS,  // All fingers except thumb
}

/**
 * Direction component for custom gestures.
 */
enum class CustomGestureDirection {
    NONE,
    LEFT,
    RIGHT,
    UP,
    DOWN,
}

/**
 * Individual finger types for finger count triggers.
 */
enum class FingerType {
    THUMB,
    INDEX,
    MIDDLE,
    RING,
    PINKY,
}
