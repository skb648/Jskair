package com.aircontrol.gesture.intent

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafetyPolicyTest {
    @Test
    fun clickRequiresStrongConfidenceAndSettledHand() {
        assertFalse(SafetyPolicy.isClickSafe(0.70f, 120L, 0.005f, 1000L, Long.MIN_VALUE))
        assertFalse(SafetyPolicy.isClickSafe(0.90f, 50L, 0.005f, 1000L, Long.MIN_VALUE))
        assertFalse(SafetyPolicy.isClickSafe(0.90f, 120L, 0.025f, 1000L, Long.MIN_VALUE))
        assertTrue(SafetyPolicy.isClickSafe(0.90f, 120L, 0.005f, 1000L, Long.MIN_VALUE))
    }

    @Test
    fun clickCooldownBlocksRepeatedFalseClicks() {
        assertFalse(SafetyPolicy.isClickSafe(0.95f, 150L, 0.002f, 1050L, 1000L))
        assertTrue(SafetyPolicy.isClickSafe(0.95f, 150L, 0.002f, 1200L, 1000L))
    }

    @Test
    fun doubleClickRequiresCloseSpatialAndTemporalMatch() {
        assertTrue(SafetyPolicy.isDoubleClickCandidate(0.90f, 1250L, 1000L, 0.001f))
        assertFalse(SafetyPolicy.isDoubleClickCandidate(0.90f, 1350L, 1000L, 0.001f))
        assertFalse(SafetyPolicy.isDoubleClickCandidate(0.90f, 1250L, 1000L, 0.01f))
        assertFalse(SafetyPolicy.isDoubleClickCandidate(0.70f, 1250L, 1000L, 0.001f))
    }

    @Test
    fun customGestureRequiresDeliberateHoldAndConfidence() {
        assertFalse(SafetyPolicy.isCustomGestureSafe(0.90f, 100L))
        assertFalse(SafetyPolicy.isCustomGestureSafe(0.80f, 200L))
        assertTrue(SafetyPolicy.isCustomGestureSafe(0.90f, 200L))
    }
}
