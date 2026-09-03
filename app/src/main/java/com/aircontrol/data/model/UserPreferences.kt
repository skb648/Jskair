package com.aircontrol.data.model

data class UserPreferences(
    val gesturesEnabled: Boolean = true,
    val sensitivity: Int = 50,
    val handPreference: HandPreference = HandPreference.ANY,
    val analysisFps: Int = 24,
    val cursorEnabled: Boolean = true,
    val hapticFeedback: Boolean = true,
    val onboardingCompleted: Boolean = false,
    val cursorSpeed: Int = 50,
    val holdDuration: Int = 600,
    val batterySaver: Boolean = false,
    val startOnBoot: Boolean = false,
    // Fix #32: status pill is OFF by default so no system-wide overlay appears
    // on first run without the user asking for it.
    val statusPillEnabled: Boolean = false,
    val calibratedHandSizeMm: Float = 0f,
    val calibratedPinchDistanceMm: Float = 0f,
    val isCalibrated: Boolean = false,

    // ---- Vision Pro feature toggles ----
    // F1: Dwell-to-click (cursor held still for dwellDurationMs = auto click)
    val dwellEnabled: Boolean = false,
    val dwellDurationMs: Int = 800,
    // F8: Stationary-click — ignore pinches that start while the hand is moving
    val stationaryClickEnabled: Boolean = true,
    // F4: Palm → Home gesture.
    //
    // Fix A-4: default OFF. OPEN_PALM is the pose that arms the app and the
    // natural resting pose while armed, so an enabled-by-default "hold an open
    // palm for 2s → go Home" meant users were thrown out of whatever app they
    // were using just for pausing to think. The gesture is still available (and
    // now requires a still, deliberately presented palm) for people who want it.
    val palmHomeEnabled: Boolean = false,

    // Fix A-11: only an open-palm sweep counts as a swipe, so moving the pointer
    // across the screen can never scroll the page. Turn off for swipe-while-pointing.
    val swipeRequiresOpenHand: Boolean = true,
    // F6: Sit-back mode — reduce how high the user must raise their hand
    val sitBackMode: Boolean = false,
    // F9: Reduced motion — disable pulse/glow/ripple animations
    val reducedMotion: Boolean = false,
    // F7: Cursor gain (0..100) — how much hand movement maps to screen
    val cursorGain: Int = 50,

    // ---- Eye tracking ("eye is mouse") ----
    // When enabled, the cursor follows your gaze (face landmarker) instead of the
    // index fingertip; hand pinch still performs clicks.
    val eyeTrackingEnabled: Boolean = false,
    // Gaze sensitivity (0..100) — how much eye movement maps to cursor movement.
    val gazeSensitivity: Int = 50,
    // Inverts the horizontal gaze axis (front-camera mirroring varies by device).
    val gazeInvertX: Boolean = true,

    // Blink-to-click (Eye Aspect Ratio): both eyes closed 300–800ms → click.
    val blinkClickEnabled: Boolean = false,
    // Persisted 5-point gaze calibration coefficients (comma-separated, 6 floats).
    val gazeCalibration: String = "",
) {
    /**
     * Legacy name for the smoothing slider. The stored key is `cursor_speed` for
     * compatibility with existing installs, but the control is labelled "Cursor
     * smoothing" in the UI and it only ever touched the filter — actual pointer
     * speed is [cursorGain]. Kept as an alias so both names stay readable at the
     * call sites.
     */
    val cursorSmoothing: Int
        get() = cursorSpeed

    /** How much hand travel maps to screen travel (the real "speed"). */
    val effectiveCursorGain: Int
        get() = cursorGain
}
