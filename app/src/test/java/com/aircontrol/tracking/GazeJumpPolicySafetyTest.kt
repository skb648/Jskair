package com.aircontrol.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GazeJumpPolicySafetyTest {
    @Test
    fun nonFinitePredictionIsHeldNotAccepted() {
        val policy = GazeJumpPolicy()
        val decision = policy.evaluate(
            predictedX = Float.NaN,
            predictedY = 0.5f,
            irisX = 0f,
            irisY = 0f,
            timestampMs = 100L,
        )
        assertEquals(GazeJumpPolicy.JumpOutcome.HOLD, decision.outcome)
        assertTrue(decision.actionConfidenceFactor < 0.45f)
        assertTrue(decision.x.isFinite() && decision.y.isFinite())
    }

    @Test
    fun nonFinitePredictionUsesLastAcceptedPositionWhenAvailable() {
        val policy = GazeJumpPolicy()
        policy.evaluate(0.2f, 0.3f, 0f, 0f, 100L)
        val decision = policy.evaluate(Float.POSITIVE_INFINITY, 0.9f, 0f, 0f, 120L)
        assertEquals(GazeJumpPolicy.JumpOutcome.HOLD, decision.outcome)
        assertFalse(decision.x.isNaN())
        assertEquals(0.2f, decision.x, 1e-6f)
        assertEquals(0.3f, decision.y, 1e-6f)
    }
}
