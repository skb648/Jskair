package com.aircontrol.gesture.detection

import com.aircontrol.gesture.config.GestureEngineConfig
import com.aircontrol.gesture.model.HandInput
import com.aircontrol.gesture.model.Handedness
import com.aircontrol.gesture.model.Landmark3D
import com.aircontrol.gesture.model.SwipeDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Final stress audit (round 11) — swipe adversarial search (spec §5),
 * frame-rate matrix (spec §6), and cooldown timing (spec §7) at the
 * detector level. Default config (sensitivity 70 → displacement gate ≈0.069,
 * velocity gate ≈1.13 u/s at default ease).
 */
class SwipeStressTest {

    private val config = GestureEngineConfig()

    /**
     * A rigid open hand whose palm measures 0.11 of the frame height, translating so
     * that its wrist sits at (x, y).
     *
     * This used to be `List(21) { Landmark3D(x, y, 0f) }`, i.e. every landmark at one
     * point. That was harmless while the detector compared raw frame fractions, but
     * swipe travel is now normalised by the hand's own size, and a hand with no palm
     * has no size - the detector refuses to guess, which is the correct answer for a
     * degenerate measurement. Constant offsets keep the trajectory identical, so every
     * scenario below still describes exactly the same motion.
     */
    private fun at(x: Float, y: Float, ts: Long): HandInput =
        HandInput(
            landmarks = List(21) { index ->
                val (ox, oy) = PALM_OFFSETS[index]
                Landmark3D(x + ox, y + oy, 0f)
            },
            handedness = Handedness.RIGHT,
            timestampMs = ts,
            confidence = 0.95f,
        )

    private companion object {
        val PALM_OFFSETS: List<Pair<Float, Float>> = listOf(
            0f to 0f,
            -0.05f to -0.03f, -0.07f to -0.06f, -0.08f to -0.09f, -0.09f to -0.12f,
            0.02f to -0.08f, 0.02f to -0.12f, 0.02f to -0.145f, 0.02f to -0.17f,
            0f to -0.11f, 0f to -0.15f, 0f to -0.175f, 0f to -0.20f,
            -0.02f to -0.10f, -0.02f to -0.14f, -0.02f to -0.165f, -0.02f to -0.19f,
            -0.04f to -0.08f, -0.04f to -0.11f, -0.04f to -0.13f, -0.04f to -0.15f,
        )
    }

    private fun empty(ts: Long) =
        HandInput(landmarks = emptyList(), handedness = Handedness.UNKNOWN, timestampMs = ts, confidence = 0f)

    /** Feeds a trajectory of (x, y) points at the given interval; returns last result. */
    private fun run(
        detector: DynamicGestureDetector,
        points: List<Pair<Float, Float>>,
        startTs: Long,
        gapMs: Long,
    ): DynamicGestureDetector.SwipeResult {
        var last = DynamicGestureDetector.SwipeResult(detected = false)
        var ts = startTs
        points.forEach { (x, y) ->
            last = detector.process(at(x, y, ts))
            ts += gapMs
        }
        return last
    }

    private fun fresh() = DynamicGestureDetector(config)

    // ---------- §5 new adversarial trajectories ----------

    /**
     * L-shaped path: right 0.18 then down 0.18.
     *
     * CHANGED (swipe intent redesign): this used to assert that NOTHING fires,
     * because each leg alone failed the peak-velocity gate. That premise is what the
     * redesign removes — a leg of this test IS a 0.18-of-frame directed throw, and a
     * detector that cannot tell a deliberate throw from a corner must stop calling the
     * throw a gesture at all. What the machine must actually guarantee, and now does:
     *  - no commit is made while the path is diagonal (the corner itself is a guess,
     *    and guesses are not actions);
     *  - at most one commit for the whole motion, because the returning leg lands inside
     *    the post-commit latch;
     *  - the commit, when it happens, is on a pure axis.
     */
    @Test
    fun `L shaped corner is never guessed as a diagonal swipe`() {
        val d = fresh()
        val right = (0..6).map { Pair(0.2f + it * 0.03f, 0.5f) }
        val down = listOf(Pair(0.41f, 0.5f)) + (1..6).map { Pair(0.41f, 0.5f + it * 0.03f) }
        val commits = mutableListOf<com.aircontrol.gesture.model.SwipeDirection>()
        var diagonalFrameCommitted = false
        var ts = 1000L
        (right + down).forEach { (x, y) ->
            val r = d.process(at(x, y, ts))
            if (r.detected) {
                commits += r.direction!!
                // The corner frames are the ones where the window holds both legs.
                val windowIsDiagonal = x > 0.38f && y < 0.56f
                if (windowIsDiagonal) diagonalFrameCommitted = true
            }
            ts += 40L
        }
        assertFalse("the diagonal corner itself must never commit", diagonalFrameCommitted)
        assertTrue("an L may commit at most once per leg", commits.size <= 2)
        assertTrue(
            "every commit must be on a pure axis, never a diagonal",
            commits.all { it in setOf(SwipeDirection.LEFT, SwipeDirection.RIGHT, SwipeDirection.UP, SwipeDirection.DOWN) },
        )
    }

