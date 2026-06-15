package com.aircontrol.gesture.detection

import com.aircontrol.gesture.config.GestureEngineConfig
import com.aircontrol.gesture.model.FingerExtensionState
import com.aircontrol.gesture.model.HandInput
import com.aircontrol.gesture.model.Landmark3D
import com.aircontrol.gesture.model.LandmarkIndex
import kotlin.math.sqrt

/**
 * Detects whether each finger is extended or curled based on landmark positions.
 *
 * For non-thumb fingers (index, middle, ring, pinky):
 *   A finger is extended when the tip-to-wrist distance is greater than
 *   the PIP-to-wrist distance multiplied by a threshold. This works because
 *   an extended finger's tip is farther from the wrist than its PIP joint,
 *   while a curled finger's tip is closer.
 *
 * For the thumb:
 *   The thumb uses angle-based detection because its lateral range of motion
 *   makes distance-based detection unreliable. We compute the angle at the
 *   IP joint (landmark 3) between the MCP (landmark 2) and TIP (landmark 4).
 *   An angle above the configured threshold indicates extension.
 */
class FingerExtensionDetector(private val config: GestureEngineConfig) {

    /**
     * Detects the extension state of all five fingers from a hand input frame.
     * Returns [FingerExtensionState.NONE] if the hand is not detected or
     * landmarks are incomplete.
     */
    fun detect(input: HandInput): FingerExtensionState {
        if (!input.isDetected) return FingerExtensionState()

        val landmarks = input.landmarks
        val wrist = landmarks[LandmarkIndex.WRIST]
        val threshold = config.scaledFingerExtensionThreshold()

        return FingerExtensionState(
            thumb = isThumbExtended(landmarks),
            index = isFingerExtended(
                tip = landmarks[LandmarkIndex.INDEX_TIP],
                pip = landmarks[LandmarkIndex.INDEX_PIP],
                wrist = wrist,
                threshold = threshold,
            ),
            middle = isFingerExtended(
                tip = landmarks[LandmarkIndex.MIDDLE_TIP],
                pip = landmarks[LandmarkIndex.MIDDLE_PIP],
                wrist = wrist,
                threshold = threshold,
            ),
            ring = isFingerExtended(
                tip = landmarks[LandmarkIndex.RING_TIP],
                pip = landmarks[LandmarkIndex.RING_PIP],
                wrist = wrist,
                threshold = threshold,
            ),
            pinky = isFingerExtended(
                tip = landmarks[LandmarkIndex.PINKY_TIP],
                pip = landmarks[LandmarkIndex.PINKY_PIP],
                wrist = wrist,
                threshold = threshold,
            ),
        )
    }

    /**
     * Determines if a non-thumb finger is extended.
     * Compares the distance from the fingertip to the wrist against
     * the distance from the PIP joint to the wrist, scaled by a threshold.
     *
     * @param tip The fingertip landmark
     * @param pip The PIP (proximal interphalangeal) joint landmark
     * @param wrist The wrist landmark (reference point)
     * @param threshold Distance ratio threshold (from config, sensitivity-scaled)
     * @return true if the finger is extended
     */
    internal fun isFingerExtended(
        tip: Landmark3D,
        pip: Landmark3D,
        wrist: Landmark3D,
        threshold: Float,
    ): Boolean {
        val tipToWrist = distance3D(tip, wrist)
        val pipToWrist = distance3D(pip, wrist)
        // Avoid division by zero for degenerate cases
        if (pipToWrist < EPSILON) return false
        return tipToWrist > pipToWrist * threshold
    }

    /**
     * Determines if the thumb is extended using angle-based detection.
     * Computes the angle at the IP joint between the MCP and TIP vectors.
     * A straight thumb (extended) produces a large angle (close to 180°),
     * while a bent thumb produces a small angle.
     *
     * The angle threshold is configurable and sensitivity-scaled.
     */
    internal fun isThumbExtended(landmarks: List<Landmark3D>): Boolean {
        val mcp = landmarks[LandmarkIndex.THUMB_MCP]
        val ip = landmarks[LandmarkIndex.THUMB_IP]
        val tip = landmarks[LandmarkIndex.THUMB_TIP]

        val angleDeg = angleAtVertex(ip, mcp, tip)
        return angleDeg > config.scaledThumbExtensionAngleDeg()
    }

    /**
     * Computes the Euclidean distance between two 3D points.
     */
    internal fun distance3D(a: Landmark3D, b: Landmark3D): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * Computes the angle in degrees at vertex B in the triangle A-B-C.
     * Uses the dot product formula: cos(angle) = (BA · BC) / (|BA| * |BC|)
     *
     * @param vertex The point at which the angle is measured (B)
     * @param a First endpoint (A)
     * @param c Second endpoint (C)
     * @return Angle in degrees [0, 180]
     */
    internal fun angleAtVertex(vertex: Landmark3D, a: Landmark3D, c: Landmark3D): Float {
        val baX = a.x - vertex.x
        val baY = a.y - vertex.y
        val baZ = a.z - vertex.z

        val bcX = c.x - vertex.x
        val bcY = c.y - vertex.y
        val bcZ = c.z - vertex.z

        val dot = baX * bcX + baY * bcY + baZ * bcZ
        val magBA = sqrt(baX * baX + baY * baY + baZ * baZ)
        val magBC = sqrt(bcX * bcX + bcY * bcY + bcZ * bcZ)

        if (magBA < EPSILON || magBC < EPSILON) return 0f

        val cosAngle = (dot / (magBA * magBC)).coerceIn(-1f, 1f)
        return acosToDeg(cosAngle).toFloat()
    }

    /**
     * Convert a cosine value to degrees using the standard library.
     */
    private fun acosToDeg(x: Float): Double {
        return kotlin.math.acos(x.coerceIn(-1.0, 1.0)) * 180.0 / kotlin.math.PI
    }

    companion object {
        private const val EPSILON = 1e-6f
    }
}
