package com.aircontrol.gesture.model

/**
 * Represents a single 3D landmark point from hand detection.
 * Coordinates are normalized [0,1] relative to the image dimensions,
 * with z representing depth relative to the wrist.
 *
 * This is a pure-Kotlin mirror of the tracking module's Landmark3D,
 * kept separate to maintain zero Android dependency in this module.
 */
data class Landmark3D(
    val x: Float,
    val y: Float,
    val z: Float,
)

/**
 * Standard MediaPipe hand landmark indices for reference.
 * Used internally by the gesture engine to index into the 21-landmark list.
 */
object LandmarkIndex {
    const val WRIST = 0
    const val THUMB_CMC = 1
    const val THUMB_MCP = 2
    const val THUMB_IP = 3
    const val THUMB_TIP = 4
    const val INDEX_MCP = 5
    const val INDEX_PIP = 6
    const val INDEX_DIP = 7
    const val INDEX_TIP = 8
    const val MIDDLE_MCP = 9
    const val MIDDLE_PIP = 10
    const val MIDDLE_DIP = 11
    const val MIDDLE_TIP = 12
    const val RING_MCP = 13
    const val RING_PIP = 14
    const val RING_DIP = 15
    const val RING_TIP = 16
    const val PINKY_MCP = 17
    const val PINKY_PIP = 18
    const val PINKY_DIP = 19
    const val PINKY_TIP = 20

    val FINGER_TIPS = setOf(THUMB_TIP, INDEX_TIP, MIDDLE_TIP, RING_TIP, PINKY_TIP)
    val FINGER_PIPS = setOf(THUMB_IP, INDEX_PIP, MIDDLE_PIP, RING_PIP, PINKY_PIP)
}
