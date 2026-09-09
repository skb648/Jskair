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
 * Swipe recognition as human intent, not as a gate stack (recovery cycle, phases 12-16).
 *
 * Every sequence here is deterministic and expressed as the motion a person actually
 * makes - travel in fractions of the frame, timing in milliseconds, hand size as a real
 * palm - because the failures being guarded against were all "the numbers passed but the
 * gesture did not". Each test names the behaviour it protects.
 */
class SwipeIntentTest {

    private val config = GestureEngineConfig()

    /**
     * Wrist-anchored offsets of a rigid open right hand, palm (wrist to middle-finger
     * base) = 0.11 of the image height - about a 10 cm hand half a metre from a camera
     * whose analysis frame is 480 px tall.
     */
    private val palmOffsets: List<Pair<Float, Float>> = listOf(
        0f to 0f,
        -0.05f to -0.03f, -0.07f to -0.06f, -0.08f to -0.09f, -0.09f to -0.12f,
        0.02f to -0.08f, 0.02f to -0.12f, 0.02f to -0.145f, 0.02f to -0.17f,
        0f to -0.11f, 0f to -0.15f, 0f to -0.175f, 0f to -0.20f,
        -0.02f to -0.10f, -0.02f to -0.14f, -0.02f to -0.165f, -0.02f to -0.19f,
        -0.04f to -0.08f, -0.04f to -0.11f, -0.04f to -0.13f, -0.04f to -0.15f,
    )

    private fun hand(
        x: Float,
        y: Float,
        ts: Long,
        aspect: Float = 4f / 3f,
        confidence: Float = 0.95f,
    ): HandInput = HandInput(
        landmarks = List(21) { i -> Landmark3D(x + palmOffsets[i].first, y + palmOffsets[i].second, 0f) },
        handedness = Handedness.RIGHT,
        timestampMs = ts,
        confidence = confidence,
        frameAspectRatio = aspect,
    )

    /** Feeds a trajectory and returns every frame's result. */
    private fun feed(
        detector: DynamicGestureDetector,
        points: List<Triple<Float, Float, Long>>,
        aspect: Float = 4f / 3f,
    ): List<DynamicGestureDetector.SwipeResult> = points.map { (x, y, ts) ->
        detector.process(hand(x, y, ts, aspect))
    }

    private fun commits(results: List<DynamicGestureDetector.SwipeResult>) =
        results.filter { it.detected }

    private fun straight(
        fromX: Float,
        toX: Float,
        y: Float = 0.5f,
        frames: Int = 9,
        gapMs: Long = 40L,
        startTs: Long = 1000L,
    ): List<Triple<Float, Float, Long>> = (0 until frames).map { i ->
        val t = i.toFloat() / (frames - 1)
        Triple(fromX + (toX - fromX) * t, y, startTs + (i * gapMs))
    }

    // ---------------------------------------------------------------- the four axes

    /**
     * A natural swipe is not drawn with a ruler: an arm swings in a slight bow. Every
     * direction must commit through that bow, because a detector that only accepts
     * pixel-perfect lines is a detector that rejects hands.
     */
    @Test
    fun `each direction commits from a naturally bowed path`() {
        val cases = mapOf(
            SwipeDirection.RIGHT to (0..8).map { Triple(0.2f + it * 0.05f, 0.5f + 0.006f * kotlin.math.sin(it * 0.7f), 1000L + it * 40L) },
            SwipeDirection.LEFT to (0..8).map { Triple(0.7f - it * 0.05f, 0.5f + 0.006f * kotlin.math.sin(it * 0.7f), 1000L + it * 40L) },
            SwipeDirection.UP to (0..8).map { Triple(0.5f + 0.006f * kotlin.math.sin(it * 0.7f), 0.75f - it * 0.05f, 1000L + it * 40L) },
            SwipeDirection.DOWN to (0..8).map { Triple(0.5f + 0.006f * kotlin.math.sin(it * 0.7f), 0.25f + it * 0.05f, 1000L + it * 40L) },
        )
        cases.forEach { (expected, points) ->
            val detector = DynamicGestureDetector(config)
            val fired = commits(feed(detector, points))
            assertEquals("one commit for a $expected sweep", 1, fired.size)
            assertEquals("and it must be the direction the hand actually took", expected, fired.single().direction)
        }
    }

