package com.aircontrol.gesture.detection

import com.aircontrol.gesture.config.GestureEngineConfig
import com.aircontrol.gesture.model.FingerExtensionState
import com.aircontrol.gesture.model.HandInput
import com.aircontrol.gesture.model.Landmark3D
import com.aircontrol.gesture.model.LandmarkIndex
import com.aircontrol.gesture.model.LandmarkTemplate
import com.aircontrol.gesture.model.Pose
import kotlin.concurrent.Volatile
import kotlin.math.sqrt

/**
 * Classifies a static hand pose from landmarks + finger extension state, with a
 * time-based debounce so the confirmation delay is the same at 5fps and at 30fps.
 *
 * Priority order (Fix A-13, "a fist may be read as PINCH"):
 *   1. FIST   — all four fingers curled (a fist also puts the thumb next to the
 *               curled index tip, which used to be classified as a pinch, so the
 *               1s disarm never counted and "fist doesn't turn it off").
 *   2. PINCH  — thumb and index tips together *while the index is extended*.
 *   3. OPEN_PALM, POINTING, VICTORY, THREE/FOUR_FINGERS
 *   4. THUMB_UP / THUMB_DOWN — only when the hand is nearly still (Fix A-12).
 *
 * Pinch uses exactly one threshold for the whole app — [GestureEngineConfig
 * .scaledPinchDistanceRatio] — which is also what the click FSM in
 * [com.aircontrol.gesture.GestureEngine] reads (Fix A-6: the pose classifier
 * used ~0.40 while the click needed 0.05, so a half-closed hand blocked other
 * poses yet still did not click).
 */
class StaticPoseClassifier(config: GestureEngineConfig) {

    @Volatile
    private var config: GestureEngineConfig = config

    private val fingerDetector = FingerExtensionDetector(config)

    private val poseHistory = ArrayDeque<Pose>()

    /** The last confirmed pose (after debounce). */
    @Volatile
    var confirmedPose: Pose = Pose.NONE
        private set

    /**
     * Consecutive-frame counter for the current raw pose. Drives the thumb-pose
     * grace rule below (a thumb that flicks out while the hand is closing must
     * not fire a volume change).
     */
    private var runPose: Pose = Pose.NONE
    private var runLength: Int = 0

    /**
     * Debounce length in frames. The engine updates this from the measured
     * frame interval so the *wall-clock* debounce stays [GestureEngineConfig.poseDebounceMs].
     */
    @Volatile
    var effectiveDebounceFrames: Int = config.poseDebounceFrames
        set(value) {
            field = value.coerceAtLeast(1)
        }

    /**
     * True while the hand is "fist-like": all four fingers curled. Reported to
     * the state machine so the FIST disarm hold keeps counting even if the raw
     * pose briefly reads THUMB_UP while the hand closes (Fix A-12).
     */
    @Volatile
    var lastFrameFistLike: Boolean = false
        private set

    // Templates for custom gesture matching.
    @Volatile
    private var customTemplates: List<LandmarkTemplate> = emptyList()

    /**
     * Replaces the list of user-defined templates. Pass an empty list to clear.
     */
    fun updateCustomTemplates(templates: List<LandmarkTemplate>) {
        customTemplates = templates
    }

    /**
     * Processes a hand input frame and returns the current confirmed pose after
     * applying the debounce filter. Returns [Pose.NONE] if no pose is confirmed.
     *
     * @param handVelocity Index-tip speed in normalized units/second, used to
     *   reject thumb poses made while the hand is moving (Fix A-12).
     */
    fun classify(input: HandInput, handVelocity: Float = 0f): Pose {
        if (!input.isDetected) {
            poseHistory.clear()
            runPose = Pose.NONE
            runLength = 0
            lastFrameFistLike = false
            confirmedPose = Pose.NONE
            return Pose.NONE
        }

        val fingerState = fingerDetector.detect(input)
        lastFrameFistLike = fingerState.extendedFingerCount == 0

        val rawPose = classifyRaw(input, fingerState, handVelocity)
        return applyDebounce(rawPose)
    }

