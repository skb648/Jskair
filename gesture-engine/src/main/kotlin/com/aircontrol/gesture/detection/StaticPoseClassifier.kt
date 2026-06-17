package com.aircontrol.gesture.detection

import com.aircontrol.gesture.config.GestureEngineConfig
import com.aircontrol.gesture.model.FingerExtensionState
import com.aircontrol.gesture.model.HandInput
import com.aircontrol.gesture.model.Landmark3D
import com.aircontrol.gesture.model.LandmarkIndex
import com.aircontrol.gesture.model.LandmarkTemplate
import com.aircontrol.gesture.model.Pose
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
 * 3. OPEN_PALM — at least 4 of 5 digits extended (tolerates curled pinky)
 * 4. POINTING — only index extended (and no thumb)
 * 5. VICTORY — index and middle extended (peace/V sign)
 * 6. THREE_FINGERS — index + middle + ring extended, pinky not
 * 7. FOUR_FINGERS — all four fingers extended (index, middle, ring, pinky)
 * 8. THUMB_UP — only thumb extended, thumb tip above thumb MCP
 * 9. THUMB_DOWN — only thumb extended, thumb tip below thumb MCP
 */
class StaticPoseClassifier(private val config: GestureEngineConfig) {

    private val fingerDetector = FingerExtensionDetector(config)

    /**
     * Bug #13 Fix: Effective number of debounce frames to require for pose
     * confirmation. Defaults to [GestureEngineConfig.poseDebounceFrames] but
     * can be overridden per-frame by the engine when low-confidence tracking
     * is detected (e.g., near camera boundaries). When set higher than the
     * default, the classifier requires more consecutive agreeing frames before
     * confirming a pose, suppressing erratic pose flips from noisy landmarks.
     *
     * The [poseHistory] ArrayDeque is pre-sized to the config default + 1, but
     * since ArrayDeque grows dynamically, a larger effective value works
     * correctly — it just holds more entries before trimming.
     */
    @Volatile
    var effectiveDebounceFrames: Int = config.poseDebounceFrames
        set(value) {
            if (value > 0) field = value
        }

    /** Recent pose history for debounce. */
    private val poseHistory = ArrayDeque<Pose>(config.poseDebounceFrames + 1)

    /** The last confirmed pose (after debounce). */
    var confirmedPose: Pose = Pose.NONE
        private set

    /**
     * Bug: Custom Gestures Not Triggering Fix — User-defined landmark templates
     * that are matched against live [HandInput] frames after the default
     * hardcoded pose classification returns [Pose.NONE].
     *
     * Updated dynamically via [updateCustomTemplates] by the engine, which
     * receives the list from the app-layer SettingsRepository. This decouples
     * the pure-Kotlin classifier from the Android DataStore layer.
     *
     * When non-empty, [matchCustomTemplate] is called by the engine after each
     * default classification. If a template matches within
     * [LandmarkTemplate.MATCH_TOLERANCE], the engine emits a
     * [com.aircontrol.gesture.model.GestureEvent.CustomGestureTriggered] event
     * instead of (or in addition to) a standard PoseTriggered event.
     */
    @Volatile
    private var customTemplates: List<LandmarkTemplate> = emptyList()

    /**
     * Bug: Custom Gestures Not Triggering Fix — Updates the dynamic list of
     * user-defined landmark templates. Called by the engine when the
     * SettingsRepository emits a new custom-gestures list.
     *
     * This is safe to call from any thread (the field is @Volatile). The
     * template list is replaced atomically — no concurrent modification risk.
     *
     * @param templates The new list of templates. Pass an empty list to clear
     *   all custom gestures (disables custom gesture matching).
     */
    fun updateCustomTemplates(templates: List<LandmarkTemplate>) {
        customTemplates = templates
    }

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

        // 3. OPEN_PALM — at least 4 of 5 digits extended (tolerates curled pinky)
        if (fingerState.totalExtendedCount >= 4) return Pose.OPEN_PALM

        // 4. POINTING — index only extended (thumb may or may not be, but
        //    only index among the four fingers)
        if (fingerState.index && !fingerState.middle && !fingerState.ring && !fingerState.pinky) {
            return Pose.POINTING
        }

        // 5. VICTORY — index + middle extended, ring and pinky not
        if (fingerState.index && fingerState.middle && !fingerState.ring && !fingerState.pinky) {
            return Pose.VICTORY
        }

        // 6. THREE_FINGERS — index + middle + ring extended, pinky not
        if (fingerState.index && fingerState.middle && fingerState.ring && !fingerState.pinky) {
            return Pose.THREE_FINGERS
        }

        // 7. FOUR_FINGERS — all four fingers extended (index, middle, ring, pinky)
        if (fingerState.index && fingerState.middle && fingerState.ring && fingerState.pinky) {
            return Pose.FOUR_FINGERS
        }

