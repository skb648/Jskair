package com.aircontrol.accessibility

/**
 * Controlled interaction-state reasons (Issue 10: "why was my action blocked?").
 *
 * These are NOT toasts and are never emitted per frame. [BlockReasonHub] turns
 * raw block events into a throttled, change-only "current reason" that the
 * status pill / debug screen can surface, so the user can understand why the
 * system chose to do nothing without being spammed.
 *
 * [stableId] is a short, stable token used for logs and metrics. The display
 * string lives in the UI layer (string resources).
 */
enum class BlockReason(val stableId: String) {
    /** A hand gesture needs the hand to be present & tracked (arming). */
    WAITING_FOR_HAND("waiting_for_hand"),

    /** Hand is visible but the tracker is not confident enough to act. */
    HAND_NOT_CONFIDENT("hand_not_confident"),

    /** Eye/gaze signal is present but not settled enough to act on. */
    EYE_NOT_STABLE("eye_not_stable"),

    /** The face/eyes left the camera frame. */
    FACE_LOST("face_lost"),

    /** A blink completed too quickly to be deliberate. */
    BLINK_TOO_SHORT("blink_too_short"),

    /** Eyes stayed closed too long (rest) — not a click. */
    BLINK_TOO_LONG("blink_too_long"),

    /** Blink happened while the gaze was still moving — ignored. */
    BLINK_DURING_MOVEMENT("blink_during_movement"),

    /** Waiting for the gaze to stop moving before the cursor may act. */
    WAITING_FOR_GAZE_TO_SETTLE("waiting_for_gaze_to_settle"),

    /** The modality's cursor/gesture is not currently armed. */
    CURSOR_NOT_ARMED("cursor_not_armed"),

    /** A gesture cooldown is suppressing repeated execution. */
    GESTURE_COOLDOWN("gesture_cooldown"),

    /** A pinch never travelled far enough to become a drag. */
    DRAG_THRESHOLD_NOT_REACHED("drag_threshold_not_reached"),

    /** Actions are suppressed while a calibration/setup screen is open. */
    SUPPRESSED_DURING_CALIBRATION("suppressed_during_calibration"),

    /** A tap was refused because another input modality just acted. */
    ANOTHER_MODALITY_ACTED("another_modality_acted"),

    /** The requested action is not supported on this Android version. */
    ACTION_UNSUPPORTED("action_unsupported"),

    /** The accessibility service is not available to dispatch. */
    ACCESSIBILITY_SERVICE_UNAVAILABLE("accessibility_service_unavailable"),

    /** A pinch was refused because the hand was moving too fast at that moment. */
    PINCH_MOVED_TOO_FAST("pinch_moved_too_fast"),

    /** The target surface is protected by Android security (system dialog, secure app). */
    PROTECTED_SCREEN_BLOCKED("protected_screen_blocked"),
}
