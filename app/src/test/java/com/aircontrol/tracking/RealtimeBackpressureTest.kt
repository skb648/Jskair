package com.aircontrol.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capacity rules that keep a real-time tracking pipeline from turning latency into a backlog
 * (recovery round, root causes P0-1 and P0-2).
 *
 * These are behavioural tests about *ordering and counts*, driven by injected timestamps, because
 * the property that matters — "one job in flight per channel", "at most N buffers alive" — is a
 * property of the protocol, not of any particular speed. A test that slept on a real clock would
 * only prove the machine was fast at the moment it ran.
 */
class RealtimeBackpressureTest {

    // ---------------------------------------------------------------- InFlightGate

    @Test
    fun `a channel with an outstanding submission refuses the next frame`() {
        val gate = InFlightGate()
        assertTrue(gate.tryReserve(nowMs = 1_000L))
        assertFalse(
            "the graph is still busy, so the new frame must be dropped",
            gate.tryReserve(nowMs = 1_040L),
        )
        val stats = gate.stats()
        assertEquals(1L, stats.submitted)
        assertEquals(1L, stats.refused)
        assertEquals(0.5f, stats.refusalRate, 1e-6f)
    }

    @Test
    fun `the slot is available again as soon as the result is released`() {
        val gate = InFlightGate()
        assertTrue(gate.tryReserve(1_000L))
        gate.release()
        assertTrue(gate.tryReserve(1_040L))
        assertEquals("no expiry was needed", 0L, gate.stats().expired)
    }

    /** A late callback must not be reclaimed by time: close/reinitialize is the safe owner action. */
    @Test
    fun `a stalled submission remains reserved until the graph releases it`() {
        val gate = InFlightGate(timeoutMs = 1_000L)
        assertTrue(gate.tryReserve(1_000L))
        assertTrue(gate.isBusy(1_999L))
        assertTrue(gate.isStalled(2_000L))
        assertFalse(gate.tryReserve(2_001L))
        assertEquals(1L, gate.stats().expired)
        gate.release()
        assertTrue(gate.tryReserve(2_002L))
    }

    @Test
    fun `reset clears the reservation without counting a submission`() {
        val gate = InFlightGate()
        assertTrue(gate.tryReserve(10L))
        gate.reset()
        assertFalse(gate.isBusy(11L))
        assertEquals(0L, gate.stats().refused)
        assertTrue(gate.tryReserve(12L))
        assertEquals(2L, gate.stats().submitted)
    }

    @Test
    fun `a channel that keeps up reports no refusal at all`() {
        val gate = InFlightGate()
        repeat(50) { i ->
            assertTrue("frame $i", gate.tryReserve(i * 40L))
            gate.release()
        }
        assertEquals(0f, gate.stats().refusalRate, 1e-6f)
        assertEquals(50L, gate.stats().submitted)
    }

    // -------------------------------------------------------------------- SlotPool

    private class Buffer(val id: Int)

    /** A pool whose buffers are counted, so "allocated once per slot" is asserted, not hoped for. */
    private inner class Fixture(capacity: Int, private val failCreate: Boolean = false) {
        val created = mutableListOf<Int>()
        val destroyed = mutableListOf<Int>()
        val pool = SlotPool(
            capacity = capacity,
            create = {
                if (failCreate) null else Buffer(created.size).also { created += created.size }
            },
            destroy = { destroyed += it.id },
        )
    }

    @Test
    fun `the pool never hands out more buffers than its capacity`() {
        val f = Fixture(capacity = 2)
        val a = f.pool.acquire()
        val b = f.pool.acquire()
        assertNotNull(a)
        assertNotNull(b)
        assertNull("the third concurrent frame must be refused", f.pool.acquire())
        assertEquals(2, f.created.size)
        val stats = f.pool.stats()
        assertEquals(2, stats.busy)
        assertEquals(0, stats.freeCount)
        assertEquals(1L, stats.refused)
        assertEquals(2, stats.created)
    }

    @Test
    fun `a released buffer is reused rather than reallocated`() {
        val f = Fixture(capacity = 2)
        val a = checkNotNull(f.pool.acquire())
        assertTrue(f.pool.release(a))
        assertSame("steady state allocates per slot, not per frame", a, f.pool.acquire())
        assertEquals(1, f.created.size)
        assertEquals(0, f.pool.stats().freeCount)
    }

