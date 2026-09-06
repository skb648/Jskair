package com.aircontrol.gesture

import com.aircontrol.gesture.config.GestureEngineConfig
import com.aircontrol.gesture.model.GestureEngineState
import com.aircontrol.gesture.model.GestureEvent
import com.aircontrol.gesture.model.HandInput
import com.aircontrol.gesture.model.Handedness
import com.aircontrol.gesture.model.Landmark3D
import com.aircontrol.gesture.model.Pose
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 — THREE-FINGERS → VOLUME_UP adversarial battery (spec §10).
 *
 * Recognition semantics under test (existing, hardened — not rewritten):
 * THREE_FINGERS = index+middle+ring extended, pinky curled, thumb NOT
 * extended (a thumb-out hand with three fingers reads as OPEN_PALM by the
 * classifier's ≥4-extended priority — pinned below as intended conservative
 * behavior). Behind it: 120ms wall-clock pose debounce, ARMED requirement,
 * one-shot lastExecutedPose lock with neutral re-arm, 100ms engine cooldown,
 * low-confidence execution suppression (round 10), tracking-loss reset.
 *
 * The action side (VOLUME_UP) is mapping-layer only — verified in
 * GestureMapConfigTest (pose_three_fingers → VOLUME_UP default + migration).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreeFingerVolumeTest {

    private fun hand(
        timestampMs: Long,
        offsetX: Float = 0f,
        scale: Float = 0.35f,
        index: Boolean = true,
        middle: Boolean = true,
        ring: Boolean = true,
        pinky: Boolean = true,
        thumbOut: Boolean = true,
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
        // thumbOut: collinear with CMC→MCP→IP (straight, 180° IP angle ⇒ truly
        // extended at every sensitivity). Tucked: folded across the palm
        // (~27° ⇒ never extended). The classifier's OPEN_PALM priority needs
        // this distinction: 4+ EXTENDED digits = OPEN_PALM, so an accidental
        // thumb-out hand must never read as THREE_FINGERS.
        val thumbTip = if (thumbOut) {
            Landmark3D(wx - 0.7f * s, wy - 0.7f * s, 0f)
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

    /** The ✌️➕ gesture under test: index+middle+ring out, pinky+thumb folded. */
    private fun threeFinger(ts: Long, offsetX: Float = 0f, scale: Float = 0.35f, confidence: Float = 0.95f) =
        hand(ts, offsetX = offsetX, scale = scale, pinky = false, thumbOut = false, confidence = confidence)

    private fun openPalm(ts: Long, offsetX: Float = 0f, scale: Float = 0.35f, confidence: Float = 0.95f) =
        hand(ts, offsetX = offsetX, scale = scale, confidence = confidence)

    private fun empty(ts: Long) =
        HandInput(landmarks = emptyList(), handedness = Handedness.UNKNOWN, timestampMs = ts, confidence = 0f)

    private fun arm(engine: GestureEngine, startTs: Long = 1000L, gap: Long = 40L): Long {
        var ts = startTs
        repeat(12) {
            engine.processFrame(openPalm(ts)); ts += gap
            if (engine.engineState.value == GestureEngineState.ARMED) return ts
        }
        throw AssertionError("never armed")
    }

    private fun poseEvents(events: List<GestureEvent>, pose: Pose) =
        events.count { it is GestureEvent.PoseTriggered && it.pose == pose }

    // §10.1/14: clean pose fires exactly ONE action; holding never repeats.
    @Test
    fun `clean three finger pose fires exactly once and holding never repeats`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = GestureEngine(GestureEngineConfig())
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()
        var ts = arm(engine)
        repeat(30) { i ->
            engine.processFrame(threeFinger(ts)); ts += 40L
            if (i % 5 == 0) runCurrent()
        }
        runCurrent()
        assertEquals("one intentional pose = one action", 1, poseEvents(events, Pose.THREE_FINGERS))
        job.cancel()
    }

    // §10.4: a single-frame pose between open-palm frames must not fire.
    @Test
    fun `one frame three finger pose does not fire`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = GestureEngine(GestureEngineConfig())
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()
        var ts = arm(engine)
        engine.processFrame(openPalm(ts)); ts += 40L
        engine.processFrame(threeFinger(ts)); ts += 40L
        engine.processFrame(openPalm(ts)); ts += 40L
        engine.processFrame(openPalm(ts)); ts += 40L
        runCurrent()
        assertEquals(0, poseEvents(events, Pose.THREE_FINGERS))
        job.cancel()
    }

    // §10.20: three → neutral (open palm) → three fires exactly twice.
    @Test
    fun `three to neutral to three re-arms and fires twice`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = GestureEngine(GestureEngineConfig())
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()
        var ts = arm(engine)
        repeat(6) { engine.processFrame(threeFinger(ts)); ts += 40L }
        repeat(6) { engine.processFrame(openPalm(ts)); ts += 40L } // neutral re-arm
        repeat(6) { engine.processFrame(threeFinger(ts)); ts += 40L }
        runCurrent()
        assertEquals("re-arm after neutral allows a second action", 2, poseEvents(events, Pose.THREE_FINGERS))
        job.cancel()
    }

    // §10.17/18: two-finger ↔ three-finger transitions — each pose fires once.
    @Test
    fun `victory and three finger transitions each fire once`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = GestureEngine(GestureEngineConfig())
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()
        var ts = arm(engine)
        // VICTORY (index+middle) → THREE_FINGERS (add ring).
        repeat(6) { engine.processFrame(hand(ts, ring = false, pinky = false, thumbOut = false)); ts += 40L }
        repeat(6) { engine.processFrame(threeFinger(ts)); ts += 40L }
        // Back to VICTORY.
        repeat(6) { engine.processFrame(hand(ts, ring = false, pinky = false, thumbOut = false)); ts += 40L }
        runCurrent()
        assertEquals(2, poseEvents(events, Pose.VICTORY))
        assertEquals(1, poseEvents(events, Pose.THREE_FINGERS))
        job.cancel()
    }

    // §10.19: open hand → three fingers fires once.
    @Test
    fun `open palm to three fingers fires once`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = GestureEngine(GestureEngineConfig())
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()
        var ts = arm(engine)
        repeat(6) { engine.processFrame(openPalm(ts)); ts += 40L }
        repeat(6) { engine.processFrame(threeFinger(ts)); ts += 40L }
        runCurrent()
        assertEquals(1, poseEvents(events, Pose.THREE_FINGERS))
        job.cancel()
    }

    // §10.7 + §9 conflict audit: thumb extended + three fingers = 4 extended
    // digits → OPEN_PALM by classifier priority, NOT THREE_FINGERS. This is
    // the intended conservative arbitration (an accidental four-out hand must
    // never change the volume).
    @Test
    fun `thumb out with three fingers reads open palm not three fingers`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = GestureEngine(GestureEngineConfig())
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()
        var ts = arm(engine)
        repeat(10) { engine.processFrame(hand(ts, pinky = false, thumbOut = true)); ts += 40L }
        runCurrent()
        assertEquals("thumb-out variant must NOT fire the volume gesture", 0, poseEvents(events, Pose.THREE_FINGERS))
        job.cancel()
    }

    // §10.6/8: pinky oscillation (three ↔ four fingers) never passes debounce.
    @Test
    fun `pinky oscillation between three and four fingers never fires`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = GestureEngine(GestureEngineConfig())
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()
        var ts = arm(engine)
        repeat(20) { i ->
            engine.processFrame(hand(ts, pinky = i % 2 == 0, thumbOut = false)); ts += 40L
        }
        runCurrent()
        assertEquals("oscillating raw poses must never confirm", 0,
            poseEvents(events, Pose.THREE_FINGERS) + poseEvents(events, Pose.FOUR_FINGERS))
        job.cancel()
    }

    // §10.9: low-confidence tracking never initiates the action; recovery fires.
    @Test
    fun `low confidence three finger pose is muted until recovery`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = GestureEngine(GestureEngineConfig())
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()
        var ts = arm(engine)
        repeat(12) { engine.processFrame(threeFinger(ts, confidence = 0.5f)); ts += 40L }
        runCurrent()
        assertEquals("blurry frames must not change the volume", 0, poseEvents(events, Pose.THREE_FINGERS))
        repeat(8) { engine.processFrame(threeFinger(ts, confidence = 0.95f)); ts += 40L }
        runCurrent()
        assertEquals("recovery fires exactly once", 1, poseEvents(events, Pose.THREE_FINGERS))
        job.cancel()
    }

    // §10.11/13: candidate + tracking loss → fresh validation on return.
    @Test
    fun `tracking loss during pose requires fresh validation on return`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = GestureEngine(GestureEngineConfig())
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()
        var ts = arm(engine)
        // Two candidate frames (below the 3-frame debounce)…
        engine.processFrame(threeFinger(ts)); ts += 40L
        engine.processFrame(threeFinger(ts)); ts += 40L
        // …hand lost…
        repeat(4) { engine.processFrame(empty(ts)); ts += 40L }
        // …and returns still holding the pose.
        val firstReturnTs = ts
        repeat(8) { engine.processFrame(threeFinger(ts)); ts += 40L }
        runCurrent()
        val fired = events.filterIsInstance<GestureEvent.PoseTriggered>()
            .firstOrNull { it.pose == Pose.THREE_FINGERS }
        assertTrue("the returning pose must fire after fresh validation", fired != null)
        assertEquals("exactly one action for the returned pose", 1, poseEvents(events, Pose.THREE_FINGERS))
        // A stale candidate would fire on the FIRST return frame; fresh
        // validation needs 3 consecutive frames (fires on the third).
        // Bug #11 pin: before the fix, the 240ms gap poisoned the frame-
        // interval EMA (40→90ms), dropped the debounce to 2 frames, and the
        // pose committed at firstReturn+40 — half the spec'd validation.
        assertTrue("event must come from fresh post-return frames", fired!!.timestampMs >= firstReturnTs + 80L)
        job.cancel()
    }

    // Bug #11 (spec §6): a LONG dropout (seconds, still inside the 10s
    // auto-disarm window) used to inflate the interval EMA so much that the
    // effective pose debounce collapsed to ONE frame — a single misread
    // reacquisition frame could fire the volume action. The estimator must
    // ignore post-gap deltas; validation depth stays at 3 frames.
    @Test
    fun `long tracking dropout does not collapse the pose debounce to one frame`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = GestureEngine(GestureEngineConfig())
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()
        var ts = arm(engine)
        // ~3s of tracking loss in 500ms hops.
        repeat(6) { engine.processFrame(empty(ts)); ts += 500L }
        val firstReturnTs = ts
        repeat(8) { engine.processFrame(threeFinger(ts)); ts += 40L }
        runCurrent()
        val fired = events.filterIsInstance<GestureEvent.PoseTriggered>()
            .firstOrNull { it.pose == Pose.THREE_FINGERS }
        assertTrue("pose must still fire after fresh validation", fired != null)
        assertEquals(1, poseEvents(events, Pose.THREE_FINGERS))
        assertTrue(
            "debounce must survive the dropout (no 1-frame commit)",
            fired!!.timestampMs >= firstReturnTs + 80L,
        )
        job.cancel()
    }

    // §10.22/23/21: NaN, collapsed and degenerate landmarks never fire.
    @Test
    fun `nan and collapsed landmarks never fire the pose`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = GestureEngine(GestureEngineConfig())
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()
        var ts = arm(engine)
        val nan = HandInput(List(21) { Landmark3D(Float.NaN, Float.NaN, Float.NaN) }, Handedness.RIGHT, ts, 0.95f)
        val collapsed = HandInput(List(21) { Landmark3D(0.5f, 0.5f, 0f) }, Handedness.RIGHT, ts, 0.95f)
        repeat(8) { engine.processFrame(nan.copy(timestampMs = ts)); ts += 40L }
        repeat(8) { engine.processFrame(collapsed.copy(timestampMs = ts)); ts += 40L }
        runCurrent()
        assertEquals(0, poseEvents(events, Pose.THREE_FINGERS))
        job.cancel()
    }

    // §10.24: hand-size normalization holds at extreme scales.
    @Test
    fun `extreme hand scales still recognize the pose`() = runTest {
        for (scale in listOf(0.02f, 3.0f)) {
            val events = mutableListOf<GestureEvent>()
            val engine = GestureEngine(GestureEngineConfig())
            val job = launch { engine.gestureEvents.toList(events) }
            runCurrent()
            var ts = 1000L
            var guard = 0
            while (engine.engineState.value != GestureEngineState.ARMED && guard++ < 40) {
                engine.processFrame(openPalm(ts, scale = scale)); ts += 40L
            }
            assertEquals("arm at scale=$scale", GestureEngineState.ARMED, engine.engineState.value)
            repeat(8) { engine.processFrame(threeFinger(ts, scale = scale)); ts += 40L }
            runCurrent()
            assertEquals("scale=$scale must fire through normalization", 1, poseEvents(events, Pose.THREE_FINGERS))
            job.cancel()
        }
    }

    // §10.16: pose during cursor movement — existing semantics: non-thumb
    // poses are not velocity-gated; the one-shot lock still guarantees ONE
    // action per intentional pose.
    @Test
    fun `three finger pose while the cursor moves fires exactly once`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = GestureEngine(GestureEngineConfig())
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()
        var ts = arm(engine)
        var x = 0f
        repeat(10) { i ->
            engine.processFrame(threeFinger(ts, offsetX = x)); x += 0.03f; ts += 40L
            if (i % 5 == 0) runCurrent()
        }
        runCurrent()
        assertEquals("moving pose still fires exactly once", 1, poseEvents(events, Pose.THREE_FINGERS))
        job.cancel()
    }
}
