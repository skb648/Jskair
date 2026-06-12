package com.aircontrol.gesture.statemachine

import com.aircontrol.gesture.config.GestureEngineConfig
import com.aircontrol.gesture.model.GestureEngineState
import com.aircontrol.gesture.model.Pose

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
 * Every state transition is tracked and can be observed for UI rendering.
 */
class GestureStateMachine(private val config: GestureEngineConfig) {

    /** Current state of the machine. */
    var currentState: GestureEngineState = GestureEngineState.DISARMED
        private set

    /** Timestamp when the current state was entered. */
    private var stateEntryTimeMs: Long = 0L

    /** Timestamp of the last hand detection. */
    private var lastHandDetectedTimeMs: Long = 0L

    /** Whether a hand was detected in the current frame. */
    private var handCurrentlyDetected: Boolean = false

    /** How long the FIST pose has been held continuously. */
    private var fistHoldStartMs: Long = 0L
    private var fistWasHeld: Boolean = false

    /** Arming progress: 0.0 to 1.0, where 1.0 means fully armed. */
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
     */
    fun process(pose: Pose, handDetected: Boolean, timestampMs: Long): TransitionResult {
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

        // FIST held for disarm
        if (pose == Pose.FIST) {
            if (!fistWasHeld) {
                fistHoldStartMs = timestampMs
                fistWasHeld = true
            } else if (timestampMs - fistHoldStartMs >= config.fistDisarmDurationMs) {
                resetFistTracking()
                transitionTo(GestureEngineState.DISARMED)
                return
            }
        } else {
            resetFistTracking()
        }

        // Execute gesture on any actionable pose (not NONE, OPEN_PALM, or FIST)
        if (pose != Pose.NONE && pose != Pose.OPEN_PALM && pose != Pose.FIST) {
            resetFistTracking()
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
        if (state != GestureEngineState.ARMING) {
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
        armingProgress = 0f
    }
}
