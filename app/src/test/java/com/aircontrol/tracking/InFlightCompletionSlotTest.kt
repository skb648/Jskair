package com.aircontrol.tracking

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class InFlightCompletionSlotTest {

    @Test
    fun `late old timestamp cannot consume current completion`() {
        val calls = AtomicInteger(0)
        val slot = InFlightCompletionSlot()

        slot.replace(
            InFlightCompletionSlot.Pending(
                reservationToken = 1L,
                mediaPipeTimestampMs = 100L,
                onConsumed = { calls.incrementAndGet() },
            ),
        )
        slot.replace(
            InFlightCompletionSlot.Pending(
                reservationToken = 2L,
                mediaPipeTimestampMs = 200L,
                onConsumed = { calls.incrementAndGet() },
            ),
        )

        assertNull(slot.takeForTimestamp(100L))
        val current = slot.takeForTimestamp(200L)
        assertNotNull(current)
        current?.onConsumed?.invoke()

        assertTrue(calls.get() == 1)
    }

    @Test
    fun `cleared completion ignores late callback`() {
        val calls = AtomicInteger(0)
        val slot = InFlightCompletionSlot()
        slot.replace(
            InFlightCompletionSlot.Pending(
                reservationToken = 7L,
                mediaPipeTimestampMs = 700L,
                onConsumed = { calls.incrementAndGet() },
            ),
        )

        val cleared = slot.clear()
        assertNotNull(cleared)
        cleared?.onConsumed?.invoke()

        assertNull(slot.takeForTimestamp(700L))
        assertTrue(calls.get() == 1)
    }

    @Test
    fun `tokenized gate rejects stale release after reset and new reserve`() {
        val gate = InFlightGate()

        val oldToken = gate.tryReserve(1_000L)
        assertNotNull(oldToken)
        gate.reset()

        val newToken = gate.tryReserve(2_000L)
        assertNotNull(newToken)

        assertFalse(gate.release(oldToken!!))
        assertTrue(gate.isBusy(2_100L))
        assertTrue(gate.release(newToken!!))
        assertFalse(gate.isBusy(2_200L))
    }
}
