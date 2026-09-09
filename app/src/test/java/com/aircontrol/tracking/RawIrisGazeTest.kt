package com.aircontrol.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Phase 2/15 — raw eye geometry, verified BEFORE smoothing or the personalized
 * model can influence anything.
 *
 * These are the tests whose absence let the horizontal-cancellation bug ship: the
 * old code had no deterministic check that "look right" produces a rightward
 * change, because the only raw-gaze implementation was a private method inside
 * [FaceTracker] and therefore untestable. Fixing that is part of the repair.
 *
 * Fixtures are authored in the CANONICAL person view (x: viewer right, y: down),
 * then horizontally flipped to emulate what a front camera actually hands the
 * pipeline, with `isFrontCameraMirrored = true`. So every test below also proves
 * the mirror is undone exactly once.
 */
class RawIrisGazeTest {

    // --- synthetic face ------------------------------------------------------

    private data class EyeSpec(
        val centerX: Float,
        val centerY: Float,
        val halfWidth: Float,
        val halfLid: Float,
        val irisX: Float,
        val irisY: Float,
    )

    /**
     * Places one eye's landmarks in the canonical person view.
     *
     * The temporal (outer) corner is always the corner FARTHEST FROM THE NOSE, so
     * which side of the eye it lies on depends on which side of the face the eye is
     * on. Encoding that here is deliberate: an earlier version of this fixture used
     * one convention for both eyes and it is exactly the mistake that made the
     * production horizontal signal cancel.
     */
    private fun putLandmarks(
        list: Array<FloatArray>,
        def: EyeLandmarkDefinition,
        eye: EyeSpec,
    ) {
        fun set(index: Int, x: Float, y: Float) {
            list[index] = floatArrayOf(x, y, 0f)
        }
        val temporal = if (eye.centerX >= 0.5f) 1f else -1f
        val outerX = eye.centerX + temporal * eye.halfWidth
        val innerX = eye.centerX - temporal * eye.halfWidth
        set(def.outerCorner, outerX, eye.centerY)
        set(def.innerCorner, innerX, eye.centerY)
        set(def.upperOuter, outerX - temporal * eye.halfWidth * 0.5f, eye.centerY - eye.halfLid * 0.7f)
        set(def.upperInner, innerX + temporal * eye.halfWidth * 0.5f, eye.centerY - eye.halfLid * 0.7f)
        set(def.lowerInner, innerX + temporal * eye.halfWidth * 0.5f, eye.centerY + eye.halfLid * 0.7f)
        set(def.lowerOuter, outerX - temporal * eye.halfWidth * 0.5f, eye.centerY + eye.halfLid * 0.7f)
        set(def.irisCenter, eye.irisX, eye.irisY)
        def.irisRing.forEachIndexed { i, idx ->
            val angle = Math.toRadians((90.0 * i).toFloat().toDouble()).toFloat()
            set(idx, eye.irisX + 0.004f * abs(kotlin.math.cos(angle)), eye.irisY + 0.004f * abs(kotlin.math.sin(angle)))
        }
    }

