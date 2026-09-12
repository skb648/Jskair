package com.aircontrol.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingLifecycleStateMachineTest {
    @Test
    fun `camera loss is waiting not a user disable and recovery is one generation`() {
        val machine = TrackingLifecycleStateMachine()
        machine.dispatch(TrackingLifecycleEvent.USER_ENABLE)
        assertEquals(TrackingState.STARTING, machine.snapshot().state)
        machine.dispatch(TrackingLifecycleEvent.CAMERA_BOUND)
        assertEquals(TrackingState.RUNNING, machine.snapshot().state)
        val generationBeforeLoss = machine.snapshot().generation
        machine.dispatch(TrackingLifecycleEvent.CAMERA_TEMPORARILY_UNAVAILABLE)
        assertEquals(TrackingState.CAMERA_LOST, machine.snapshot().state)
        assertTrue(machine.snapshot().desiredTrackingEnabled)
        machine.dispatch(TrackingLifecycleEvent.CAMERA_AVAILABLE)
        assertEquals(TrackingState.STARTING, machine.snapshot().state)
        assertEquals(generationBeforeLoss + 1, machine.snapshot().generation)
        machine.dispatch(TrackingLifecycleEvent.CAMERA_BOUND)
        assertEquals(TrackingState.RUNNING, machine.snapshot().state)
    }

    @Test
    fun `duplicate starts do not create generations`() {
        val machine = TrackingLifecycleStateMachine()
        val first = machine.dispatch(TrackingLifecycleEvent.USER_ENABLE)
        val second = machine.dispatch(TrackingLifecycleEvent.USER_ENABLE)
        assertEquals(first.generation, second.generation)
        assertEquals(TrackingState.STARTING, second.state)
    }

    @Test
    fun `disable invalidates old generation and stale camera events are ignored`() {
        val machine = TrackingLifecycleStateMachine()
        machine.dispatch(TrackingLifecycleEvent.USER_ENABLE)
        val generation = machine.snapshot().generation
        machine.dispatch(TrackingLifecycleEvent.USER_DISABLE)
        val stopped = machine.snapshot()
        assertFalse(stopped.desiredTrackingEnabled)
        assertEquals(TrackingState.STOPPED, stopped.state)
        assertTrue(stopped.generation > generation)
        machine.dispatch(TrackingLifecycleEvent.CAMERA_BOUND)
        assertEquals(TrackingState.STOPPED, machine.snapshot().state)
    }

    @Test
    fun `pause and resume preserve desired intent`() {
        val machine = TrackingLifecycleStateMachine()
        machine.dispatch(TrackingLifecycleEvent.USER_ENABLE)
        machine.dispatch(TrackingLifecycleEvent.CAMERA_BOUND)
        machine.dispatch(TrackingLifecycleEvent.SYSTEM_PAUSE)
        assertEquals(TrackingState.PAUSED, machine.snapshot().state)
        assertTrue(machine.snapshot().desiredTrackingEnabled)
        machine.dispatch(TrackingLifecycleEvent.RESUME)
        assertEquals(TrackingState.STARTING, machine.snapshot().state)
        assertTrue(machine.snapshot().desiredTrackingEnabled)
    }
}
