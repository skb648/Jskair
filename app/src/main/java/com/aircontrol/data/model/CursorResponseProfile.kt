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
     * Zero-Latency Instant: Predictive kinematic lead (~14 ms) + direct VSYNC locking + high
     * dynamic cutoff for instantaneous, live 1:1 tracking.
     */
    INSTANT(
        minCutoff = 2.4f,
        beta = 28.0f,
        leadMs = 14.0f,
        deadZoneNormalized = 0.00045f,
        directVsyncSnap = true,
    ),

    /**
     * Balanced: Moderate kinematic lead (~8 ms) + 120Hz VSYNC sub-frame glide + adaptive One Euro
     * damping for everyday use.
     */
    BALANCED(
        minCutoff = 1.45f,
        beta = 18.0f,
        leadMs = 8.0f,
        deadZoneNormalized = 0.0006f,
        directVsyncSnap = false,
    ),

    /**
     * Precision: Extra stillness stabilization and fine micro-target locking for dense UI controls.
     */
    PRECISION(
        minCutoff = 0.95f,
        beta = 12.0f,
        leadMs = 0.0f,
        deadZoneNormalized = 0.00085f,
        directVsyncSnap = false,
    ),
}
