package com.aircontrol.gesture.model

/**
 * Events emitted by the gesture engine.
 * Each event represents a discrete, recognized user action or state change.
 */
sealed class GestureEvent {
    
    /** Timestamp when this event occurred. */
    abstract val timestampMs: Long

    /** A swipe gesture in the given direction. */
    data class Swipe(
        val direction: SwipeDirection,
        override val timestampMs: Long,
    ) : GestureEvent()

    /** A pinch gesture with phase tracking and normalized position.
     *
     * @param x For START: anchored index-tip position (where user was pointing when pinch began).
     *          For MOVE: ACTUAL current hand position (for drag movement tracking).
     *          For END: anchored position (same as START, for tap/long-press targeting).
     * @param y Same as x but for Y coordinate.
     * @param anchoredX The original anchored position (always the index-tip at pinch START).
     *                  Used for tap/long-press targeting that shouldn't drift.
     * @param anchoredY Same as anchoredX but for Y coordinate.
     */
    data class Pinch(
        val phase: PinchPhase,
        val x: Float,
        val y: Float,
        override val timestampMs: Long,
        val anchoredX: Float = x,
        val anchoredY: Float = y,
        // Normalized hand/cursor velocity (0..1 units per second) at the moment of
        // this event. Used by the app layer for "stationary-click" (Midas-touch
        // prevention): a pinch that starts while the hand is moving fast is likely
        // accidental and should not fire a tap.
        val velocity: Float = 0f,
    ) : GestureEvent()

    /** A static pose was confirmed after debounce. */
    data class PoseTriggered(
        val pose: Pose,
        override val timestampMs: Long,
    ) : GestureEvent()

    /**
     * Emitted when the user holds an open palm in the ARMED state for the
     * configured duration. Maps to the "Palm → Home" system gesture (visionOS 2
     * style): a deliberate open-palm hold brings up the Home View / app launcher.
     */
    data class PalmHome(
        override val timestampMs: Long,
    ) : GestureEvent()

    /**
     * Bug: Custom Gestures Not Triggering Fix — A user-defined custom gesture
     * (matched via landmark template comparison) was confirmed.
     *
     * Unlike [PoseTriggered] (which carries a hardcoded [Pose] enum value),
     * this event carries a [gestureId] string that the ActionDispatcher uses
     * to look up the user's configured [com.aircontrol.accessibility.GestureAction].
     *
     * @param gestureId The unique ID of the matched [LandmarkTemplate]. This
     *   corresponds to the `id` field of the user's
     *   [com.aircontrol.data.model.CustomGesture].
     * @param gestureName Human-readable name (for logging/debugging only).
     * @param timestampMs Frame timestamp when the match was confirmed.
     */
    data class CustomGestureTriggered(
        val gestureId: String,
        val gestureName: String,
        override val timestampMs: Long,
    ) : GestureEvent()

    /** The state machine transitioned to ARMED. */
    data class Armed(
        override val timestampMs: Long,
    ) : GestureEvent()

    /** The state machine transitioned to DISARMED. */
    data class Disarmed(
        override val timestampMs: Long,
    ) : GestureEvent()

    /**
     * The cursor moved to a new normalized position.
     *
     * @param x Normalized X coordinate [0,1] of the cursor (post-engine-processing).
     * @param y Normalized Y coordinate [0,1] of the cursor.
     * @param timestampMs Frame timestamp in milliseconds.
     * @param isSilent Bug #18 Fix: When true, this CursorMoved event is emitted
     *   during the ARMING state to pre-warm the CursorSmoother. Consumers should
     *   feed the coordinates to the smoother but SKIP showing/updating the visual
     *   cursor overlay — the cursor should remain hidden until the engine reaches
     *   the ARMED state. This prevents a visible "jump" when the cursor first
     *   appears (the smoother has already converged on a stable position).
     * @param minCutoffHint Bug #13 Fix: Optional adaptive smoothing hint. When
     *   non-null, the consumer should call `cursorSmoother.updateParams(minCutoff
     *   = minCutoffHint, beta = currentBeta)` to temporarily increase smoothing
     *   for low-confidence frames near camera boundaries. When null, the consumer
     *   should restore the smoother's default parameters.
     */
    data class CursorMoved(
        val x: Float,
        val y: Float,
        override val timestampMs: Long,
        val isSilent: Boolean = false,
        val minCutoffHint: Float? = null,
    ) : GestureEvent()
}
