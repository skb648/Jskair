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
import org.junit.Test

/**
 * Phase 2 — PINCH → LEFT CLICK click-specific battery (spec §12 cases +
 * invariants A–H not already pinned by earlier suites).
 *
 * The pinch→click production path is the existing hardened FSM:
 * IDLE → HOVER (<enter×1.9) → PINCH_START (<enter, 80ms confirm) →
 * HOLD (START event = the click) → PINCH_RELEASE (>enter×1.45, 80ms) → IDLE,
 * plus an 80ms post-END cooldown. Default config (s=70, pinch ease 0.85–1.15
 * band → ease 1.06): enter ≈ 0.2332, exit ≈ 0.3381, hover ≈ 0.4430
 * (thumb-index distance normalized by wrist→middle-MCP hand size).
 *
 * Already pinned elsewhere (not duplicated here): tracking-loss candidate
 * cancel (INV2), low-confidence entry + flicker (R2/adversarial #7), degenerate
 * /NaN/∞ geometry (INV7), noisy below-enter oscillation (R4), held-pinch
 * single click (R5), swipe-suppression during drag (R3).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PinchClickTest {

    private fun hand(
        timestampMs: Long,
        offsetX: Float = 0f,
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
        val wy = 0.75f
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

    private fun starts(events: List<GestureEvent>) = events.count { it is GestureEvent.Pinch && it.phase == PinchPhase.START }
    private fun ends(events: List<GestureEvent>) = events.count { it is GestureEvent.Pinch && it.phase == PinchPhase.END }

    // §12 case 2/3 + 25: barely-valid vs barely-invalid entry (enter ≈ 0.2332).
    @Test
    fun `barely valid pinch clicks and barely invalid does not`() = runTest {
        // Barely VALID: 0.23 < 0.2332.
        run {
            val events = mutableListOf<GestureEvent>()
            val engine = GestureEngine(GestureEngineConfig())
            val job = launch { engine.gestureEvents.toList(events) }
            runCurrent()
            var ts = arm(engine)
            repeat(8) { engine.processFrame(hand(ts, pinchGap = 0.23f)); ts += 40L }
            runCurrent()
            assertEquals("gap 0.23 must click", 1, starts(events))
            job.cancel()
        }
        // Barely INVALID: 0.24 > 0.2332 (and < hover → HOVER, never START).
        run {
            val events = mutableListOf<GestureEvent>()
            val engine = GestureEngine(GestureEngineConfig())
            val job = launch { engine.gestureEvents.toList(events) }
            runCurrent()
            var ts = arm(engine)
            repeat(8) { engine.processFrame(hand(ts, pinchGap = 0.24f)); ts += 40L }
            runCurrent()
            assertEquals("gap 0.24 must not click", 0, starts(events))
            job.cancel()
        }
    }

    // §12 case 4 + invariant F: oscillation across ENTER but below EXIT —
    // the fingers keep reaching pinch distance, so exactly ONE click commits;
    // oscillation can never repeat it (no release → no re-arm).
    @Test
    fun `enter threshold oscillation yields exactly one click and no repeats`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = GestureEngine(GestureEngineConfig())
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()
        var ts = arm(engine)
        repeat(30) { i ->
            engine.processFrame(hand(ts, pinchGap = if (i % 2 == 0) 0.23f else 0.33f))
            ts += 40L
        }
        runCurrent()
        assertEquals("oscillation commits at most one click", 1, starts(events))
        assertEquals("never released → no END", 0, ends(events))
        job.cancel()
    }

    // §12 case 20: pinch-LIKE jitter that never actually reaches pinch
    // distance (always above enter, below hover) must never click.
    @Test
    fun `jitter above enter that never stabilizes never clicks`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = GestureEngine(GestureEngineConfig())
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()
        var ts = arm(engine)
        repeat(30) { i ->
            engine.processFrame(hand(ts, pinchGap = if (i % 2 == 0) 0.30f else 0.42f))
            ts += 40L
        }
        runCurrent()
        assertEquals("never-pinching jitter must not click", 0, events.count { it is GestureEvent.Pinch })
        job.cancel()
    }

    // §12 case 5/6: long hold → release: exactly one click, one release.
    @Test
    fun `long hold then release is one click and one release`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = GestureEngine(GestureEngineConfig())
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()
        var ts = arm(engine)
        repeat(60) { i ->
            engine.processFrame(hand(ts, pinchGap = 0.08f)); ts += 40L
            // Drain the SharedFlow periodically: ~2 events/frame (CursorMoved +
            // Pinch MOVE) overflow the 64-slot test buffer within ~32 frames
            // and would evict the START event before collection (harness
            // artifact, not production behavior).
            if (i % 5 == 0) runCurrent()
        }
        repeat(6) { i ->
            engine.processFrame(hand(ts, pinchGap = 0.6f)); ts += 40L
            if (i % 3 == 0) runCurrent()
        }
        runCurrent()
        assertEquals(1, starts(events))
        assertEquals(1, ends(events))
        job.cancel()
    }

    // §12 case 7/23: rapid repeated pinch across the 80ms cooldown — exactly
    // two clicks for two physical pinches, never more.
    @Test
    fun `rapid repeated pinch yields exactly two clicks`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = GestureEngine(GestureEngineConfig())
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()
        var ts = arm(engine)
        repeat(6) { engine.processFrame(hand(ts, pinchGap = 0.08f)); ts += 40L } // pinch 1
        repeat(4) { engine.processFrame(hand(ts, pinchGap = 0.6f)); ts += 40L } // release 1
        repeat(8) { engine.processFrame(hand(ts, pinchGap = 0.08f)); ts += 40L } // pinch 2 (immediate)
        repeat(4) { engine.processFrame(hand(ts, pinchGap = 0.6f)); ts += 40L } // release 2
        runCurrent()
        assertEquals("two physical pinches = exactly two clicks", 2, starts(events))
        assertEquals(2, ends(events))
        job.cancel()
    }

    // §12 case 24/26: release needs to pass EXIT (enter×1.45 ≈ 0.3381);
    // hovering just under it keeps the press held.
    @Test
    fun `release requires passing the exit threshold`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = GestureEngine(GestureEngineConfig())
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()
        var ts = arm(engine)
        repeat(8) { engine.processFrame(hand(ts, pinchGap = 0.08f)); ts += 40L } // click
        repeat(20) { i ->
            engine.processFrame(hand(ts, pinchGap = 0.30f)); ts += 40L // above enter, BELOW exit
            if (i % 5 == 0) runCurrent()
        }
        runCurrent()
        assertEquals("press must stay held below exit", 1, starts(events))
        assertEquals("no release below exit", 0, ends(events))
        repeat(6) { engine.processFrame(hand(ts, pinchGap = 0.36f)); ts += 40L } // above exit 0.3381
        runCurrent()
        assertEquals("release fires once past exit", 1, ends(events))
        job.cancel()
    }

    // §12 case 17: hand-size normalization must hold at extreme scales.
    @Test
    fun `extreme hand scales still click via normalization`() = runTest {
        for (scale in listOf(0.02f, 3.0f)) {
            val events = mutableListOf<GestureEvent>()
            val engine = GestureEngine(GestureEngineConfig())
            val job = launch { engine.gestureEvents.toList(events) }
            runCurrent()
            var ts = 1000L
            // Arm with the extreme-scale open palm.
            repeat(12) {
                engine.processFrame(hand(ts, scale = scale)); ts += 40L
                if (engine.engineState.value == GestureEngineState.ARMED) return@repeat
            }
            // scale=3.0 arming may need more frames (debounce measures wall
            // time, not size) — keep feeding until armed or budget exhausted.
            var guard = 0
            while (engine.engineState.value != GestureEngineState.ARMED && guard++ < 30) {
                engine.processFrame(hand(ts, scale = scale)); ts += 40L
            }
            assertEquals("engine must arm at scale=$scale", GestureEngineState.ARMED, engine.engineState.value)
            repeat(8) { engine.processFrame(hand(ts, scale = scale, pinchGap = 0.08f)); ts += 40L }
            runCurrent()
            assertEquals("scale=$scale must click through normalization", 1, starts(events))
            job.cancel()
        }
    }

    // §12 case 21 + §10: a completed swipe followed by a deliberate pinch —
    // deterministic sequencing, no conflict, no duplicate.
    @Test
    fun `swipe then pinch sequence does not conflict`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = GestureEngine(GestureEngineConfig())
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()
        var ts = arm(engine)
        var x = 0f
        repeat(6) { engine.processFrame(hand(ts, offsetX = x)); x += 0.0625f; ts += 40L } // swipe
        repeat(12) { engine.processFrame(hand(ts, offsetX = x)); ts += 40L } // stillness (re-arm)
        repeat(6) { engine.processFrame(hand(ts, offsetX = x, pinchGap = 0.08f)); ts += 40L } // pinch
        repeat(4) { engine.processFrame(hand(ts, offsetX = x, pinchGap = 0.6f)); ts += 40L } // release
        runCurrent()
        assertEquals(1, events.count { it is GestureEvent.Swipe })
        assertEquals(1, starts(events))
        assertEquals(1, ends(events))
        job.cancel()
    }

    // §12 case 19 + invariant G: cursor-only movement (pointing) never clicks.
    @Test
    fun `cursor movement alone never clicks`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = GestureEngine(GestureEngineConfig())
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()
        var ts = arm(engine)
        var x = 0f
        repeat(12) {
            engine.processFrame(hand(ts, offsetX = x, middle = false, ring = false, pinky = false, thumbOut = false))
            x += 0.0625f; ts += 40L
        }
        runCurrent()
        assertEquals("pointing sweep must not click", 0, events.count { it is GestureEvent.Pinch })
        job.cancel()
    }

    // Invariant H: reset during an ACTIVE HOLD leaves no stale state — no
    // phantom END leaks afterwards, and a fresh pinch still works.
    @Test
    fun `reset during hold clears stale pinch state`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = GestureEngine(GestureEngineConfig())
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()
        var ts = arm(engine)
        repeat(8) { engine.processFrame(hand(ts, pinchGap = 0.08f)); ts += 40L } // HOLD (clicked)
        runCurrent()
        assertEquals(1, starts(events))
        engine.reset()
        events.clear()

        // Hand-loss frames right after reset: a stale FSM would emit a
        // phantom END here (the !isDetected branch fires when wasPinching).
        repeat(4) { engine.processFrame(empty(ts)); ts += 40L }
        runCurrent()
        assertEquals("no stale END after reset", 0, events.size)

        // Fresh session: arm and pinch again — exactly one new click.
        ts = arm(engine, startTs = ts)
        repeat(8) { engine.processFrame(hand(ts, pinchGap = 0.08f)); ts += 40L }
        runCurrent()
        assertEquals("fresh pinch must click after reset", 1, starts(events))
        job.cancel()
    }
}
