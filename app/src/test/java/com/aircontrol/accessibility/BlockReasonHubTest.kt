package com.aircontrol.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue 10 acceptance: change-only, throttled reason surfacing (never per-frame). */
class BlockReasonHubTest {

    @Test
    fun `first record surfaces and notifies`() {
        val hub = BlockReasonHub(minRepeatMs = 1_500L)
        val seen = mutableListOf<BlockReason?>()
        hub.onChange = { seen.add(it) }
        assertTrue(hub.record(BlockReason.FACE_LOST, atMs = 1_000L))
        assertEquals(BlockReason.FACE_LOST, hub.currentReason())
        assertEquals(listOf(BlockReason.FACE_LOST), seen)
    }

    @Test
    fun `same reason within quiet gap is suppressed`() {
        val hub = BlockReasonHub(minRepeatMs = 1_500L)
        hub.record(BlockReason.FACE_LOST, atMs = 1_000L)
        assertFalse(hub.record(BlockReason.FACE_LOST, atMs = 1_100L))
        assertFalse(hub.record(BlockReason.FACE_LOST, atMs = 1_500L)) // 500 < 1500
        assertEquals(BlockReason.FACE_LOST, hub.currentReason())
        assertEquals(3, hub.occurrences(BlockReason.FACE_LOST))
    }

    @Test
    fun `same reason after quiet gap resurfaces`() {
        val hub = BlockReasonHub(minRepeatMs = 1_500L)
        hub.record(BlockReason.FACE_LOST, atMs = 1_000L)
        assertTrue(hub.record(BlockReason.FACE_LOST, atMs = 1_000L + 1_500L + 1L))
    }

    @Test
    fun `different reason changes immediately`() {
        val hub = BlockReasonHub(minRepeatMs = 1_500L)
        hub.record(BlockReason.FACE_LOST, 1_000L)
        assertTrue(hub.record(BlockReason.ANOTHER_MODALITY_ACTED, 1_010L))
        assertEquals(BlockReason.ANOTHER_MODALITY_ACTED, hub.currentReason())
    }

    @Test
    fun `clearIfCurrent only clears the given current reason`() {
        val hub = BlockReasonHub(minRepeatMs = 1_500L)
        hub.record(BlockReason.FACE_LOST, 1_000L)
        assertFalse(hub.clearIfCurrent(BlockReason.EYE_NOT_STABLE, 1_010L))
        assertTrue(hub.clearIfCurrent(BlockReason.FACE_LOST, 1_010L))
        assertNull(hub.currentReason())
        // A second clear is a no-op.
        assertFalse(hub.clearIfCurrent(BlockReason.FACE_LOST, 1_020L))
    }

    @Test
    fun `clearAll notifies with null`() {
        val hub = BlockReasonHub(minRepeatMs = 1_500L)
        val seen = mutableListOf<BlockReason?>()
        hub.onChange = { seen.add(it) }
        hub.record(BlockReason.BLINK_TOO_SHORT, 1_000L)
        assertTrue(hub.clearAll(1_010L))
        assertNull(hub.currentReason())
        assertEquals(listOf<BlockReason?>(BlockReason.BLINK_TOO_SHORT, null), seen)
    }

    @Test
    fun `counts accumulate per reason`() {
        val hub = BlockReasonHub(minRepeatMs = 1_500L)
        hub.record(BlockReason.BLINK_TOO_SHORT, 1_000L)
        hub.record(BlockReason.BLINK_TOO_SHORT, 1_010L)
        hub.record(BlockReason.BLINK_TOO_SHORT, 1_020L)
        hub.record(BlockReason.FACE_LOST, 1_030L)
        assertEquals(3, hub.occurrences(BlockReason.BLINK_TOO_SHORT))
        assertEquals(1, hub.occurrences(BlockReason.FACE_LOST))
        assertEquals(2, hub.countSnapshot().size)
    }
}