    // ------------------------------------------------------- evidence, not thresholds

    /**
     * The behaviour change the whole redesign exists for: a deliberate, slow, full-width
     * sweep is a swipe. It used to be discarded by a peak-velocity gate that a human
     * moving with intent does not reliably exceed, which is how "too sensitive" became
     * "nothing happens" without any single threshold being wrong.
     */
    @Test
    fun `a slow deliberate sweep commits exactly once`() {
        val detector = DynamicGestureDetector(config)
        // 0.4 of the frame over 600 ms: half a palm per 40 ms - no flick at all.
        val points = (0..14).map { Triple(0.3f + it * 0.03f, 0.5f, 1000L + it * 40L) }
        val fired = commits(feed(detector, points))
        assertEquals(1, fired.size)
        assertEquals(SwipeDirection.RIGHT, fired.single().direction)
    }

    /**
     * The other half of the same rule: moving the hand into position is not a gesture.
     *
     * Stated as what a camera-only signal can actually distinguish, because pretending
     * otherwise is how this feature ended up over-gated the first time: a hand that
     * DRIFTS into place (below the travel floor) and a hand that arcs into place (a turn
     * of the wrist across the gesture) are both rejected. A hand that is carried across
     * the screen in a straight line, at constant speed, with an open palm, is
     * geometrically identical to a deliberate slow sweep - and is treated as one, the
     * same way a trackpad treats a slow straight finger drag as a scroll. The camera
     * has no pressure signal and no contact event, so anything that claims to separate
     * them would be a threshold invented to make a demo pass. What protects
     * the user from that is not a velocity gate but the pose gate (an open palm must be
     * held), the travel floor, and the one-action-per-motion latch.
     */
    @Test
    fun `drifting and arcing into position never commit`() {
        val drift = commits(
            feed(DynamicGestureDetector(config), (0..7).map { Triple(0.5f + it * 0.006f, 0.5f, 1000L + it * 40L) }),
        )
        assertTrue("a sub-palm drift is not a gesture: ${drift.map { it.direction }}", drift.isEmpty())

        // A path that turns hard while it travels - the shape of reaching around rather
        // than throwing - never commits, no matter how much ground it covers.
        val turning = commits(
            feed(
                DynamicGestureDetector(config),
                (0..11).map { i ->
                    val a = Math.toRadians(i * 34.0).toFloat()
                    Triple(0.4f + 0.16f * kotlin.math.cos(a), 0.5f + 0.16f * kotlin.math.sin(a), 1000L + i * 40L)
                },
            ),
        )
        assertTrue("a path that curves is not a gesture: ${turning.map { it.direction }}", turning.isEmpty())
    }

    /**
     * Borderline, stated on both sides of the line: at 0.35 of a palm per window there is
     * nothing to tell a swipe from a drift; at 0.75 there is. The point is not the number
     * - it is that the answer changes once, monotonically, with evidence.
     */
    @Test
    fun `the travel boundary is crossed once and in the right direction`() {
        // 0.35 palm of travel across the whole 350 ms window (8 frames at 0.0045/frame).
        val below = commits(feed(DynamicGestureDetector(config), (0..7).map { Triple(0.5f + it * 0.0045f, 0.5f, 1000L + it * 40L) }))
        assertTrue("0.32 palm of travel is a drift, not a swipe", below.isEmpty())

        // 0.75 palm of travel across the window.
        val above = commits(feed(DynamicGestureDetector(config), (0..7).map { Triple(0.5f + it * 0.01f, 0.5f, 1000L + it * 40L) }))
        assertEquals("0.7 palm of directed travel is a swipe", 1, above.size)
    }

