package com.aircontrol.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CursorSmootherStabilityTest {

    @Test
    fun `cursor locks still under landmark noise at rest`() {
        val smoother = CursorSmoother(minCutoff = 1.1f, beta = 3.5f, leadMs = 0f, deadZoneNormalized = 0.0016f)
        val rnd = Random(7)
        var t = 0L
        repeat(30) {
            smoother.filter(0.5f + (rnd.nextFloat() - 0.5f) * 0.004f, 0.5f + (rnd.nextFloat() - 0.5f) * 0.004f, t)
            t += 33
        }
        val locked = smoother.lastPosition!!
        repeat(60) {
            val (x, y) = smoother.filter(0.5f + (rnd.nextFloat() - 0.5f) * 0.004f, 0.5f + (rnd.nextFloat() - 0.5f) * 0.004f, t)
            assertEquals(locked.first, x, 0.0015f)
            assertEquals(locked.second, y, 0.0015f)
            t += 33
        }
    }

    @Test
    fun `cursor unlocks and follows a real movement`() {
        val smoother = CursorSmoother(minCutoff = 1.1f, beta = 3.5f, leadMs = 0f, deadZoneNormalized = 0.0016f)
        var t = 0L
        repeat(20) {
            smoother.filter(0.3f, 0.5f, t)
            t += 33
        }
        var x = 0.3f
        repeat(20) {
            x += 0.01f
            smoother.filter(x, 0.5f, t)
            t += 33
        }
        val out = smoother.lastPosition!!.first
        assertTrue("Expected cursor to follow movement, got $out", out > 0.45f)
    }

    @Test
    fun `gaze fixation lock holds small eye jitter`() {
        val smoother = CursorSmoother(minCutoff = 0.5f, beta = 1.5f, leadMs = 0f, deadZoneNormalized = 0.008f)
        val rnd = Random(11)
        var t = 0L
        repeat(30) {
            smoother.filter(0.4f + (rnd.nextFloat() - 0.5f) * 0.02f, 0.6f + (rnd.nextFloat() - 0.5f) * 0.02f, t)
            t += 33
        }
        val locked = smoother.lastPosition!!
        var moves = 0
        repeat(60) {
            val (gx, gy) = smoother.filter(0.4f + (rnd.nextFloat() - 0.5f) * 0.02f, 0.6f + (rnd.nextFloat() - 0.5f) * 0.02f, t)
            if (gx != locked.first || gy != locked.second) moves++
            t += 33
        }
        assertTrue("Gaze dot moved on $moves of 60 resting frames", moves <= 6)
    }
}
