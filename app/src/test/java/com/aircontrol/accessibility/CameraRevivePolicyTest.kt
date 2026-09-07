package com.aircontrol.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Perf audit P1: the watchdog churned 30 revival attempts + WARN logs per 5 s
 * tick while the Activity was invisible. These tests pin the decision table
 * and its precondition priority so the churn cannot silently return.
 */
class CameraRevivePolicyTest {

    private fun decide(
        gesturesEnabled: Boolean = true,
        isTracking: Boolean = false,
        keyguardLocked: Boolean = false,
        exclusiveCameraUser: Boolean = false,
        permissionGranted: Boolean = true,
        activityVisible: Boolean = true,
    ) = CameraRevivePolicy.decide(
        gesturesEnabled = gesturesEnabled,
        isTracking = isTracking,
        keyguardLocked = keyguardLocked,
        exclusiveCameraUser = exclusiveCameraUser,
        permissionGranted = permissionGranted,
        activityVisible = activityVisible,
    )

    @Test
    fun `gestures enabled and already tracking is consistent`() {
        assertEquals(CameraRevivePolicy.Decision.ALREADY_CONSISTENT, decide(isTracking = true))
    }

    @Test
    fun `gestures disabled and not tracking is consistent`() {
        assertEquals(
            CameraRevivePolicy.Decision.ALREADY_CONSISTENT,
            decide(gesturesEnabled = false, isTracking = false),
        )
    }

    @Test
    fun `gestures disabled while tracking means stop the service`() {
        assertEquals(
            CameraRevivePolicy.Decision.STOP_SERVICE,
            decide(gesturesEnabled = false, isTracking = true),
        )
    }

    @Test
    fun `stop-service wins even when the keyguard is locked`() {
        // STOP_SERVICE is a consistency decision, not a revival precondition.
        assertEquals(
            CameraRevivePolicy.Decision.STOP_SERVICE,
            decide(gesturesEnabled = false, isTracking = true, keyguardLocked = true),
        )
    }

    @Test
    fun `locked device defers revival`() {
        assertEquals(CameraRevivePolicy.Decision.DEFER_KEYGUARD, decide(keyguardLocked = true))
    }

    @Test
    fun `exclusive camera user suppresses revival`() {
        assertEquals(
            CameraRevivePolicy.Decision.SUPPRESS_EXCLUSIVE_USER,
            decide(exclusiveCameraUser = true),
        )
    }

    @Test
    fun `missing permission defers revival`() {
        assertEquals(
            CameraRevivePolicy.Decision.DEFER_PERMISSION,
            decide(permissionGranted = false),
        )
    }

    @Test
    fun `invisible activity defers revival (the 2026-09-07 churn case)`() {
        assertEquals(
            CameraRevivePolicy.Decision.DEFER_NOT_VISIBLE,
            decide(activityVisible = false),
        )
    }

    @Test
    fun `all preconditions met revives`() {
        assertEquals(CameraRevivePolicy.Decision.REVIVE, decide())
    }

    @Test
    fun `precondition priority is keyguard, exclusive user, permission, visibility`() {
        // Every later precondition may also be false; the first match wins.
        assertEquals(
            CameraRevivePolicy.Decision.DEFER_KEYGUARD,
            decide(
                keyguardLocked = true,
                exclusiveCameraUser = true,
                permissionGranted = false,
                activityVisible = false,
            ),
        )
        assertEquals(
            CameraRevivePolicy.Decision.SUPPRESS_EXCLUSIVE_USER,
            decide(
                exclusiveCameraUser = true,
                permissionGranted = false,
                activityVisible = false,
            ),
        )
        assertEquals(
            CameraRevivePolicy.Decision.DEFER_PERMISSION,
            decide(permissionGranted = false, activityVisible = false),
        )
    }
}