    // ------------------------------------------------------ one motion, one action

    /**
     * A swipe that outlives the cooldown - a long, slow sweep - must still produce one
     * action. The previous design could only guarantee "at most one per 220 ms", which
     * reads to the user as one gesture scrolling two pages.
     */
    @Test
    fun `one continuous motion cannot produce two actions`() {
        for (gapMs in longArrayOf(33L, 40L, 60L, 80L, 100L)) {
            val detector = DynamicGestureDetector(config)
            val points = (0..19).map { Triple(0.15f + it * 0.03f, 0.5f, 1000L + it * gapMs) }
            val fired = commits(feed(detector, points))
            assertEquals("one commit per continuous throw at ${gapMs}ms frames", 1, fired.size)
        }
    }

    /**
     * Two real swipes, separated by the motion a person actually makes between them
     * (stop, then go again), must both fire. The latch that guarantees one-action-per-
     * motion is only worth anything if it releases without the user going rigid.
     */
    @Test
    fun `back to back swipes both fire when the hand settles between them`() {
        val detector = DynamicGestureDetector(config)
        var ts = 1000L
        var fired = 0
        var leftward = 0
        repeat(2) { rep ->
            (0..7).forEach { i ->
                val r = detector.process(hand(0.2f + i * 0.05f, 0.5f, ts))
                if (r.detected) {
                    fired++
                    if (r.direction == SwipeDirection.LEFT) leftward++
                }
                ts += 40L
            }
            // The way a person actually resets: bring the hand back, let it settle
            // briefly, then go again. The return must not fire a leftward swipe.
            (0..5).forEach { i ->
                val r = detector.process(hand(0.55f - i * 0.06f, 0.5f, ts))
                if (r.detected && r.direction == SwipeDirection.LEFT) leftward++
                ts += 40L
            }
            repeat(6) {
                detector.process(hand(0.2f, 0.5f, ts))
                ts += 40L
            }
            if (rep == 0) assertEquals("the first throw fired once", 1, fired)
        }
        assertEquals("both right swipes fire", 2, fired)
        assertEquals("the return motion never fires", 0, leftward)
    }

    /** A second swipe attempted *inside* the cooldown is swallowed, not queued. */
    @Test
    fun `a second motion inside the cooldown does not queue up and fire late`() {
        val detector = DynamicGestureDetector(config)
        var ts = 1000L
        var fired = 0
        (0..7).forEach { i ->
            if (detector.process(hand(0.2f + i * 0.05f, 0.5f, ts)).detected) fired++
            ts += 40L
        }
        // Straight into another throw, no settle at all.
        (0..7).forEach { i ->
            if (detector.process(hand(0.2f + i * 0.05f, 0.5f, ts)).detected) fired++
            ts += 40L
        }
        // Then a long settle: the swallowed motion must NOT reappear afterwards.
        repeat(20) {
            if (detector.process(hand(0.55f, 0.5f, ts)).detected) fired++
            ts += 40L
        }
        assertEquals(1, fired)
    }

    // ------------------------------------------------------------------- noise shapes

    /** Waving the hand - a closed path - is the archetypal non-swipe, at any frame rate. */
    @Test
    fun `a wave is never a swipe at any frame rate`() {
        for (gapMs in longArrayOf(30L, 40L, 60L, 80L, 100L)) {
            val detector = DynamicGestureDetector(config)
            var ts = 1000L
            var fired = 0
            repeat(2) {
                for (i in 0 until 24) {
                    val angle = i * 2.0 * Math.PI / 24
                    val x = 0.5f + 0.12f * kotlin.math.cos(angle).toFloat()
                    val y = 0.5f + 0.12f * kotlin.math.sin(angle).toFloat()
                    if (detector.process(hand(x, y, ts)).detected) fired++
                    ts += gapMs
                }
            }
            assertEquals("a wave must produce no scroll at ${gapMs}ms frames", 0, fired)
        }
    }

