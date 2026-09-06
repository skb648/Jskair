package com.aircontrol.gesture.config

/**
 * Tunable configuration for the gesture engine.
 *
 * ---------------------------------------------------------------------------
 * SENSITIVITY MODEL (Fix A-3: "the slider breaks detection at both ends")
 * ---------------------------------------------------------------------------
 * Every user-facing threshold used to be derived as `base / (0.5 + s/100)`.
 * That single formula was wrong in three ways:
 *
 *  1. At s=0 it produced *impossible* thresholds: finger extension needed a
 *     tip/PIP ratio of 2.0 (a straight finger is ~1.35-1.55) and the thumb
 *     needed an angle of 280° (max is 180°). Result: every hand read as FIST
 *     and the app could never arm.
 *  2. At s=100 it produced *always true* thresholds: finger ratio 0.67 meant a
 *     curled finger counted as extended, so everything was OPEN_PALM.
 *  3. It scaled the pinch threshold in the *wrong direction* for the pose
 *     classifier (higher sensitivity made clicking harder), while the click FSM
 *     scaled it in the *opposite* direction with a different formula. Two
 *     disagreeing definitions of "pinch" was the root of "pinch click kaam nahi
 *     karta".
 *
 * The model now used is a single bounded "ease" multiplier:
 *
 *   ease = 0.85 .. 1.15   (1.0 at sensitivity 50)
 *
 * and each threshold is `base / ease` or `base * ease` — chosen so that
 * *higher sensitivity always means easier to trigger* — then hard-clamped to a
 * physically reachable range. No slider position can make a pose impossible or
 * unconditional any more.
 *
 * @param sensitivity User sensitivity setting (0-100, default 50).
 * @param poseDebounceFrames Upper bound on the pose debounce in frames. The
 *   engine normally derives the frame count from [poseDebounceMs] and the
 *   measured frame interval, so this acts as the cap at high frame rates and as
 *   the fallback when no frame interval is known yet (Fix A-14: a fixed 3-frame
 *   debounce was 125ms at 24fps but 600ms at 5fps, which made arming feel
 *   broken after the phone warmed up).
 * @param poseDebounceMs How long a raw pose must be held before it is
 *   confirmed. Default 120ms — the same wall-clock time at 5fps and at 30fps.
 * @param fingerExtensionThreshold tip-to-wrist / PIP-to-wrist ratio above
 *   which a finger counts as extended.
 * @param thumbExtensionAngleDeg Angle at the thumb IP joint above which the
 *   thumb counts as extended.
 * @param pinchDistanceRatio THE ONE AND ONLY definition of a pinch
 *   (Fix A-6): thumb-tip to index-tip distance, divided by hand size
 *   (wrist to middle-MCP). Both the pose classifier and the click FSM read this
 *   number, so "the pose says PINCH" and "a click fires" are now the same
 *   physical event. 0.22 corresponds to fingertip pads touching for a typical
 *   hand at 2D-landmark scale.
 * @param swipeWindowMs Sliding window duration for swipe detection.
 * @param swipeDisplacementRatio Minimum displacement as a fraction of the frame
 *   dimension to qualify as a swipe.
 * @param swipeVelocityThreshold Minimum peak velocity (normalized units/sec).
 * @param swipeAxisDominanceRatio Dominant:secondary displacement ratio needed to
 *   reject diagonal ambiguity.
 * @param swipeRequiresOpenHand Swipes only fire while an open palm is held
 *   (Fix A-11). Without this, simply moving the cursor across the screen
 *   triggered a scroll — the "kab kya swipe ho jata hai" complaint.
 * @param armingDurationMs How long the open palm must be held to arm.
 * @param cooldownDurationMs Cooldown after a gesture executes.
 * @param autoDisarmTimeoutMs No hand for this long → DISARMED.
 * @param fistDisarmDurationMs FIST hold needed to disarm.
 * @param swipeCooldownMs Minimum gap between two swipe detections.
 * @param palmHomeHoldMs How long a *still, close, deliberately presented* open
 *   palm must be held in ARMED state before Palm→Home fires (Fix A-4).
 * @param palmHomeMinHandSizeNormalized Minimum hand size (wrist→middle-MCP as a
 *   fraction of frame height) for Palm→Home. A raised-hand "presentation" fills
 *   the frame; a hand resting near the keyboard does not.
 * @param palmHomeMaxCursorMovement Normalized cursor travel allowed during the
 *   palm hold. Exceeding it means the user is pointing/browsing, not holding a
 *   palm still for Home.
 * @param thumbGestureMaxVelocity Index-tip speed (normalized units/sec) below
 *   which THUMB_UP/THUMB_DOWN may fire (Fix A-12: closing the hand to disarm
 *   used to change the volume; a closing hand is moving fast, a deliberate
 *   thumb-up is held still).
 * @param thumbGestureHoldMs How long the hand must be held still before a thumb
 *   pose executes. A hand closing passes through "thumb out" for far less than
 *   this; a deliberate thumb-up is held.
 * @param calibratedPinchRatio User-calibrated pinch distance ratio from
 *   calibration. Overrides [pinchDistanceRatio] (still sensitivity-scaled and
 *   clamped to a sane band), so calibration can actually change how a click
 *   feels — previously it was clamped to 0.08, i.e. always ignored.
 */
