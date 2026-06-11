package com.aircontrol.gesture.model

/**
 * Phase of a pinch gesture lifecycle.
 * START: thumb and index finger just came together (pinch formed)
 * MOVE: pinch is held and the hand is moving (normalized position updates)
 * END: fingers separated after a pinch
 */
enum class PinchPhase {
    START,
    MOVE,
    END,
}
