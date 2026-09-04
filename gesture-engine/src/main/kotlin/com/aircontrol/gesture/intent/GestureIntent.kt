package com.aircontrol.gesture.intent

/** Immutable decision emitted by the intent layer. */
data class GestureIntent(
    val type: IntentType,
    val x: Float? = null,
    val y: Float? = null,
    val confidence: Float = 1f,
    val source: String = "",
    val timestampMs: Long = 0L,
) {
    init {
        require(confidence in 0f..1f) { "confidence must be in [0,1]" }
        if (type == IntentType.MOVE || type == IntentType.CLICK || type == IntentType.DRAG) {
            require(x != null && y != null) { "$type requires coordinates" }
        }
    }
}
