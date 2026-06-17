package com.aircontrol.data.model

import com.aircontrol.accessibility.GestureAction
import com.aircontrol.gesture.model.LandmarkTemplate

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
 * Can be a static pose, a pose with direction, a finger count pattern, or a
 * recorded landmark template.
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

    /**
     * Bug: Custom Gestures Not Triggering Fix — A recorded landmark template
     * that is matched against live hand frames using Euclidean distance
     * comparison in the [com.aircontrol.gesture.detection.StaticPoseClassifier].
     *
     * This trigger type enables truly custom hand shapes that don't map to any
     * of the hardcoded [CustomGesturePose] enum values. The user records a hand
     * shape, and the engine matches it by comparing normalized inter-landmark
     * distances within [LandmarkTemplate.MATCH_TOLERANCE].
     *
     * The [LandmarkTemplate] is stored as-is (it's a pure-Kotlin data class with
     * no Android dependencies, so it serializes cleanly to JSON).
     */
    data class LandmarkTemplateTrigger(
        val template: LandmarkTemplate,
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
