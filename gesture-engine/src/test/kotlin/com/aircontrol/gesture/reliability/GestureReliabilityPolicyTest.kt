package com.aircontrol.gesture.reliability

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GestureReliabilityPolicyTest {
    @Test
    fun swipeIsSuppressedImmediatelyAfterPinch() {
        assertFalse(GestureReliabilityPolicy.allowSwipeAfterPinch(1100L, 1000L))
        assertTrue(GestureReliabilityPolicy.allowSwipeAfterPinch(1300L, 1000L))
    }

    @Test
    fun doubleTapWindowIsBounded() {
        assertTrue(GestureReliabilityPolicy.withinDoubleTapWindow(1200L, 1000L))
        assertFalse(GestureReliabilityPolicy.withinDoubleTapWindow(1301L, 1000L))
        assertFalse(GestureReliabilityPolicy.withinDoubleTapWindow(1200L, 0L))
    }
}
