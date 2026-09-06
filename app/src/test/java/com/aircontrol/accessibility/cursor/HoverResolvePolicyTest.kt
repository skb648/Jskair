package com.aircontrol.accessibility.cursor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rate-limit policy (spec §7/§8): resolves only on meaningful movement, never
 * stationary, never faster than the interval, invalid positions ignored.
 */
class HoverResolvePolicyTest {

    private val threshold = 24f // px
    private val interval = 120L
    private fun policy() = HoverResolvePolicy(threshold, interval)

    @Test
    fun `first position always resolves`() {
        assertTrue(policy().shouldResolve(100f, 100f, 0L))
    }

    @Test
    fun `stationary cursor never rescans`() {
        val p = policy()
        p.markResolved(100f, 100f, 0L)
        // Long after the interval — still no movement, still no scan.
        assertFalse(p.shouldResolve(100f, 100f, 10_000L))
        assertFalse(p.shouldResolve(100.5f, 100.5f, 20_000L)) // sub-threshold jitter
    }

    @Test
    fun `movement below threshold does not resolve`() {
        val p = policy()
        p.markResolved(100f, 100f, 0L)
        assertFalse(p.shouldResolve(100f + threshold - 1f, 100f, 1_000L))
    }

    @Test
    fun `movement above threshold resolves after interval`() {
        val p = policy()
        p.markResolved(100f, 100f, 0L)
        assertFalse(p.shouldResolve(200f, 100f, 10L)) // moved, but too soon
        assertTrue(p.shouldResolve(200f, 100f, 200L)) // moved AND interval elapsed
    }

    @Test
    fun `diagonal movement uses euclidean distance`() {
        val p = policy()
        p.markResolved(100f, 100f, 0L)
        // 16+16 → ~22.6 < 24 → no resolve
        assertFalse(p.shouldResolve(116f, 116f, 500L))
        // 20+20 → ~28.3 ≥ 24 → resolve
        assertTrue(p.shouldResolve(120f, 120f, 500L))
    }

    @Test
    fun `reset forces the next position to resolve`() {
        val p = policy()
        p.markResolved(100f, 100f, 0L)
        p.reset()
        assertTrue(p.shouldResolve(100f, 100f, 0L))
    }

    @Test
    fun `nan positions never resolve`() {
        val p = policy()
        assertFalse(p.shouldResolve(Float.NaN, 100f, 1_000L))
        // A NaN sample must not poison the first-resolve rule for real ones.
        assertFalse(p.shouldResolve(100f, Float.NaN, 1_000L))
        assertTrue(p.shouldResolve(100f, 100f, 1_000L))
    }
}
