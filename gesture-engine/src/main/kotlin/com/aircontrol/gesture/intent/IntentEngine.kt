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
    private val doubleClickWindowMs: Long = 350L,
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
                    val isDouble = lastClickTimestampMs != Long.MIN_VALUE &&
                        event.timestampMs - lastClickTimestampMs in 1L..doubleClickWindowMs &&
                        distanceSquared(x, y, lastClickX, lastClickY) <= DOUBLE_CLICK_MAX_DISTANCE_SQ
                    lastClickTimestampMs = event.timestampMs
                    lastClickX = x
                    lastClickY = y
                    GestureIntent(
                        type = if (isDouble) IntentType.CLICK else IntentType.CLICK,
                        x = x,
                        y = y,
                        confidence = confidence,
                        source = if (isDouble) "pinch-double-click" else "pinch-click",
                        timestampMs = event.timestampMs,
                    )
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
            is GestureEvent.CustomGestureTriggered -> noAction(event.timestampMs, "custom-awaiting-explicit-action", confidence)
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

    companion object {
        private const val DOUBLE_CLICK_MAX_DISTANCE_SQ = 0.0025f
    }
}
