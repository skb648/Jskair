package com.aircontrol.gesture.model

/** Events emitted by the gesture engine. */
sealed class GestureEvent {
    abstract val timestampMs: Long

    data class Swipe(
        val direction: SwipeDirection,
        override val timestampMs: Long,
    ) : GestureEvent()

    data class Pinch(
        val phase: PinchPhase,
        val x: Float,
        val y: Float,
        override val timestampMs: Long,
        val anchoredX: Float = x,
        val anchoredY: Float = y,
        val velocity: Float = 0f,
    ) : GestureEvent()

    data class PoseTriggered(
        val pose: Pose,
        override val timestampMs: Long,
    ) : GestureEvent()

    data class PalmHome(
        override val timestampMs: Long,
    ) : GestureEvent()

    data class CustomGestureTriggered(
        val gestureId: String,
        val gestureName: String,
        override val timestampMs: Long,
    ) : GestureEvent()

    data class Armed(
        override val timestampMs: Long,
    ) : GestureEvent()

    data class Disarmed(
        override val timestampMs: Long,
    ) : GestureEvent()

    /** Cursor position. Coordinates are always derived from the stable palm anchor. */
    data class CursorMoved(
        val x: Float,
        val y: Float,
        override val timestampMs: Long,
        val isSilent: Boolean = false,
        val minCutoffHint: Float? = null,
    ) : GestureEvent()
}
