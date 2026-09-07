package com.aircontrol.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Perf audit P5: raw thermal samples used to map 1:1 to FPS tiers, producing
 * 30↔10 FPS oscillation at threshold boundaries. These tests pin the
 * hysteresis: 2 consecutive samples to worsen, CRITICAL immediate, 3 samples
 * + 30 s cooldown to relax, and transitions-only returns.
 */
class ThermalGovernorTest {

    private val governor = ThermalGovernor()

    @Test
    fun `fresh governor applies NONE`() {
        assertEquals(ThermalStatus.NONE, governor.current())
    }

    @Test
    fun `a single worsening sample does not apply`() {
        assertNull(governor.onSample(ThermalStatus.SEVERE, nowMs = 0L))
        assertEquals(ThermalStatus.NONE, governor.current())
    }

    @Test
    fun `two consecutive worsening samples apply the transition`() {
        assertNull(governor.onSample(ThermalStatus.SEVERE, nowMs = 0L))
        assertEquals(ThermalStatus.SEVERE, governor.onSample(ThermalStatus.SEVERE, nowMs = 5_000L))
        assertEquals(ThermalStatus.SEVERE, governor.current())
    }

    @Test
    fun `an interrupted confirmation run restarts the count`() {
        assertNull(governor.onSample(ThermalStatus.SEVERE, nowMs = 0L))
        assertNull(governor.onSample(ThermalStatus.LIGHT, nowMs = 5_000L))
        // SEVERE streak broken: this is sample 1 of a new run, not sample 2.
        assertNull(governor.onSample(ThermalStatus.SEVERE, nowMs = 10_000L))
        assertEquals(ThermalStatus.NONE, governor.current())
        assertEquals(ThermalStatus.SEVERE, governor.onSample(ThermalStatus.SEVERE, nowMs = 15_000L))
    }

    @Test
    fun `CRITICAL applies immediately on the first sample`() {
        assertEquals(ThermalStatus.CRITICAL, governor.onSample(ThermalStatus.CRITICAL, nowMs = 0L))
        assertEquals(ThermalStatus.CRITICAL, governor.current())
    }

    @Test
    fun `steady samples never emit a transition`() {
        governor.onSample(ThermalStatus.SEVERE, nowMs = 0L)
        governor.onSample(ThermalStatus.SEVERE, nowMs = 5_000L)
        repeat(10) { i ->
            assertNull(governor.onSample(ThermalStatus.SEVERE, nowMs = 10_000L + 5_000L * i))
        }
        assertEquals(ThermalStatus.SEVERE, governor.current())
    }

    @Test
    fun `relaxing needs three consecutive lower samples`() {
        governor.onSample(ThermalStatus.SEVERE, nowMs = 0L)
        governor.onSample(ThermalStatus.SEVERE, nowMs = 5_000L)
        // Cooldown already satisfied (lastWorsen at t=5000, relax at t≥35000).
        assertNull(governor.onSample(ThermalStatus.MODERATE, nowMs = 35_000L))
        assertNull(governor.onSample(ThermalStatus.MODERATE, nowMs = 40_000L))
        assertEquals(
            ThermalStatus.MODERATE,
            governor.onSample(ThermalStatus.MODERATE, nowMs = 45_000L),
        )
    }

    @Test
    fun `relaxing is blocked during the cooldown after a worsening`() {
        governor.onSample(ThermalStatus.SEVERE, nowMs = 0L)
        val applied = governor.onSample(ThermalStatus.SEVERE, nowMs = 5_000L)
        assertEquals(ThermalStatus.SEVERE, applied)
        // Three lower samples, but still inside the 30 s cooldown: hold SEVERE.
        repeat(3) { i ->
            assertNull(governor.onSample(ThermalStatus.MODERATE, nowMs = 10_000L + 5_000L * i))
        }
        assertEquals(ThermalStatus.SEVERE, governor.current())
        // After the cooldown the very next confirmed run relaxes.
        assertNull(governor.onSample(ThermalStatus.MODERATE, nowMs = 36_000L))
        assertNull(governor.onSample(ThermalStatus.MODERATE, nowMs = 41_000L))
        assertEquals(
            ThermalStatus.MODERATE,
            governor.onSample(ThermalStatus.MODERATE, nowMs = 46_000L),
        )
    }

    @Test
    fun `a worsening while cooling down still applies after confirmation`() {
        governor.onSample(ThermalStatus.LIGHT, nowMs = 0L)
        governor.onSample(ThermalStatus.LIGHT, nowMs = 5_000L)
        assertEquals(ThermalStatus.LIGHT, governor.current())
        assertNull(governor.onSample(ThermalStatus.SEVERE, nowMs = 10_000L))
        assertEquals(ThermalStatus.SEVERE, governor.onSample(ThermalStatus.SEVERE, nowMs = 15_000L))
    }

    @Test
    fun `reset clears applied status and pending confirmation`() {
        governor.onSample(ThermalStatus.SEVERE, nowMs = 0L)
        governor.reset()
        assertEquals(ThermalStatus.NONE, governor.current())
        // The pre-reset sample must not count toward a new confirmation run.
        assertNull(governor.onSample(ThermalStatus.SEVERE, nowMs = 5_000L))
        assertEquals(ThermalStatus.NONE, governor.current())
    }

    @Test
    fun `multi-tier jumps apply in one transition`() {
        // NONE → SEVERE directly (the logcat's instant NONE→SEVERE case):
        // debounced over two samples, reported as a single transition.
        assertNull(governor.onSample(ThermalStatus.SEVERE, nowMs = 0L))
        assertEquals(ThermalStatus.SEVERE, governor.onSample(ThermalStatus.SEVERE, nowMs = 5_000L))
    }
}
