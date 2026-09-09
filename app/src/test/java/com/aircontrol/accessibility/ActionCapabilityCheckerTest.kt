package com.aircontrol.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue 14 acceptance: unsupported system actions are refused, never substituted. */
class ActionCapabilityCheckerTest {

    @Test
    fun `screenshot requires API 28`() {
        assertEquals(28, ActionCapabilityChecker.minSdkFor(GestureAction.SCREENSHOT))
        assertFalse(ActionCapabilityChecker.isSupported(GestureAction.SCREENSHOT, 26))
        assertFalse(ActionCapabilityChecker.isSupported(GestureAction.SCREENSHOT, 27))
        assertTrue(ActionCapabilityChecker.isSupported(GestureAction.SCREENSHOT, 28))
        assertTrue(ActionCapabilityChecker.isSupported(GestureAction.SCREENSHOT, 35))
    }

    @Test
    fun `lock screen requires API 28`() {
        assertEquals(28, ActionCapabilityChecker.minSdkFor(GestureAction.LOCK_SCREEN))
        assertFalse(ActionCapabilityChecker.isSupported(GestureAction.LOCK_SCREEN, 27))
        assertTrue(ActionCapabilityChecker.isSupported(GestureAction.LOCK_SCREEN, 28))
    }

    @Test
    fun `actions available on every supported version are always supported`() {
        assertNull(ActionCapabilityChecker.minSdkFor(GestureAction.TAP))
        assertNull(ActionCapabilityChecker.minSdkFor(GestureAction.BACK))
        assertNull(ActionCapabilityChecker.minSdkFor(GestureAction.SCROLL_UP))
        assertTrue(ActionCapabilityChecker.isSupported(GestureAction.TAP, 26))
        assertTrue(ActionCapabilityChecker.isSupported(GestureAction.BACK, 26))
        assertTrue(ActionCapabilityChecker.isSupported(GestureAction.HOME, 26))
        assertTrue(ActionCapabilityChecker.isSupported(GestureAction.DOUBLE_TAP, 26))
        assertTrue(ActionCapabilityChecker.isSupported(GestureAction.LONG_PRESS, 26))
        assertTrue(ActionCapabilityChecker.isSupported(GestureAction.DRAG, 26))
    }
}
