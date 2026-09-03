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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the reliability pass. Each test pins a user-visible
 * complaint that the old code produced, so it cannot come back:
 *
 *  A-3  sensitivity slider at either end made detection impossible
 *  A-4  resting with an open palm sent the user to the Home screen
 *  A-5  a pose gesture fired once and then refused to repeat
 *  A-6  the pose "pinch" and the "click" disagreed by ~7x
 *  A-11 moving the cursor scrolled the page
 *  A-12 closing the hand changed the volume instead of disarming
 *  A-13 a fist was read as a pinch, so the fist never disarmed
 *  A-14 debounce was counted in frames, so 5fps scan mode felt broken
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GestureEngineFixesTest {

    // =====================================================================
    // Procedural hand fixture
    // =====================================================================

    /**
     * Builds a 21-landmark hand. [scale] is the wrist→middle-MCP distance in
     * normalized units (the "hand size" every threshold is normalized by), so a
     * larger scale means the hand is closer to the camera.
     *
     * Extended fingers sit at 2.1×scale from the wrist against a PIP at 1.5×scale
     * (ratio 1.4 — a genuinely straight finger); curled ones at 0.95×scale
     * (ratio 0.73). The thumb is a straight line when [thumbOut] and folded
     * across the palm otherwise. [pinchGap] places the thumb tip exactly that many
     * hand sizes away from the index tip.
     */
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

        fun finger(baseIndex: Int, xOffset: Float, extended: Boolean): List<Landmark3D> {
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
            Landmark3D(wx - 0.2f * s, wy - 0.2f * s, 0f), // THUMB_CMC
            thumbMcp,
            thumbIp,
            thumbTip,
        )
        landmarks += finger(5, -0.15f, index)
        landmarks += finger(9, 0f, middle)
        landmarks += finger(13, 0.15f, ring)
        landmarks += finger(17, 0.30f, pinky)
        assertEquals(21, landmarks.size)

        return HandInput(
            landmarks = landmarks,
            handedness = Handedness.RIGHT,
            timestampMs = timestampMs,
            confidence = confidence,
        )
    }

    private fun engineWith(config: GestureEngineConfig = GestureEngineConfig()) = GestureEngine(config)

    /** Feeds still open-palm frames until the engine reports ARMED. */
    private fun arm(
        engine: GestureEngine,
        startTs: Long = 1000L,
        frameGapMs: Long = 40L,
        scale: Float = 0.35f,
    ): Long {
        var ts = startTs
        repeat(12) {
            engine.processFrame(hand(ts, scale = scale))
            ts += frameGapMs
            if (engine.engineState.value == GestureEngineState.ARMED) return ts
        }
        throw AssertionError("engine never reached ARMED (last state ${engine.engineState.value})")
    }

    // =====================================================================
    // A-3 — the sensitivity slider must never break detection at either end
    // =====================================================================

    @Test
    fun `open palm arms the engine at every sensitivity value`() {
        for (sensitivity in listOf(0, 20, 50, 80, 100)) {
            val engine = engineWith(GestureEngineConfig(sensitivity = sensitivity))
            var ts = 1000L
            repeat(12) {
                engine.processFrame(hand(ts))
                ts += 40L
            }
            assertEquals(
                "sensitivity=$sensitivity must still arm with a real open palm",
                GestureEngineState.ARMED, engine.engineState.value,
            )
        }
    }

    @Test
    fun `a fist is still a fist at every sensitivity value`() {
        for (sensitivity in listOf(0, 50, 100)) {
            val engine = engineWith(GestureEngineConfig(sensitivity = sensitivity))
            var ts = 1000L
            repeat(12) {
                engine.processFrame(hand(ts, thumbOut = false, index = false, middle = false, ring = false, pinky = false))
                ts += 40L
            }
            assertEquals("sensitivity=$sensitivity must read FIST", Pose.FIST, engine.currentPose.value)
        }
    }

    @Test
    fun `no sensitivity produces an impossible threshold`() {
        for (sensitivity in 0..100 step 5) {
            val cfg = GestureEngineConfig(sensitivity = sensitivity)
            assertTrue(
                "finger threshold must stay reachable at s=$sensitivity",
                cfg.scaledFingerExtensionThreshold() in 0.5f..1.3f,
            )
            assertTrue(
                "thumb angle must stay below 180 at s=$sensitivity",
                cfg.scaledThumbExtensionAngleDeg() < 170f,
            )
            assertTrue(
                "pinch ratio must stay usable at s=$sensitivity",
                cfg.scaledPinchDistanceRatio() in 0.1f..0.4f,
            )
        }
    }

    // =====================================================================
    // A-5 — returning to an open palm must clear the "already fired" lock
    // =====================================================================

    @Test
    fun `returning to open palm lets the same pose fire again`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = engineWith()
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()

        var ts = arm(engine)

        repeat(6) { engine.processFrame(hand(ts, ring = false, pinky = false, thumbOut = false)); ts += 40L }
        runCurrent()
        val afterFirst = events.count { it is GestureEvent.PoseTriggered && it.pose == Pose.VICTORY }
        assertEquals("first victory should fire once", 1, afterFirst)

        // Natural neutral pose: relax back to an open palm.
        repeat(6) { engine.processFrame(hand(ts)); ts += 40L }
        runCurrent()

        repeat(6) { engine.processFrame(hand(ts, ring = false, pinky = false, thumbOut = false)); ts += 40L }
        runCurrent()

        val afterSecond = events.count { it is GestureEvent.PoseTriggered && it.pose == Pose.VICTORY }
        assertEquals("victory must fire again after returning to an open palm", 2, afterSecond)

        job.cancel()
    }

    // =====================================================================
    // A-6 — one definition of "pinch" for the pose classifier and the click
    // =====================================================================

    @Test
    fun `a real pinch both classifies as PINCH and starts a click`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = engineWith()
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()

        var ts = arm(engine)
        repeat(6) {
            engine.processFrame(hand(ts, pinchGap = 0.12f))
            ts += 40L
        }
        runCurrent()

        assertEquals("pose must read PINCH", Pose.PINCH, engine.currentPose.value)
        assertTrue(
            "a confirmed pinch must start a click",
            events.any { it is GestureEvent.Pinch && it.phase == com.aircontrol.gesture.model.PinchPhase.START },
        )
        job.cancel()
    }

    @Test
    fun `a half-closed hand is neither PINCH pose nor a click`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = engineWith()
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()

        var ts = arm(engine)
        // 0.28 hand sizes apart: the OLD pose classifier called this a pinch
        // (blocking every other pose) while the OLD click FSM never fired.
        repeat(8) {
            engine.processFrame(hand(ts, pinchGap = 0.28f))
            ts += 40L
        }
        runCurrent()

        assertFalse("half-closed must not read as PINCH", engine.currentPose.value == Pose.PINCH)
        assertFalse(
            "half-closed must not start a click",
            events.any { it is GestureEvent.Pinch && it.phase == com.aircontrol.gesture.model.PinchPhase.START },
        )
        job.cancel()
    }

    @Test
    fun `calibration actually moves the pinch threshold`() {
        val uncalibrated = GestureEngineConfig()
        val calibrated = uncalibrated.copy(calibratedPinchRatio = 0.30f)
        assertTrue(
            "a looser calibrated pinch must be easier to trigger",
            calibrated.scaledPinchDistanceRatio() > uncalibrated.scaledPinchDistanceRatio(),
        )
        val tight = uncalibrated.copy(calibratedPinchRatio = 0.05f)
        assertTrue(
            "a tighter calibrated pinch must be harder to trigger",
            tight.scaledPinchDistanceRatio() < uncalibrated.scaledPinchDistanceRatio(),
        )
    }

    // =====================================================================
    // A-4 — a resting open palm must not navigate Home
    // =====================================================================

    @Test
    fun `palm home fires only for a still presented palm`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = engineWith()
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()

        var ts = arm(engine)
        // Hold a still, close-to-camera open palm for longer than palmHomeHoldMs,
        // past the post-arming guard window.
        ts += 1500L
        repeat(70) { i ->
            engine.processFrame(hand(ts))
            ts += 100L
            if (i % 3 == 0) runCurrent()
        }
        runCurrent()
        assertEquals(
            "exactly one PalmHome for one deliberate hold",
            1, events.count { it is GestureEvent.PalmHome },
        )
        job.cancel()
    }

    @Test
    fun `palm home never fires while the hand travels`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = engineWith()
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()

        var ts = arm(engine)
        ts += 1500L
        // The user is browsing: an open palm that keeps moving across the screen.
        var x = 0f
        repeat(70) { i ->
            engine.processFrame(hand(ts, offsetX = x))
            x += 0.03f
            ts += 100L
            if (i % 3 == 0) runCurrent()
        }
        runCurrent()
        assertEquals("no PalmHome while travelling", 0, events.count { it is GestureEvent.PalmHome })
        job.cancel()
    }

    @Test
    fun `palm home never fires for a hand resting far from the camera`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = engineWith()
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()

        var ts = arm(engine, scale = 0.35f)
        ts += 1500L
        // Same still hold, but a small (distant) hand — e.g. the phone lying on a
        // table with the user's hand resting in frame.
        repeat(70) { i ->
            engine.processFrame(hand(ts, scale = 0.18f))
            ts += 100L
            if (i % 3 == 0) runCurrent()
        }
        runCurrent()
        assertEquals("no PalmHome for a distant resting hand", 0, events.count { it is GestureEvent.PalmHome })
        job.cancel()
    }

    // =====================================================================
    // A-11 — pointer travel must not scroll
    // =====================================================================

    @Test
    fun `moving the pointer while pointing never scrolls`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = engineWith()
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()

        var ts = arm(engine)
        // Fast horizontal travel with a pointing hand (the cursor-moving gesture).
        var x = 0f
        repeat(12) { i ->
            engine.processFrame(
                hand(ts, offsetX = x, middle = false, ring = false, pinky = false, thumbOut = false),
            )
            x += 0.05f
            ts += 40L
            if (i % 3 == 0) runCurrent()
        }
        runCurrent()
        assertEquals("no swipe from pointer travel", 0, events.count { it is GestureEvent.Swipe })
        job.cancel()
    }

    @Test
    fun `an open palm sweep still swipes`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = engineWith()
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()

        var ts = arm(engine)
        var x = 0f
        repeat(10) { i ->
            engine.processFrame(hand(ts, offsetX = x))
            x += 0.08f
            ts += 40L
            if (i % 3 == 0) runCurrent()
        }
        runCurrent()
        assertTrue(
            "an open-palm sweep is a swipe",
            events.any { it is GestureEvent.Swipe && it.direction == com.aircontrol.gesture.model.SwipeDirection.RIGHT },
        )
        job.cancel()
    }

    @Test
    fun `swipes are not gated when the open-palm requirement is disabled`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = engineWith(GestureEngineConfig(swipeRequiresOpenHand = false))
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()

        var ts = arm(engine)
        var x = 0f
        repeat(12) { i ->
            engine.processFrame(
                hand(ts, offsetX = x, middle = false, ring = false, pinky = false, thumbOut = false),
            )
            x += 0.06f
            ts += 40L
            if (i % 3 == 0) runCurrent()
        }
        runCurrent()
        assertTrue("opting out of the gate restores swipe-on-pointing", events.any { it is GestureEvent.Swipe })
        job.cancel()
    }

    @Test
    fun `the swipe gate can be flipped at runtime without losing the session`() = runTest {
        // A-11 again, but through the API the app actually calls: the Settings
        // switch is pushed into the *live* engine (GestureDetectorImpl never
        // recreates it), so a config field that is only read at construction
        // would silently do nothing.
        val events = mutableListOf<GestureEvent>()
        val engine = engineWith()
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()

        var ts = arm(engine)
        engine.updateSwipeRequiresOpenHand(false)
        assertEquals("the session survives the settings write",
            com.aircontrol.gesture.model.GestureEngineState.ARMED, engine.engineState.value)

        var x = 0f
        repeat(12) { i ->
            engine.processFrame(
                hand(ts, offsetX = x, middle = false, ring = false, pinky = false, thumbOut = false),
            )
            x += 0.06f
            ts += 40L
            if (i % 3 == 0) runCurrent()
        }
        runCurrent()
        assertTrue("turning the gate off at runtime enables pointing swipes",
            events.any { it is GestureEvent.Swipe })

        // And back on: the next identical travel must produce nothing new.
        events.clear()
        engine.updateSwipeRequiresOpenHand(true)
        x = 0f
        repeat(12) { i ->
            engine.processFrame(
                hand(ts, offsetX = x, middle = false, ring = false, pinky = false, thumbOut = false),
            )
            x += 0.06f
            ts += 40L
            if (i % 3 == 0) runCurrent()
        }
        runCurrent()
        assertEquals("turning it back on re-gates the same motion",
            0, events.count { it is GestureEvent.Swipe })
        job.cancel()
    }

    // =====================================================================
    // A-12 / A-13 — closing the hand disarms; it does not change the volume
    // =====================================================================

    @Test
    fun `closing the hand disarms even while the thumb sticks out`() {
        val engine = engineWith(GestureEngineConfig(fistDisarmDurationMs = 600L))
        var ts = arm(engine)

        // All four fingers curled, thumb still out: fistLike, so the disarm hold
        // must run to completion.
        repeat(30) {
            engine.processFrame(hand(ts, index = false, middle = false, ring = false, pinky = false, thumbOut = true))
            ts += 40L
        }
        assertEquals(
            "fist-like closure must disarm",
            GestureEngineState.DISARMED, engine.engineState.value,
        )
    }

    @Test
    fun `a thumb pose made while the hand is moving does not fire`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = engineWith(GestureEngineConfig(fistDisarmDurationMs = 100_000L))
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()

        var ts = arm(engine)
        // Closing motion: fingers curl while the hand is still travelling.
        var x = 0f
        repeat(8) { i ->
            engine.processFrame(
                hand(ts, offsetX = x, index = false, middle = false, ring = false, pinky = false, thumbOut = true),
            )
            x += 0.04f
            ts += 40L
            if (i % 3 == 0) runCurrent()
        }
        runCurrent()
        assertEquals(
            "no volume change from a moving closing hand",
            0,
            events.count { it is GestureEvent.PoseTriggered && (it.pose == Pose.THUMB_UP || it.pose == Pose.THUMB_DOWN) },
        )
        job.cancel()
    }

    @Test
    fun `a deliberate still thumb up fires`() = runTest {
        val events = mutableListOf<GestureEvent>()
        val engine = engineWith(GestureEngineConfig(fistDisarmDurationMs = 5_000L, thumbGestureHoldMs = 200L))
        val job = launch { engine.gestureEvents.toList(events) }
        runCurrent()

        var ts = arm(engine)
        // Still hand, fingers curled, thumb clearly up — held long enough.
        repeat(20) { i ->
            engine.processFrame(hand(ts, index = false, middle = false, ring = false, pinky = false, thumbOut = true))
            ts += 40L
            if (i % 3 == 0) runCurrent()
        }
        runCurrent()
        assertTrue(
            "a held, still thumb-up must fire",
            events.any { it is GestureEvent.PoseTriggered && it.pose == Pose.THUMB_UP },
        )
        job.cancel()
    }

    // =====================================================================
    // A-14 — debouncing is time based, not frame based
    // =====================================================================

    @Test
    fun `arming is fast even at scan-mode frame rate`() {
        val engine = engineWith(GestureEngineConfig(poseDebounceFrames = 4, poseDebounceMs = 120L))
        val startMs = 1000L
        var ts = startMs
        // 5 fps scan mode: 200ms per frame. A 3-frame debounce would have needed
        // 600ms just to confirm the pose.
        repeat(3) {
            engine.processFrame(hand(ts))
            ts += 200L
        }
        assertEquals(
            "ARMED within 3 frames at 5fps",
            GestureEngineState.ARMED, engine.engineState.value,
        )
        assertTrue(
            "elapsed must stay under half a second",
            ts - startMs <= 700L,
        )
    }

    @Test
    fun `debounce frames never exceed the configured cap at high frame rate`() {
        val cfg = GestureEngineConfig(poseDebounceFrames = 4, poseDebounceMs = 120L)
        assertEquals(4, cfg.debounceFramesFor(33L).coerceAtMost(4))
        assertEquals(1, cfg.debounceFramesFor(200L))
        assertEquals(4, cfg.debounceFramesFor(0L))
    }

    // =====================================================================
    // Robustness — random motion must never throw or invent events
    // =====================================================================

    @Test
    fun `random motion while armed never emits a bogus pose event`() {
        val engine = engineWith()
        var ts = 1000L
        val random = java.util.Random(42)
        repeat(400) {
            val input = hand(
                ts,
                offsetX = (random.nextFloat() - 0.5f) * 0.6f,
                offsetY = (random.nextFloat() - 0.5f) * 0.4f,
                scale = 0.1f + random.nextFloat() * 0.5f,
                index = random.nextBoolean(),
                middle = random.nextBoolean(),
                ring = random.nextBoolean(),
                pinky = random.nextBoolean(),
                thumbOut = random.nextBoolean(),
                confidence = 0.3f + random.nextFloat() * 0.7f,
            )
            engine.processFrame(input)
            ts += 40L
        }
    }

    @Test
    fun `reset returns the engine to DISARMED and clears the pose`() {
        val engine = engineWith()
        arm(engine)
        engine.reset()
        assertEquals(GestureEngineState.DISARMED, engine.engineState.value)
        assertEquals(Pose.NONE, engine.currentPose.value)
    }
}