    /**
     * @param gazeX  horizontal iris offset, in eye widths, in the canonical frame
     *               (positive = looking toward the viewer's right)
     * @param gazeY  vertical iris offset, in eye widths (positive = looking down)
     * @param dropRightEye  simulate one eye unusable (occlusion / out of crop)
     */
    private fun frame(
        gazeX: Float,
        gazeY: Float = 0f,
        dropRightEye: Boolean = false,
        dropLeftEye: Boolean = false,
        mirrored: Boolean = true,
        widthPx: Int = 640,
        heightPx: Int = 480,
    ): FaceLandmarkFrame {
        val list = Array(CanonicalEyes.MIN_LANDMARK_COUNT) { floatArrayOf(0f, 0f, 0f) }

        // Eye half-width in normalized image units. A 40 px eye on a 640 px wide
        // analysis frame is 0.0625 -> half of that.
        val half = 0.03f
        val left = EyeSpec(
            centerX = 0.62f, centerY = 0.40f, halfWidth = half, halfLid = 0.010f,
            irisX = 0.62f + gazeX * half * 2f, irisY = 0.40f + gazeY * half * 2f,
        )
        val right = EyeSpec(
            centerX = 0.38f, centerY = 0.40f, halfWidth = half, halfLid = 0.010f,
            irisX = 0.38f + gazeX * half * 2f, irisY = 0.40f + gazeY * half * 2f,
        )
        if (!dropLeftEye) putLandmarks(list, CanonicalEyes.LEFT, left)
        if (!dropRightEye) putLandmarks(list, CanonicalEyes.RIGHT, right)

        // Face anchors used by the head-pose tier; keep them neutral and symmetric.
        list[FacePoseLandmarks.NOSE_TIP] = floatArrayOf(0.50f, 0.55f, 0f)
        list[FacePoseLandmarks.FOREHEAD] = floatArrayOf(0.50f, 0.15f, 0f)
        list[FacePoseLandmarks.CHIN] = floatArrayOf(0.50f, 0.85f, 0f)

        val coords = if (mirrored) {
            list.map { floatArrayOf(1f - it[0], it[1], it[2]) }
        } else {
            list.map { it.copyOf() }
        }
        return FaceLandmarkFrame(
            frameId = 1L,
            timestampNs = 1_000_000L,
            timestampMs = 1L,
            trackerWidthPx = widthPx,
            trackerHeightPx = heightPx,
            isFrontCameraMirrored = mirrored,
            landmarks = coords.map { FaceLandmark(it[0], it[1], it[2]) },
        )
    }

    private fun gazeFor(gazeX: Float, gazeY: Float = 0f): RawIrisGaze? =
        RawIrisGazeExtractor.from(EyeFeatureExtractor.extract(frame(gazeX, gazeY)))

    // --- horizontal: the cancelled axis -------------------------------------

    @Test fun lookingRightMovesGazeRightBeyondNeutral() {
        val neutral = gazeFor(0f)!!
        val right = gazeFor(0.35f)!!
        assertTrue(
            "expected h to rise with rightward gaze: neutral=${neutral.h} right=${right.h}",
            right.h > neutral.h + 0.1f,
        )
    }

    @Test fun lookingLeftMovesGazeLeftSymmetrically() {
        val neutral = gazeFor(0f)!!
        val left = gazeFor(-0.35f)!!
        assertEquals(neutral.h - left.h, rightDelta(mirrored = true), 1e-3f)
        assertTrue(left.h < neutral.h - 0.1f)
    }

    /** Both eyes must contribute the SAME sign; that is the whole bug. */
    @Test fun perEyeViewerOffsetsAgreeInSign() {
        val features = EyeFeatureExtractor.extract(frame(0.3f))
        val l = features.left!!
        val r = features.right!!
        assertTrue("left eye should report +x", l.irisViewerX > 0f)
        assertTrue("right eye should report +x too, not the opposite", r.irisViewerX > 0f)
        // Their raw per-eye ratios move in OPPOSITE directions, which is why they
        // could never be averaged directly.
        assertTrue("irisAlongAxis is per-eye temporal-positive: signs must differ",
            (l.irisAlongAxis - 0.5f) * (r.irisAlongAxis - 0.5f) < 0f)
    }

    @Test fun binocularMeanDoesNotCancelHorizontalGaze() {
        val right = gazeFor(0.4f)!!
        val left = gazeFor(-0.4f)!!
        // A cancelled signal would leave both stuck at 0.5.
        assertTrue("h must move by > 0.6 of the range end to end, was ${right.h - left.h}",
            right.h - left.h > 0.6f)
    }

    // --- vertical ------------------------------------------------------------

    @Test fun lookingDownIncreasesVAndLookingUpDecreasesIt() {
        val neutral = gazeFor(0f)!!
        val down = gazeFor(0f, gazeY = 0.30f)!!
        val up = gazeFor(0f, gazeY = -0.30f)!!
        assertTrue(down.v > neutral.v + 0.05f)
        assertTrue(up.v < neutral.v - 0.05f)
    }

