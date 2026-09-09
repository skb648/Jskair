package com.aircontrol.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue 15 acceptance: one physical intent → one action; deterministic ownership. */
class InputOwnershipPolicyTest {

    @Test
    fun `first request always owns the slot`() {
        val p = InputOwnershipPolicy(serializationWindowMs = 350L)
        assertTrue(p.tryAcquire("blink", nowMs = 1_000L))
        assertEquals("blink", p.lastSource())
        assertEquals(1_000L, p.lastAcquiredAt())
    }

    @Test
    fun `different modality inside window is refused and counted`() {
        val p = InputOwnershipPolicy(serializationWindowMs = 350L)
        assertTrue(p.tryAcquire("hand_gesture", 1_000L))
        assertFalse(p.tryAcquire("blink", 1_200L))
        assertTrue(p.tryAcquire("dwell", 1_349L))
        assertEquals(2L, p.refusedCount())
        assertEquals("hand_gesture", p.lastSource()) // owner unchanged
    }

    @Test
    fun `different modality after window is allowed and takes ownership`() {
        val p = InputOwnershipPolicy(serializationWindowMs = 350L)
        assertTrue(p.tryAcquire("hand_gesture", 1_000L))
        assertTrue(p.tryAcquire("blink", 1_400L)) // 400 > 350
        assertEquals("blink", p.lastSource())
        assertEquals(0L, p.refusedCount())
    }

    @Test
    fun `same modality may fire again quickly (deliberate repeated click)`() {
        val p = InputOwnershipPolicy(serializationWindowMs = 350L)
        assertTrue(p.tryAcquire("blink", 1_000L))
        assertTrue(p.tryAcquire("blink", 1_050L)) // same source: allowed
        assertEquals(0L, p.refusedCount())
    }

    @Test
    fun `reset clears owner and allows any modality immediately`() {
        val p = InputOwnershipPolicy(serializationWindowMs = 350L)
        assertTrue(p.tryAcquire("hand_gesture", 1_000L))
        assertFalse(p.tryAcquire("blink", 1_100L))
        p.reset()
        assertNull(p.lastSource())
        assertTrue(p.tryAcquire("blink", 1_200L))
        assertEquals("blink", p.lastSource())
    }
}