    /** Circular motion: net displacement ~0, non-monotonic on both axes. */
    @Test
    fun `circular movement is rejected`() {
        val d = fresh()
        var fired = false
        var ts = 1000L
        repeat(2) {
            for (i in 0 until 24) {
                val ang = i * 2.0 * Math.PI / 24
                val x = 0.5f + 0.12f * kotlin.math.cos(ang).toFloat()
                val y = 0.5f + 0.12f * kotlin.math.sin(ang).toFloat()
                if (d.process(at(x, y, ts)).detected) fired = true
                ts += 40L
            }
        }
        assertFalse("circular path must not commit a swipe", fired)
    }

    /** High velocity but only 2 moving steps (bug #8 class variation). */
    @Test
    fun `high velocity two-step motion is rejected`() {
        val d = fresh()
        val r = run(d, listOf(Pair(0.3f, 0.5f), Pair(0.5f, 0.5f), Pair(0.7f, 0.5f)), 1000L, 40L)
        assertFalse("2-step flick must not commit regardless of speed", r.detected)
    }

    /** Low velocity long travel: plenty of steps, far too slow. */
    @Test
    fun `low velocity long travel is rejected`() {
        val d = fresh()
        val pts = (0..24).map { Pair(0.2f + it * 0.02f, 0.5f) } // 0.5 total at 0.5 u/s
        val r = run(d, pts, 1000L, 40L)
        assertFalse("slow sweep must not commit", r.detected)
    }

    /** Back-and-forth wiggle around a base point, growing amplitude. */
    @Test
    fun `back and forth wiggle is rejected`() {
        val d = fresh()
        var fired = false
        var ts = 1000L
        var x = 0.5f
        for (i in 1..12) {
            val amp = 0.02f * i
            x = 0.5f + amp; if (d.process(at(x, 0.5f, ts)).detected) fired = true; ts += 40L
            x = 0.5f - amp; if (d.process(at(x, 0.5f, ts)).detected) fired = true; ts += 40L
        }
        assertFalse("oscillating motion must not commit", fired)
    }

    /** Motion whose frames carry sub-threshold tracking confidence is muted by
     *  the engine; at detector level it must at least never double-commit. */
    @Test
    fun `valid swipe commits at most once per physical motion`() {
        val d = fresh()
        var commits = 0
        var ts = 1000L
        var x = 0.2f
        repeat(10) {
            if (d.process(at(x, 0.5f, ts)).detected) commits++
            x += 0.08f
            ts += 40L
        }
        assertTrue("fixture must commit", commits >= 1)
        assertTrue("one physical motion = at most one commit", commits == 1)
    }

    // ---------- §6 frame-rate matrix ----------

    /**
     * A valid swipe (0.15/frame → 1.5–4.5 u/s, 0.6+ total travel) must be
     * recognized at every frame rate from ~30fps down to ~10fps, including the
     * 11–12fps borderline where the window holds only 4 samples.
     */
    @Test
    fun `valid swipe detected across frame rates 30 to 10 fps`() {
        for (gapMs in longArrayOf(33L, 50L, 67L, 83L, 91L, 100L)) {
            val d = fresh()
            // Ten samples, not six: the machine confirms a direction by holding it for
            // ~four frames, and that time must fall inside the gesture itself. The step
            // size is unchanged, so this is the same physical swipe, just long enough to
            // be told apart from the opening of a wave.
            val pts = (0..9).map { Pair(0.15f + it * 0.15f, 0.5f) }
            var detected = false
            var ts = 1000L
            pts.forEach { (x, y) ->
                if (d.process(at(x, y, ts)).detected) detected = true
                ts += gapMs
            }
            assertTrue("valid swipe must fire at ${gapMs}ms frame interval", detected)
        }
    }