    @Test fun neutralGazeIsStableAndCentred() {
        val neutral = gazeFor(0f)!!
        assertEquals(0.5f, neutral.h, 1e-4f)
        assertEquals(0.5f, neutral.v, 1e-4f)
    }

    // --- mirroring / conventions --------------------------------------------

    @Test fun mirroredAndUnmirroredFramesProduceTheSameGaze() {
        val mirrored = RawIrisGazeExtractor.from(
            EyeFeatureExtractor.extract(frame(0.3f, mirrored = true)),
        )!!
        val unmirrored = RawIrisGazeExtractor.from(
            EyeFeatureExtractor.extract(frame(0.3f, mirrored = false)),
        )!!
        // In the unmirrored fixture the raw landmark x are already canonical, so
        // the SAME gaze offset must come out; if a stage inverted twice these
        // would be mirror images of each other.
        assertEquals(mirrored.h, unmirrored.h, 1e-4f)
        assertEquals(mirrored.v, unmirrored.v, 1e-4f)
    }

    @Test fun eyesAreNeverSwapped() {
        val features = EyeFeatureExtractor.extract(frame(0f))
        // Canonical LEFT is the eye at larger x in the person view.
        assertNotNull(features.left)
        assertTrue(features.left!!.eyeCenterX > features.right!!.eyeCenterX)
        assertEquals(1f, features.left!!.axisSign, 0f)
        assertEquals(-1f, features.right!!.axisSign, 0f)
    }

    // --- robustness ----------------------------------------------------------

    @Test fun monocularGazeStillMovesHorizontally() {
        val both = gazeFor(0.35f)!!
        val onlyLeft = RawIrisGazeExtractor.from(
            EyeFeatureExtractor.extract(frame(0.35f, dropRightEye = true)),
        )!!
        val onlyRight = RawIrisGazeExtractor.from(
            EyeFeatureExtractor.extract(frame(0.35f, dropLeftEye = true)),
        )!!
        assertEquals(1, onlyLeft.eyesUsed)
        assertEquals(1, onlyRight.eyesUsed)
        // Losing one eye must not invert or flatten the horizontal response.
        assertTrue(onlyLeft.h > 0.5f && onlyRight.h > 0.5f)
        assertEquals(both.h, (onlyLeft.h + onlyRight.h) / 2f, 1e-3f)
    }

    @Test fun nonFiniteLandmarksProduceNoGaze() {
        val broken = frame(0f).let { src ->
            src.copy(landmarks = src.landmarks.map { FaceLandmark(Float.NaN, it.y, it.z) })
        }
        assertNull(RawIrisGazeExtractor.from(EyeFeatureExtractor.extract(broken)))
    }

    @Test fun gazeIsAlwaysWithinUnitRange() {
        for (g in listOf(-1f, -0.6f, -0.2f, 0f, 0.2f, 0.6f, 1f, 1.4f)) {
            val gaze = gazeFor(g)!!
            assertTrue("h out of range: ${gaze.h}", gaze.h in 0f..1f)
            assertTrue("v out of range: ${gaze.v}", gaze.v in 0f..1f)
            assertTrue(gaze.isValid)
        }
    }

    @Test fun aspectRatioDoesNotRotateHorizontalIntoVertical() {
        // Same physical gaze on a square and a 4:3 tracker must give the same gaze.
        val wide = RawIrisGazeExtractor.from(EyeFeatureExtractor.extract(frame(0.3f, widthPx = 640, heightPx = 480)))!!
        val square = RawIrisGazeExtractor.from(EyeFeatureExtractor.extract(frame(0.3f, widthPx = 480, heightPx = 480)))!!
        // x normalisation is per-width, so a pure horizontal offset must not leak
        // into v when the aspect changes; h stays identical.
        assertEquals(wide.h, square.h, 1e-3f)
        assertEquals(0.5f, square.v, 1e-3f)
    }

    private fun rightDelta(mirrored: Boolean): Float {
        val g = RawIrisGazeExtractor.from(EyeFeatureExtractor.extract(frame(0.35f, mirrored = mirrored)))!!
        return g.h - 0.5f
    }
}
