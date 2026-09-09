package com.aircontrol.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit P0-4: the jump policy's hold is the only stage that can make a *working* tracker look
 * broken, because while it holds, the cursor does not move. These tests pin the two properties the
 * old implementation lacked — the hold's length is a duration, not a frame count, and a held cursor
 * keeps travelling on the velocity that was actually measured instead of stopping dead.
 *
 * Every case drives the policy with explicit timestamps, so the assertions are about elapsed
 * pipeline time. Nothing sleeps: a test that waited on a real clock would only prove this machine
 * was fast while it ran.
 */
class GazeJumpPolicyTimingTest {

    private val acceptedX = 0.30f
    private val acceptedY = 0.50f

    /** A jump far past the limit with no iris motion to explain it: the hold case. */
    private fun disputed(policy: GazeJumpPolicy, atMs: Long, x: Float = 0.95f) =
        policy.evaluate(predictedX = x, predictedY = acceptedY, irisX = 0f, irisY = 0f, timestampMs = atMs)

    private fun prime(policy: GazeJumpPolicy, firstTs: Long, intervalMs: Long) {
        // Two accepted samples, 0.01 apart in x: a real, measured velocity of 0.01/interval.
        policy.evaluate(acceptedX - 0.01f, acceptedY, 0f, 0f, firstTs)
        policy.evaluate(acceptedX, acceptedY, 0f, 0f, firstTs + intervalMs)
    }

    @Test
    fun `a hold expires after MAX_HOLD_MS of pipeline time whatever the frame rate`() {
        // 20 fps: 50 ms per frame.
        val at20fps = GazeJumpPolicy()
        prime(at20fps, firstTs = 0L, intervalMs = 50L)
        assertEquals(GazeJumpPolicy.JumpOutcome.HOLD, disputed(at20fps, 100L).outcome)
        assertEquals(GazeJumpPolicy.JumpOutcome.HOLD, disputed(at20fps, 150L).outcome)
        assertEquals(
            "120 ms of staleness is the bound, and at 20 fps that is ~2 frames",
            GazeJumpPolicy.JumpOutcome.REPRIME,
            disputed(at20fps, 200L).outcome,
        )

        // 5 fps: samples at 0, 200, 400 ms. The old 10-frame bound was a 2 SECOND freeze here.
        val at5fps = GazeJumpPolicy()
        prime(at5fps, firstTs = 0L, intervalMs = 200L)
        assertEquals(
            "the first held frame is already older than the bound, so the cursor waits one frame",
            GazeJumpPolicy.JumpOutcome.REPRIME,
            disputed(at5fps, 400L).outcome,
        )
    }

    @Test
    fun `the frame ceiling still ends a hold when no clock is supplied`() {
        // The pre-recovery contract, kept as a safety net: a caller without timestamps must never
        // be able to freeze the pointer forever.
        val policy = GazeJumpPolicy(maxHoldFrames = 3)
        policy.evaluate(acceptedX, acceptedY, 0f, 0f)
        repeat(3) {
            assertEquals(GazeJumpPolicy.JumpOutcome.HOLD, policy.evaluate(0.95f, acceptedY, 0f, 0f).outcome)
        }
        assertEquals(GazeJumpPolicy.JumpOutcome.REPRIME, policy.evaluate(0.95f, acceptedY, 0f, 0f).outcome)
    }

    @Test
    fun `a stalled timestamp cannot produce an unbounded hold`() {
        // Every sample arrives with the same timestamp (a real failure mode: the tracker clamps
        // MediaPipe timestamps to be monotonic, so a burst can share one value). holdAgeMs stays 0,
        // which is why the frame ceiling is not merely legacy - it is what keeps this bounded.
        val policy = GazeJumpPolicy(maxHoldFrames = 4)
        policy.evaluate(acceptedX, acceptedY, 0f, 0f, 1_000L)
        policy.evaluate(acceptedX, acceptedY, 0f, 0f, 1_000L)
        repeat(4) {
            assertEquals(GazeJumpPolicy.JumpOutcome.HOLD, disputed(policy, 1_000L).outcome)
        }
        assertEquals(GazeJumpPolicy.JumpOutcome.REPRIME, disputed(policy, 1_000L).outcome)
    }