    /** Returns the finger extension state for [input] without classifying. */
    fun getFingerState(input: HandInput): FingerExtensionState = fingerDetector.detect(input)

    /**
     * Raw pose classification. Poses are checked in priority order — earlier
     * checks take precedence.
     */
    internal fun classifyRaw(
        input: HandInput,
        fingerState: FingerExtensionState,
        handVelocity: Float = 0f,
    ): Pose {
        val landmarks = input.landmarks

        // 1. FIST — no digit extended at all. Checked BEFORE pinch so a closed
        //    hand (thumb resting across curled fingers, tips close together) is
        //    never mistaken for a pinch (Fix A-13).
        if (fingerState.totalExtendedCount == 0) return Pose.FIST

        // 2. PINCH — thumb-tip/index-tip within the one shared pinch threshold,
        //    and the index must be extended (in a fist it is not).
        if (fingerState.index && isPinch(landmarks)) return Pose.PINCH

        // 3. OPEN_PALM — at least 4 of 5 digits extended (tolerates a curled pinky).
        if (fingerState.totalExtendedCount >= 4) return Pose.OPEN_PALM

        // 4. POINTING — index only.
        if (fingerState.index && !fingerState.middle && !fingerState.ring && !fingerState.pinky) {
            return Pose.POINTING
        }

        // 5. VICTORY — index + middle.
        if (fingerState.index && fingerState.middle && !fingerState.ring && !fingerState.pinky) {
            return Pose.VICTORY
        }

        // 6. THREE_FINGERS — index + middle + ring.
        if (fingerState.index && fingerState.middle && fingerState.ring && !fingerState.pinky) {
            return Pose.THREE_FINGERS
        }

        // 7. FOUR_FINGERS — all four fingers.
        if (fingerState.index && fingerState.middle && fingerState.ring && fingerState.pinky) {
            return Pose.FOUR_FINGERS
        }

        // 8. THUMB_UP / THUMB_DOWN — only thumb extended, the thumb must be
        //    *clearly* extended (margin over the plain threshold), and the hand
        //    must be nearly still. Fix A-12: closing the hand to disarm passes
        //    through a moment where the thumb sticks out; that used to change the
        //    volume instead of disarming.
        if (fingerState.thumb && fingerState.extendedFingerCount == 0 &&
            handVelocity <= config.thumbGestureMaxVelocity &&
            isThumbClearlyExtended(landmarks)
        ) {
            val thumbTip = landmarks[LandmarkIndex.THUMB_TIP]
            val thumbMcp = landmarks[LandmarkIndex.THUMB_MCP]
            return if (thumbTip.y < thumbMcp.y) Pose.THUMB_UP else Pose.THUMB_DOWN
        }

        return Pose.NONE
    }

    /**
     * Detects a pinch by measuring the thumb-tip/index-tip distance normalized by
     * hand size (wrist to middle-MCP), so it works at any distance from the
     * camera at any resolution.
     */
    internal fun isPinch(landmarks: List<Landmark3D>): Boolean {
        val thumbTip = landmarks[LandmarkIndex.THUMB_TIP]
        val indexTip = landmarks[LandmarkIndex.INDEX_TIP]
        val wrist = landmarks[LandmarkIndex.WRIST]
        val middleMcp = landmarks[LandmarkIndex.MIDDLE_MCP]

        // 2D on purpose: MediaPipe's Z is noisy, and 3D made the ratio jitter
        // frame-to-frame.
        val pinchDistance = distance2D(thumbTip, indexTip)
        val handSize = distance2D(wrist, middleMcp)
        if (handSize < EPSILON) return false

        return pinchDistance / handSize < config.scaledPinchDistanceRatio()
    }

    /**
     * A thumb is "clearly" extended when its IP angle exceeds the extension
     * threshold by [THUMB_CLEAR_MARGIN_DEG]. This hysteresis is what keeps a
     * half-curled thumb (during hand closure) from registering as a thumb pose.
     */
    private fun isThumbClearlyExtended(landmarks: List<Landmark3D>): Boolean {
        val angle = fingerDetector.thumbAngleDeg(landmarks)
        return angle > config.scaledThumbExtensionAngleDeg() + THUMB_CLEAR_MARGIN_DEG
    }

