package com.aircontrol.gesture.statemachine

import com.aircontrol.gesture.config.GestureEngineConfig
import com.aircontrol.gesture.model.GestureEngineState
import com.aircontrol.gesture.model.Pose
import kotlin.concurrent.Volatile

/**
 * State machine governing the gesture engine's armed/disarmed lifecycle.
 *
 * State transitions:
 *   DISARMED → ARMING: Open palm detected
 *   ARMING → ARMED: Open palm held for [GestureEngineConfig.armingDurationMs]
 *   ARMING → DISARMED: Palm lost before arming timeout
 *   ARMED → EXECUTING: Gesture triggered (swipe, pinch, pose)
 *   EXECUTING → COOLDOWN: Gesture fired, entering cooldown
 *   COOLDOWN → ARMED: Cooldown duration elapsed
 *   Any → DISARMED: No hand for [GestureEngineConfig.autoDisarmTimeoutMs]
 *   ARMED → DISARMED: FIST held for [GestureEngineConfig.fistDisarmDurationMs]
 *
 * RAPID-FIRE HOLD PREVENTION (Bug #3 Fix):
 *   When a pose triggers EXECUTING, its value is recorded in [lastExecutedPose].
 *   After COOLDOWN → ARMED, the same held pose will NOT trigger EXECUTING again —
 *   the user must first return to a neutral pose (NONE or POINTING) to clear
 *   [lastExecutedPose]. This prevents a single held VICTORY/THUMB_UP/etc. from
 *   firing the same action on every cooldown cycle (e.g., volume ramping to max
 *   in one continuous hold).
 *
 * Every state transition is tracked and can be observed for UI rendering.
 */
class GestureStateMachine(config: GestureEngineConfig) {
    // H-06 Fix: Make config mutable so sensitivity can be updated without recreating the engine
    @Volatile
    private var config: GestureEngineConfig = config

    /** Current state of the machine. */
    @Volatile
    var currentState: GestureEngineState = GestureEngineState.DISARMED
        private set

    /** Timestamp when the current state was entered. */
    @Volatile
    private var stateEntryTimeMs: Long = 0L

    /** Timestamp of the last hand detection. */
    @Volatile
    private var lastHandDetectedTimeMs: Long = 0L

    /** Whether a hand was detected in the current frame. */
    private var handCurrentlyDetected: Boolean = false

    /** How long the FIST pose has been held continuously. */
    @Volatile
    private var fistHoldStartMs: Long = 0L
    @Volatile
    private var fistWasHeld: Boolean = false

    /**
     * Last pose that triggered EXECUTING. Used to prevent rapid-fire repeats
     * when the user holds the same pose across the COOLDOWN boundary.
     *
     * Reset to [Pose.NONE] only when a neutral pose (NONE or POINTING) is
     * observed in ARMED state, indicating the user has relaxed their hand.
     *
     * (Bug #3 Fix)
     */
    @Volatile
    private var lastExecutedPose: Pose = Pose.NONE

    /** Per-frame inputs supplied by [process] (see its parameter docs). */
    private var currentFistLike: Boolean = false
    private var currentSuppressExecution: Boolean = false

    /** Arming progress: 0.0 to 1.0, where 1.0 means fully armed. */
    @Volatile
    var armingProgress: Float = 0f
        private set

    /**
     * Result of processing a frame through the state machine.
     * Indicates what events should be emitted.
     */
    data class TransitionResult(
        val stateChanged: Boolean,
        val previousState: GestureEngineState,
        val newState: GestureEngineState,
        val shouldExecute: Boolean,
    )

