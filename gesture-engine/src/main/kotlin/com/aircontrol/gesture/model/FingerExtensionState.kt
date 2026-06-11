package com.aircontrol.gesture.model

/**
 * Extension state for each finger, used by the pose classifier.
 * @property thumb Whether the thumb is extended (angle-based detection)
 * @property index Whether the index finger is extended
 * @property middle Whether the middle finger is extended
 * @property ring Whether the ring finger is extended
 * @property pinky Whether the pinky finger is extended
 */
data class FingerExtensionState(
    val thumb: Boolean = false,
    val index: Boolean = false,
    val middle: Boolean = false,
    val ring: Boolean = false,
    val pinky: Boolean = false,
) {
    /** Count of extended fingers (excluding thumb). */
    val extendedFingerCount: Int
        get() = listOf(index, middle, ring, pinky).count { it }

    /** Count of all extended digits including thumb. */
    val totalExtendedCount: Int
        get() = listOf(thumb, index, middle, ring, pinky).count { it }
}