    /**
     * Debounce filter: a pose is only confirmed when the same raw pose has been
     * observed for [effectiveDebounceFrames] consecutive frames.
     *
     * Fix A-14: the frame count is derived from a *duration* by the engine, so
     * confirmation always takes ~[GestureEngineConfig.poseDebounceMs] milliseconds
     * — not 600ms in 5fps scan mode.
     */
    internal fun applyDebounce(rawPose: Pose): Pose {
        val requiredFrames = effectiveDebounceFrames.coerceAtLeast(1)

        if (rawPose == runPose) {
            runLength++
        } else {
            runPose = rawPose
            runLength = 1
        }

        poseHistory.addLast(rawPose)
        while (poseHistory.size > requiredFrames) {
            poseHistory.removeFirst()
        }

        if (runLength >= requiredFrames && poseHistory.all { it == rawPose }) {
            confirmedPose = rawPose
        }
        return confirmedPose
    }

    /** Resets the classifier state. */
    fun reset() {
        poseHistory.clear()
        runPose = Pose.NONE
        runLength = 0
        confirmedPose = Pose.NONE
        lastFrameFistLike = false
    }

    /** Updates the config in place (sensitivity change) preserving in-progress state. */
    fun updateConfig(newConfig: GestureEngineConfig) {
        this.config = newConfig
        effectiveDebounceFrames = newConfig.poseDebounceFrames
        fingerDetector.updateConfig(newConfig)
    }

    /**
     * Landmark-template matching for user-defined custom gestures. Returns the
     * best matching template if its total normalized-distance error is below
     * [LandmarkTemplate.MATCH_TOLERANCE]; null on low-confidence frames.
     */
    fun matchCustomTemplate(input: HandInput): LandmarkTemplate? {
        if (input.confidence < MIN_TEMPLATE_MATCH_CONFIDENCE) return null
        if (customTemplates.isEmpty()) return null
        if (input.landmarks.size < HandInput.LANDMARK_COUNT) return null

        val landmarks = input.landmarks
        val wrist = landmarks[LandmarkIndex.WRIST]
        val middleMcp = landmarks[LandmarkIndex.MIDDLE_MCP]
        val handSize = distance2D(wrist, middleMcp)
        if (handSize < EPSILON) return null

        val liveDistances = FloatArray(LandmarkTemplate.EXPECTED_DISTANCE_COUNT)
        for (i in LandmarkTemplate.TEMPLATE_LANDMARK_PAIRS.indices) {
            val (a, b) = LandmarkTemplate.TEMPLATE_LANDMARK_PAIRS[i]
            liveDistances[i] = distance2D(landmarks[a], landmarks[b]) / handSize
        }

        var bestTemplate: LandmarkTemplate? = null
        var bestError = Float.MAX_VALUE

        for (template in customTemplates) {
            if (template.normalizedDistances.size != LandmarkTemplate.EXPECTED_DISTANCE_COUNT) continue
            var totalError = 0f
            for (i in liveDistances.indices) {
                totalError += kotlin.math.abs(liveDistances[i] - template.normalizedDistances[i])
            }
            if (totalError < bestError) {
                bestError = totalError
                bestTemplate = template
            }
        }

        return if (bestTemplate != null && bestError < LandmarkTemplate.MATCH_TOLERANCE) bestTemplate else null
    }

    internal fun distance2D(a: Landmark3D, b: Landmark3D): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    companion object {
        private const val EPSILON = 1e-6f

        /** Degrees of extra thumb straightness required to call a thumb pose. */
        private const val THUMB_CLEAR_MARGIN_DEG = 8f

        /**
         * Minimum tracking confidence for template matching. Slightly below the
         * engine's 0.7 low-confidence threshold so a deliberate custom shape is
         * still matched when the hand is near the frame edge.
         */
        private const val MIN_TEMPLATE_MATCH_CONFIDENCE = 0.6f
    }
}
