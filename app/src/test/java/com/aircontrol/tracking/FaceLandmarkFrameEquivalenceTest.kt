package com.aircontrol.tracking

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * Audit P0-5: the frame became a primitive buffer, and a buffer is only acceptable if every number
 * the pipeline produces is *identical* to what the old 478-object list produced. That equivalence is
 * the whole claim of the change, so it is tested directly rather than assumed: the same landmark set
 * goes in through the slot-mode reader and through the list constructor, and both the eye features
 * and the head-pose estimate must match bit-for-bit.
 */
class FaceLandmarkFrameEquivalenceTest {

    /** Deterministic, non-degenerate face: a sphere-ish mesh with a distinct iris offset per side. */
    private fun landmarks(count: Int = CanonicalEyes.MIN_LANDMARK_COUNT): List<FaceLandmark> =
        List(count) { i ->
            val angle = i * 0.017_453_293f // arbitrary but fixed spread over the mesh
            val x = 0.5f + 0.28f * sin(angle) + (i % 7) * 0.001_5f
            val y = 0.45f + 0.22f * sin(angle * 1.7f) + (i % 5) * 0.001_1f
            FaceLandmark(x, y, sin(angle * 0.5f) * 0.05f)
        }

    private fun slotFrame(landmarks: List<FaceLandmark>, mirrored: Boolean = false): FaceLandmarkFrame {
        val reader = object : LandmarkReader {
            override val size: Int get() = landmarks.size
            override fun x(index: Int) = landmarks[index].x
            override fun y(index: Int) = landmarks[index].y
            override fun z(index: Int) = landmarks[index].z
        }
        return checkNotNull(
            FaceLandmarkFrame.fromReader(
                frameId = 123L,
                timestampNs = 123_000_000L,
                timestampMs = 123L,
                trackerWidthPx = 640,
                trackerHeightPx = 480,
                isFrontCameraMirrored = mirrored,
                facialTransformationMatrix = null,
                reader = reader,
            ),
        )
    }

    private fun listFrame(landmarks: List<FaceLandmark>, mirrored: Boolean = false) = FaceLandmarkFrame(
        frameId = 123L,
        timestampNs = 123_000_000L,
        timestampMs = 123L,
        trackerWidthPx = 640,
        trackerHeightPx = 480,
        isFrontCameraMirrored = mirrored,
        landmarks = landmarks,
        facialTransformationMatrix = null,
    )

    @Test
    fun `slot mode stores only the used indices and reads them back identically`() {
        val landmarks = landmarks()
        val slot = slotFrame(landmarks)
        val list = listFrame(landmarks)
        assertTrue(slot.isSlotMode)
        assertFalse(list.isSlotMode)
        assertEquals(
            "a slot-mode frame must hold 3 floats per used index, nothing else",
            FaceLandmarkSlots.USED_INDICES.size * 3,
            FaceLandmarkSlots.FLOATS_PER_FRAME,
        )
        for (index in FaceLandmarkSlots.USED_INDICES) {
            assertEquals(landmarks[index].x, slot.xOf(index), 0f)
            assertEquals(landmarks[index].y, slot.yOf(index), 0f)
            assertEquals(landmarks[index].z, slot.zOf(index), 0f)
            assertEquals(list.xOf(index), slot.xOf(index), 0f)
            assertEquals(list.landmark(index), slot.landmark(index))
            assertTrue(slot.isLandmarkIndexValid(index))
        }
    }

    @Test
    fun `copyUsedCoordinates agrees with per-landmark reads in both modes`() {
        val landmarks = landmarks()
        val stride = FaceLandmarkSlots.USED_INDICES.size
        val out = FloatArray(stride * 3)
        var previousSlotMode: FloatArray? = null
        for (frame in listOf(slotFrame(landmarks), listFrame(landmarks))) {
            frame.copyUsedCoordinates(out)
            // Planar layout: x block, y block, z block, all in USED_INDICES order.
            for (slot in 0 until stride) {
                val index = FaceLandmarkSlots.USED_INDICES[slot]
                assertEquals("x of landmark $index", frame.xOf(index), out[slot], 0f)
                assertEquals("y of landmark $index", frame.yOf(index), out[stride + slot], 0f)
                assertEquals("z of landmark $index", frame.zOf(index), out[2 * stride + slot], 0f)
            }
            val snapshot = out.copyOf()
            previousSlotMode?.let { assertArrayEquals(it, snapshot, 0f) } // both modes byte-identical
            previousSlotMode = snapshot
        }
        assertEquals(stride, slotFrame(landmarks).usedCoordinateStride)
    }