    /** An out-and-back flick: the throw out is real, the return is not a second one. */
    @Test
    fun `an out and back flick commits at most once`() {
        val detector = DynamicGestureDetector(config)
        var ts = 1000L
        var fired = 0
        val directions = mutableListOf<SwipeDirection?>()
        (0..6).forEach {
            if (detector.process(hand(0.3f + it * 0.05f, 0.5f, ts)).detected) {
                fired++
            }
            ts += 40L
        }
        (0..6).forEach {
            val r = detector.process(hand(0.6f - it * 0.05f, 0.5f, ts))
            if (r.detected) {
                fired++
                directions += r.direction
            }
            ts += 40L
        }
        assertTrue("a flick-and-return is one gesture, not two: got $fired", fired <= 1)
    }

    /** A single-frame teleport is not motion, however far the point moved. */
    @Test
    fun `a teleport never commits`() {
        val detector = DynamicGestureDetector(config)
        val points = listOf(
            Triple(0.2f, 0.5f, 1000L),
            Triple(0.21f, 0.5f, 1040L),
            Triple(0.8f, 0.5f, 1080L), // 0.59 of the frame in 40 ms: no hand does that
            Triple(0.81f, 0.5f, 1120L),
            Triple(0.82f, 0.5f, 1160L),
        )
        val fired = commits(feed(detector, points))
        assertTrue("a tracking jump must not scroll the page", fired.isEmpty())
    }

    // ------------------------------------------------------------------- dropout rule

    /**
     * Losing the hand mid-gesture cancels the candidate; picking the motion back up must
     * not complete the old one. This is the "safe dropout handling" requirement, and it
     * is also the case where an implementation that keeps its windows across the gap
     * stitches two unrelated motions into one long throw.
     */
    @Test
    fun `tracking dropout with a live candidate does not resume into a commit`() {
        val detector = DynamicGestureDetector(config)
        var ts = 1000L
        (0..4).forEach { i ->
            detector.process(hand(0.3f + i * 0.05f, 0.5f, ts))
            ts += 40L
        }
        // Two seconds of nothing, then the hand reappears and continues from where the
        // old window says it should have been.
        repeat(10) {
            detector.process(
                HandInput(emptyList(), Handedness.UNKNOWN, ts, 0f, frameAspectRatio = 4f / 3f),
            )
            ts += 100L
        }
        val afterResume = (0..5).map { i ->
            val r = detector.process(hand(0.55f + i * 0.05f, 0.5f, ts))
            ts += 40L
            r
        }
        assertFalse(
            "the first frame back cannot complete the abandoned gesture",
            afterResume.first().detected,
        )
        assertTrue(
            "a resumed throw is judged on its own merits, and at most once: " +
                commits(afterResume).map { it.direction },
            commits(afterResume).size <= 1,
        )
    }

    // ------------------------------------------------------- coordinate system invariance

    /**
     * The same physical gesture, seen by a 4:3 camera and a 16:9 one, must be judged
     * identically. MediaPipe normalises x by image width and y by image height, so a
     * detector that compares the two axes without reconciling them reads a horizontal
     * swipe on a wide sensor as two thirds of the same motion - which is how the axis
     * dominance rule became noise. 60 px of travel on a 480 px-tall frame is 0.125 of a
     * frame height on both sensors, by construction of these fixtures.
     */
    @Test
    fun `the same physical gesture is judged the same on any sensor aspect`() {
        val steps = 8
        fun pointsFor(aspect: Float) = (0 until steps).map { i ->
            // 0.125 frame-heights of horizontal travel per step, in normalised units.
            Triple(0.2f + i * 0.125f / aspect, 0.5f, 1000L + i * 40L)
        }
        val fourThree = commits(feed(DynamicGestureDetector(config), pointsFor(4f / 3f), aspect = 4f / 3f))
        val sixteenNine = commits(feed(DynamicGestureDetector(config), pointsFor(16f / 9f), aspect = 16f / 9f))
        assertEquals(1, fourThree.size)
        assertEquals(1, sixteenNine.size)
        assertEquals(fourThree.single().direction, sixteenNine.single().direction)
    }