data class GestureEngineConfig(
    val sensitivity: Int = 70,
    val poseDebounceFrames: Int = 4,
    val poseDebounceMs: Long = 120L,
    val fingerExtensionThreshold: Float = 1.0f,
    val thumbExtensionAngleDeg: Float = 140f,
    val pinchDistanceRatio: Float = 0.22f,
    // Fix (swipe latency): 500ms window + 300ms cooldown added a visible
    // 0.3–0.6s "did it register?" delay to every swipe. A fast hand swipe
    // completes in ~200–300ms; a 350ms window still holds enough intermediate
    // samples for every consistency check, and a 220ms cooldown still blocks
    // double-fires while feeling immediate.
    val swipeWindowMs: Long = 350L,
    val swipeDisplacementRatio: Float = 0.08f,
    val swipeVelocityThreshold: Float = 1.2f,
    val swipeAxisDominanceRatio: Float = 2.0f,
    val swipeRequiresOpenHand: Boolean = true,
    val armingDurationMs: Long = 100L,
    val cooldownDurationMs: Long = 100L,
    val autoDisarmTimeoutMs: Long = 10_000L,
    val fistDisarmDurationMs: Long = 1000L,
    val swipeCooldownMs: Long = 220L,
    val palmHomeHoldMs: Long = 2500L,
    val palmHomeMinHandSizeNormalized: Float = 0.26f,
    val palmHomeMaxCursorMovement: Float = 0.05f,
    val thumbGestureMaxVelocity: Float = 0.35f,
    val thumbGestureHoldMs: Long = 600L,
    val calibratedPinchRatio: Float? = null,
) {
    init {
        require(sensitivity in 0..100) { "Sensitivity must be 0-100, got $sensitivity" }
        require(poseDebounceFrames > 0) { "Pose debounce frames must be positive" }
        require(poseDebounceMs > 0) { "Pose debounce ms must be positive" }
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
        require(thumbGestureMaxVelocity > 0f) { "Thumb gesture velocity must be positive" }
        require(thumbGestureHoldMs >= 0) { "Thumb gesture hold must not be negative" }
    }

    /**
     * Single bounded sensitivity multiplier: 0.85 at sensitivity 0, 1.0 at 50,
     * 1.15 at 100. Bounded so that no slider position can produce a threshold
     * that is physically unreachable (or always true).
     */
    val ease: Float
        get() = EASE_MIN + (sensitivity.coerceIn(0, 100) / 100f) * (EASE_MAX - EASE_MIN)

    /**
     * Swipe displacement threshold, sensitivity-aware.
     * Higher sensitivity → smaller required travel → easier to swipe.
     */
    /**
     * Swipe thresholds get a wider sensitivity band than pose thresholds
     * (0.60..1.40 vs 0.85..1.15). Reason: swipes are cheap to make more
     * forgiving — the false-positive risk is now handled structurally by
     * [swipeRequiresOpenHand] — while an over-loose *finger extension*
     * threshold silently turns a half-closed hand into an open palm.
     */
    val swipeEase: Float
        get() = SWIPE_EASE_MIN +
            (sensitivity.coerceIn(0, 100) / 100f) * (SWIPE_EASE_MAX - SWIPE_EASE_MIN)

    fun scaledSwipeDisplacement(): Float =
        (swipeDisplacementRatio / swipeEase).coerceIn(MIN_SWIPE_DISPLACEMENT, MAX_SWIPE_DISPLACEMENT)

    /** Swipe peak-velocity threshold, sensitivity-aware. */
    fun scaledSwipeVelocity(): Float =
        (swipeVelocityThreshold / swipeEase).coerceIn(MIN_SWIPE_VELOCITY, MAX_SWIPE_VELOCITY)

    /**
     * THE pinch threshold (Fix A-6): used by the pose classifier *and* the click
     * FSM. A calibrated value replaces the generic one, but always passes
     * through the same sensitivity scaling and the same safety band, so a bad
     * calibration can never make clicking impossible or always-on.
     */
    fun scaledPinchDistanceRatio(): Float {
        val base = calibratedPinchRatio ?: pinchDistanceRatio
        // Calibration measures the distance at the moment of the *start* of a
        // pinch; add a small margin so the confirmed click is slightly easier
        // than the bare measurement (a held pinch is always tighter than the
        // first contact frame).
        val withMargin = if (calibratedPinchRatio != null) base * CALIBRATED_PINCH_MARGIN else base
        return (withMargin * ease).coerceIn(MIN_PINCH_DISTANCE_RATIO, MAX_PINCH_DISTANCE_RATIO)
    }

    /**
     * Hysteresis partner of [scaledPinchDistanceRatio]: the pinch is released
     * only once the fingers separate beyond this ratio. Derived (never
     * independent) so the enter/exit gap stays proportional.
     */
    fun scaledPinchReleaseRatio(): Float =
        scaledPinchDistanceRatio() * PINCH_RELEASE_HYSTERESIS

    /** Distance ratio at which fingers are considered "approaching" (hover). */
    fun scaledPinchHoverRatio(): Float =
        scaledPinchDistanceRatio() * PINCH_HOVER_HYSTERESIS

    /** Finger extension threshold, sensitivity-aware and always reachable. */
    fun scaledFingerExtensionThreshold(): Float =
        (fingerExtensionThreshold / ease).coerceIn(MIN_FINGER_THRESHOLD, MAX_FINGER_THRESHOLD)

    /** Thumb extension angle, sensitivity-aware and always < 180°. */
    fun scaledThumbExtensionAngleDeg(): Float =
        (thumbExtensionAngleDeg / ease).coerceIn(MIN_THUMB_ANGLE_DEG, MAX_THUMB_ANGLE_DEG)

    /**
     * Number of consecutive frames a pose must hold, for a given measured frame
     * interval (Fix A-14). At 24fps this is 3 frames; at 5fps it is 4 (capped),
     * so arming never becomes a 600ms wait.
     */
    fun debounceFramesFor(frameIntervalMs: Long): Int {
        if (frameIntervalMs <= 0L) return poseDebounceFrames.coerceAtLeast(1)
        val computed = kotlin.math.ceil(poseDebounceMs.toFloat() / frameIntervalMs).toInt()
        return computed.coerceIn(1, poseDebounceFrames)
    }

    companion object {
        // Sensitivity multiplier bounds. 0.85..1.15 keeps every derived
        // threshold inside a physically meaningful band.
        const val EASE_MIN = 0.85f
        const val EASE_MAX = 1.15f

        // Reachable bands. A straight finger is 1.35-1.55 tip/PIP, so
        // [0.90, 1.12] leaves room to require a *fully* straight finger at the
        // strict end without ever demanding the impossible.
        const val MIN_FINGER_THRESHOLD = 0.90f
        const val MAX_FINGER_THRESHOLD = 1.12f

        // A straight thumb is ~165-178°, a bent one 60-110°.
        const val MIN_THUMB_ANGLE_DEG = 118f
        const val MAX_THUMB_ANGLE_DEG = 158f

        // Pinch band: 0.14 = pads must clearly touch (very deliberate),
        // 0.32 = half-closed hand clicks (too loose). The default 0.22 sits in
        // the middle and matches "fingertips touching".
        const val MIN_PINCH_DISTANCE_RATIO = 0.14f
        const val MAX_PINCH_DISTANCE_RATIO = 0.32f

        const val SWIPE_EASE_MIN = 0.60f
        const val SWIPE_EASE_MAX = 1.40f

        const val MIN_SWIPE_DISPLACEMENT = 0.06f
        const val MAX_SWIPE_DISPLACEMENT = 0.09f
        const val MIN_SWIPE_VELOCITY = 0.9f
        const val MAX_SWIPE_VELOCITY = 1.8f

        const val PINCH_RELEASE_HYSTERESIS = 1.45f
        const val PINCH_HOVER_HYSTERESIS = 1.9f
        const val CALIBRATED_PINCH_MARGIN = 1.15f
    }
}
