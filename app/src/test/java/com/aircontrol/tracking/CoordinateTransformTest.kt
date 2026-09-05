package com.aircontrol.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CoordinateTransformTest {
    private val crop = CropRect(100, 50, 640, 480)

    @Test fun identity() {
        val t = CoordinateTransform(1280, 720, crop, Rotation.DEG_0, false)
        assertEquals(640, t.transformedWidthPx)
        assertEquals(480, t.transformedHeightPx)
        assertEquals(TransformPoint(0f, 0f), t.toTracker(TransformPoint(100f, 50f)))
        assertEquals(TransformPoint(640f, 480f), t.toTracker(TransformPoint(740f, 530f)))
    }

    @Test fun mirror() {
        val t = CoordinateTransform(1280, 720, crop, Rotation.DEG_0, true)
        assertEquals(640f, t.toTracker(TransformPoint(100f, 50f)).x, 1e-6f)
        assertEquals(0f, t.toTracker(TransformPoint(740f, 50f)).x, 1e-6f)
    }

    @Test fun allRotationsHaveExpectedDimensions() {
        assertEquals(640, CoordinateTransform(1280, 720, crop, Rotation.DEG_0, false).transformedWidthPx)
        assertEquals(480, CoordinateTransform(1280, 720, crop, Rotation.DEG_0, false).transformedHeightPx)
        assertEquals(480, CoordinateTransform(1280, 720, crop, Rotation.DEG_90, false).transformedWidthPx)
        assertEquals(640, CoordinateTransform(1280, 720, crop, Rotation.DEG_90, false).transformedHeightPx)
        assertEquals(640, CoordinateTransform(1280, 720, crop, Rotation.DEG_180, false).transformedWidthPx)
        assertEquals(480, CoordinateTransform(1280, 720, crop, Rotation.DEG_180, false).transformedHeightPx)
        assertEquals(480, CoordinateTransform(1280, 720, crop, Rotation.DEG_270, false).transformedWidthPx)
        assertEquals(640, CoordinateTransform(1280, 720, crop, Rotation.DEG_270, false).transformedHeightPx)
    }

    @Test fun rotationPlusMirrorOrderingIsDeterministic() {
        val t = CoordinateTransform(1280, 720, crop, Rotation.DEG_90, true)
        val source = TransformPoint(260f, 170f)
        val tracker = t.toTracker(source)
        assertEquals(120f, tracker.x, 1e-6f)
        assertEquals(160f, tracker.y, 1e-6f)
        val roundTrip = t.fromTracker(tracker)
        assertEquals(source.x, roundTrip.x, 1e-5f)
        assertEquals(source.y, roundTrip.y, 1e-5f)
    }

    @Test fun inverseRoundTrip() {
        val source = TransformPoint(350f, 275f)
        for (rotation in Rotation.entries) {
            for (mirror in listOf(false, true)) {
                val t = CoordinateTransform(1280, 720, crop, rotation, mirror)
                val roundTrip = t.fromTracker(t.toTracker(source))
                assertEquals(source.x, roundTrip.x, 1e-4f)
                assertEquals(source.y, roundTrip.y, 1e-4f)
            }
        }
    }

    @Test fun normalizedBoundariesAndValidation() {
        val t = CoordinateTransform(1280, 720, crop, Rotation.DEG_0, false)
        assertEquals(0f, t.sourceToNormalized(TransformPoint(100f, 50f)).x, 1e-6f)
        assertEquals(1f, t.sourceToNormalized(TransformPoint(740f, 530f)).x, 1e-6f)
        assertEquals(740f, t.normalizedToSource(TransformPoint(1f, 1f)).x, 1e-6f)
        assertThrows(IllegalArgumentException::class.java) { t.normalizedToSource(TransformPoint(1.01f, 0.5f)) }
        assertThrows(IllegalArgumentException::class.java) { CoordinateTransform(1280, 720, CropRect(1200, 0, 100, 100), Rotation.DEG_0, false) }
    }

    @Test fun signaturesAreDeterministicAndContractBound() {
        val a = CoordinateTransform(1280, 720, crop, Rotation.DEG_0, false)
        val b = CoordinateTransform(1280, 720, crop, Rotation.DEG_0, false)
        assertEquals(a.signature, b.signature)
        assertNotEquals(a.signature, CoordinateTransform(1280, 720, crop, Rotation.DEG_90, false).signature)
        assertNotEquals(a.signature, CoordinateTransform(1280, 720, crop, Rotation.DEG_0, true).signature)
        assertNotEquals(a.signature, CoordinateTransform(1920, 1080, crop, Rotation.DEG_0, false).signature)
        assertNotEquals(a.signature, CoordinateTransform(1280, 720, CropRect(99, 50, 640, 480), Rotation.DEG_0, false).signature)
        assertNotEquals(a.signature, CoordinateTransform(1280, 720, CropRect(100, 50, 641, 480), Rotation.DEG_0, false).signature)
    }
}

class TaskInputLeaseTest {
    @Test fun referenceCountingProtectsOwnership() {
        var cleaned = 0
        val lease = TaskInputLease("frame") { cleaned++ }
        val retained = lease.retain()
        assertEquals(2, lease.referenceCount)
        assertEquals("frame", retained.get())
        lease.release()
        assertEquals(1, lease.referenceCount)
        retained.release()
        assertEquals(0, lease.referenceCount)
        assertEquals(1, cleaned)
        assertThrows(IllegalStateException::class.java) { lease.get() }
        assertThrows(IllegalStateException::class.java) { lease.release() }
    }
}