        // 8. THUMB_UP — only thumb extended, thumb tip is above MCP (lower y = higher)
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
     * Bug: Custom Gestures Not Triggering Fix — Mathematical landmark template
     * matching algorithm.
     *
     * Compares the incoming live [HandInput] landmarks against all user-defined
     * [LandmarkTemplate]s. If any template's normalized inter-landmark distances
     * match the live distances within [LandmarkTemplate.MATCH_TOLERANCE], that
     * template is returned.
     *
     * Algorithm:
     * 1. Compute the hand size (wrist-to-middle-MCP distance) from the live
     *    frame. This is the normalization factor.
     * 2. For each curated landmark pair in [LandmarkTemplate.TEMPLATE_LANDMARK_PAIRS],
     *    compute the live 2D distance and normalize by hand size.
     * 3. For each template, compute the total Euclidean error: the sum of
     *    absolute differences between the template's stored normalized distances
     *    and the live normalized distances.
     * 4. Return the template with the lowest total error, IF that error is
     *    below [LandmarkTemplate.MATCH_TOLERANCE]. Otherwise return null.
     *
     * Confidence gating:
     * - If [HandInput.confidence] is below [MIN_TEMPLATE_MATCH_CONFIDENCE],
     *   return null immediately — low-confidence frames produce erratic
     *   landmarks that would cause false matches.
     * - If hand size is degenerate (below EPSILON), return null — can't
     *   normalize.
     *
     * @param input The live hand frame. Must have 21 landmarks.
     * @return The best-matching [LandmarkTemplate], or null if no template
     *   matches within tolerance.
     */
    fun matchCustomTemplate(input: HandInput): LandmarkTemplate? {
        // Confidence gate — don't match on noisy frames
        if (input.confidence < MIN_TEMPLATE_MATCH_CONFIDENCE) return null
        if (customTemplates.isEmpty()) return null
        if (input.landmarks.size < HandInput.LANDMARK_COUNT) return null

        val landmarks = input.landmarks
        val wrist = landmarks[LandmarkIndex.WRIST]
        val middleMcp = landmarks[LandmarkIndex.MIDDLE_MCP]
        val handSize = distance2D(wrist, middleMcp)
        if (handSize < EPSILON) return null

        // Compute live normalized distances for all template pairs
        val liveDistances = FloatArray(LandmarkTemplate.EXPECTED_DISTANCE_COUNT)
        for (i in LandmarkTemplate.TEMPLATE_LANDMARK_PAIRS.indices) {
            val (a, b) = LandmarkTemplate.TEMPLATE_LANDMARK_PAIRS[i]
            liveDistances[i] = distance2D(landmarks[a], landmarks[b]) / handSize
        }

        // Find the template with the lowest total error
        var bestTemplate: LandmarkTemplate? = null
        var bestError = Float.MAX_VALUE

        for (template in customTemplates) {
            if (template.normalizedDistances.size != LandmarkTemplate.EXPECTED_DISTANCE_COUNT) continue

            var totalError = 0f
            for (i in liveDistances.indices) {
                val diff = liveDistances[i] - template.normalizedDistances[i]
                totalError += kotlin.math.abs(diff)
            }

            if (totalError < bestError) {
                bestError = totalError
                bestTemplate = template
            }
        }

        // Only return if within tolerance
        return if (bestTemplate != null && bestError < LandmarkTemplate.MATCH_TOLERANCE) {
            bestTemplate
        } else {
            null
        }
    }

    companion object {
        private const val EPSILON = 1e-6f

        // Bug: Custom Gestures Not Triggering Fix — Minimum tracking confidence
        // required for landmark template matching. Below this, landmarks are too
        // erratic to reliably match against saved templates. 0.7 matches the
        // engine's low-confidence threshold (see GestureEngine.CONFIDENCE_THRESHOLD).
        private const val MIN_TEMPLATE_MATCH_CONFIDENCE = 0.7f
    }

    /**
     * Applies the N-frame debounce filter. A pose is only confirmed when
     * the same raw pose has been observed for [effectiveDebounceFrames]
     * consecutive frames. Any interruption resets the debounce counter.
     *
     * Bug #13 Fix: Uses [effectiveDebounceFrames] (which defaults to
     * [GestureEngineConfig.poseDebounceFrames] but can be raised by the engine
     * for low-confidence frames) instead of the static config value.
     */
    internal fun applyDebounce(rawPose: Pose): Pose {
        val debounceFrames = effectiveDebounceFrames
        poseHistory.addLast(rawPose)

        // Keep only the last N frames
        while (poseHistory.size > debounceFrames) {
            poseHistory.removeFirst()
        }

        // Check if all recent frames agree on the same pose
        if (poseHistory.size >= debounceFrames && poseHistory.all { it == rawPose }) {
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
}
