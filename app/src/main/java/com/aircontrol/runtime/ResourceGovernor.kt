package com.aircontrol.runtime

/**
 * Deterministic policy for system resource pressure (perf audit P9).
 *
 * Deliberate design constraints:
 * - **Battery LEVEL is not an input.** A low battery says nothing about CPU
 *   throttling; the only battery-related signal honored is the OS power-save
 *   MODE (an explicit OS decision). Battery state, power saver, thermal state
 *   and memory pressure are distinct signals with distinct remedies and must
 *   never be conflated (thermal is handled by ThermalGovernor).
 * - **Low storage is not low RAM** — storage pressure is out of scope here and
 *   no trim level is interpreted as disk space.
 *
 * Pure Kotlin (no Android imports) — the trim constants mirror
 * `android.content.ComponentCallbacks2` values so tests stay deterministic.
 */
class ResourceGovernor {

    enum class MemoryLevel { NORMAL, MODERATE, CRITICAL }

    /**
     * FPS ceiling while the OS power-save mode is active. [Int.MAX_VALUE]
     * (i.e. "no cap") otherwise. The cap value itself is deliberately not a
     * supported FPS tier: AdaptiveFpsController coerces it down to the
     * nearest supported tier (10).
     */
    fun fpsCapFor(isPowerSaveMode: Boolean): Int =
        if (isPowerSaveMode) FPS_POWER_SAVE_CAP else Int.MAX_VALUE

    /**
     * Maps a `ComponentCallbacks2` trim level to a coarse pressure level.
     *
     * Android trim levels are NOT globally ordered by severity: the running
     * band grows with severity (5 < 10 < 15) but must never be compared
     * numerically against the background band (UI_HIDDEN=20 would otherwise
     * outrank RUNNING_CRITICAL=15). Explicit mapping:
     */
    fun classifyTrim(level: Int): MemoryLevel = when (level) {
        TRIM_RUNNING_CRITICAL -> MemoryLevel.CRITICAL
        TRIM_RUNNING_LOW, TRIM_RUNNING_MODERATE -> MemoryLevel.MODERATE
        // Background trims: only COMPLETE (the system is about to kill
        // background processes) is critical; the rest is routine.
        TRIM_BACKGROUND_COMPLETE -> MemoryLevel.CRITICAL
        TRIM_BACKGROUND_MODERATE, TRIM_BACKGROUND, TRIM_UI_HIDDEN -> MemoryLevel.MODERATE
        else -> MemoryLevel.NORMAL
    }

    /**
     * Whether idle (not session-bound) MediaPipe trackers should be released
     * at this memory level. Never releases trackers belonging to a live
     * session — callers must check that no camera session is running.
     */
    fun shouldReleaseIdleTrackers(level: MemoryLevel): Boolean =
        level == MemoryLevel.MODERATE || level == MemoryLevel.CRITICAL

    companion object {
        /** 12 fps → coerced to the supported 10 fps tier by AdaptiveFpsController. */
        const val FPS_POWER_SAVE_CAP = 12

        // Mirrors of android.content.ComponentCallbacks2 trim levels.
        const val TRIM_RUNNING_MODERATE = 5
        const val TRIM_RUNNING_LOW = 10
        const val TRIM_RUNNING_CRITICAL = 15
        const val TRIM_UI_HIDDEN = 20
        const val TRIM_BACKGROUND = 40
        const val TRIM_BACKGROUND_MODERATE = 60
        const val TRIM_BACKGROUND_COMPLETE = 80
    }
}
