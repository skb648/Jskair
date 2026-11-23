package com.aircontrol.data.model

/**
 * Selectable cursor response profiles that govern kinematic velocity lead compensation,
 * One Euro adaptive cutoff parameters, and VSYNC sub-frame snapping.
 */
enum class CursorResponseProfile(
    val minCutoff: Float,
    val beta: Float,
    /** Kinematic velocity lead-time prediction in milliseconds to cancel camera + inference delay. */
    val leadMs: Float,
    /** Micro-deadzone radius in normalized [0..1] screen units for stillness tremor clamping. */
    val deadZoneNormalized: Float,
    /** Whether VSYNC snaps directly on the next display frame for zero interpolation lag. */
    val directVsyncSnap: Boolean,
) {
    /**
     * Instant: light smoothing and a small velocity lead (~8 ms) for fast users. Still glides on
     * VSYNC so low camera frame rates do not look like stutter.
     */
    INSTANT(
        minCutoff = 1.6f,
        beta = 6.0f,
        leadMs = 8.0f,
        deadZoneNormalized = 0.0012f,
        directVsyncSnap = false,
    ),

    /**
     * Balanced (default): steady at rest, responsive in motion, no prediction overshoot.
     */
    BALANCED(
        minCutoff = 1.1f,
        beta = 3.5f,
        leadMs = 0.0f,
        deadZoneNormalized = 0.0016f,
        directVsyncSnap = false,
    ),

    /**
     * Precision: Extra stillness stabilization and fine micro-target locking for dense UI controls.
     */
    PRECISION(
        minCutoff = 0.7f,
        beta = 2.0f,
        leadMs = 0.0f,
        deadZoneNormalized = 0.0022f,
        directVsyncSnap = false,
    ),
}
