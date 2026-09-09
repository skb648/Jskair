package com.aircontrol.accessibility.cursor

/**
 * Explicit cursor-visibility state machine (Issue 1 hardening).
 *
 * The overlay's show()/hide() used to be frame operations: every show() during a
 * running 200 ms fade-in cancelled the in-flight animation and restarted a fresh
 * fade-in from alpha 0. At 30–60 gaze frames/sec that re-created the animation
 * ~every frame, so the fade could never complete (the cursor flickered and never
 * reached alpha 1).
 *
 * Visibility is now a state machine with explicit transitions:
 *
 * ```
 * HIDDEN --show--> SHOWING --fade-in-complete--> VISIBLE
 * SHOWING --hide--> HIDING --fade-out-complete--> HIDDEN
 * VISIBLE --hide--> HIDING
 * HIDING --show--> VISIBLE        (restore instantly, never re-fade)
 * (any)  --removed--> HIDDEN
 * ```
 *
 * - [onShowRequest] is IDEMPOTENT: VISIBLE stays VISIBLE, SHOWING stays SHOWING
 *   (no fade restart). A show while HIDING cancels the fade-out and returns the
 *   cursor to full visibility WITHOUT starting a new fade-in.
 * - [onHideRequest] is idempotent in HIDDEN/HIDING.
 * - [onRemoved] cancels everything; a stale completion arriving after removal is
 *   ignored because the machine is already HIDDEN.
 *
 * The machine only DECIDES; the overlay performs the returned
 * [CursorVisibilityAction] on its view and reports back the animation
 * completion events. Pure Kotlin — JVM-testable.
 */
enum class CursorVisibilityState { HIDDEN, SHOWING, VISIBLE, HIDING }

/**
 * What the overlay must do as the result of a state transition.
 *
 * - [START_FADE_IN] begin the fade from 0 → 1 over the fade duration.
 * - [START_FADE_OUT] begin the fade from the current alpha → 0.
 * - [CANCEL_AND_RESTORE] cancel any running animation and set alpha to 1
 *   immediately (show during a fade-out: no new fade-in).
 * - [MAKE_INVISIBLE] hide the view (alpha already 0, fade-out finished).
 * - [CANCEL_ALL] cancel any running animation (removal / teardown).
 * - [NONE] nothing to do.
 */
enum class CursorVisibilityAction {
    NONE,
    START_FADE_IN,
    START_FADE_OUT,
    CANCEL_AND_RESTORE,
    MAKE_INVISIBLE,
    CANCEL_ALL,
}

class CursorVisibilityStateMachine {

    var state: CursorVisibilityState = CursorVisibilityState.HIDDEN
        private set

    /** True while the cursor is on screen (fading in, visible, or fading out). */
    val isEffectivelyVisible: Boolean
        get() = state == CursorVisibilityState.SHOWING ||
            state == CursorVisibilityState.VISIBLE ||
            state == CursorVisibilityState.HIDING

    /**
     * A request to show the cursor. Returns the action the overlay must run.
     * Repeated calls are idempotent — they never restart a running fade-in.
     */
    fun onShowRequest(): CursorVisibilityAction = when (state) {
        CursorVisibilityState.HIDDEN -> {
            state = CursorVisibilityState.SHOWING
            CursorVisibilityAction.START_FADE_IN
        }
        // Already fading in / already fully visible: do NOT restart the animation.
        CursorVisibilityState.SHOWING,
        CursorVisibilityState.VISIBLE,
        -> CursorVisibilityAction.NONE

        // A fade-out is running and the cursor must stay visible: cancel the
        // fade-out and restore full visibility cleanly (no new fade-in).
        CursorVisibilityState.HIDING -> {
            state = CursorVisibilityState.VISIBLE
            CursorVisibilityAction.CANCEL_AND_RESTORE
        }
    }

    /** The fade-in animation finished and the cursor reached alpha 1. */
    fun onFadeInCompleted(): CursorVisibilityAction = when (state) {
        CursorVisibilityState.SHOWING -> {
            state = CursorVisibilityState.VISIBLE
            CursorVisibilityAction.NONE
        }
        // A stale completion after removal/cancellation must be a no-op.
        else -> CursorVisibilityAction.NONE
    }

    /** A request to hide the cursor. */
    fun onHideRequest(): CursorVisibilityAction = when (state) {
        CursorVisibilityState.HIDDEN,
        CursorVisibilityState.HIDING,
        -> CursorVisibilityAction.NONE

        CursorVisibilityState.SHOWING,
        CursorVisibilityState.VISIBLE,
        -> {
            state = CursorVisibilityState.HIDING
            CursorVisibilityAction.START_FADE_OUT
        }
    }

    /** The fade-out animation finished; the cursor is now fully hidden. */
    fun onFadeOutCompleted(): CursorVisibilityAction = when (state) {
        CursorVisibilityState.HIDING -> {
            state = CursorVisibilityState.HIDDEN
            CursorVisibilityAction.MAKE_INVISIBLE
        }
        else -> CursorVisibilityAction.NONE
    }

    /** The overlay view was removed / torn down. Cancels every animation. */
    fun onRemoved(): CursorVisibilityAction = when (state) {
        CursorVisibilityState.HIDDEN -> CursorVisibilityAction.NONE
        else -> {
            state = CursorVisibilityState.HIDDEN
            CursorVisibilityAction.CANCEL_ALL
        }
    }
}