    @Test
    fun `a held cursor glides on the measured velocity instead of stopping dead`() {
        val policy = GazeJumpPolicy()
        prime(policy, firstTs = 0L, intervalMs = 50L)
        val held = disputed(policy, 100L)
        assertEquals(GazeJumpPolicy.JumpOutcome.HOLD, held.outcome)
        assertTrue("the frame must be reported as continued", held.continued)
        assertTrue(
            "the cursor keeps moving in the direction it was already moving",
            held.x > acceptedX,
        )
        assertTrue(
            "and never past the disputed position it is refusing to trust yet",
            held.x < 0.95f,
        )
        assertEquals("one interval of travel at 0.01/50ms", 0.31f, held.x, 1e-4f)
        assertEquals(1L, policy.continuedFrameCount())
    }

    @Test
    fun `the continuation is bounded by time and by the disputed position itself`() {
        // A very fast gaze (0.1 per 10 ms) with a disputed sample 90 ms later. Two independent
        // caps must both hold, because either one alone is enough to turn a hold into a jump:
        //   time    - CONTINUATION_MAX_MS (60 ms) of travel, so 0.6 and not 0.9;
        //   evidence - never past the position the policy is currently refusing to trust.
        val policy = GazeJumpPolicy()
        policy.evaluate(0.1f, acceptedY, 0f, 0f, 0L)
        policy.evaluate(0.2f, acceptedY, 0f, 0f, 10L)
        val farDispute = policy.evaluate(0.95f, acceptedY, 0f, 0f, 100L)
        assertEquals(GazeJumpPolicy.JumpOutcome.HOLD, farDispute.outcome)
        assertTrue(farDispute.continued)
        assertEquals("60 ms of the measured velocity, not 90", 0.8f, farDispute.x, 1e-4f)

        val nearDispute = GazeJumpPolicy()
        nearDispute.evaluate(0.1f, acceptedY, 0f, 0f, 0L)
        nearDispute.evaluate(0.2f, acceptedY, 0f, 0f, 10L)
        val held = nearDispute.evaluate(0.4f, acceptedY, 0f, 0f, 100L)
        assertTrue(held.continued)
        assertEquals("the glide stops at the disputed position", 0.4f, held.x, 1e-4f)
    }

    @Test
    fun `a still gaze is held exactly and is not invented into motion`() {
        val policy = GazeJumpPolicy()
        policy.evaluate(acceptedX, acceptedY, 0f, 0f, 0L)
        policy.evaluate(acceptedX, acceptedY, 0f, 0f, 50L) // no movement between accepted samples
        val held = disputed(policy, 100L)
        assertEquals(GazeJumpPolicy.JumpOutcome.HOLD, held.outcome)
        assertFalse("zero measured velocity must produce zero continuation", held.continued)
        assertEquals(acceptedX, held.x, 0f)
        assertEquals(acceptedY, held.y, 0f)
    }

    @Test
    fun `without a clock the position is held exactly as before`() {
        // No-timestamp callers must see the old behaviour unchanged, including no continuation.
        val policy = GazeJumpPolicy()
        policy.evaluate(acceptedX - 0.01f, acceptedY, 0f, 0f)
        policy.evaluate(acceptedX, acceptedY, 0f, 0f)
        val held = policy.evaluate(0.95f, acceptedY, 0f, 0f)
        assertEquals(GazeJumpPolicy.JumpOutcome.HOLD, held.outcome)
        assertFalse(held.continued)
        assertEquals(acceptedX, held.x, 0f)
    }

    @Test
    fun `an explained jump still accepts immediately with no hold or continuation`() {
        // Regression guard for the part of the policy that already worked: a jump the iris motion
        // explains must not be held, whatever the timing rules around it.
        val policy = GazeJumpPolicy()
        policy.evaluate(acceptedX, acceptedY, 0f, 0f, 0L)
        policy.evaluate(acceptedX, acceptedY, 0f, 0f, 50L)
        val accepted = policy.evaluate(
            predictedX = 0.95f,
            predictedY = acceptedY,
            irisX = 1.6f,
            irisY = 0f,
            timestampMs = 100L,
        )
        assertEquals(GazeJumpPolicy.JumpOutcome.ACCEPT, accepted.outcome)
        assertFalse(accepted.continued)
        assertEquals(0.95f, accepted.x, 0f)
        assertEquals(1f, accepted.actionConfidenceFactor, 0f)
    }

    @Test
    fun `reset clears the timing baseline so a restart reprimes instead of holding`() {
        val policy = GazeJumpPolicy()
        prime(policy, firstTs = 0L, intervalMs = 50L)
        disputed(policy, 100L)
        policy.reset()
        assertFalse(policy.hasBaseline)
        assertEquals(GazeJumpPolicy.JumpOutcome.ACCEPT, disputed(policy, 100L).outcome)
    }
}