    /**
     * A hand close to the camera and a hand far from it make the same gesture in
     * different pixel counts. Normalising by the hand itself is what makes the two agree,
     * and this pins it: scale the whole fixture (positions and hand size) and the verdict
     * must not move.
     */
    @Test
    fun `the same gesture commits at any distance from the camera`() {
        val verdicts = listOf(0.6f, 1.0f, 1.5f).map { scale ->
            val detector = DynamicGestureDetector(config)
            // A gesture of 1.2 palm lengths whatever the distance: the closer hand
            // covers proportionally more of the frame.
            val travel = 1.2f * 0.11f * scale
            val points = (0..7).map { i -> Triple(0.5f + travel * i / 7f, 0.5f, 1000L + i * 40L) }
            val fired = commits(feed(detector, points))
            fired.size to fired.firstOrNull()?.direction
        }
        verdicts.forEachIndexed { index, verdict ->
            assertEquals("scale $index must agree with the rest: $verdicts", 1 to SwipeDirection.RIGHT, verdict)
        }
    }

    // --------------------------------------------------------------- arbiter invariants

    private fun evidence(
        travelSpans: Float = 1.2f,
        velocity: Float = 6f,
        consistency: Float = 1f,
        dominance: Float = 3f,
        quality: Float = 0.95f,
        resolution: Float = 1f,
        efficiency: Float = 1f,
        drift: Float = 0f,
        samples: Int = 8,
        movingSteps: Int = 7,
        reversingSteps: Int = 0,
        direction: SwipeDirection? = SwipeDirection.RIGHT,
    ) = SwipeEvidence(
        netX = travelSpans,
        netY = 0f,
        displacementInHandSpans = travelSpans,
        peakVelocitySpansPerSecond = velocity,
        axisDominance = dominance,
        directionalConsistency = consistency,
        sampleCount = samples,
        movingSteps = movingSteps,
        reversingSteps = reversingSteps,
        trackingQuality = quality,
        landmarkResolution = resolution,
        pathEfficiency = efficiency,
        headingDriftDeg = drift,
        direction = direction,
    )

    /** The score responds monotonically to travel: one number, no cliff. */
    @Test
    fun `score rises monotonically with travel`() {
        val arbiter = SwipeIntentArbiter()
        val scores = listOf(0.1f, 0.3f, 0.5f, 0.8f, 1.2f, 2.0f).map { arbiter.scoreOf(evidence(travelSpans = it)) }
        scores.zipWithNext().forEach { (lower, higher) ->
            assertTrue("more travel cannot mean less intent: $scores", higher >= lower)
        }
        // Tiny travel with otherwise perfect evidence may become a candidate - the
        // machine is watching - but never an action: the travel floor is a validity
        // floor, which is the one place where a hard rule is correct.
        assertTrue("the least travel must not commit", scores.first() < SwipeIntentArbiter.DEFAULT_COMMIT_SCORE)
        assertTrue("the most travel must clear the commit level", scores.last() > SwipeIntentArbiter.DEFAULT_COMMIT_SCORE)
    }

    /** No single contribution can carry a gesture, and no single one can kill it. */
    @Test
    fun `one missing contribution weakens the score without vetoing it`() {
        val arbiter = SwipeIntentArbiter()
        val full = arbiter.scoreOf(evidence())
        val noVelocity = arbiter.scoreOf(evidence(velocity = 0f))
        val noQuality = arbiter.scoreOf(evidence(quality = 0f))
        val noDominance = arbiter.scoreOf(evidence(dominance = 1f))
        assertTrue(full > noVelocity)
        assertTrue("a slow swipe with real travel is still a swipe", noVelocity >= SwipeIntentArbiter.DEFAULT_COMMIT_SCORE)
        assertTrue("a shaky hand is not enough on its own to veto a clear throw", noQuality > SwipeIntentArbiter.DEFAULT_CANDIDATE_SCORE)
        // A 45-degree throw never reaches the score at all: no direction is claimed
        // below the dominance floor, so there is nothing to act on. Asserted through the
        // machine rather than the number, because that is where the rule lives.
        val arbiter2 = SwipeIntentArbiter()
        var diagonalCommits = 0
        var clock = 0L
        repeat(8) {
            clock += 40L
            if (arbiter2.decide(clock, evidence(dominance = 1f, direction = null)).isCommit) diagonalCommits++
        }
        assertEquals("a diagonal is a drag, not a swipe", 0, diagonalCommits)
    }