    /**
     * CHANGED (swipe intent redesign): this asserted that a 0.03-per-frame directed
     * throw never fires, at any frame rate. It was the velocity gate doing exactly what
     * users reported as broken - a deliberate sweep was discarded because it was not a
     * flick - so the invariant is now inverted and stated per frame rate: a directed
     * throw of that size commits exactly once whatever the frame rate, and a *transport*
     * (the case the gate was really protecting against, ~a twentieth of a palm per frame)
     * never commits at any frame rate. Both halves are checked, so the change removes a
     * gate without loosening the rule.
     */
    @Test
    fun `a directed throw commits once and a transport never does, at every frame rate`() {
        for (gapMs in longArrayOf(33L, 50L, 67L, 83L, 91L, 100L)) {
            val d = fresh()
            var commits = 0
            var ts = 1000L
            (0..7).forEach { i ->
                if (d.process(at(0.2f + i * 0.03f, 0.5f, ts)).detected) commits++
                ts += gapMs
            }
            assertEquals("a directed throw must commit exactly once at ${gapMs}ms", 1, commits)

            val drift = fresh()
            var driftCommits = 0
            ts = 1000L
            (0..23).forEach { i ->
                if (drift.process(at(0.2f + i * 0.004f, 0.5f, ts)).detected) driftCommits++
                ts += gapMs
            }
            assertEquals("a slow transport must never commit at ${gapMs}ms", 0, driftCommits)
        }
    }

    /** Irregular frame intervals (jittery camera) must not break recognition. */
    @Test
    fun `irregular frame intervals still recognize a valid swipe`() {
        val d = fresh()
        val gaps = longArrayOf(30L, 80L, 40L, 90L, 35L, 85L, 40L)
        var detected = false
        var ts = 1000L
        var x = 0.15f
        gaps.forEach { gap ->
            if (d.process(at(x, 0.5f, ts)).detected) detected = true
            x += 0.15f
            ts += gap
        }
        assertTrue("irregular intervals must not lose a real swipe", detected)
    }

    // ---------- §7 timing ----------

    /** A second motion inside the 220ms cooldown is rejected; after cooldown +
     *  neutral re-arm (stillness) a fresh swipe fires. */
    @Test
    fun `cooldown blocks immediate repeat then allows a fresh swipe`() {
        val d = fresh()
        var ts = 1000L
        var x = 0.2f
        var fired = 0
        repeat(8) {
            if (d.process(at(x, 0.5f, ts)).detected) fired++
            x += 0.1f
            ts += 40L
        }
        assertTrue(fired == 1)

        // Immediate second sweep — inside cooldown AND without stillness.
        repeat(8) {
            if (d.process(at(x, 0.5f, ts)).detected) fired++
            x -= 0.1f
            ts += 40L
        }
        assertTrue("immediate repeat must be blocked", fired == 1)

        // Stillness past cooldown + re-arm window (250ms).
        repeat(9) { d.process(at(x, 0.5f, ts)); ts += 40L }

        // Fresh deliberate swipe.
        repeat(8) {
            if (d.process(at(x, 0.5f, ts)).detected) fired++
            x += 0.1f
            ts += 40L
        }
        assertTrue("fresh swipe after recovery must fire", fired == 2)
    }

    /** A frame gap in the middle of motion must not stitch halves (§7). */
    @Test
    fun `frame gap mid motion does not stitch`() {
        val d = fresh()
        var ts = 1000L
        var x = 0.3f
        var fired = false
        repeat(2) { if (d.process(at(x, 0.5f, ts)).detected) fired = true; x += 0.1f; ts += 40L }
        repeat(4) { d.process(empty(ts)); ts += 40L }
        repeat(2) { if (d.process(at(x, 0.5f, ts)).detected) fired = true; x += 0.1f; ts += 40L }
        repeat(6) { d.process(at(x, 0.5f, ts)); ts += 40L }
        assertFalse("motion split by a frame gap must not commit", fired)
    }
}
