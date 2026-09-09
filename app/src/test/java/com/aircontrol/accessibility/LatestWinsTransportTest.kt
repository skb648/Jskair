package com.aircontrol.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Audit P0-3: the cursor is a latest-state channel, not a queue. These tests pin the transport's
 * ordering and coalescing rules — the properties that decide whether the pointer shows where the
 * eyes are *now* or replays where they were. Nothing here sleeps or measures time: a frame is
 * simulated by calling [LatestWinsTransport.consume] directly, because the property under test is
 * "at most one application per frame, newest wins", not "the UI is fast right now".
 */
class LatestWinsTransportTest {

    private class Fixture {
        val schedules = AtomicInteger(0)
        var failNextSchedule = false
        val transport = LatestWinsTransport<String>(schedule = {
            schedules.incrementAndGet()
            if (failNextSchedule) {
                failNextSchedule = false
                throw IllegalStateException("no looper")
            }
        })
    }

    @Test
    fun `the first publish schedules exactly one frame`() {
        val f = Fixture()
        f.transport.publish("a")
        assertEquals(1, f.schedules.get())
        assertEquals("a", f.transport.consume())
        assertNull("nothing else was published", f.transport.consume())
    }

    @Test
    fun `publishes while a frame is pending coalesce into one application of the newest value`() {
        val f = Fixture()
        f.transport.publish("a")
        f.transport.publish("b")
        f.transport.publish("c")
        assertEquals("still one frame request", 1, f.schedules.get())
        assertSame("newest wins", "c", f.transport.consume())
        val stats = f.transport.stats()
        assertEquals(3L, stats.published)
        assertEquals(1L, stats.applied)
        assertEquals(2, stats.coalesced)
        assertFalse(stats.hasPending)
        assertFalse(stats.awaitingFrame)
    }

    @Test
    fun `a frame with nothing new to paint applies nothing`() {
        val f = Fixture()
        f.transport.publish("a")
        assertEquals("a", f.transport.consume())
        // The second callback is the shape of "a frame was already scheduled when the slot went
        // empty": the UI must not touch WindowManager on a callback it cannot attribute to a
        // change — that is the Rule 16 no-op case, and it is what makes a still gaze free.
        assertNull(f.transport.consume())
        assertEquals(1L, f.transport.stats().applied)
    }

    @Test
    fun `reset drops a stale target before the frame callback can paint it`() {
        val f = Fixture()
        f.transport.publish("target-under-face")
        f.transport.reset()
        assertNull("tracking was lost; the pending position must not be painted", f.transport.consume())
        assertEquals(1, f.schedules.get())
    }

    @Test
    fun `reset keeps the transport usable for the next sample`() {
        val f = Fixture()
        f.transport.publish("a")
        f.transport.reset()
        f.transport.consume()
        f.transport.publish("b")
        assertEquals("the scheduler must be re-armable after a reset", 2, f.schedules.get())
        assertEquals("b", f.transport.consume())
    }

    @Test
    fun `a failed schedule does not wedge the transport`() {
        val f = Fixture()
        f.failNextSchedule = true
        try {
            f.transport.publish("a")
            org.junit.Assert.fail("the exception must reach the caller")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("looper"))
        }
        // The frame flag was rolled back, so the next sample gets another chance rather than
        // sitting in a slot nobody will ever come to take.
        f.transport.publish("b")
        assertEquals(2, f.schedules.get())
        assertEquals("b", f.transport.consume())
    }

    @Test
    fun `published equals applied plus coalesced plus whatever is still pending`() {
        val f = Fixture()
        f.transport.publish("a")
        f.transport.publish("b")
        var stats = f.transport.stats()
        assertEquals(stats.published, stats.applied + stats.coalesced + 1)
        f.transport.consume()
        stats = f.transport.stats()
        assertEquals(stats.published, stats.applied + stats.coalesced)
        assertTrue("overwrites must be visible in the counters", stats.coalesced > 0)
    }

    @Test
    fun `a concurrent producer never makes the consumer see an older target`() {
        val f = Fixture()
        val seen = java.util.Collections.synchronizedList(mutableListOf<Int>())
        var consumed = true
        val last = AtomicInteger(0)
        val producer = Thread {
            for (i in 1..5_000) {
                last.set(i)
                f.transport.publish("v$i")
            }
        }
        val consumer = Thread {
            while (consumed || f.transport.stats().hasPending) {
                f.transport.consume()?.let { value ->
                    seen += value.substring(1).toInt()
                    // Simulate the paint cost the real overlay pays, so publishes pile up.
                    var spin = 0
                    while (spin < 200) spin++
                    f.transport.consume()
                    if (seen.size % 500 == 0) {
                        f.schedules.incrementAndGet()
                    }
                }
            }
        }
        producer.start(); consumer.start()
        producer.join(); consumed = false; consumer.join()

        assertTrue("the consumer must have seen a subset of the targets", seen.isNotEmpty())
        // Never a duplicate, never a regression: each applied value must be strictly newer than the
        // previous one, because a queue would replay older values after newer ones.
        for (i in 1 until seen.size) {
            assertTrue("frame $i replayed an older target (${seen[i - 1]} -> ${seen[i]})", seen[i] > seen[i - 1])
        }
        val stats = f.transport.stats()
        assertEquals(5_000L, stats.published)
        assertTrue("every applied value came from a publish", stats.applied <= stats.published)
        assertTrue("the consumer must have seen values", stats.applied > 0)
    }
}
