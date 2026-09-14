package com.aircontrol.accessibility

/**
 * Pure decision policy for the accessibility-service camera watchdog
 * (perf audit P1/P11).
 *
 * Root cause this fixes: the watchdog ticked every 5 s and attempted a camera
 * revival whenever gestures were enabled and tracking was off — even while the
 * phone was locked, another screen exclusively owned the camera, the runtime
 * permission was missing, or the AirControl Activity was invisible. Each of
 * those attempts no-oped immediately, producing 30 pointless attempts and 30
 * WARN-level log lines per 5 s tick in the 2026-09-07 logcat.
 *
 * This object contains NO Android dependencies and reads no state: the service
 * supplies the six booleans, gets back one decision, and only that decision is
 * logged (transition-only, rate-limited — see `logWatchdogDecision`).
 */
object CameraRevivePolicy {

    enum class Decision {
        /** Desired and actual state already agree (running, or stopped). */
        ALREADY_CONSISTENT,

        /** Gestures disabled but the camera service is still running — stop it. */
        STOP_SERVICE,

        /** Device locked: never start the camera behind the keyguard. */
        DEFER_KEYGUARD,

        /** An exclusive camera user (debug screen) owns the camera right now. */
        SUPPRESS_EXCLUSIVE_USER,

        /** Camera runtime permission not granted. */
        DEFER_PERMISSION,

        /** AirControl Activity not visible — starting would no-op anyway. */
        DEFER_NOT_VISIBLE,

        /** All preconditions met and the app is on screen: start now. */
        REVIVE,

        /** All preconditions met but the app is backgrounded: still revive —
         *  the camera FGS start is attempted and retried on rejection. */
        REVIVE_BACKGROUND,
    }

    /**
     * Precondition priority (first match wins):
     * consistency → keyguard → exclusive user → permission → revive.
     *
     * Keyguard outranks the exclusive-user check because a locked device must
     * never even consider the camera.
     *
     * Fix (verified critical #4): visibility is intentionally NOT a gate any
     * more. The old `!activityVisible -> DEFER_NOT_VISIBLE` rule meant the
     * watchdog could never revive an OEM-killed service while the user was in
     * another app — the exact moment gestures matter. A background start is
     * attempted while unlocked + permitted; the platform rejection (if any) is
     * caught and retried with backoff by the service itself.
     */
    fun decide(
        gesturesEnabled: Boolean,
        isTracking: Boolean,
        keyguardLocked: Boolean,
        exclusiveCameraUser: Boolean,
        permissionGranted: Boolean,
        activityVisible: Boolean,
    ): Decision = when {
        gesturesEnabled && isTracking -> Decision.ALREADY_CONSISTENT
        !gesturesEnabled && !isTracking -> Decision.ALREADY_CONSISTENT
        !gesturesEnabled && isTracking -> Decision.STOP_SERVICE
        keyguardLocked -> Decision.DEFER_KEYGUARD
        exclusiveCameraUser -> Decision.SUPPRESS_EXCLUSIVE_USER
        !permissionGranted -> Decision.DEFER_PERMISSION
        else -> if (activityVisible) Decision.REVIVE else Decision.REVIVE_BACKGROUND
    }
}
