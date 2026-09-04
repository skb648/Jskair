package com.aircontrol.gesture.intent

import com.aircontrol.gesture.model.GestureEngineState
import com.aircontrol.gesture.model.GestureEvent
import com.aircontrol.gesture.model.PinchPhase
import com.aircontrol.gesture.model.SwipeDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IntentEngineTest {
    private val engine = IntentEngine()

    @Test
    fun cursorMoveBecomesMove() {
        val intent = engine.classify(
            GestureEvent.CursorMoved(0.3f, 0.7f, 10L),
            GestureEngineState.ARMED,
        )
        assertEquals(IntentType.MOVE, intent.type)
        assertEquals(0.3f, intent.x)
        assertEquals(0.7f, intent.y)
    }

    @Test
    fun silentCursorMoveBecomesNoAction() {
        val intent = engine.classify(
            GestureEvent.CursorMoved(0.3f, 0.7f, 10L, isSilent = true),
            GestureEngineState.ARMING,
        )
        assertEquals(IntentType.NO_ACTION, intent.type)
    }

    @Test
    fun pinchStartBecomesClickAtAnchor() {
        val intent = engine.classify(
            GestureEvent.Pinch(PinchPhase.START, 0.2f, 0.4f, 20L, anchoredX = 0.35f, anchoredY = 0.45f),
            GestureEngineState.ARMED,
        )
        assertEquals(IntentType.CLICK, intent.type)
        assertEquals(0.35f, intent.x)
        assertEquals(0.45f, intent.y)
    }

    @Test
    fun pinchMoveBecomesDrag() {
        val intent = engine.classify(
            GestureEvent.Pinch(PinchPhase.MOVE, 0.6f, 0.65f, 30L, anchoredX = 0.4f, anchoredY = 0.4f),
            GestureEngineState.EXECUTING,
        )
        assertEquals(IntentType.DRAG, intent.type)
        assertEquals(0.6f, intent.x)
        assertEquals(0.65f, intent.y)
    }

    @Test
    fun pinchEndDoesNotExecuteAnAction() {
        val intent = engine.classify(
            GestureEvent.Pinch(PinchPhase.END, 0.6f, 0.65f, 40L),
            GestureEngineState.COOLDOWN,
        )
        assertEquals(IntentType.NO_ACTION, intent.type)
    }

    @Test
    fun swipeBecomesSwipe() {
        val intent = engine.classify(
            GestureEvent.Swipe(SwipeDirection.LEFT, 50L),
            GestureEngineState.ARMED,
        )
        assertEquals(IntentType.SWIPE, intent.type)
    }

    @Test
    fun lowConfidenceAlwaysFailsClosed() {
        val intent = engine.classify(
            GestureEvent.Swipe(SwipeDirection.RIGHT, 60L),
            GestureEngineState.ARMED,
            confidence = 0.2f,
        )
        assertEquals(IntentType.NO_ACTION, intent.type)
        assertEquals("low-confidence", intent.source)
    }

    @Test
    fun disarmedAlwaysFailsClosed() {
        val intent = engine.classify(
            GestureEvent.Pinch(PinchPhase.START, 0.2f, 0.3f, 70L),
            GestureEngineState.DISARMED,
        )
        assertEquals(IntentType.NO_ACTION, intent.type)
        assertEquals("disarmed", intent.source)
    }

    @Test
    fun clampingKeepsCoordinatesSafe() {
        val intent = engine.classify(
            GestureEvent.CursorMoved(-1f, 2f, 80L),
            GestureEngineState.ARMED,
        )
        assertEquals(0f, intent.x)
        assertEquals(1f, intent.y)
    }

    @Test
    fun nonActionableEventsReturnNoAction() {
        val pose = engine.classify(
            GestureEvent.PoseTriggered(com.aircontrol.gesture.model.Pose.VICTORY, 90L),
            GestureEngineState.ARMED,
        )
        assertEquals(IntentType.NO_ACTION, pose.type)
        assertNull(pose.x)
        assertNull(pose.y)
    }
}
