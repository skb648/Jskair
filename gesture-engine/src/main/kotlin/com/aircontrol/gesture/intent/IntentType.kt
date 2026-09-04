package com.aircontrol.gesture.intent

/** High-level user intent produced from low-level gesture signals. */
enum class IntentType {
    MOVE,
    CLICK,
    DRAG,
    SCROLL,
    SWIPE,
    NO_ACTION,
}