    /**
     * Processes the current pose and hand detection status, returning
     * a [TransitionResult] indicating any state changes and whether
     * a gesture should be executed.
     *
     * @param pose The current confirmed pose (after debounce)
     * @param handDetected Whether a hand is currently detected
     * @param timestampMs Current frame timestamp
     * @param fistLike True when all four fingers are curled, regardless of what
     *   the thumb is doing. Drives the disarm hold so that closing the hand
     *   (which flickers FIST → NONE → THUMB_* across frames) still disarms
     *   instead of resetting the hold timer every frame (Fix A-12/A-13).
     * @param suppressPoseExecution True while the hand is still settling from a
     *   movement. A thumb pose produced by a *closing* hand is then ignored,
     *   while a deliberate, held thumb-up still fires.
     */
    fun process(
        pose: Pose,
        handDetected: Boolean,
        timestampMs: Long,
        fistLike: Boolean = pose == Pose.FIST,
        suppressPoseExecution: Boolean = false,
    ): TransitionResult {
        currentFistLike = fistLike
        currentSuppressExecution = suppressPoseExecution
        handCurrentlyDetected = handDetected
        val previousState = currentState

        if (handDetected) {
            lastHandDetectedTimeMs = timestampMs
        }

        when (currentState) {
            GestureEngineState.DISARMED -> processDisarmed(pose, handDetected)
            GestureEngineState.ARMING -> processArming(pose, handDetected, timestampMs)
            GestureEngineState.ARMED -> processArmed(pose, handDetected, timestampMs)
            GestureEngineState.EXECUTING -> processExecuting()
            GestureEngineState.COOLDOWN -> processCooldown(timestampMs)
        }

        val stateChanged = previousState != currentState
        if (stateChanged) {
            stateEntryTimeMs = timestampMs
        }

        // Determine if this frame should trigger gesture execution
        val shouldExecute = currentState == GestureEngineState.EXECUTING &&
            previousState == GestureEngineState.ARMED

        return TransitionResult(
            stateChanged = stateChanged,
            previousState = previousState,
            newState = currentState,
            shouldExecute = shouldExecute,
        )
    }

    /**
     * DISARMED: Waiting for open palm to start arming.
     */
    private fun processDisarmed(pose: Pose, handDetected: Boolean) {
        if (handDetected && pose == Pose.OPEN_PALM) {
            transitionTo(GestureEngineState.ARMING)
        }
    }

    /**
     * ARMING: Open palm must be held for the arming duration.
     * Shows progress to the user. If palm is lost, return to DISARMED.
     */
    private fun processArming(pose: Pose, handDetected: Boolean, timestampMs: Long) {
        val elapsed = timestampMs - stateEntryTimeMs
        armingProgress = (elapsed.toFloat() / config.armingDurationMs).coerceIn(0f, 1f)

        if (!handDetected || pose != Pose.OPEN_PALM) {
            // Palm lost during arming
            armingProgress = 0f
            transitionTo(GestureEngineState.DISARMED)
        } else if (elapsed >= config.armingDurationMs) {
            // Arming complete
            armingProgress = 1f
            transitionTo(GestureEngineState.ARMED)
        }
    }

