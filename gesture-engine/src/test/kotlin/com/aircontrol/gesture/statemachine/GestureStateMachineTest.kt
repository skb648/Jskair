package com.aircontrol.gesture.statemachine

import com.aircontrol.gesture.config.GestureEngineConfig
import com.aircontrol.gesture.model.GestureEngineState
import com.aircontrol.gesture.model.Pose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for GestureStateMachine.
 * Tests the full lifecycle: DISARMED → ARMING → ARMED → EXECUTING → COOLDOWN → ARMED,
 * auto-disarm, fist disarm, and edge cases.
 */
class GestureStateMachineTest {

    private lateinit var stateMachine: GestureStateMachine
    private lateinit var config: GestureEngineConfig

    @Before
    fun setUp() {
        config = GestureEngineConfig(
            armingDurationMs = 600L,
            cooldownDurationMs = 700L,
            autoDisarmTimeoutMs = 10_000L,
            fistDisarmDurationMs = 1000L,
        )
        stateMachine = GestureStateMachine(config)
    }

    // ========== DISARMED state ==========

    @Test
    fun `initial state is DISARMED`() {
        assertEquals(GestureEngineState.DISARMED, stateMachine.currentState)
    }

    @Test
    fun `DISARMED stays DISARMED without open palm`() {
        val result = stateMachine.process(Pose.FIST, true, 100L)
        assertEquals(GestureEngineState.DISARMED, stateMachine.currentState)
        assertFalse(result.stateChanged)
    }

    @Test
    fun `DISARMED stays DISARMED with no hand`() {
        val result = stateMachine.process(Pose.NONE, false, 100L)
        assertEquals(GestureEngineState.DISARMED, stateMachine.currentState)
    }

    @Test
    fun `DISARMED transitions to ARMING on open palm`() {
        val result = stateMachine.process(Pose.OPEN_PALM, true, 100L)
        assertEquals(GestureEngineState.ARMING, stateMachine.currentState)
        assertTrue(result.stateChanged)
    }

    // ========== ARMING state ==========

    @Test
    fun `ARMING transitions to ARMED after arming duration`() {
        // Start arming
        stateMachine.process(Pose.OPEN_PALM, true, 100L)
        assertEquals(GestureEngineState.ARMING, stateMachine.currentState)

        // Hold for arming duration
        val result = stateMachine.process(Pose.OPEN_PALM, true, 100L + config.armingDurationMs)
        assertEquals(GestureEngineState.ARMED, stateMachine.currentState)
        assertTrue(result.stateChanged)
    }

    @Test
    fun `ARMING progress increases over time`() {
        stateMachine.process(Pose.OPEN_PALM, true, 100L)
        assertEquals(0f, stateMachine.armingProgress, 0.01f)

        stateMachine.process(Pose.OPEN_PALM, true, 100L + 300L) // 50% of 600ms
        assertEquals(0.5f, stateMachine.armingProgress, 0.1f)

        stateMachine.process(Pose.OPEN_PALM, true, 100L + config.armingDurationMs)
        assertEquals(1f, stateMachine.armingProgress, 0.01f)
    }

    @Test
    fun `ARMING reverts to DISARMED if palm is lost`() {
        stateMachine.process(Pose.OPEN_PALM, true, 100L)
        assertEquals(GestureEngineState.ARMING, stateMachine.currentState)

        // Pose changes away from open palm
        val result = stateMachine.process(Pose.NONE, true, 200L)
        assertEquals(GestureEngineState.DISARMED, stateMachine.currentState)
        assertTrue(result.stateChanged)
        assertEquals(0f, stateMachine.armingProgress, 0.01f)
    }

    @Test
    fun `ARMING reverts to DISARMED if hand is lost`() {
        stateMachine.process(Pose.OPEN_PALM, true, 100L)
        val result = stateMachine.process(Pose.OPEN_PALM, false, 200L)
        assertEquals(GestureEngineState.DISARMED, stateMachine.currentState)
    }

    @Test
    fun `ARMING does not transition to ARMED before duration`() {
        stateMachine.process(Pose.OPEN_PALM, true, 100L)
        stateMachine.process(Pose.OPEN_PALM, true, 100L + config.armingDurationMs - 1L)
        assertEquals(GestureEngineState.ARMING, stateMachine.currentState)
    }

