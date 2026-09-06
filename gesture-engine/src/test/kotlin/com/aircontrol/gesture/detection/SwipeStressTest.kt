package com.aircontrol.gesture.detection

import com.aircontrol.gesture.config.GestureEngineConfig
import com.aircontrol.gesture.model.HandInput
import com.aircontrol.gesture.model.Handedness
import com.aircontrol.gesture.model.Landmark3D
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

    private fun at(x: Float, y: Float, ts: Long): HandInput =
        HandInput(List(21) { Landmark3D(x, y, 0f) }, Handedness.RIGHT, ts, 0.95f)

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

    /** L-shaped: right 0.18 then down 0.18, each leg individually too slow
     *  (0.75 u/s < gate) — and the corner window is diagonal. No guess. */
    @Test
    fun `L shaped movement is rejected`() {
        val d = fresh()
        val right = (0..6).map { Pair(0.2f + it * 0.03f, 0.5f) }
        val down = (1..6).map { Pair(0.41f, 0.5f + it * 0.03f) }
        var fired = false
        var ts = 1000L
        (right + down).forEach { (x, y) -> if (d.process(at(x, y, ts)).detected) fired = true; ts += 40L }
        assertFalse("L-shaped path must not commit any direction", fired)
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
            val pts = (0..5).map { Pair(0.15f + it * 0.15f, 0.5f) }
            var detected = false
            var ts = 1000L
            pts.forEach { (x, y) ->
                if (d.process(at(x, y, ts)).detected) detected = true
                ts += gapMs
            }
            assertTrue("valid swipe must fire at ${gapMs}ms frame interval", detected)
        }
    }

    /** The too-slow rejection must hold at every frame rate too. */
    @Test
    fun `too slow swipe rejected across frame rates`() {
        for (gapMs in longArrayOf(33L, 50L, 67L, 83L, 91L, 100L)) {
            val d = fresh()
            val pts = (0..7).map { Pair(0.2f + it * 0.03f, 0.5f) } // ~0.03/frame
            var detected = false
            var ts = 1000L
            pts.forEach { (x, y) ->
                if (d.process(at(x, y, ts)).detected) detected = true
                ts += gapMs
            }
            assertFalse("slow motion must not fire at ${gapMs}ms interval", detected)
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