    @Test
    fun `a foreign or double release is refused and counted instead of corrupting the free set`() {
        val f = Fixture(capacity = 1)
        val a = checkNotNull(f.pool.acquire())
        assertFalse(f.pool.release(Buffer(99)))
        assertTrue(f.pool.release(a))
        assertFalse("a second release of the same buffer must not create two free slots", f.pool.release(a))
        assertEquals(2L, f.pool.stats().foreignReleases)
        assertEquals(1, f.pool.stats().created)
        assertNotNull(f.pool.acquire())
    }

    @Test
    fun `discarding a buffer destroys it and lets a replacement be created`() {
        val f = Fixture(capacity = 1)
        val a = checkNotNull(f.pool.acquire())
        assertTrue(f.pool.discard(a))
        assertEquals(listOf(0), f.destroyed)
        val b = checkNotNull(f.pool.acquire())
        assertNotSame(a, b)
        assertEquals(2, f.created.size)
        f.pool.drain()
        assertEquals("the active replacement is retired but still owned", listOf(0), f.destroyed)
        assertEquals(1, f.pool.stats().retiredCount)
        assertTrue(f.pool.release(b))
        assertEquals(listOf(0, 1), f.destroyed)
        assertEquals(0, f.pool.stats().created)
    }

    @Test
    fun `a create failure is a refusal and leaves the pool usable`() {
        val f = Fixture(capacity = 1, failCreate = true)
        assertNull(f.pool.acquire())
        assertEquals(1L, f.pool.stats().refused)
        assertEquals(0, f.pool.stats().created)
    }

    @Test
    fun `draining retires leases and releases capacity only after consumers finish`() {
        val f = Fixture(capacity = 3)
        val held = List(3) { checkNotNull(f.pool.acquire()) }
        assertEquals(3, f.pool.stats().busy)
        assertNull(f.pool.acquire())
        f.pool.drain()
        assertEquals(3, f.pool.stats().busy)
        assertEquals(3, f.pool.stats().retiredCount)
        held.forEach { assertTrue(f.pool.release(it)) }
        assertEquals(0, f.pool.stats().busy)
        assertNotNull(f.pool.acquire())
        assertEquals(4, f.created.size)
    }

    @Test
    fun `release of a buffer the pool never handed out does not free a slot`() {
        val f = Fixture(capacity = 1)
        val held = checkNotNull(f.pool.acquire())
        assertFalse(f.pool.release(Buffer(-5)))
        assertEquals(1, f.pool.stats().busy)
        assertTrue(f.pool.release(held))
        assertEquals(0, f.pool.stats().busy)
    }
    // ------------------------------------------- the composition the service relies on

    @Test
    fun `the two-channel refcount returns a leased buffer exactly once per frame`() {
        // One buffer, two channels, one shared hook: each channel fires the hook exactly once —
        // immediately when it refuses the frame, otherwise on delivery. The slot must come back
        // once, and only after every reader has reported.
        var next = 0
        val pool = SlotPool<Buffer>(capacity = 4, create = { Buffer(next++) }, destroy = { })
        val first = checkNotNull(pool.acquire())
        var released = 0
        val remaining = java.util.concurrent.atomic.AtomicInteger(2)
        val onConsumed: () -> Unit = {
            if (remaining.decrementAndGet() <= 0) {
                released++
                pool.release(first)
            }
        }
        onConsumed()
        assertEquals("the hand channel refused, but the face channel still owns the buffer", 0, released)
        assertEquals(1, pool.stats().busy)
        onConsumed()
        assertEquals("the last reader returning must release exactly once", 1, released)
        assertEquals(0, pool.stats().busy)

        // Both channels refuse: still one release, never two — a double release would let a second
        // frame lease the same pixels while the first frame's hook is still outstanding.
        val second = checkNotNull(pool.acquire())
        var released2 = 0
        val remaining2 = java.util.concurrent.atomic.AtomicInteger(2)
        val hook2: () -> Unit = {
            if (remaining2.decrementAndGet() <= 0) {
                released2++
                pool.release(second)
            }
        }
        hook2()
        hook2()
        assertEquals(1, released2)

        // Eye tracking off: a single-channel frame returns on its one report.
        val third = checkNotNull(pool.acquire())
        var released3 = 0
        val remaining3 = java.util.concurrent.atomic.AtomicInteger(1)
        val hook3: () -> Unit = {
            if (remaining3.decrementAndGet() <= 0) {
                released3++
                pool.release(third)
            }
        }
        hook3()
        assertEquals(1, released3)

        // LIFO reuse: a released buffer is handed straight back out, so the steady state allocates
        // nothing per frame — `next` counts creations, and it stayed at one because every lease was
        // returned before the next frame arrived.
        assertSame(third, pool.acquire())
        assertEquals("the pool must reuse buffers, not re-create them", 1, next)
    }
}
