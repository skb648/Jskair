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
        adapter.onHandFrame(syntheticHandFrame(0.5f, 0.5f, timestampMs = 1_000L))
        assertEquals(0, input.sent)

        // 1/16 right (exact in float) = 0.0625 * 1600 = 100 counts.
        adapter.onHandFrame(syntheticHandFrame(0.5625f, 0.5f, timestampMs = 1_050L))
        assertEquals(1, input.sent)
        assertEquals(100, input.lastDx)
        assertEquals(0, input.lastDy)
    }

    @Test
    fun `jitter below the dead zone sends nothing`() {
        val input = RecordingInput()
        val adapter = NativeHidInputAdapter(input)
        adapter.onHandFrame(syntheticHandFrame(0.40f, 0.50f, timestampMs = 1_000L))
        adapter.onHandFrame(syntheticHandFrame(0.4005f, 0.4998f, timestampMs = 1_050L))
        assertEquals(0, input.sent)
    }

    @Test
    fun `fractional counts floor and keep the remainder`() {
        val input = RecordingInput()
        val adapter = NativeHidInputAdapter(input)
        adapter.onHandFrame(syntheticHandFrame(0.5f, 0.5f, timestampMs = 1_000L))
        // 1/256 (exact in float) above the dead zone × 1600 = 6.25 counts:
        // the report carries whole counts (6) and the 0.25 remainder rides on.
        var x = 0.5f
        repeat(3) { i ->
            x += 0.00390625f
            adapter.onHandFrame(syntheticHandFrame(x, 0.5f, timestampMs = 1_050L + i * 50L))
        }
        assertEquals(3, input.sent)
        assertEquals(6, input.lastDx) // each of the first 3 steps floors to 6 (residual grows 0.25 → 0.5 → 0.75)
        // Fourth step: 0.75 carried + 6.25 new = 7.0 → the carry finally tips a whole count.
        x += 0.00390625f
        adapter.onHandFrame(syntheticHandFrame(x, 0.5f, timestampMs = 1_200L))
        assertEquals(7, input.lastDx)
    }

    @Test
    fun `hand loss re-primes the anchor instead of flinging`() {
        val input = RecordingInput()
        val adapter = NativeHidInputAdapter(input)
        adapter.onHandFrame(syntheticHandFrame(0.40f, 0.50f, timestampMs = 1_000L))
        // Hand lost, then reappears far away: the jump must NOT become a report.
        adapter.onHandFrame(
            com.aircontrol.tracking.HandFrame(
                landmarks = emptyList(),
                handedness = com.aircontrol.tracking.Handedness.RIGHT,
                timestampMs = 2_000L,
                confidence = 0f,
            ),
        )
        adapter.onHandFrame(syntheticHandFrame(0.80f, 0.20f, timestampMs = 3_000L))
        assertEquals(0, input.sent)
    }

    @Test
    fun `movement report respects the int8 hid range`() {
        val input = RecordingInput()
        val adapter = NativeHidInputAdapter(input)
        adapter.onHandFrame(syntheticHandFrame(0.10f, 0.10f, timestampMs = 1_000L))
        // A gigantic single-frame jump (>127 counts) must clamp, not overflow.
        adapter.onHandFrame(syntheticHandFrame(0.95f, 0.95f, timestampMs = 1_050L))
        assertEquals(1, input.sent)
        assertEquals(127, input.lastDx)
        assertEquals(127, input.lastDy)
    }

    @Test
    fun `nan or infinite landmarks never poison the adapter`() {
        val input = RecordingInput()
        val adapter = NativeHidInputAdapter(input)
        adapter.onHandFrame(syntheticHandFrame(0.5f, 0.5f, timestampMs = 1_000L))
        // Tracker glitch: NaN/∞ landmarks must not reach the HID report and
        // must not corrupt the carry state…
        adapter.onHandFrame(syntheticHandFrame(Float.NaN, Float.POSITIVE_INFINITY, timestampMs = 1_050L))
        assertEquals(0, input.sent)
        // …so the very next clean movement still works: the NaN frame re-primed
        // the anchor, this frame primes again —
        adapter.onHandFrame(syntheticHandFrame(0.5625f, 0.5f, timestampMs = 1_100L))
        assertEquals(0, input.sent)
        // — and this real movement delivers exact counts.
        adapter.onHandFrame(syntheticHandFrame(0.625f, 0.5f, timestampMs = 1_150L))
        assertEquals(1, input.sent)
        assertEquals(100, input.lastDx) // 1/16 exact step × GAIN 1600
        assertEquals(0, input.lastDy)
    }
}
