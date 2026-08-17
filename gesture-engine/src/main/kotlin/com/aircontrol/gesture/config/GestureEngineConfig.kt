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
 *   Default: 0.40.
 *
 * @param swipeWindowMs Sliding window duration for swipe detection.
 *   Default: 500ms.
 *
 * @param swipeDisplacementRatio Minimum wrist displacement as a fraction
 *   of frame dimension to qualify as a swipe. Default: 0.08 (8%).
 *
 * @param swipeVelocityThreshold Minimum peak velocity in normalized
 *   units per second for a swipe. Default: 1.2.
 *
 * @param swipeAxisDominanceRatio Minimum ratio of dominant axis
 *   displacement to secondary axis displacement to reject diagonal
 *   ambiguity. Default: 2.0 (2:1 ratio required).
 *
 * @param armingDurationMs Duration the open palm must be held to
 *   transition from ARMING to ARMED. Default: 100ms.
 *
 * @param cooldownDurationMs Duration of the COOLDOWN state after
 *   a gesture is executed. Default: 100ms.
 *
 * @param autoDisarmTimeoutMs Duration of no hand detection before
 *   auto-disarming. Default: 10_000ms (10 seconds).
 *
 * @param fistDisarmDurationMs Duration FIST must be held to
 *   trigger disarm. Default: 1000ms (1 second).
 */
data class GestureEngineConfig(
    val sensitivity: Int = 50,
    // 3 frames (~125ms @ 24fps): enough to debounce noisy landmarks without the
    // DISARMED↔ARMING flapping that 2 frames caused (cursor/pill blinking).
    val poseDebounceFrames: Int = 3,
    val fingerExtensionThreshold: Float = 1.0f,
    val thumbExtensionAngleDeg: Float = 140f, // Thumb-up 140 now triggers (was 150 too strict, 140 bent thumb)
    // UG-08 Fix: Increased from 0.35 to 0.40 for easier pinch detection with large hands
    // 40% of hand size is still precise enough to avoid accidental pinches
    val pinchDistanceRatio: Float = 0.40f,
    // UG-06 Fix: Increased from 350ms to 500ms for slower, more deliberate swipes
    // Users with limited mobility or slower movements can now complete swipes
    val swipeWindowMs: Long = 500L,
    // UG-07 Fix: Reduced from 15% to 10% for easier swipe detection with small hands.
    // Further reduced to 8% so swipes trigger more consistently ("swipe miss hota hai").
    val swipeDisplacementRatio: Float = 0.06f, // Small hand: 6% =65px vs 8%=86px, 65px reachable
    val swipeVelocityThreshold: Float = 1.2f,
    // Kept at 2.0: relaxing this would accept diagonal swipes and worsen the
    // "kab kya swipe ho jata hai pata nahi" direction-confusion complaint.
    val swipeAxisDominanceRatio: Float = 2.0f,
    // CRITICAL FIX: Reduced from 200ms to 100ms for instant arming (Apple Vision Pro level)
    // 100ms feels instantaneous while still preventing accidental arming
    val armingDurationMs: Long = 100L,
    // CRITICAL FIX: Reduced from 200ms to 100ms for rapid gesture recovery
    // Apple Vision Pro level responsiveness
    val cooldownDurationMs: Long = 100L,
    val autoDisarmTimeoutMs: Long = 10_000L,
    val fistDisarmDurationMs: Long = 1000L,
    val swipeCooldownMs: Long = 300L, // Fast left-right 300ms now 2 swipes, was 500ms ignored second

    /**
     * How long an open palm must be held (while ARMED) to trigger the PalmHome
     * gesture. Default 2000ms — long enough to avoid accidental triggers during
     * normal use (OPEN_PALM is the neutral ARMED pose).
     */
    val palmHomeHoldMs: Long = 2000L,

    /**
     * User-calibrated pinch ratio (thumb-tip to index-tip distance / hand size)
     * measured during calibration. When non-null, it personalizes the pinch
     * "enter" threshold so clicking feels tuned to the user's own hand. Null
     * means "not calibrated" — the engine falls back to the sensitivity-scaled
     * default threshold.
     */
    val calibratedPinchRatio: Float? = null,
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
        require(palmHomeHoldMs > 0) { "Palm home hold must be positive" }
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
        val scaled = pinchDistanceRatio / (0.7f + sensitivity / 200f) // Narrow 0.29-0.5 intentional: wider causes resting hand false pinch (see #11)
        return scaled.coerceAtLeast(MIN_PINCH_DISTANCE_RATIO)
    }

    fun scaledFingerExtensionThreshold(): Float =
        fingerExtensionThreshold / (0.5f + sensitivity / 100f)

    fun scaledThumbExtensionAngleDeg(): Float =
        thumbExtensionAngleDeg / (0.5f + sensitivity / 100f)

    /**
     * Multiplier applied to the pinch FSM thresholds so the sensitivity slider
     * actually influences click/drag pinch detection (it previously had no
     * effect — the FSM used hardcoded constants). 1.0 at sensitivity=50, 1.5 at
     * 100 (easier: pinch engages at a larger finger gap), 0.5 at 0 (harder).
     */
    val pinchSensitivityFactor: Float
        get() = 0.5f + sensitivity / 100f

    companion object {
        // Bug #11 Fix: Absolute minimum pinch distance ratio. Even at sensitivity=100,
        // the pinch threshold never drops below this value. Prevents resting hands
        // (where thumb and index naturally curl close together) from triggering
        // accidental clicks. 0.25 = thumb-index distance must be < 25% of hand size
        // (wrist-to-middle-MCP distance) to count as a pinch.
        const val MIN_PINCH_DISTANCE_RATIO = 0.25f
    }
}
