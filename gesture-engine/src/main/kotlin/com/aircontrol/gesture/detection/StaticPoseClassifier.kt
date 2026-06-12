package com.aircontrol.gesture.detection

import com.aircontrol.gesture.config.GestureEngineConfig
import com.aircontrol.gesture.model.FingerExtensionState
import com.aircontrol.gesture.model.HandInput
import com.aircontrol.gesture.model.LandmarkIndex
import com.aircontrol.gesture.model.Pose
import com.aircontrol.gesture.model.Landmark3D
import kotlin.math.sqrt

/**
 * Classifies static hand poses from finger extension states.
 *
 * A pose must be confirmed for [config.poseDebounceFrames] consecutive
 * frames before being emitted. This prevents flicker between poses
 * during transitions and noisy landmark detection.
 *
 * Poses are classified in priority order:
 * 1. PINCH — thumb and index tips close together (distance-based, hand-size scaled)
 * 2. FIST — no fingers extended
 * 3. OPEN_PALM — all five digits extended
 * 4. POINTING — only index extended (and no thumb)
 * 5. VICTORY — index and middle extended (peace/V sign)
 * 6. THUMB_UP — only thumb extended, thumb tip above thumb MCP
 * 7. THUMB_DOWN — only thumb extended, thumb tip below thumb MCP
 */
class StaticPoseClassifier(private val config: GestureEngineConfig) {

    private val fingerDetector = FingerExtensionDetector(config)

    /** Recent pose history for debounce. */
    private val poseHistory = ArrayDeque<Pose>(config.poseDebounceFrames + 1)

    /** The last confirmed pose (after debounce). */
    var confirmedPose: Pose = Pose.NONE
        private set

    /**
     * Processes a hand input frame and returns the current confirmed pose
     * after applying the debounce filter. Returns [Pose.NONE] if the hand
     * is not detected or no pose has been confirmed.
     */
    fun classify(input: HandInput): Pose {
        if (!input.isDetected) {
            poseHistory.clear()
            confirmedPose = Pose.NONE
            return Pose.NONE
        }

        val fingerState = fingerDetector.detect(input)
        val rawPose = classifyRaw(input, fingerState)
        return applyDebounce(rawPose)
    }

    /**
     * Returns the current finger extension state without classifying a pose.
     * Useful for external consumers that need the raw finger data.
     */
    fun getFingerState(input: HandInput): FingerExtensionState {
        return fingerDetector.detect(input)
    }

    /**
     * Raw pose classification from finger extension state and landmark positions.
     * Poses are checked in priority order — earlier checks take precedence.
     */
    internal fun classifyRaw(input: HandInput, fingerState: FingerExtensionState): Pose {
        val landmarks = input.landmarks

        // 1. PINCH — thumb-index distance < dynamic threshold (scaled by hand size)
        if (isPinch(landmarks)) return Pose.PINCH

        // 2. FIST — no digits extended at all
        if (fingerState.totalExtendedCount == 0) return Pose.FIST

        // 3. OPEN_PALM — all five digits extended
        if (fingerState.totalExtendedCount == 5) return Pose.OPEN_PALM

        // 4. POINTING — index only extended (thumb may or may not be, but
        //    only index among the four fingers)
        if (fingerState.index && !fingerState.middle && !fingerState.ring && !fingerState.pinky) {
            return Pose.POINTING
        }

        // 5. VICTORY — index + middle extended, ring and pinky not
        if (fingerState.index && fingerState.middle && !fingerState.ring && !fingerState.pinky) {
            return Pose.VICTORY
        }

        // 6. THUMB_UP — only thumb extended, thumb tip is above MCP (lower y = higher)
        if (fingerState.thumb && fingerState.extendedFingerCount == 0) {
            val thumbTip = landmarks[LandmarkIndex.THUMB_TIP]
            val thumbMcp = landmarks[LandmarkIndex.THUMB_MCP]
            return if (thumbTip.y < thumbMcp.y) Pose.THUMB_UP else Pose.THUMB_DOWN
        }

        return Pose.NONE
    }

    /**
     * Detects a pinch gesture by measuring the distance between thumb tip
     * and index tip, scaled by hand size (wrist-to-middle-MCP distance).
     * This normalization ensures pinch works at any distance from the camera.
     */
    internal fun isPinch(landmarks: List<Landmark3D>): Boolean {
        val thumbTip = landmarks[LandmarkIndex.THUMB_TIP]
        val indexTip = landmarks[LandmarkIndex.INDEX_TIP]
        val wrist = landmarks[LandmarkIndex.WRIST]
        val middleMcp = landmarks[LandmarkIndex.MIDDLE_MCP]

        val pinchDistance = distance2D(thumbTip, indexTip)
        val handSize = distance2D(wrist, middleMcp)

        if (handSize < EPSILON) return false

        val ratio = pinchDistance / handSize
        return ratio < config.scaledPinchDistanceRatio()
    }

    /**
     * Applies the N-frame debounce filter. A pose is only confirmed when
     * the same raw pose has been observed for [config.poseDebounceFrames]
     * consecutive frames. Any interruption resets the debounce counter.
     */
    internal fun applyDebounce(rawPose: Pose): Pose {
        poseHistory.addLast(rawPose)

        // Keep only the last N frames
        while (poseHistory.size > config.poseDebounceFrames) {
            poseHistory.removeFirst()
        }

        // Check if all recent frames agree on the same pose
        if (poseHistory.size >= config.poseDebounceFrames && poseHistory.all { it == rawPose }) {
            confirmedPose = rawPose
            return rawPose
        }

        // Not yet confirmed — return the previously confirmed pose
        return confirmedPose
    }

    /** Resets the classifier state. */
    fun reset() {
        poseHistory.clear()
        confirmedPose = Pose.NONE
    }

    internal fun distance2D(a: Landmark3D, b: Landmark3D): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    companion object {
        private const val EPSILON = 1e-6f
    }
}
