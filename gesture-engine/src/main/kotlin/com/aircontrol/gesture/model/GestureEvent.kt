package com.aircontrol.gesture.model

/**
 * Events emitted by the gesture engine.
 * Each event represents a discrete, recognized user action or state change.
 */
sealed class GestureEvent {

    /** A swipe gesture in the given direction. */
    data class Swipe(
        val direction: SwipeDirection,
        val timestampMs: Long,
    ) : GestureEvent()

    /** A pinch gesture with phase tracking and normalized position. */
    data class Pinch(
        val phase: PinchPhase,
        val x: Float,
        val y: Float,
        val timestampMs: Long,
    ) : GestureEvent()

    /** A static pose was confirmed after debounce. */
    data class PoseTriggered(
        val pose: Pose,
        val timestampMs: Long,
    ) : GestureEvent()

    /** The state machine transitioned to ARMED. */
    data class Armed(
        val timestampMs: Long,
    ) : GestureEvent()

    /** The state machine transitioned to DISARMED. */
    data class Disarmed(
        val timestampMs: Long,
    ) : GestureEvent()

    /** The cursor moved to a new normalized position. */
    data class CursorMoved(
        val x: Float,
        val y: Float,
        val timestampMs: Long,
    ) : GestureEvent()
}
