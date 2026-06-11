package com.aircontrol.gesture.model

/**
 * States of the gesture engine's state machine.
 *
 * Lifecycle:
 *   DISARMED → ARMING (open palm detected, holding for 600ms) → ARMED →
 *   EXECUTING (gesture fired) → COOLDOWN (700ms) → ARMED
 *
 * Auto-disarm after 10s of no hand, or via FIST held for 1s.
 * Every transition is emitted to the UI for overlay rendering.
 */
enum class GestureEngineState {
    DISARMED,
    ARMING,
    ARMED,
    EXECUTING,
    COOLDOWN,
}
