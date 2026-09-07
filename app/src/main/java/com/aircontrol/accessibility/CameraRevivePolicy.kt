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

        /** All preconditions met: start/revive the camera service now. */
        REVIVE,
    }

    /**
     * Precondition priority (first match wins):
     * consistency → keyguard → exclusive user → permission → visibility.
     *
     * Keyguard outranks the exclusive-user check because a locked device must
     * never even consider the camera; visibility is last because it is the
     * condition that changes most often.
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
        !activityVisible -> Decision.DEFER_NOT_VISIBLE
        else -> Decision.REVIVE
    }
}
