package com.aircontrol.gesture.model

/**
 * Static hand poses recognized by the gesture engine.
 * Each pose is defined by a specific combination of finger extension states.
 */
enum class Pose {
    NONE,
    OPEN_PALM,
    FIST,
    PINCH,
    POINTING,
    VICTORY,
    THUMB_UP,
    THUMB_DOWN,
    THREE_FINGERS,
    FOUR_FINGERS,
}
