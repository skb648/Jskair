package com.aircontrol.gesture.intent

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GestureReliabilityTest {
    @Test
    fun pinchMustRemainInsideThresholdBeforeStarting() {
        val r = GestureReliability(pinchStartHoldMs = 80L)
        assertFalse(r.pinchStartStable(0.30f, 0.22f, 0L))
        assertFalse(r.pinchStartStable(0.20f, 0.22f, 40L))
        assertTrue(r.pinchStartStable(0.20f, 0.22f, 80L))
    }

    @Test
    fun pinchReleaseNeedsStableSeparation() {
        val r = GestureReliability(pinchReleaseHoldMs = 80L)
        assertFalse(r.pinchReleaseStable(0.30f, 0.32f, 0L))
        assertTrue(r.pinchReleaseStable(0.35f, 0.32f, 80L))
    }

    @Test
    fun swipeIsSuppressedImmediatelyAfterPinch() {
        val r = GestureReliability(swipeSuppressionAfterPinchMs = 350L)
        r.markPinchEnd(1000L)
        assertTrue(r.shouldSuppressSwipe(1100L))
        assertFalse(r.shouldSuppressSwipe(1400L))
    }

    @Test
    fun secondNearbyClickWithinWindowIsDoubleClick() {
        val r = GestureReliability(doubleClickWindowMs = 350L, doubleClickMaxDistance = 0.05f)
        assertFalse(r.registerClick(1000L, 0.50f, 0.50f))
        assertTrue(r.registerClick(1200L, 0.52f, 0.51f))
    }

    @Test
    fun farApartClicksDoNotBecomeDoubleClick() {
        val r = GestureReliability(doubleClickWindowMs = 350L, doubleClickMaxDistance = 0.05f)
        assertFalse(r.registerClick(1000L, 0.10f, 0.10f))
        assertFalse(r.registerClick(1200L, 0.80f, 0.80f))
    }
}
