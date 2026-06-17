package com.aircontrol.gesture.config

/**
 * Tunable configuration for the gesture engine.
 * All thresholds scale with the sensitivity parameter (0–100).
 *
 * @param sensitivity User sensitivity setting (0–100, default 50).
 *   At 50, all base thresholds are used as-is. Higher sensitivity
 *   lowers thresholds (easier to trigger), lower sensitivity raises them.
 *
 * @param poseDebounceFrames Number of consecutive frames a pose must
 *   hold before being confirmed. Prevents flicker. Default: 3.
 *
 * @param fingerExtensionThreshold Distance ratio threshold for detecting
 *   finger extension. For non-thumb fingers: a finger is extended when
 *   tip-to-wrist distance / PIP-to-wrist distance > this value.
 *   Default: 1.0 (tip must be at least as far from wrist as PIP).
 *
 * @param thumbExtensionAngleDeg Angle in degrees above which the thumb
 *   is considered extended, measured at the IP joint. Default: 150°.
 *
 * @param pinchDistanceRatio Threshold for pinch detection: thumb-tip
 *   to index-tip distance < this ratio * hand-size (wrist-to-middle-MCP).
 *   Default: 0.35 (about 1/3 of hand size).
 *
 * @param swipeWindowMs Sliding window duration for swipe detection.
 *   Default: 350ms.
 *
 * @param swipeDisplacementRatio Minimum wrist displacement as a fraction
 *   of frame dimension to qualify as a swipe. Default: 0.15 (15%).
 *
 * @param swipeVelocityThreshold Minimum peak velocity in normalized
 *   units per second for a swipe. Default: 1.5.
 *
 * @param swipeAxisDominanceRatio Minimum ratio of dominant axis
 *   displacement to secondary axis displacement to reject diagonal
 *   ambiguity. Default: 2.0 (2:1 ratio required).
 *
 * @param armingDurationMs Duration the open palm must be held to
 *   transition from ARMING to ARMED. Default: 400ms.
 *
 * @param cooldownDurationMs Duration of the COOLDOWN state after
 *   a gesture is executed. Default: 350ms.
 *
 * @param autoDisarmTimeoutMs Duration of no hand detection before
 *   auto-disarming. Default: 10_000ms (10 seconds).
 *
 * @param fistDisarmDurationMs Duration FIST must be held to
 *   trigger disarm. Default: 1000ms (1 second).
 */
data class GestureEngineConfig(
    val sensitivity: Int = 50,
    val poseDebounceFrames: Int = 5,
    val fingerExtensionThreshold: Float = 1.0f,
    val thumbExtensionAngleDeg: Float = 150f,
    val pinchDistanceRatio: Float = 0.35f,
    val swipeWindowMs: Long = 350L,
    val swipeDisplacementRatio: Float = 0.15f,
    val swipeVelocityThreshold: Float = 1.5f,
    val swipeAxisDominanceRatio: Float = 2.0f,
    val armingDurationMs: Long = 400L,
    val cooldownDurationMs: Long = 400L,
    val autoDisarmTimeoutMs: Long = 10_000L,
    val fistDisarmDurationMs: Long = 1000L,
    val swipeCooldownMs: Long = 500L,
) {
    init {
        require(sensitivity in 0..100) { "Sensitivity must be 0-100, got $sensitivity" }
        require(poseDebounceFrames > 0) { "Pose debounce frames must be positive" }
        require(fingerExtensionThreshold > 0f) { "Finger extension threshold must be positive" }
        require(thumbExtensionAngleDeg > 0f) { "Thumb extension angle must be positive" }
        require(pinchDistanceRatio > 0f) { "Pinch distance ratio must be positive" }
        require(swipeWindowMs > 0) { "Swipe window must be positive" }
        require(swipeDisplacementRatio > 0f) { "Swipe displacement ratio must be positive" }
        require(swipeVelocityThreshold > 0f) { "Swipe velocity threshold must be positive" }
        require(swipeAxisDominanceRatio > 1f) { "Swipe axis dominance ratio must be > 1" }
        require(armingDurationMs > 0) { "Arming duration must be positive" }
        require(cooldownDurationMs > 0) { "Cooldown duration must be positive" }
        require(autoDisarmTimeoutMs > 0) { "Auto-disarm timeout must be positive" }
        require(fistDisarmDurationMs > 0) { "Fist disarm duration must be positive" }
        require(swipeCooldownMs > 0) { "Swipe cooldown must be positive" }
    }

    /**
     * Scales a threshold by sensitivity. Higher sensitivity lowers the threshold,
     * making gestures easier to trigger.
     *
     * Formula: baseThreshold / (0.5 + sensitivity / 100)
     * - At sensitivity=50: baseThreshold / 1.0 = baseThreshold
     * - At sensitivity=100: baseThreshold / 1.5 = 0.67 × base (easier to trigger)
     * - At sensitivity=0: baseThreshold / 0.5 = 2.0 × base (harder to trigger)
     */
    fun scaledSwipeDisplacement(): Float =
        swipeDisplacementRatio / (0.5f + sensitivity / 100f)

    fun scaledSwipeVelocity(): Float =
        swipeVelocityThreshold / (0.5f + sensitivity / 100f)

    /**
     * Bug #11 Fix: Pinch distance ratio with a SEPARATE, gentler scaling formula
     * and a strict minimum floor.
     *
     * Why a separate formula:
     *   The global formula `base / (0.5 + sensitivity/100)` is too aggressive for
     *   pinch — at sensitivity=100 it would yield 0.35 / 1.5 = 0.233, which is so
     *   sensitive that a resting hand with fingers naturally curled can register
     *   as a continuous pinch (and trigger accidental clicks/drags).
     *
     *   Pinch uses a gentler curve: `base / (0.7 + sensitivity / 200)`.
     *     - At sensitivity=50:  0.35 / (0.7 + 0.25) = 0.35 / 0.95 ≈ 0.368 (≈ base)
     *     - At sensitivity=100: 0.35 / (0.7 + 0.50) = 0.35 / 1.20 ≈ 0.292 (only ~16% easier)
     *     - At sensitivity=0:   0.35 / (0.7 + 0.00) = 0.35 / 0.70 = 0.500 (harder)
     *
     * Strict minimum floor of 0.25:
     *   Even at maximum sensitivity, the pinch distance ratio never drops below
     *   0.25. This prevents resting hands (where thumb and index naturally sit
     *   close together) from triggering clicks. A ratio of 0.25 means the
     *   thumb-index distance must be less than 25% of the wrist-to-middle-MCP
     *   distance — a deliberate finger-touch, not a relaxed hand.
     */
    fun scaledPinchDistanceRatio(): Float {
        val scaled = pinchDistanceRatio / (0.7f + sensitivity / 200f)
        return scaled.coerceAtLeast(MIN_PINCH_DISTANCE_RATIO)
    }

    fun scaledFingerExtensionThreshold(): Float =
        fingerExtensionThreshold / (0.5f + sensitivity / 100f)

    fun scaledThumbExtensionAngleDeg(): Float =
        thumbExtensionAngleDeg / (0.5f + sensitivity / 100f)

    companion object {
        // Bug #11 Fix: Absolute minimum pinch distance ratio. Even at sensitivity=100,
        // the pinch threshold never drops below this value. Prevents resting hands
        // (where thumb and index naturally curl close together) from triggering
        // accidental clicks. 0.25 = thumb-index distance must be < 25% of hand size
        // (wrist-to-middle-MCP distance) to count as a pinch.
        const val MIN_PINCH_DISTANCE_RATIO = 0.25f
    }
}