    /**
     * ARMED: Ready to detect gestures.
     * - Trigger gesture execution on non-trivial poses
     * - Disarm on FIST held for 1s
     * - Auto-disarm on no hand timeout
     *
     * RAPID-FIRE HOLD PREVENTION (Bug #3 Fix):
     * If the current actionable pose matches [lastExecutedPose], execution is
     * skipped. The user must first return to a neutral pose (NONE or POINTING)
     * to clear [lastExecutedPose] before the same pose can trigger again.
     */
    private fun processArmed(pose: Pose, handDetected: Boolean, timestampMs: Long) {
        // Auto-disarm on no hand
        if (!handDetected) {
            val timeSinceHand = timestampMs - lastHandDetectedTimeMs
            if (timeSinceHand >= config.autoDisarmTimeoutMs) {
                resetFistTracking()
                transitionTo(GestureEngineState.DISARMED)
                return
            }
        }

        // FIST held for disarm. Fix A-12: the hold is driven by "all four fingers
        // curled" rather than the exact FIST pose, because a closing hand passes
        // through poses that are not literally FIST and used to reset this timer
        // every frame — so the 1s disarm never completed.
        if (pose == Pose.FIST || currentFistLike) {
            if (!fistWasHeld) {
                fistHoldStartMs = timestampMs
                fistWasHeld = true
            } else if (timestampMs - fistHoldStartMs >= config.fistDisarmDurationMs) {
                resetFistTracking()
                lastExecutedPose = Pose.NONE
                transitionTo(GestureEngineState.DISARMED)
                return
            }
        } else {
            resetFistTracking()
        }

        // Fix A-5: clear the rapid-fire lock whenever the hand returns to a
        // neutral pose. OPEN_PALM is the natural neutral while ARMED (it is also
        // the arming pose and is never actionable), so returning to an open palm
        // must re-arm the previous pose. Previously only NONE/POINTING cleared
        // it, so "victory → open palm → victory" fired once and then nothing:
        // users had to make a random shape between repeats, and raising the
        // volume a few steps was painful.
        if (pose == Pose.NONE || pose == Pose.POINTING || pose == Pose.OPEN_PALM ||
            (currentFistLike && pose != Pose.THUMB_UP && pose != Pose.THUMB_DOWN)
        ) {
            if (lastExecutedPose != Pose.NONE) {
                lastExecutedPose = Pose.NONE
            }
        }

        // Execute gesture on any actionable pose (not NONE, OPEN_PALM, FIST, PINCH, or POINTING)
        // PINCH has its own lifecycle (START/MOVE/END) managed by GestureEngine.processPinch()
        // POINTING is a neutral preparatory pose that maps to NONE action
        //
        // Bug #3 Fix: Skip execution if this pose is the same as the one that just
        // fired (i.e., the user is holding the pose across the COOLDOWN boundary).
        // The lock is cleared above when the user returns to a neutral pose.
        // Fix A-12: a thumb pose produced by a hand that is still moving (i.e.
        // the user is closing it to disarm) must not change the volume. The
        // disarm hold above keeps running, so the closing hand still powers the
        // feature off — which is what the user meant to do.
        //
        // Round 10 (hardening): suppression now applies to EVERY actionable
        // pose, not just thumbs. The engine sets it while tracking is in
        // low-confidence mode — a muted gesture must not silently consume the
        // one-shot lastExecutedPose latch (it would then never fire after
        // tracking recovered).
        if (!currentSuppressExecution &&
            pose != Pose.NONE && pose != Pose.OPEN_PALM && pose != Pose.FIST &&
            pose != Pose.PINCH && pose != Pose.POINTING &&
            pose != lastExecutedPose
        ) {
            // NOTE: deliberately does NOT call resetFistTracking(). Executing a
            // pose used to restart the fist-disarm hold, so a hand that was
            // closing (and momentarily read THUMB_UP) pushed the disarm further
            // and further away — "fist doesn't turn it off". The hold now only
            // resets when the hand genuinely stops being fist-like.
            lastExecutedPose = pose
            transitionTo(GestureEngineState.EXECUTING)
        }
    }

    /**
     * EXECUTING: A gesture was just triggered.
     * Immediately transition to COOLDOWN.
     */
    private fun processExecuting() {
        transitionTo(GestureEngineState.COOLDOWN)
    }

    /**
     * COOLDOWN: Brief pause after gesture execution.
     * After cooldown duration, return to ARMED (if hand still present)
     * or DISARMED (if hand lost).
     */
    private fun processCooldown(timestampMs: Long) {
        val elapsed = timestampMs - stateEntryTimeMs
        if (elapsed >= config.cooldownDurationMs) {
            if (handCurrentlyDetected) {
                transitionTo(GestureEngineState.ARMED)
            } else {
                transitionTo(GestureEngineState.DISARMED)
            }
        }
    }

    private fun transitionTo(state: GestureEngineState) {
        currentState = state
        if (state == GestureEngineState.DISARMED) {
            armingProgress = 0f
        }
    }

    private fun resetFistTracking() {
        fistWasHeld = false
        fistHoldStartMs = 0L
    }

    /** Resets the state machine to DISARMED. */
    fun reset() {
        currentState = GestureEngineState.DISARMED
        stateEntryTimeMs = 0L
        lastHandDetectedTimeMs = 0L
        handCurrentlyDetected = false
        fistHoldStartMs = 0L
        fistWasHeld = false
        currentFistLike = false
        currentSuppressExecution = false
        lastExecutedPose = Pose.NONE
        armingProgress = 0f
    }

    /**
     * H-06 Fix: Update the config (e.g., sensitivity change) without recreating the state machine.
     * This preserves the current state and timing, avoiding disruption of in-progress gestures.
     */
    fun updateConfig(newConfig: GestureEngineConfig) {
        this.config = newConfig
    }
}
