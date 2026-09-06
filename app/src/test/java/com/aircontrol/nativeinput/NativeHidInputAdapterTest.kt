package com.aircontrol.nativeinput

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1 POC tests: hand-frame deltas → relative HID counts, with remainder
 * carry and re-prime on hand loss. Pure JVM — the Bluetooth transport is
 * faked, so no device is needed for the math contract.
 */
class NativeHidInputAdapterTest {

    private class RecordingInput : NativeMouseInput {
        var sent = 0
        var lastDx = 0
        var lastDy = 0
        var connected = true
        override fun move(dx: Int, dy: Int): Boolean {
            if (!connected) return false
            sent++
            lastDx = dx
            lastDy = dy
            return true
        }
    }

    @Test
    fun `movement produces scaled relative reports`() {
        val input = RecordingInput()
        val adapter = NativeHidInputAdapter(input)

        // Prime on the first frame — nothing sent yet.
        adapter.onHandFrame(syntheticHandFrame(0.40f, 0.50f, ts = 1_000L))
        assertEquals(0, input.sent)

        // 0.05 normalized right = 0.05 * 1600 = 80 counts.
        adapter.onHandFrame(syntheticHandFrame(0.45f, 0.50f, ts = 1_050L))
        assertEquals(1, input.sent)
        assertEquals(80, input.lastDx)
        assertEquals(0, input.lastDy)
    }

    @Test
    fun `jitter below the dead zone sends nothing`() {
        val input = RecordingInput()
        val adapter = NativeHidInputAdapter(input)
        adapter.onHandFrame(syntheticHandFrame(0.40f, 0.50f, ts = 1_000L))
        adapter.onHandFrame(syntheticHandFrame(0.4005f, 0.4998f, ts = 1_050L))
        assertEquals(0, input.sent)
    }

    @Test
    fun `fractional counts floor and keep the remainder`() {
        val input = RecordingInput()
        val adapter = NativeHidInputAdapter(input)
        adapter.onHandFrame(syntheticHandFrame(0.40f, 0.50f, ts = 1_000L))
        // 0.0015 normalized (just above the dead zone) × 1600 = 2.4 counts:
        // the report carries whole counts (2), and the 0.4 remainder rides on.
        adapter.onHandFrame(syntheticHandFrame(0.4015f, 0.50f, ts = 1_050L))
        assertEquals(1, input.sent)
        assertEquals(2, input.lastDx)
        // 0.4 remainder + 2.4 new = 2.8 → floors to 2 again, not 3.
        adapter.onHandFrame(syntheticHandFrame(0.403f, 0.50f, ts = 1_100L))
        assertEquals(2, input.sent)
        assertEquals(2, input.lastDx)
    }

    @Test
    fun `hand loss re-primes the anchor instead of flinging`() {
        val input = RecordingInput()
        val adapter = NativeHidInputAdapter(input)
        adapter.onHandFrame(syntheticHandFrame(0.40f, 0.50f, ts = 1_000L))
        // Hand lost, then reappears far away: the jump must NOT become a report.
        adapter.onHandFrame(
            com.aircontrol.tracking.HandFrame(
                landmarks = emptyList(),
                handedness = com.aircontrol.tracking.Handedness.RIGHT,
                timestampMs = 2_000L,
                confidence = 0f,
            ),
        )
        adapter.onHandFrame(syntheticHandFrame(0.80f, 0.20f, ts = 3_000L))
        assertEquals(0, input.sent)
    }

    @Test
    fun `movement report respects the int8 hid range`() {
        val input = RecordingInput()
        val adapter = NativeHidInputAdapter(input)
        adapter.onHandFrame(syntheticHandFrame(0.10f, 0.10f, ts = 1_000L))
        // A gigantic single-frame jump (>127 counts) must clamp, not overflow.
        adapter.onHandFrame(syntheticHandFrame(0.95f, 0.95f, ts = 1_050L))
        assertEquals(1, input.sent)
        assertEquals(127, input.lastDx)
        assertEquals(127, input.lastDy)
    }
}
