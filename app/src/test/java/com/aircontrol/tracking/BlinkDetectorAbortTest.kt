package com.aircontrol.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue 4 acceptance: face-loss aborts an in-progress blink; no stale click after reacquisition. */
class BlinkDetectorAbortTest {

    private fun detector() = BlinkDetector(
        earThreshold = 0.22f,
        minBlinkMs = 250L,
        maxBlinkMs = 750L,
    )

    @Test
    fun `closure that never reopens never clicks`() {
        val d = detector()
        assertEquals(BlinkResult.NONE, d.update(0.10f, 0L)) // closed
        assertTrue(d.hasInProgressBlink)
        assertTrue(d.isClosed())
        assertEquals(BlinkResult.NONE, d.update(0.10f, 400L)) // still closed
        assertEquals(0, d.abortedBlinkCount())
    }

    @Test
    fun `abort during closure prevents a stale click after reacquisition`() {
        val d = detector()
        d.update(0.10f, 0L) // closure starts
        assertTrue(d.hasInProgressBlink)

        // Face is lost while the eyes are closed → abort (VALID→LOST transition).
        d.abortInProgressBlink()
        assertFalse(d.hasInProgressBlink)
        assertEquals(1, d.abortedBlinkCount())

        // Face returns: the same physical blink reopening must NOT click.
        assertEquals(BlinkResult.NONE, d.update(0.30f, 250L))
        assertEquals(1, d.abortedBlinkCount())

        // A fresh deliberate blink afterwards still works.
        d.update(0.10f, 300L)
        assertEquals(BlinkResult.NONE, d.update(0.10f, 600L))
        assertEquals(BlinkResult.CLICK, d.update(0.30f, 650L))
    }

    @Test
    fun `abort is a no-op when no blink is in progress`() {
        val d = detector()
        d.update(0.30f, 0L) // eyes open
        assertFalse(d.hasInProgressBlink)
        d.abortInProgressBlink() // transient uncertain state: must not disturb anything
        assertEquals(0, d.abortedBlinkCount())
        // A blink that starts afterwards still detects normally.
        d.update(0.10f, 100L)
        assertEquals(BlinkResult.NONE, d.update(0.10f, 400L))
        assertEquals(BlinkResult.CLICK, d.update(0.30f, 450L))
        assertEquals(0, d.abortedBlinkCount())
    }

    @Test
    fun `reset still clears an in-progress blink without counting an abort`() {
        val d = detector()
        d.update(0.10f, 0L)
        d.reset()
        assertEquals(0, d.abortedBlinkCount())
        assertFalse(d.hasInProgressBlink)
    }
}
