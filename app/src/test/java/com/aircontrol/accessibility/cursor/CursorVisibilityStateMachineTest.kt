package com.aircontrol.accessibility.cursor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue 1 acceptance: visibility is a state machine, never a per-frame animation op. */
class CursorVisibilityStateMachineTest {

    private val machine = CursorVisibilityStateMachine()

    @Test
    fun `show from hidden starts exactly one fade-in`() {
        assertEquals(CursorVisibilityAction.START_FADE_IN, machine.onShowRequest())
        assertEquals(CursorVisibilityState.SHOWING, machine.state)
        assertTrue(machine.isEffectivelyVisible)
    }

    @Test
    fun `repeated show while fading in does not restart the fade`() {
        assertEquals(CursorVisibilityAction.START_FADE_IN, machine.onShowRequest())
        // Every extra gaze frame calls show(); none may restart the fade.
        repeat(100) {
            assertEquals(CursorVisibilityAction.NONE, machine.onShowRequest())
            assertEquals(CursorVisibilityState.SHOWING, machine.state)
        }
    }

    @Test
    fun `repeated show while fully visible stays visible and returns NONE`() {
        machine.onShowRequest()
        machine.onFadeInCompleted()
        assertEquals(CursorVisibilityState.VISIBLE, machine.state)
        repeat(100) {
            assertEquals(CursorVisibilityAction.NONE, machine.onShowRequest())
            assertEquals(CursorVisibilityState.VISIBLE, machine.state)
        }
    }

    @Test
    fun `stale fade-in completion after cancellation is a no-op`() {
        // Fade-in started but the overlay was removed before it finished.
        machine.onShowRequest()
        machine.onRemoved()
        assertEquals(CursorVisibilityAction.NONE, machine.onFadeInCompleted())
        assertEquals(CursorVisibilityState.HIDDEN, machine.state)
        assertFalse(machine.isEffectivelyVisible)
    }

    @Test
    fun `hide while visible begins fade-out once, idempotent afterwards`() {
        machine.onShowRequest()
        machine.onFadeInCompleted()
        assertEquals(CursorVisibilityAction.START_FADE_OUT, machine.onHideRequest())
        assertEquals(CursorVisibilityState.HIDING, machine.state)
        assertEquals(CursorVisibilityAction.NONE, machine.onHideRequest())
        assertEquals(CursorVisibilityState.HIDING, machine.state)
    }

    @Test
    fun `fade-out completion hides the cursor exactly once`() {
        machine.onShowRequest()
        machine.onFadeInCompleted()
        machine.onHideRequest()
        assertEquals(CursorVisibilityAction.MAKE_INVISIBLE, machine.onFadeOutCompleted())
        assertEquals(CursorVisibilityState.HIDDEN, machine.state)
        // A stale duplicate completion must not change anything.
        assertEquals(CursorVisibilityAction.NONE, machine.onFadeOutCompleted())
        assertEquals(CursorVisibilityState.HIDDEN, machine.state)
    }

    @Test
    fun `show during fade-out cancels and restores without a new fade-in`() {
        machine.onShowRequest()
        machine.onFadeInCompleted()
        machine.onHideRequest()
        // Face re-appears during the 200ms fade-out.
        assertEquals(CursorVisibilityAction.CANCEL_AND_RESTORE, machine.onShowRequest())
        assertEquals(CursorVisibilityState.VISIBLE, machine.state)
        assertTrue(machine.isEffectivelyVisible)
        // The now-stale fade-out completion must NOT hide the cursor.
        assertEquals(CursorVisibilityAction.NONE, machine.onFadeOutCompleted())
        assertEquals(CursorVisibilityState.VISIBLE, machine.state)
        assertTrue(machine.isEffectivelyVisible)
    }

    @Test
    fun `hide while still fading in begins fade-out from current alpha`() {
        machine.onShowRequest()
        assertEquals(CursorVisibilityState.SHOWING, machine.state)
        assertEquals(CursorVisibilityAction.START_FADE_OUT, machine.onHideRequest())
        assertEquals(CursorVisibilityState.HIDING, machine.state)
    }

    @Test
    fun `removal from any non-hidden state returns CANCEL_ALL and resets`() {
        machine.onShowRequest() // SHOWING
        assertEquals(CursorVisibilityAction.CANCEL_ALL, machine.onRemoved())
        assertEquals(CursorVisibilityState.HIDDEN, machine.state)
        assertEquals(CursorVisibilityAction.NONE, machine.onRemoved()) // already hidden
        assertEquals(CursorVisibilityState.HIDDEN, machine.state)
    }

    @Test
    fun `hide when already hidden is a no-op`() {
        assertEquals(CursorVisibilityAction.NONE, machine.onHideRequest())
        assertEquals(CursorVisibilityState.HIDDEN, machine.state)
        assertFalse(machine.isEffectivelyVisible)
    }
}
