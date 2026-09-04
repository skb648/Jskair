package com.aircontrol.gesture.intent

import com.aircontrol.gesture.model.GestureEngineState
import com.aircontrol.gesture.model.GestureEvent
import com.aircontrol.gesture.model.PinchPhase

/**
 * Deterministic arbitration layer between recognition signals and executable intent.
 *
 * Safety rule: when signals overlap or confidence is insufficient, prefer NO_ACTION
 * over an unintended system action.
 */
class IntentEngine(
    private val minimumConfidence: Float = 0.55f,
) {
    init {
        require(minimumConfidence in 0f..1f)
    }

    fun classify(
        event: GestureEvent,
        engineState: GestureEngineState,
        confidence: Float = 1f,
    ): GestureIntent {
        if (engineState == GestureEngineState.DISARMED) {
            return noAction(event.timestampMs, "disarmed", confidence)
        }
        if (confidence < minimumConfidence) {
            return noAction(event.timestampMs, "low-confidence", confidence)
        }

        return when (event) {
            is GestureEvent.CursorMoved -> {
                if (event.isSilent) {
                    noAction(event.timestampMs, "arming", confidence)
                } else {
                    GestureIntent(
                        type = IntentType.MOVE,
                        x = event.x.coerceIn(0f, 1f),
                        y = event.y.coerceIn(0f, 1f),
                        confidence = confidence,
                        source = "palm",
                        timestampMs = event.timestampMs,
                    )
                }
            }
            is GestureEvent.Pinch -> when (event.phase) {
                PinchPhase.START -> GestureIntent(
                    type = IntentType.CLICK,
                    x = event.anchoredX.coerceIn(0f, 1f),
                    y = event.anchoredY.coerceIn(0f, 1f),
                    confidence = confidence,
                    source = "pinch-start",
                    timestampMs = event.timestampMs,
                )
                PinchPhase.MOVE -> GestureIntent(
                    type = IntentType.DRAG,
                    x = event.x.coerceIn(0f, 1f),
                    y = event.y.coerceIn(0f, 1f),
                    confidence = confidence,
                    source = "pinch-move",
                    timestampMs = event.timestampMs,
                )
                PinchPhase.END -> noAction(event.timestampMs, "pinch-end", confidence)
            }
            is GestureEvent.Swipe -> GestureIntent(
                type = IntentType.SWIPE,
                confidence = confidence,
                source = "open-hand-swipe",
                timestampMs = event.timestampMs,
            )
            is GestureEvent.PoseTriggered -> noAction(event.timestampMs, "pose-awaiting-action-map", confidence)
            is GestureEvent.CustomGestureTriggered -> noAction(event.timestampMs, "custom-awaiting-action-map", confidence)
            is GestureEvent.PalmHome -> noAction(event.timestampMs, "palm-home-awaiting-policy", confidence)
            is GestureEvent.Armed -> noAction(event.timestampMs, "armed", confidence)
            is GestureEvent.Disarmed -> noAction(event.timestampMs, "disarmed", confidence)
        }
    }

    private fun noAction(timestampMs: Long, source: String, confidence: Float) = GestureIntent(
        type = IntentType.NO_ACTION,
        confidence = confidence,
        source = source,
        timestampMs = timestampMs,
    )
}
