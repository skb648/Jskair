package com.aircontrol.runtime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Perf audit P9: deterministic memory-pressure and power-save policy.
 * Notably ABSENT by design: any battery-LEVEL input (a low battery alone
 * must never throttle) and any storage/disk concept (low storage ≠ low RAM).
 */
class ResourceGovernorTest {

    private val governor = ResourceGovernor()

    @Test
    fun `power-save off means no cap`() {
        assertEquals(Int.MAX_VALUE, governor.fpsCapFor(isPowerSaveMode = false))
    }

    @Test
    fun `power-save on caps FPS below the full-speed default`() {
        val cap = governor.fpsCapFor(isPowerSaveMode = true)
        assertEquals(ResourceGovernor.FPS_POWER_SAVE_CAP, cap)
        assertTrue(cap < 24) // below the app's default 24 fps
    }

    @Test
    fun `running-critical trim classifies as CRITICAL`() {
        assertEquals(
            ResourceGovernor.MemoryLevel.CRITICAL,
            governor.classifyTrim(ResourceGovernor.TRIM_RUNNING_CRITICAL),
        )
    }

    @Test
    fun `running-low trim classifies as MODERATE`() {
        assertEquals(
            ResourceGovernor.MemoryLevel.MODERATE,
            governor.classifyTrim(ResourceGovernor.TRIM_RUNNING_LOW),
        )
    }

    @Test
    fun `running-moderate trim classifies as MODERATE`() {
        assertEquals(
            ResourceGovernor.MemoryLevel.MODERATE,
            governor.classifyTrim(ResourceGovernor.TRIM_RUNNING_MODERATE),
        )
    }

    @Test
    fun `background trims classify at least MODERATE, complete as CRITICAL`() {
        // UI_HIDDEN=20 must NOT outrank RUNNING_CRITICAL=15 numerically —
        // the bands are mapped explicitly, not compared.
        assertEquals(
            ResourceGovernor.MemoryLevel.MODERATE,
            governor.classifyTrim(ResourceGovernor.TRIM_UI_HIDDEN),
        )
        assertEquals(
            ResourceGovernor.MemoryLevel.MODERATE,
            governor.classifyTrim(ResourceGovernor.TRIM_BACKGROUND),
        )
        assertEquals(
            ResourceGovernor.MemoryLevel.MODERATE,
            governor.classifyTrim(ResourceGovernor.TRIM_BACKGROUND_MODERATE),
        )
        assertEquals(
            ResourceGovernor.MemoryLevel.CRITICAL,
            governor.classifyTrim(ResourceGovernor.TRIM_BACKGROUND_COMPLETE),
        )
    }

    @Test
    fun `nominal trim classifies as NORMAL`() {
        assertEquals(ResourceGovernor.MemoryLevel.NORMAL, governor.classifyTrim(0))
    }

    @Test
    fun `idle trackers are released under MODERATE or CRITICAL pressure`() {
        assertTrue(governor.shouldReleaseIdleTrackers(ResourceGovernor.MemoryLevel.MODERATE))
        assertTrue(governor.shouldReleaseIdleTrackers(ResourceGovernor.MemoryLevel.CRITICAL))
        assertFalse(governor.shouldReleaseIdleTrackers(ResourceGovernor.MemoryLevel.NORMAL))
    }

    @Test
    fun `governor has no battery-level input (low battery alone never throttles)`() {
        // Design constraint: a low battery says nothing about CPU throttling,
        // so the only battery-related signal this policy accepts is the OS
        // power-save MODE. The API surface is the proof: fpsCapFor takes
        // exactly one parameter (the power-save boolean) — there is no
        // battery-level input anywhere in this class. JDK reflection only:
        // kotlin-reflect is not on this module's classpath.
        val methods = ResourceGovernor::class.java.declaredMethods.filter { it.name == "fpsCapFor" }
        assertEquals(1, methods.size)
        assertArrayEquals(
            arrayOf<Class<*>>(Boolean::class.javaPrimitiveType!!),
            methods.single().parameterTypes,
        )
        assertEquals(Int.MAX_VALUE, governor.fpsCapFor(isPowerSaveMode = false))
    }

    @Test
    fun `no storage or disk concept exists in the trim mapping`() {
        // The trim constants mirror ComponentCallbacks2 memory levels only.
        // Unknown levels (e.g. hypothetical storage signals) map to NORMAL —
        // storage pressure is never conflated with memory pressure.
        assertEquals(ResourceGovernor.MemoryLevel.NORMAL, governor.classifyTrim(-1))
        assertEquals(ResourceGovernor.MemoryLevel.NORMAL, governor.classifyTrim(999))
    }
}
