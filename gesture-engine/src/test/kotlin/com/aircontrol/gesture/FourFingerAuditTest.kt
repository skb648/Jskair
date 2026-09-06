package com.aircontrol.gesture

import com.aircontrol.gesture.config.GestureEngineConfig
import com.aircontrol.gesture.detection.StaticPoseClassifier
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 — Task 15 AUDIT PROOF: FOUR_FINGERS is UNREACHABLE in the current
 * classifier, so "FOUR_FINGER → existing action" cannot be implemented
 * without changing existing pose meanings (banned by the task scope).
 *
 * The subsumption, from StaticPoseClassifier.classifyRaw:
 *
 *   priority 3: totalExtendedCount >= 4            → OPEN_PALM
 *   priority 7: index && middle && ring && pinky    → FOUR_FINGERS
 *
 * FingerExtensionState.totalExtendedCount counts thumb+index+middle+ring+pinky,
 * so any hand satisfying priority 7 (all four fingers extended) has
 * totalExtendedCount >= 4 and already returned OPEN_PALM at priority 3 —
 * for BOTH thumb states:
 *   - 4 fingers + thumb tucked → count 4 → OPEN_PALM (this is also the
 *     ARMING pose, the palm-home trigger, the swipe open-hand gate and the
 *     neutral re-arm pose)
 *   - 4 fingers + thumb out    → count 5 → OPEN_PALM
 *
 * These tests PIN that shadowing so the dead branch cannot silently start
 * firing (e.g. via a future OPEN_PALM rule change) without a test turning
 * red — at which point the OPEN_PALM/arming interactions must be re-audited.
 *
 * Fixture geometry (verified against FingerExtensionDetector math at every
 * sensitivity: threshold band 0.90–1.12, thumb angle band 118°–158°):
 * extended tip/PIP-to-wrist worst ratio 1.397 > 1.12; curled worst 0.747 <
 * 0.90; tucked-thumb IP angle ≈26.6° < 118°; straight-thumb 180° > 158°.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FourFingerAuditTest {

    private fun hand(
        timestampMs: Long,
        scale: Float = 0.35f,
        pinky: Boolean = true,
        thumbOut: Boolean = false,
        confidence: Float = 0.95f,
    ): HandInput {
        val wx = 0.5f
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
        // Straight thumb is collinear with CMC→MCP→IP (180° IP angle); tucked
        // thumb folds across the palm (~26.6°) — never reads as extended.
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
        landmarks += finger(-0.15f, true) // index
        landmarks += finger(0f, true)     // middle
        landmarks += finger(0.15f, true)  // ring
        landmarks += finger(0.30f, pinky)
        assertEquals(21, landmarks.size)
        return HandInput(landmarks, Handedness.RIGHT, timestampMs, confidence)
    }

    /** The canonical "four-finger pose": all four fingers out, thumb folded. */
    private fun fourFinger(ts: Long, thumbOut: Boolean = false) = hand(ts, pinky = true, thumbOut = thumbOut)

    // §3/§4 unit proof: the four-finger hand's finger state is exactly
    // index+middle+ring+pinky extended, thumb NOT extended — and the
    // classifier still returns OPEN_PALM (priority 3 subsumes priority 7).
    @Test
    fun `four finger hand with tucked thumb classifies open palm never four fingers`() {
        val classifier = StaticPoseClassifier(GestureEngineConfig())
        var ts = 1000L
        var classifiedPose = Pose.NONE
        repeat(8) {
            classifiedPose = classifier.classify(fourFinger(ts)); ts += 40L
        }
        val fingerState = classifier.getFingerState(fourFinger(ts))
        assertTrue("index extended", fingerState.index)
        assertTrue("middle extended", fingerState.middle)
        assertTrue("ring extended", fingerState.ring)
        assertTrue("pinky extended", fingerState.pinky)
        assertFalse("thumb must NOT read extended (tucked geometry)", fingerState.thumb)
        assertEquals("totalExtendedCount is exactly 4 → OPEN_PALM by priority 3", 4, fingerState.totalExtendedCount)
        assertEquals("confirmed pose must be OPEN_PALM, not FOUR_FINGERS", Pose.OPEN_PALM, classifiedPose)
    }

    // §4: thumb-out variant — count 5, unambiguous OPEN_PALM.
    @Test
    fun `four finger hand with extended thumb classifies open palm`() {
        val classifier = StaticPoseClassifier(GestureEngineConfig())
        var ts = 1000L
        var classifiedPose = Pose.NONE
        repeat(8) {
            classifiedPose = classifier.classify(fourFinger(ts, thumbOut = true)); ts += 40L
        }
        val fingerState = classifier.getFingerState(fourFinger(ts, thumbOut = true))
        assertTrue(fingerState.thumb)
        assertEquals(5, fingerState.totalExtendedCount)
        assertEquals(Pose.OPEN_PALM, classifiedPose)
    }

    // §2 engine-level proof: a stable four-finger hand ARMS the engine — it
    // IS the open-palm arming pose — and never emits a FOUR_FINGERS event.
    @Test
    fun `stable four finger hand arms the engine as open palm and never fires four fingers`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = GestureEngine(GestureEngineConfig())
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()
        var ts = 1000L
        // Only OPEN_PALM can move DISARMED → ARMING; the four-finger hand
        // drives the whole arming sequence by itself.
        repeat(15) {
            engine.processFrame(fourFinger(ts)); ts += 40L
            if (engine.engineState.value == GestureEngineState.ARMED) break
        }
        runCurrent()
        assertEquals("the four-finger hand must ARM the engine (it reads OPEN_PALM)", GestureEngineState.ARMED, engine.engineState.value)
        assertEquals(Pose.OPEN_PALM, engine.currentPose.value)
        assertEquals("no FOUR_FINGERS event can ever fire", 0,
            events.count { it is GestureEvent.PoseTriggered && it.pose == Pose.FOUR_FINGERS })
        job.cancel()
    }

    // §6/§10 duplicate-invariant (negative form): holding the four-finger
    // "pose" is holding OPEN_PALM — a neutral pose — so no discrete action of
    // any kind may fire, no matter how long it is held.
    @Test
    fun `held four finger hand stays open palm with zero pose actions`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = GestureEngine(GestureEngineConfig())
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()
        var ts = 1000L
        // Arm with a plain open palm (5 digits) first, then hold the
        // four-finger shape (which also reads OPEN_PALM).
        repeat(15) {
            engine.processFrame(hand(ts, thumbOut = true)); ts += 40L
            if (engine.engineState.value == GestureEngineState.ARMED) break
        }
        assertEquals(GestureEngineState.ARMED, engine.engineState.value)
        repeat(30) { i ->
            engine.processFrame(fourFinger(ts)); ts += 40L
            if (i % 5 == 0) runCurrent()
        }
        runCurrent()
        assertEquals(Pose.OPEN_PALM, engine.currentPose.value)
        assertEquals("OPEN_PALM is neutral: zero pose actions, held or not", 0,
            events.count { it is GestureEvent.PoseTriggered })
        job.cancel()
    }
}
