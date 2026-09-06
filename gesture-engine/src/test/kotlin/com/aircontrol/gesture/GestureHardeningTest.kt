package com.aircontrol.gesture

import com.aircontrol.gesture.config.GestureEngineConfig
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
 * Hardening round 9 regression tests — one test per discovered bug/risk, plus
 * the spec §19 scenarios that were not previously pinned:
 *
 *  R1  a 1-frame tracking dropout after a fired swipe must NOT let the return
 *      sweep fire a phantom second swipe (neutral re-arm survives hand loss)
 *  R2  low-confidence tracking must never START a pinch click (CANCEL > GUESS)
 *  R3  a swipe must never fire while a pinch/drag is active (arbitration),
 *      even with the open-palm pose gate user-disabled
 *  R4  noisy pinch-distance flicker must not click (80ms entry debounce)
 *  R5  a held pinch fires exactly ONE click; a second click requires a real
 *      release + a fresh intentional pinch
 *  R6  neutral re-arm genuinely requires stillness before the next swipe
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GestureHardeningTest {

    // Same procedural hand fixture as GestureEngineFixesTest.
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
        val thumbMcp = Landmark3D(wx - 0.3f * s, wy - 0.3f * s, 0f)
        val thumbIp = Landmark3D(wx - 0.5f * s, wy - 0.5f * s, 0f)
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
            thumbMcp,
            thumbIp,
            thumbTip,
        )
        landmarks += finger(-0.15f, index)
        landmarks += finger(0f, middle)
        landmarks += finger(0.15f, ring)
        landmarks += finger(0.30f, pinky)
        assertEquals(21, landmarks.size)

        return HandInput(
            landmarks = landmarks,
            handedness = Handedness.RIGHT,
            timestampMs = timestampMs,
            confidence = confidence,
        )
    }

    private fun engineWith(config: GestureEngineConfig = GestureEngineConfig()) = GestureEngine(config)

    private fun arm(
        engine: GestureEngine,
        startTs: Long = 1000L,
        frameGapMs: Long = 40L,
    ): Long {
        var ts = startTs
        repeat(12) {
            engine.processFrame(hand(ts))
            ts += frameGapMs
            if (engine.engineState.value == com.aircontrol.gesture.model.GestureEngineState.ARMED) return ts
        }
        throw AssertionError("engine never reached ARMED")
    }

    // =====================================================================
    // R1 + R6 — neutral re-arm survives hand loss and requires stillness
    // =====================================================================

    @Test
    fun `one swipe motion yields exactly one event even with hand flicker`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = engineWith()
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()

        var ts = arm(engine)

        // A clean fast right swipe (6 frames × 0.05 at 40ms → 1.25 u/s).
        var x = 0f
        repeat(6) {
            engine.processFrame(hand(ts, offsetX = x))
            x += 0.05f
            ts += 40L
        }
        runCurrent()
        assertEquals("the swipe must fire exactly once", 1, events.count { it is GestureEvent.Swipe })

        // 1-frame tracking dropout immediately after the swipe.
        engine.processFrame(HandInput.EMPTY.copy(timestampMs = ts))
        ts += 40L

        // The hand sweeps back left, well past the 220ms swipe cooldown.
        repeat(6) {
            engine.processFrame(hand(ts, offsetX = x))
            x -= 0.05f
            ts += 40L
        }
        runCurrent()
        assertEquals(
            "return sweep after a hand flicker must not fire a phantom second swipe",
            1,
            events.count { it is GestureEvent.Swipe },
        )

        // R6: after the hand is STILL for >250ms the detector re-arms…
        repeat(10) {
            engine.processFrame(hand(ts, offsetX = x))
            ts += 40L
        }
        // …and a fresh intentional swipe fires again (recovery works).
        repeat(6) {
            engine.processFrame(hand(ts, offsetX = x))
            x += 0.05f
            ts += 40L
        }
        runCurrent()
        assertEquals(
            "after stillness re-arm a NEW swipe must be possible",
            2,
            events.count { it is GestureEvent.Swipe },
        )
        assertTrue(
            "both swipes must point right (the return sweep never fired)",
            events.filterIsInstance<GestureEvent.Swipe>().all { it.direction == SwipeDirection.RIGHT },
        )
        job.cancel()
    }

    // =====================================================================
    // R2 — low-confidence tracking must not START a pinch click
    // =====================================================================

    @Test
    fun `low confidence tracking never starts a pinch click`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = engineWith()
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()

        var ts = arm(engine)

        // Fingers physically together, but tracking is unreliable (<0.7).
        repeat(10) {
            engine.processFrame(hand(ts, pinchGap = 0.08f, confidence = 0.5f))
            ts += 40L
        }
        runCurrent()
        assertEquals(
            "blurry frames must never commit a click",
            0,
            events.count { it is GestureEvent.Pinch && it.phase == PinchPhase.START },
        )

        // Tracking recovers → the same physical pinch now clicks.
        // (Round 10: exiting low-confidence mode itself needs 3 good frames,
        // then HOVER → PINCH_START → 80ms confirm → click.)
        repeat(10) {
            engine.processFrame(hand(ts, pinchGap = 0.08f, confidence = 0.95f))
            ts += 40L
        }
        runCurrent()
        assertTrue(
            "entry must recover when confidence returns",
            events.any { it is GestureEvent.Pinch && it.phase == PinchPhase.START },
        )
        job.cancel()
    }

    // =====================================================================
    // R4 — noisy pinch-distance flicker must not click
    // =====================================================================

    @Test
    fun `noisy pinch distance does not click`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = engineWith()
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()

        var ts = arm(engine)

        // Distance flickers around the thresholds faster than the 80ms entry
        // debounce can confirm — an oscillation, not a pinch.
        repeat(14) { i ->
            engine.processFrame(hand(ts, pinchGap = if (i % 2 == 0) 0.08f else 0.5f))
            ts += 40L
        }
        // Settle back to an open hand.
        repeat(4) {
            engine.processFrame(hand(ts))
            ts += 40L
        }
        runCurrent()
        assertEquals(
            "pinch-distance oscillation must never emit a pinch event",
            0,
            events.count { it is GestureEvent.Pinch },
        )
        job.cancel()
    }

    // =====================================================================
    // R3 — swipe loses arbitration against an active pinch drag
    // =====================================================================

    @Test
    fun `swipe is suppressed while a pinch drag is active`() = runTest {
        val events = mutableListOf<GestureEvent>()
        // Open-palm gate OFF so the ONLY protection is the new arbitration —
        // this isolates exactly the rule under test.
        val engine = engineWith(GestureEngineConfig(swipeRequiresOpenHand = false))
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()

        var ts = arm(engine)

        // Form the pinch (still hand).
        repeat(4) {
            engine.processFrame(hand(ts, pinchGap = 0.08f))
            ts += 40L
        }
        runCurrent()
        assertTrue("pinch must start", events.any { it is GestureEvent.Pinch && it.phase == PinchPhase.START })

        // Drag: same fast rightward motion that IS a swipe when not pinching.
        var x = 0f
        repeat(6) {
            engine.processFrame(hand(ts, offsetX = x, pinchGap = 0.08f))
            x += 0.05f
            ts += 40L
        }
        runCurrent()
        assertEquals(
            "the drag's motion belongs to the drag — no swipe may fire",
            0,
            events.count { it is GestureEvent.Swipe },
        )
        assertTrue(
            "the drag itself must keep producing MOVE updates",
            events.any { it is GestureEvent.Pinch && it.phase == PinchPhase.MOVE },
        )

        // Release and settle.
        repeat(4) {
            engine.processFrame(hand(ts, offsetX = x, pinchGap = 0.6f))
            ts += 40L
        }
        repeat(10) {
            engine.processFrame(hand(ts, offsetX = x))
            ts += 40L
        }

        // A fresh swipe after the drag ends still works.
        repeat(6) {
            engine.processFrame(hand(ts, offsetX = x))
            x += 0.05f
            ts += 40L
        }
        runCurrent()
        assertEquals(
            "exactly one swipe, and only after the pinch ended",
            1,
            events.count { it is GestureEvent.Swipe },
        )
        job.cancel()
    }

    // =====================================================================
    // R5 — one pinch = one click; repeat requires release + fresh pinch
    // =====================================================================

    @Test
    fun `a held pinch fires exactly one click and repeating requires release`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = engineWith()
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()

        var ts = arm(engine)

        // Pinch and HOLD for a long time — exactly one START.
        repeat(20) {
            engine.processFrame(hand(ts, pinchGap = 0.08f))
            ts += 40L
        }
        runCurrent()
        assertEquals(
            "a held pinch is ONE click, not a machine-gun",
            1,
            events.count { it is GestureEvent.Pinch && it.phase == PinchPhase.START },
        )

        // Release (fingers well past the exit threshold).
        repeat(4) {
            engine.processFrame(hand(ts, pinchGap = 0.6f))
            ts += 40L
        }
        runCurrent()
        assertEquals(
            "the held pinch must end exactly once on release",
            1,
            events.count { it is GestureEvent.Pinch && it.phase == PinchPhase.END },
        )

        // A fresh intentional pinch clicks again (release-required semantics).
        repeat(6) {
            engine.processFrame(hand(ts, pinchGap = 0.08f))
            ts += 40L
        }
        runCurrent()
        assertEquals(
            "after a real release + fresh pinch, a second click is allowed",
            2,
            events.count { it is GestureEvent.Pinch && it.phase == PinchPhase.START },
        )
        job.cancel()
    }
}
