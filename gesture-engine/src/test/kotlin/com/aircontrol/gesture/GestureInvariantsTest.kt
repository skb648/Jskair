package com.aircontrol.gesture

import com.aircontrol.gesture.config.GestureEngineConfig
import com.aircontrol.gesture.model.GestureEngineState
import com.aircontrol.gesture.model.GestureEvent
import com.aircontrol.gesture.model.HandInput
import com.aircontrol.gesture.model.Handedness
import com.aircontrol.gesture.model.Landmark3D
import com.aircontrol.gesture.model.PinchPhase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Final stress audit (round 11) — property-style invariants (spec §12) plus
 * the numeric-robustness regressions (spec §11) and the low-confidence
 * boundary sequences (spec §4). Every test states the invariant it pins.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GestureInvariantsTest {

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

    /** 21 landmarks collapsed onto a single point (degenerate tracker output). */
    private fun collapsedHand(timestampMs: Long, confidence: Float = 0.95f): HandInput =
        HandInput(List(21) { Landmark3D(0.5f, 0.5f, 0f) }, Handedness.RIGHT, timestampMs, confidence)

    /** 21 NaN landmarks — numerically invalid tracker output. */
    private fun nanHand(timestampMs: Long): HandInput =
        HandInput(List(21) { Landmark3D(Float.NaN, Float.NaN, Float.NaN) }, Handedness.RIGHT, timestampMs, 0.95f)

    private fun empty(ts: Long) =
        HandInput(landmarks = emptyList(), handedness = Handedness.UNKNOWN, timestampMs = ts, confidence = 0f)

    private fun arm(engine: GestureEngine, startTs: Long = 1000L, gap: Long = 40L): Long {
        var ts = startTs
        repeat(12) {
            engine.processFrame(hand(ts)); ts += gap
            if (engine.engineState.value == GestureEngineState.ARMED) return ts
        }
        throw AssertionError("never armed")
    }

    // INVARIANT 1: one discrete physical gesture → exactly one commit.
    @Test
    fun `INV1 one physical swipe commits exactly once`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = GestureEngine(GestureEngineConfig())
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()
        var ts = arm(engine)
        var x = 0f
        repeat(8) { engine.processFrame(hand(ts, offsetX = x)); x += 0.0625f; ts += 40L }
        repeat(12) { engine.processFrame(hand(ts, offsetX = x)); ts += 40L }
        runCurrent()
        assertEquals(1, events.count { it is GestureEvent.Swipe })
        job.cancel()
    }

    // INVARIANT 2: tracking loss cannot complete an incomplete gesture.
    @Test
    fun `INV2 pinch candidate abandoned by tracking loss never clicks`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = GestureEngine(GestureEngineConfig())
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()
        var ts = arm(engine)
        // Two pinch frames: HOVER then PINCH_START — START is only confirmed
        // after 80ms in PINCH_START (frame 3). Lose the hand before that.
        engine.processFrame(hand(ts, pinchGap = 0.08f)); ts += 40L
        engine.processFrame(hand(ts, pinchGap = 0.08f)); ts += 40L
        repeat(4) { engine.processFrame(empty(ts)); ts += 40L }
        // Hand returns with fingers still together — the FSM must start over
        // from IDLE, never resume the abandoned candidate (bug #10).
        val firstReappearTs = ts
        repeat(8) { engine.processFrame(hand(ts, pinchGap = 0.08f)); ts += 40L }
        runCurrent()
        val start = events.filterIsInstance<GestureEvent.Pinch>().firstOrNull { it.phase == PinchPhase.START }
        assertTrue("a fresh pinch must click after reappearance", start != null)
        // Pre-fix, the stale candidate committed on the FIRST reappearance
        // frame (debounce satisfied across the gap). A fresh validation needs
        // HOVER → START → 80ms, i.e. at least ~120ms after reappearance.
        assertTrue(
            "START must come from a freshly validated candidate, not a resumed one",
            start!!.timestampMs >= firstReappearTs + 120L,
        )
        assertEquals(1, events.count { it is GestureEvent.Pinch && it.phase == PinchPhase.START })
        job.cancel()
    }

    // INVARIANT 3 (spec §4 sequences): isolated bad frames are normal tracking
    // and must NOT block a deliberate pinch (mode never engages without 3
    // consecutive bad frames).
    @Test
    fun `INV3 isolated bad frames do not block clicking`() = runTest {
        for (pattern in listOf(
            booleanArrayOf(true, true, false, true, false, true, true, true, true, true), // GGBGBG…
            booleanArrayOf(false, true, false, true, false, true, true, true, true, true), // BGBGBG…
        )) {
            val events = mutableListOf<GestureEvent>()
            val engine = GestureEngine(GestureEngineConfig())
            val job = launch { engine.gestureEvents.toList(events) }
            runCurrent()
            var ts = arm(engine)
            pattern.forEach { good ->
                engine.processFrame(hand(ts, pinchGap = 0.08f, confidence = if (good) 0.95f else 0.5f))
                ts += 40L
            }
            runCurrent()
            assertEquals(
                "pattern=${pattern.joinToString()} must still click exactly once",
                1,
                events.count { it is GestureEvent.Pinch && it.phase == PinchPhase.START },
            )
            job.cancel()
        }
    }

    // INVARIANT 3 (spec §4 sequences): sustained degradation DURING FORMATION
    // blocks the click; recovery reopens it.
    @Test
    fun `INV3 sustained degradation blocks clicking until recovery`() = runTest {
        for (conf in listOf(
            floatArrayOf(0.6f, 0.5f, 0.4f, 0.3f, 0.3f, 0.3f, 0.3f, 0.3f), // gradual, stays bad
            floatArrayOf(0.2f, 0.2f, 0.2f, 0.2f, 0.2f, 0.2f, 0.2f, 0.2f), // sudden, bad from formation
            floatArrayOf(0.6f, 0.5f, 0.4f, 0.95f, 0.95f, 0.95f, 0.95f, 0.95f), // bad then recovers
        )) {
            val events = mutableListOf<GestureEvent>()
            val engine = GestureEngine(GestureEngineConfig())
            val job = launch { engine.gestureEvents.toList(events) }
            runCurrent()
            var ts = arm(engine)
            conf.forEach { c ->
                engine.processFrame(hand(ts, pinchGap = 0.08f, confidence = c))
                ts += 40L
            }
            runCurrent()
            val starts = events.count { it is GestureEvent.Pinch && it.phase == PinchPhase.START }
            val recovered = conf.takeLast(4).all { it > 0.7f }
            if (recovered) {
                assertEquals(
                    "conf=${conf.joinToString()} recovers → exactly one click",
                    1, starts,
                )
            } else {
                assertEquals(
                    "conf=${conf.joinToString()} stays degraded → no click",
                    0, starts,
                )
            }
            job.cancel()
        }
    }

    // INVARIANT 3 boundary (documented behavior): a pinch FORMED under good
    // tracking may complete its 80ms confirm even if confidence degrades
    // mid-confirm — the evidence was gathered while tracking was good, and
    // cancelling a real pinch because light changed 40ms in would be a false
    // negative. Low-confidence gating protects ENTRY, not completion.
    @Test
    fun `INV3 degradation after formation does not cancel a real pinch`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = GestureEngine(GestureEngineConfig())
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()
        var ts = arm(engine)
        engine.processFrame(hand(ts, pinchGap = 0.08f, confidence = 0.95f)); ts += 40L // HOVER
        engine.processFrame(hand(ts, pinchGap = 0.08f, confidence = 0.95f)); ts += 40L // PINCH_START
        repeat(6) { engine.processFrame(hand(ts, pinchGap = 0.08f, confidence = 0.2f)); ts += 40L }
        runCurrent()
        assertEquals(1, events.count { it is GestureEvent.Pinch && it.phase == PinchPhase.START })
        job.cancel()
    }

    // INVARIANT 4: a cancelled candidate cannot later commit using stale data.
    @Test
    fun `INV4 motion split by tracking loss never commits`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = GestureEngine(GestureEngineConfig())
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()
        var ts = arm(engine)
        var x = 0f
        repeat(2) { engine.processFrame(hand(ts, offsetX = x)); x += 0.0625f; ts += 40L }
        repeat(4) { engine.processFrame(empty(ts)); ts += 40L }
        repeat(2) { engine.processFrame(hand(ts, offsetX = x)); x += 0.0625f; ts += 40L }
        repeat(10) { engine.processFrame(hand(ts, offsetX = x)); ts += 40L }
        runCurrent()
        assertEquals(0, events.count { it is GestureEvent.Swipe })
        job.cancel()
    }

    // INVARIANT 5: a committed gesture cannot immediately self-trigger.
    @Test
    fun `INV5 swipe cannot self-trigger without fresh intent`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = GestureEngine(GestureEngineConfig())
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()
        var ts = arm(engine)
        var x = 0f
        repeat(6) { engine.processFrame(hand(ts, offsetX = x)); x += 0.0625f; ts += 40L }
        // Immediately sweep back — no stillness, no neutral re-arm.
        repeat(6) { engine.processFrame(hand(ts, offsetX = x)); x -= 0.0625f; ts += 40L }
        runCurrent()
        assertEquals("return sweep without re-arm must not fire", 1, events.count { it is GestureEvent.Swipe })
        job.cancel()
    }

    // INVARIANT 6: ambiguous candidates reject rather than guess.
    @Test
    fun `INV6 diagonal ambiguity rejects rather than guesses`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = GestureEngine(GestureEngineConfig())
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()
        var ts = arm(engine)
        var x = 0f
        var y = 0f
        repeat(8) {
            engine.processFrame(hand(ts, offsetX = x, offsetY = y))
            x += 0.0625f; y += 0.0625f; ts += 40L
        }
        repeat(10) { engine.processFrame(hand(ts, offsetX = x, offsetY = y)); ts += 40L }
        runCurrent()
        assertEquals("45° motion must not be forced into a direction", 0, events.count { it is GestureEvent.Swipe })
        job.cancel()
    }

    // INVARIANT 7 + stress-audit bug #9: degenerate (collapsed) landmarks can
    // never read as a pinch.
    @Test
    fun `INV7 degenerate collapsed landmarks never click`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = GestureEngine(GestureEngineConfig())
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()
        var ts = arm(engine)
        // Collapsed hand: all 21 landmarks identical, confidence high. The
        // pre-fix code computed thumbIndexDistance = 0 → instant pinch entry.
        repeat(15) { engine.processFrame(collapsedHand(ts)); ts += 40L }
        runCurrent()
        assertEquals(
            "degenerate landmarks must never produce a pinch event",
            0,
            events.count { it is GestureEvent.Pinch },
        )
        job.cancel()
    }

    // INVARIANT 7: NaN landmarks neither act nor poison the session.
    @Test
    fun `INV7 nan landmarks neither act nor poison the session`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = GestureEngine(GestureEngineConfig())
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()
        var ts = arm(engine)
        repeat(8) { engine.processFrame(nanHand(ts)); ts += 40L }
        runCurrent()
        assertEquals(
            "NaN landmarks must not produce discrete events",
            0,
            events.count { it is GestureEvent.Swipe || it is GestureEvent.PoseTriggered || it is GestureEvent.Pinch },
        )
        // The session recovers: still frames, then a real swipe fires.
        var x = 0f
        repeat(12) { engine.processFrame(hand(ts, offsetX = x)); ts += 40L }
        repeat(6) { engine.processFrame(hand(ts, offsetX = x)); x += 0.0625f; ts += 40L }
        runCurrent()
        assertEquals("session must recover after NaN poisoning", 1, events.count { it is GestureEvent.Swipe })
        job.cancel()
    }

    // INVARIANT 8: cursor-only movement (pointing) never fires discrete gestures.
    @Test
    fun `INV8 pointing sweep is cursor movement not a gesture`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = GestureEngine(GestureEngineConfig())
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()
        var ts = arm(engine)
        var x = 0f
        repeat(10) {
            engine.processFrame(hand(ts, offsetX = x, middle = false, ring = false, pinky = false, thumbOut = false))
            x += 0.0625f; ts += 40L
        }
        runCurrent()
        assertEquals(0, events.count { it is GestureEvent.Swipe })
        job.cancel()
    }

    // INVARIANT 9: after reset(), no stale state can cause an action — and
    // the session still works. (§13 note: feeding a long open-palm stream
    // after reset legitimately RE-ARMS the engine, so the probe must re-arm
    // deliberately, then swipe the OTHER way: stale rightward window/latch
    // data would either block the fresh swipe or fire a phantom RIGHT.)
    @Test
    fun `INV9 reset clears all transient state`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = GestureEngine(GestureEngineConfig())
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()
        var ts = arm(engine)
        var x = 0f
        repeat(3) { engine.processFrame(hand(ts, offsetX = x)); x += 0.0625f; ts += 40L } // mid-swipe RIGHT
        engine.reset()
        events.clear()

        // Re-arm from stillness at the CURRENT position (fresh open palm).
        ts = arm(engine, startTs = ts)
        repeat(6) { engine.processFrame(hand(ts, offsetX = x)); ts += 40L } // extra stillness

        // Fresh deliberate LEFT swipe.
        repeat(6) { engine.processFrame(hand(ts, offsetX = x)); x -= 0.0625f; ts += 40L }
        repeat(10) { engine.processFrame(hand(ts, offsetX = x)); ts += 40L }
        runCurrent()
        val swipes = events.filterIsInstance<GestureEvent.Swipe>()
        assertEquals("fresh LEFT swipe fires exactly once", 1, swipes.size)
        assertTrue(
            "no phantom RIGHT commit from pre-reset motion",
            swipes.all { it.direction == com.aircontrol.gesture.model.SwipeDirection.LEFT },
        )
        job.cancel()
    }

    // INVARIANT 10: minimum temporal/trajectory evidence before commit.
    // (§13 note: a teleport FOLLOWED BY further same-direction steps is a
    // legitimate 3-step trajectory — the scenarios must stay separate.)
    @Test
    fun `INV10 single-frame teleport and two-step wiggle never commit`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = GestureEngine(GestureEngineConfig())
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()
        var ts = arm(engine)
        // Teleport, then ONLY stillness — no continuation steps.
        engine.processFrame(hand(ts, offsetX = 0.4f)); ts += 40L
        repeat(12) { engine.processFrame(hand(ts, offsetX = 0.4f)); ts += 40L }
        assertEquals("teleport + stillness must not fire", 0, events.count { it is GestureEvent.Swipe })

        // Fresh out-and-back wiggle (2 steps out, 2 back) after stillness.
        var x = 0.4f
        repeat(2) { engine.processFrame(hand(ts, offsetX = x)); x += 0.0625f; ts += 40L }
        repeat(2) { engine.processFrame(hand(ts, offsetX = x)); x -= 0.0625f; ts += 40L }
        repeat(10) { engine.processFrame(hand(ts, offsetX = x)); ts += 40L }
        runCurrent()
        assertEquals("out-and-back wiggle must not fire", 0, events.count { it is GestureEvent.Swipe })
        job.cancel()
    }
}