    // ========== ARMED state ==========

    @Test
    fun `ARMED transitions to EXECUTING on actionable pose`() {
        armSystem()

        val result = stateMachine.process(Pose.POINTING, true, 2000L)
        assertEquals(GestureEngineState.EXECUTING, stateMachine.currentState)
        assertTrue(result.shouldExecute)
    }

    @Test
    fun `ARMED stays ARMED on OPEN_PALM`() {
        armSystem()

        val result = stateMachine.process(Pose.OPEN_PALM, true, 2000L)
        // OPEN_PALM should not trigger execution
        assertEquals(GestureEngineState.ARMED, stateMachine.currentState)
    }

    @Test
    fun `ARMED stays ARMED on NONE pose`() {
        armSystem()

        stateMachine.process(Pose.NONE, true, 2000L)
        assertEquals(GestureEngineState.ARMED, stateMachine.currentState)
    }

    @Test
    fun `ARMED tracks FIST for disarm countdown`() {
        armSystem()

        // Hold FIST
        stateMachine.process(Pose.FIST, true, 2000L)
        assertEquals(GestureEngineState.ARMED, stateMachine.currentState) // Not yet disarmed

        // Continue holding FIST for the disarm duration
        stateMachine.process(Pose.FIST, true, 2000L + config.fistDisarmDurationMs)
        assertEquals(GestureEngineState.DISARMED, stateMachine.currentState)
    }

    @Test
    fun `ARMED FIST disarm resets if FIST is released`() {
        armSystem()

        // Hold FIST briefly
        stateMachine.process(Pose.FIST, true, 2000L)

        // Release FIST
        stateMachine.process(Pose.OPEN_PALM, true, 2100L)

        // FIST again — timer should restart
        stateMachine.process(Pose.FIST, true, 2200L)
        assertEquals(GestureEngineState.ARMED, stateMachine.currentState)

        // Hold for full duration from restart
        stateMachine.process(Pose.FIST, true, 2200L + config.fistDisarmDurationMs)
        assertEquals(GestureEngineState.DISARMED, stateMachine.currentState)
    }

    @Test
    fun `ARMED auto-disarms after no hand timeout`() {
        armSystem()

        // Hand present
        stateMachine.process(Pose.OPEN_PALM, true, 2000L)
        assertEquals(GestureEngineState.ARMED, stateMachine.currentState)

        // Hand lost
        stateMachine.process(Pose.NONE, false, 3000L)
        assertEquals(GestureEngineState.ARMED, stateMachine.currentState) // Not yet disarmed

        // After timeout
        stateMachine.process(Pose.NONE, false, 2000L + config.autoDisarmTimeoutMs + 1L)
        assertEquals(GestureEngineState.DISARMED, stateMachine.currentState)
    }

    @Test
    fun `ARMED triggers execution on PINCH`() {
        armSystem()
        val result = stateMachine.process(Pose.PINCH, true, 2000L)
        assertEquals(GestureEngineState.EXECUTING, stateMachine.currentState)
        assertTrue(result.shouldExecute)
    }

    @Test
    fun `ARMED triggers execution on VICTORY`() {
        armSystem()
        val result = stateMachine.process(Pose.VICTORY, true, 2000L)
        assertEquals(GestureEngineState.EXECUTING, stateMachine.currentState)
        assertTrue(result.shouldExecute)
    }

    @Test
    fun `ARMED triggers execution on THUMB_UP`() {
        armSystem()
        val result = stateMachine.process(Pose.THUMB_UP, true, 2000L)
        assertEquals(GestureEngineState.EXECUTING, stateMachine.currentState)
        assertTrue(result.shouldExecute)
    }

    @Test
    fun `ARMED triggers execution on THUMB_DOWN`() {
        armSystem()
        val result = stateMachine.process(Pose.THUMB_DOWN, true, 2000L)
        assertEquals(GestureEngineState.EXECUTING, stateMachine.currentState)
        assertTrue(result.shouldExecute)
    }

    // ========== EXECUTING state ==========

