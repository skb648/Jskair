package com.aircontrol.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BlinkDetectorProductionTest {
    @Test fun nonFiniteEarCannotCompleteBlink() {
        val detector = BlinkDetector()
        detector.update(0.40f, 1000L)
        detector.update(0.08f, 1050L)
        detector.update(Float.NaN, 1100L)
        assertFalse(detector.isClosed())
        assertEquals(BlinkResult.NONE, detector.update(0.40f, 1200L))
        assertFalse(detector.isClosed())
    }

    @Test fun infiniteEarCannotCompleteBlink() {
        val detector = BlinkDetector()
        detector.update(0.40f, 1000L)
        detector.update(0.08f, 1050L)
        detector.update(Float.POSITIVE_INFINITY, 1100L)
        assertFalse(detector.isClosed())
        assertEquals(BlinkResult.NONE, detector.update(0.40f, 1200L))
    }

    @Test fun resetClearsInProgressBlinkAndBaseline() {
        val detector = BlinkDetector()
        detector.update(0.40f, 1000L)
        detector.update(0.08f, 1100L)
        assert(detector.isClosed())
        detector.reset()
        assertFalse(detector.isClosed())
        assertEquals(BlinkResult.NONE, detector.update(0.17f, 1200L))
    }
}
