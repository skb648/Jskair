package com.aircontrol.gesture

import com.aircontrol.gesture.config.GestureEngineConfig
import com.aircontrol.gesture.model.GestureEngineState
import com.aircontrol.gesture.model.GestureEvent
import com.aircontrol.gesture.model.HandInput
import com.aircontrol.gesture.model.Handedness
import com.aircontrol.gesture.model.Landmark3D
import com.aircontrol.gesture.model.PinchPhase
import com.aircontrol.gesture.model.SwipeDirection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adversarial simulation suite (hardening round 10, spec §22). Deterministic
 * sequences — no randomness unless seeded and stated. Float anchors use
 * binary-exact fractions (0.5, 0.0625, 0.125, …) per spec §18.
 *
 * Recognizer must FAIL SAFE: ambiguous → reject, low confidence → reject,
 * incomplete → cancel, tracking lost → reset.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GestureAdversarialTest {

    private fun hand(
        timestampMs: Long,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
        scale: Float = 0.35f,
        index: Boolean = true,
        middle: Boolean = true,
        ring: Boolean = true,
        pinky: Boolean = true,
        thumbOut: Boolean = true,
        pinchGap: Float? = null,
        confidence: Float = 0.95f,
    ): HandInput {
        val wx = 0.5f + offsetX
        val wy = 0.75f + offsetY
        val s = scale
        fun finger(xOffset: Float, extended: Boolean): List<Landmark3D> {
            val mcp = Landmark3D(wx + xOffset * s, wy - 1.0f * s, 0f)
            val pipY = if (extended) wy - 1.5f * s else wy - 1.3f * s
            val dipY = if (extended) wy - 1.8f * s else wy - 1.1f * s
            val tipY = if (extended) wy - 2.1f * s else wy - 0.95f * s
            val x = wx + xOffset * s
            return listOf(mcp, Landmark3D(x, pipY, 0f), Landmark3D(x, dipY, 0f), Landmark3D(x, tipY, 0f))
        }
        val wrist = Landmark3D(wx, wy, 0f)
        val thumbTip = if (pinchGap != null) {
            Landmark3D(wx - 0.15f * s + pinchGap * s, wy - 2.1f * s, 0f)
        } else if (thumbOut) {
            Landmark3D(wx - 0.68f * s, wy - 0.68f * s, 0f)
        } else {
            Landmark3D(wx - 0.35f * s, wy - 0.45f * s, 0f)
        }
        val landmarks = mutableListOf(wrist)
        landmarks += listOf(
            Landmark3D(wx - 0.2f * s, wy - 0.2f * s, 0f),
            Landmark3D(wx - 0.3f * s, wy - 0.3f * s, 0f),
            Landmark3D(wx - 0.5f * s, wy - 0.5f * s, 0f),
            thumbTip,
        )
        landmarks += finger(-0.15f, index)
        landmarks += finger(0f, middle)
        landmarks += finger(0.15f, ring)
        landmarks += finger(0.30f, pinky)
        assertEquals(21, landmarks.size)
        return HandInput(landmarks, Handedness.RIGHT, timestampMs, confidence)
    }

    private fun arm(engine: GestureEngine, startTs: Long = 1000L, gap: Long = 40L): Long {
        var ts = startTs
        repeat(12) {
            engine.processFrame(hand(ts)); ts += gap
            if (engine.engineState.value == GestureEngineState.ARMED) return ts
        }
        throw AssertionError("never armed")
    }

    private fun still(engine: GestureEngine, ts: Long, frames: Int, x: Float, gap: Long = 40L): Long {
        var t = ts
        repeat(frames) { engine.processFrame(hand(t, offsetX = x)); t += gap }
        return t
    }

    // 1. Seeded pseudo-random jitter — no discrete event may fire.
    @Test
    fun `seeded random jitter produces no discrete events`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = GestureEngine(GestureEngineConfig())
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()
        var ts = arm(engine)
        var seed = 0x2545F491L
        var x = 0f
        repeat(60) {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            val jitter = ((seed ushr 33).toInt() % 100) / 100f * 0.004f - 0.002f // ±0.002/frame
            x += jitter
            engine.processFrame(hand(ts, offsetX = x))
            ts += 40L
        }
        runCurrent()
        assertEquals(
            "tracker-level jitter must not create commands",
            0,
            events.count { it is GestureEvent.Swipe || it is GestureEvent.PoseTriggered || it is GestureEvent.Pinch || it is GestureEvent.PalmHome },
        )
        job.cancel()
    }

    // 2. Slow drift — 0.001/frame for 60 frames (0.06 total, 0.025 u/s).
    @Test
    fun `slow drift never becomes a swipe`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = GestureEngine(GestureEngineConfig())
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()
        var ts = arm(engine)
        var x = 0f
        repeat(60) {
            engine.processFrame(hand(ts, offsetX = x)); x += 0.001f; ts += 40L
        }
        runCurrent()
        assertEquals(0, events.count { it is GestureEvent.Swipe })
        job.cancel()
    }

    // 3. Single-frame teleport (tracking glitch), not a swipe.
    @Test
    fun `single frame teleport is not a swipe`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = GestureEngine(GestureEngineConfig())
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()
        var ts = arm(engine, startTs = 1000L)
        // Still at 0, then one 0.3 jump, then still.
        engine.processFrame(hand(ts, offsetX = 0.3f)); ts += 40L
        ts = still(engine, ts, 8, 0.3f)
        runCurrent()
        assertEquals("a teleport must not fire (too few moving steps)", 0, events.count { it is GestureEvent.Swipe })
        job.cancel()
    }

    // 4. Direction reversal — each half too short to be a swipe alone; if the
    //    halves were ever stitched the combined 0.25 displacement would fire.
    @Test
    fun `direction reversal is not a swipe`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = GestureEngine(GestureEngineConfig())
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()
        var ts = arm(engine)
        var x = 0f
        repeat(2) { engine.processFrame(hand(ts, offsetX = x)); x += 0.0625f; ts += 40L }
        repeat(2) { engine.processFrame(hand(ts, offsetX = x)); x -= 0.0625f; ts += 40L }
        ts = still(engine, ts, 10, x)
        runCurrent()
        // Regression (round 10 bug #8): neither half of an out-and-back wiggle
        // may fire — the old <8-sample window relaxation committed 2-step motion.
        assertEquals("rightward half must not fire", 0, events.count { it is GestureEvent.Swipe && it.direction == SwipeDirection.RIGHT })
        assertEquals("leftward return must not fire", 0, events.count { it is GestureEvent.Swipe && it.direction == SwipeDirection.LEFT })
        job.cancel()
    }

    // 4b. Regression (round 10 bug #8): a 2-moving-step half-swipe immediately
    // after arming (small window) must NOT commit.
    @Test
    fun `outward half alone does not fire`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = GestureEngine(GestureEngineConfig())
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()
        var ts = arm(engine)
        var x = 0f
        repeat(2) { engine.processFrame(hand(ts, offsetX = x)); x += 0.0625f; ts += 40L }
        ts = still(engine, ts, 10, x)
        runCurrent()
        assertEquals("two moving steps alone must not fire", 0, events.count { it is GestureEvent.Swipe })
        job.cancel()
    }

    // 5. Repeated identical motion — exactly one event per physical swipe.
    @Test
    fun `three identical swipes fire exactly three events`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = GestureEngine(GestureEngineConfig())
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()
        var ts = arm(engine)
        repeat(3) {
            var x = 0f
            repeat(6) { engine.processFrame(hand(ts, offsetX = x)); x += 0.0625f; ts += 40L }
            ts = still(engine, ts, 12, x) // full neutral re-arm between swipes
        }
        runCurrent()
        val swipes = events.filterIsInstance<GestureEvent.Swipe>()
        assertEquals("one physical swipe = one event", 3, swipes.size)
        assertTrue(swipes.all { it.direction == SwipeDirection.RIGHT })
        job.cancel()
    }

    // 6. Displacement boundary pair (binary-exact anchors; default s=70 →
    //    scaled displacement gate ≈ 0.069; velocity gate ≈ 1.03 u/s).
    @Test
    fun `displacement boundary is respected on both sides`() = runTest {
        // Below: total 0.0625 (4 × 0.015625) — also below the velocity gate.
        run {
            val events = mutableListOf<GestureEvent>()
            val engine = GestureEngine(GestureEngineConfig())
            val job = launch { engine.gestureEvents.toList(events) }
            runCurrent()
            var ts = arm(engine)
            var x = 0f
            repeat(4) { engine.processFrame(hand(ts, offsetX = x)); x += 0.015625f; ts += 40L }
            ts = still(engine, ts, 8, x)
            runCurrent()
            assertEquals("0.0625 < 0.08 gate → no swipe", 0, events.count { it is GestureEvent.Swipe })
            job.cancel()
        }
        // Above: total 0.25 (4 × 0.0625) at 1.5625 u/s → swipe.
        run {
            val events = mutableListOf<GestureEvent>()
            val engine = GestureEngine(GestureEngineConfig())
            val job = launch { engine.gestureEvents.toList(events) }
            runCurrent()
            var ts = arm(engine)
            var x = 0f
            repeat(4) { engine.processFrame(hand(ts, offsetX = x)); x += 0.0625f; ts += 40L }
            ts = still(engine, ts, 8, x)
            runCurrent()
            assertEquals("0.25 ≥ gate with valid velocity → exactly one swipe", 1, events.count { it is GestureEvent.Swipe })
            job.cancel()
        }
    }

    // 7. Confidence flicker around 0.7 with fingers pinched. Four consecutive
    //    bad frames ENTER low-confidence mode first; the alternating phase
    //    then never provides 3 consecutive good frames, so the exit-hysteresis
    //    must keep the mode (and the pinch entry gate) latched.
    @Test
    fun `confidence flicker around the threshold never clicks`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = GestureEngine(GestureEngineConfig())
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()
        var ts = arm(engine)
        repeat(4) {
            engine.processFrame(hand(ts, pinchGap = 0.08f, confidence = 0.5f)); ts += 40L
        }
        repeat(20) { i ->
            engine.processFrame(hand(ts, pinchGap = 0.08f, confidence = if (i % 2 == 0) 0.5f else 0.95f))
            ts += 40L
        }
        runCurrent()
        assertEquals(
            "oscillating confidence must keep pinch entry muted (exit hysteresis)",
            0,
            events.count { it is GestureEvent.Pinch && it.phase == PinchPhase.START },
        )
        job.cancel()
    }

    // 8. Interrupted BEFORE commit: dropout splits the motion; halves may not
    //    be stitched (each half below the sample/moving-step requirement).
    @Test
    fun `interrupted motion does not resume after reappearance`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = GestureEngine(GestureEngineConfig())
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()
        var ts = arm(engine)
        // Two fast frames only (below the 3-moving-step requirement)…
        var x = 0f
        repeat(2) { engine.processFrame(hand(ts, offsetX = x)); x += 0.0625f; ts += 40L }
        // …tracking lost for 5 frames…
        repeat(5) { engine.processFrame(HandInput(landmarks = emptyList(), handedness = Handedness.UNKNOWN, timestampMs = ts, confidence = 0f)); ts += 40L }
        // …hand reappears CONTINUING the same rightward sweep — still only two
        // moving steps of fresh evidence.
        repeat(2) { engine.processFrame(hand(ts, offsetX = x)); x += 0.0625f; ts += 40L }
        ts = still(engine, ts, 10, x)
        runCurrent()
        assertEquals(
            "motion spanning a disappearance must never be stitched into a swipe",
            0,
            events.count { it is GestureEvent.Swipe },
        )
        job.cancel()
    }

    // 9. Pinch hover-boundary flutter (0.30 / 0.50 around hover 0.418, both
    //    outside enter 0.22) — no click.
    @Test
    fun `pinch hover boundary flutter does not click`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = GestureEngine(GestureEngineConfig())
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()
        var ts = arm(engine)
        repeat(16) { i ->
            engine.processFrame(hand(ts, pinchGap = if (i % 2 == 0) 0.30f else 0.50f))
            ts += 40L
        }
        runCurrent()
        assertEquals(
            "flutter between hover and idle must never emit a pinch event",
            0,
            events.count { it is GestureEvent.Pinch },
        )
        job.cancel()
    }

    // 10. Low-confidence landmarks must not fire pose actions; recovery fires.
    @Test
    fun `low confidence victory pose is muted until tracking recovers`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = GestureEngine(GestureEngineConfig())
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()
        var ts = arm(engine)
        // VICTORY (index+middle only) held while tracking is unreliable.
        repeat(12) {
            engine.processFrame(hand(ts, ring = false, pinky = false, thumbOut = false, confidence = 0.5f))
            ts += 40L
        }
        runCurrent()
        assertEquals(
            "blurry landmarks must not fire pose actions (round 10 fix)",
            0,
            events.count { it is GestureEvent.PoseTriggered },
        )
        // Tracking recovers with the pose still held → fires exactly once.
        repeat(8) {
            engine.processFrame(hand(ts, ring = false, pinky = false, thumbOut = false, confidence = 0.95f))
            ts += 40L
        }
        runCurrent()
        assertEquals(
            "after confidence recovery the held pose fires exactly once",
            1,
            events.count { it is GestureEvent.PoseTriggered },
        )
        job.cancel()
    }
}