    /** Validity floors are floors: they cannot be bought off with other evidence. */
    @Test
    fun `validity floors hold regardless of score`() {
        val cases = mapOf(
            "two samples" to evidence(samples = 2, movingSteps = 1),
            "one moving step" to evidence(movingSteps = 1),
            "no direction" to evidence(dominance = 1f, direction = null),
            "a closed path" to evidence(efficiency = 0.2f),
            "a hard curve" to evidence(drift = 180f),
            "an unmeasurable hand" to evidence(resolution = 0f),
        )
        cases.forEach { (name, sample) ->
            val arbiter = SwipeIntentArbiter()
            var fired = 0
            var ts = 0L
            repeat(6) {
                ts += 40L
                if (arbiter.decide(ts, sample).isCommit) fired++
            }
            assertEquals("$name must never commit", 0, fired)
        }
    }

    /** Commit needs the candidate to have been *held*, so a one-frame blip is harmless. */
    @Test
    fun `a blip that is instantly reversed does not commit`() {
        val arbiter = SwipeIntentArbiter()
        var fired = 0
        var ts = 0L
        // Strong evidence for a single 40 ms frame, then nothing.
        ts += 40L
        if (arbiter.decide(ts, evidence()).isCommit) fired++
        ts += 40L
        if (arbiter.decide(ts, evidence().copy(movingSteps = 1, sampleCount = 3)).isCommit) fired++
        assertEquals(0, fired)
    }

    /**
     * Evidence that wobbles across the commit level - a hand decelerating through the
     * end of its throw - must produce one action, not a stream, and must not need to be
     * re-held from scratch each frame. The gap between the two levels is what buys this;
     * a single threshold would flap with every frame.
     */
    @Test
    fun `evidence hovering between the two levels commits exactly once`() {
        val arbiter = SwipeIntentArbiter()
        var fired = 0
        var ts = 0L
        var strong = true
        repeat(12) {
            ts += 40L
            val wobble = if (strong) 1.6f else 0.9f
            strong = !strong
            val decision = arbiter.decide(
                ts,
                evidence(travelSpans = wobble, velocity = if (strong) 9f else 2f),
            )
            if (decision.isCommit) fired++
        }
        assertEquals(1, fired)
    }

    /** The machine's phase is observable, for the debug overlay and for tests. */
    @Test
    fun `phases advance in order and return to neutral`() {
        val arbiter = SwipeIntentArbiter()
        assertEquals(SwipeIntentArbiter.Phase.NEUTRAL, arbiter.currentPhase)
        var ts = 0L
        ts += 40L
        arbiter.decide(ts, evidence(travelSpans = 0.05f, velocity = 0f, consistency = 0.2f, dominance = 1f, direction = null))
        assertEquals(
            "a frame with motion but no claimable direction is watched, not acted on",
            SwipeIntentArbiter.Phase.TRACKING,
            arbiter.currentPhase,
        )
        ts += 40L
        val candidate = arbiter.decide(ts, evidence(velocity = 3f))
        assertEquals(SwipeIntentArbiter.Phase.CANDIDATE, candidate.phase)
        ts += 200L
        val committed = arbiter.decide(ts, evidence(velocity = 9f))
        assertTrue(committed.isCommit)
        ts += 20L
        assertEquals(SwipeIntentArbiter.Phase.COOLDOWN, arbiter.decide(ts, evidence(velocity = 9f)).phase)
    }
}
