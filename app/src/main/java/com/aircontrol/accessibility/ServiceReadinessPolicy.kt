package com.aircontrol.accessibility

/**
 * Decides when [GestureControlAccessibilityService] may publish `isConnected = true`.
 *
 * Confirmed bug (release-first "service does nothing"): readiness used to be
 * published only after *every* startup step succeeded, including overlay
 * creation and `startTrackingPipeline()` → camera foreground-service start.
 * The camera start is legitimately refused while the Activity is not visible,
 * the keyguard is up or the permission is missing, so on a fresh enable from
 * Settings the service was fully bound yet reported as "not connected", and
 * onboarding / RuntimeHealthMonitor rendered that as "accessibility not running".
 *
 * Policy: the service is READY once it can receive events and dispatch actions
 * (DI + dispatcher attach). Overlay and camera are *degradations*, surfaced as
 * their own states, never as "service not started".
 */
object ServiceReadinessPolicy {

    enum class Verdict { NOT_READY, READY, READY_DEGRADED }

    fun decide(
        dependenciesInjected: Boolean,
        dispatcherAttached: Boolean,
        overlayOk: Boolean,
        cameraOk: Boolean,
    ): Verdict = when {
        !dependenciesInjected || !dispatcherAttached -> Verdict.NOT_READY
        overlayOk && cameraOk -> Verdict.READY
        else -> Verdict.READY_DEGRADED
    }

    /** `true` for every verdict that must publish `isConnected = true`. */
    fun publishesConnected(verdict: Verdict): Boolean = verdict != Verdict.NOT_READY
}