    @Test
    fun `eye features are numerically identical through either frame mode`() {
        val landmarks = landmarks()
        val fromSlot = EyeFeatureExtractor.extract(slotFrame(landmarks))
        val fromList = EyeFeatureExtractor.extract(listFrame(landmarks))
        assertEquals(fromList, fromSlot)
        assertTrue("the fixture must actually produce usable eyes", fromSlot.isValid)
        assertNotNull(fromSlot.left)
        assertNotNull(fromSlot.right)
    }

    @Test
    fun `features are identical for a mirrored frame in both modes`() {
        val landmarks = landmarks()
        val fromSlot = EyeFeatureExtractor.extract(slotFrame(landmarks, mirrored = true))
        val fromList = EyeFeatureExtractor.extract(listFrame(landmarks, mirrored = true))
        assertEquals(fromList, fromSlot)
    }

    @Test
    fun `head pose is numerically identical through either frame mode`() {
        val landmarks = landmarks()
        val slot = slotFrame(landmarks)
        val list = listFrame(landmarks)
        val slotPose = HeadPoseEstimator.estimate(slot, EyeFeatureExtractor.extract(slot))
        val listPose = HeadPoseEstimator.estimate(list, EyeFeatureExtractor.extract(list))
        assertEquals(listPose.yawDeg, slotPose.yawDeg, 0f)
        assertEquals(listPose.pitchDeg, slotPose.pitchDeg, 0f)
        assertEquals(listPose.rollDeg, slotPose.rollDeg, 0f)
        assertEquals(listPose.confidence, slotPose.confidence, 0f)
        assertEquals(listPose.source, slotPose.source)
        assertEquals(listPose.isValid, slotPose.isValid)
    }

    @Test
    fun `the calibration feature vector is identical through either frame mode`() {
        val landmarks = landmarks()
        val slot = slotFrame(landmarks)
        val list = listFrame(landmarks)
        val slotVector = GazeCalibrationFeatureVectorBuilder.from(
            HeadPoseNormalizer.normalize(
                EyeFeatureExtractor.extract(slot),
                HeadPoseEstimator.estimate(slot, EyeFeatureExtractor.extract(slot)),
            ),
        )
        val listVector = GazeCalibrationFeatureVectorBuilder.from(
            HeadPoseNormalizer.normalize(
                EyeFeatureExtractor.extract(list),
                HeadPoseEstimator.estimate(list, EyeFeatureExtractor.extract(list)),
            ),
        )
        assertNotNull(slotVector)
        assertArrayEquals(listVector?.values, slotVector?.values, 0f)
    }

    @Test
    fun `a source shorter than the canonical mesh produces no slot frame`() {
        val short = landmarks(count = 100)
        val reader = object : LandmarkReader {
            override val size: Int get() = short.size
            override fun x(index: Int) = short[index].x
            override fun y(index: Int) = short[index].y
            override fun z(index: Int) = short[index].z
        }
        assertNull(
            "a 100-landmark source has no irises, so it must not be treated as a face frame",
            FaceLandmarkFrame.fromReader(1L, 1_000_000L, 1L, 640, 480, false, null, reader),
        )
    }

    @Test
    fun `the landmark list view keeps the old contract for out-of-range reads`() {
        val frame = slotFrame(landmarks())
        assertNull(frame.landmark(999))
        assertFalse(frame.isLandmarkIndexValid(999))
        assertTrue(frame.xOf(999).isNaN())
        // An unused index is not "absent data" in a slot frame: it is not part of the contract, and
        // reading it must not silently return zero, which would look like a landmark at 0,0.
        assertTrue(frame.xOf(0).isNaN())
    }

    @Test
    fun `coordinate space documentation matches the code path used by the extractors`() {
        // Rules 3: anatomical identity comes from the landmark id, never from x position, and exactly
        // one deliberate mirror exists. Both frame modes must apply that mirror identically, which
        // the mirrored-feature test above proves; here we pin the flag itself so a new reader cannot
        // drift from the contract.
        val landmarks = landmarks()
        val unmirrored = EyeFeatureExtractor.extract(listFrame(landmarks, mirrored = false))
        val mirrored = EyeFeatureExtractor.extract(listFrame(landmarks, mirrored = true))
        assertNotNull(unmirrored.left)
        assertNotNull(mirrored.left)
        // x -> 1 - x then scaled by the tracker width, so the two centres must sum to exactly one
        // width: the mirror moves the point, it does not relabel the eye.
        assertEquals(
            "mirroring must move the eye centre, not swap which eye is LEFT",
            1f,
            unmirrored.left!!.eyeCenterX + mirrored.left!!.eyeCenterX,
            1e-3f,
        )
        assertEquals(
            "and the same for the other side, still named by anatomy",
            1f,
            unmirrored.right!!.eyeCenterX + mirrored.right!!.eyeCenterX,
            1e-3f,
        )
        assertTrue(GazeCoordinateContract.ANALYSIS_MIRRORED_HORIZONTALLY)
    }
}
