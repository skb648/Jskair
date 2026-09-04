package com.aircontrol.gesture.intent

import com.aircontrol.gesture.model.GestureEngineState
import com.aircontrol.gesture.model.GestureEvent
import com.aircontrol.gesture.model.PinchPhase

/**
 * Deterministic arbitration between low-level recognition and executable intent.
 * Safety-first: ambiguous or low-confidence input resolves to NO_ACTION.
 */
class IntentEngine(
    private val minimumConfidence: Float = 0.55f,
    private val doubleClickWindowMs: Long = SafetyPolicy.DOUBLE_CLICK_WINDOW_MS,
) {
    init {
        require(minimumConfidence in 0f..1f)
        require(doubleClickWindowMs > 0L)
    }

    private var lastClickTimestampMs = Long.MIN_VALUE
    private var lastClickX = Float.NaN
    private var lastClickY = Float.NaN

    fun reset() {
        lastClickTimestampMs = Long.MIN_VALUE
        lastClickX = Float.NaN
        lastClickY = Float.NaN
    }

    fun classify(
        event: GestureEvent,
        engineState: GestureEngineState,
        confidence: Float = 1f,
        stationaryMs: Long = SafetyPolicy.CLICK_SETTLE_MS,
        movementSincePinchStart: Float = 0f,
    ): GestureIntent {
        if (!isInteractiveState(engineState)) return noAction(event.timestampMs, "disarmed", confidence)
        if (confidence < minimumConfidence) return noAction(event.timestampMs, "low-confidence", confidence)

        return when (event) {
            is GestureEvent.CursorMoved -> {
                if (event.isSilent) noAction(event.timestampMs, "arming", confidence)
                else GestureIntent(
                    type = IntentType.MOVE,
                    x = event.x.coerceIn(0f, 1f),
                    y = event.y.coerceIn(0f, 1f),
                    confidence = confidence,
                    source = "palm-move",
                    timestampMs = event.timestampMs,
                )
            }

            is GestureEvent.Pinch -> when (event.phase) {
                PinchPhase.START -> {
                    val x = event.anchoredX.coerceIn(0f, 1f)
                    val y = event.anchoredY.coerceIn(0f, 1f)
                    val now = event.timestampMs
                    val isDouble = SafetyPolicy.isDoubleClickCandidate(
                        confidence = confidence,
                        nowMs = now,
                        lastClickMs = lastClickTimestampMs,
                        distanceSquared = distanceSquared(x, y, lastClickX, lastClickY),
                    )
                    val clickSafe = SafetyPolicy.isClickSafe(
                        confidence = confidence,
                        stationaryMs = stationaryMs,
                        travel = movementSincePinchStart,
                        nowMs = now,
                        lastClickMs = if (isDouble) Long.MIN_VALUE else lastClickTimestampMs,
                    )
                    if (!clickSafe) {
                        noAction(now, "click-safety-gate", confidence)
                    } else {
                        lastClickTimestampMs = now
                        lastClickX = x
                        lastClickY = y
                        GestureIntent(
                            type = IntentType.CLICK,
                            x = x,
                            y = y,
                            confidence = confidence,
                            source = if (isDouble) "pinch-double-click" else "pinch-click",
                            timestampMs = now,
                        )
                    }
                }
                PinchPhase.MOVE -> GestureIntent(
                    type = IntentType.DRAG,
                    x = event.x.coerceIn(0f, 1f),
                    y = event.y.coerceIn(0f, 1f),
                    confidence = confidence,
                    source = "pinch-drag",
                    timestampMs = event.timestampMs,
                )
                PinchPhase.END -> noAction(event.timestampMs, "pinch-end", confidence)
            }

            is GestureEvent.Swipe -> GestureIntent(
                type = IntentType.SWIPE,
                confidence = confidence,
                source = "swipe",
                timestampMs = event.timestampMs,
            )

            is GestureEvent.PoseTriggered -> noAction(event.timestampMs, "pose-awaiting-explicit-action", confidence)
            is GestureEvent.CustomGestureTriggered -> {
                if (confidence >= SafetyPolicy.MIN_CUSTOM_GESTURE_CONFIDENCE) {
                    noAction(event.timestampMs, "custom-awaiting-explicit-action", confidence)
                } else {
                    noAction(event.timestampMs, "custom-confidence-gate", confidence)
                }
            }
            is GestureEvent.PalmHome -> noAction(event.timestampMs, "palm-home-awaiting-policy", confidence)
            is GestureEvent.Armed -> noAction(event.timestampMs, "armed", confidence)
            is GestureEvent.Disarmed -> noAction(event.timestampMs, "disarmed", confidence)
        }
    }

    private fun isInteractiveState(state: GestureEngineState): Boolean =
        state == GestureEngineState.ARMED ||
            state == GestureEngineState.EXECUTING ||
            state == GestureEngineState.COOLDOWN

    private fun noAction(timestampMs: Long, source: String, confidence: Float) = GestureIntent(
        type = IntentType.NO_ACTION,
        confidence = confidence,
        source = source,
        timestampMs = timestampMs,
    )

    private fun distanceSquared(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        if (x2.isNaN() || y2.isNaN()) return Float.POSITIVE_INFINITY
        val dx = x1 - x2
        val dy = y1 - y2
        return dx * dx + dy * dy
    }
}