    @Test
    fun `EXECUTING transitions to COOLDOWN`() {
        armSystem()
        stateMachine.process(Pose.POINTING, true, 2000L)
        assertEquals(GestureEngineState.EXECUTING, stateMachine.currentState)

        // Next frame → COOLDOWN
        stateMachine.process(Pose.NONE, true, 2001L)
        assertEquals(GestureEngineState.COOLDOWN, stateMachine.currentState)
    }

    // ========== COOLDOWN state ==========

    @Test
    fun `COOLDOWN transitions to ARMED after duration if hand present`() {
        armSystem()
        stateMachine.process(Pose.POINTING, true, 2000L) // → EXECUTING
        stateMachine.process(Pose.NONE, true, 2001L) // → COOLDOWN

        // Before cooldown ends
        stateMachine.process(Pose.OPEN_PALM, true, 2001L + config.cooldownDurationMs - 100L)
        assertEquals(GestureEngineState.COOLDOWN, stateMachine.currentState)

        // After cooldown ends
        stateMachine.process(Pose.OPEN_PALM, true, 2001L + config.cooldownDurationMs + 1L)
        assertEquals(GestureEngineState.ARMED, stateMachine.currentState)
    }

    @Test
    fun `COOLDOWN transitions to DISARMED after duration if no hand`() {
        armSystem()
        stateMachine.process(Pose.POINTING, true, 2000L) // → EXECUTING
        stateMachine.process(Pose.NONE, true, 2001L) // → COOLDOWN

        // After cooldown, no hand detected
        stateMachine.process(Pose.NONE, false, 2001L + config.cooldownDurationMs + 1L)
        assertEquals(GestureEngineState.DISARMED, stateMachine.currentState)
    }

    // ========== Full lifecycle test ==========

    @Test
    fun `full lifecycle DISARMED through COOLDOWN back to ARMED`() {
        var ts = 100L

        // DISARMED → ARMING
        stateMachine.process(Pose.OPEN_PALM, true, ts)
        assertEquals(GestureEngineState.ARMING, stateMachine.currentState)

        // ARMING → ARMED
        ts += config.armingDurationMs
        stateMachine.process(Pose.OPEN_PALM, true, ts)
        assertEquals(GestureEngineState.ARMED, stateMachine.currentState)

        // ARMED → EXECUTING
        ts += 100L
        val execResult = stateMachine.process(Pose.VICTORY, true, ts)
        assertEquals(GestureEngineState.EXECUTING, stateMachine.currentState)
        assertTrue(execResult.shouldExecute)

        // EXECUTING → COOLDOWN
        ts += 1L
        stateMachine.process(Pose.NONE, true, ts)
        assertEquals(GestureEngineState.COOLDOWN, stateMachine.currentState)

        // COOLDOWN → ARMED
        ts += config.cooldownDurationMs + 1L
        stateMachine.process(Pose.OPEN_PALM, true, ts)
        assertEquals(GestureEngineState.ARMED, stateMachine.currentState)

        // ARMED → DISARMED via FIST
        ts += 100L
        stateMachine.process(Pose.FIST, true, ts)
        ts += config.fistDisarmDurationMs
        stateMachine.process(Pose.FIST, true, ts)
        assertEquals(GestureEngineState.DISARMED, stateMachine.currentState)
    }

    // ========== shouldExecute flag ==========

    @Test
    fun `shouldExecute is true only on ARMED to EXECUTING transition`() {
        armSystem()

        // ARMED → EXECUTING
        val result1 = stateMachine.process(Pose.POINTING, true, 2000L)
        assertTrue(result1.shouldExecute)

        // EXECUTING → COOLDOWN (not a new execution)
        val result2 = stateMachine.process(Pose.NONE, true, 2001L)
        assertFalse(result2.shouldExecute)
    }

    // ========== Reset ==========

    @Test
    fun `reset returns to DISARMED`() {
        armSystem()
        stateMachine.reset()
        assertEquals(GestureEngineState.DISARMED, stateMachine.currentState)
        assertEquals(0f, stateMachine.armingProgress, 0.01f)
    }

    // ========== Helper ==========

    private fun armSystem() {
        stateMachine.process(Pose.OPEN_PALM, true, 100L) // → ARMING
        stateMachine.process(Pose.OPEN_PALM, true, 100L + config.armingDurationMs) // → ARMED
        assertEquals(GestureEngineState.ARMED, stateMachine.currentState)
    }
}
