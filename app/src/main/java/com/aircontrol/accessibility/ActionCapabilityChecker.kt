package com.aircontrol.accessibility

/**
 * Honest capability checks for system actions (Issue 14).
 *
 * Older Android versions do not expose every global accessibility action:
 * `GLOBAL_ACTION_TAKE_SCREENSHOT` requires API 28 (P) and so does
 * `GLOBAL_ACTION_LOCK_SCREEN`. The dispatcher used to silently substitute an
 * UNRELATED action (screenshot → notifications, lock → home) so the user could
 * request A and receive B. With this checker the dispatcher refuses honestly
 * instead, and the mapping UIs hide the unavailable options.
 *
 * Pure Kotlin — JVM-testable (sdkInt passed in).
 */
object ActionCapabilityChecker {

    /** Minimum SDK (inclusive) on which [GestureAction.SCREENSHOT] exists. */
    const val SCREENSHOT_MIN_SDK = 28

    /** Minimum SDK (inclusive) on which [GestureAction.LOCK_SCREEN] exists. */
    const val LOCK_SCREEN_MIN_SDK = 28

    /**
     * Minimum SDK needed for [action], or null when it exists on every
     * supported Android version (minSdk 26).
     */
    fun minSdkFor(action: GestureAction): Int? = when (action) {
        GestureAction.SCREENSHOT -> SCREENSHOT_MIN_SDK
        GestureAction.LOCK_SCREEN -> LOCK_SCREEN_MIN_SDK
        else -> null
    }

    /** True when [action] can actually be executed on a device running [sdkInt]. */
    fun isSupported(action: GestureAction, sdkInt: Int): Boolean {
        val minSdk = minSdkFor(action) ?: return true
        return sdkInt >= minSdk
    }
}
