package com.aircontrol

import com.aircontrol.accessibility.ActionDispatcher
import com.aircontrol.accessibility.GestureAction
import com.aircontrol.data.repository.SettingsRepository
import com.aircontrol.gesture.model.GestureEngineState
import com.aircontrol.gesture.model.GestureEvent
import com.aircontrol.gesture.model.PinchPhase
import com.aircontrol.gesture.model.Pose
import com.aircontrol.gesture.model.SwipeDirection
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import javax.inject.Inject

/**
 * Instrumented tests for AccessibilityService action dispatching.
 * Tests that ActionDispatcher correctly handles gesture events
 * and maps them to the appropriate actions.
 *
 * Note: Full end-to-end dispatch tests require an AccessibilityService
 * to be running, which is not available in test context.
 * These tests focus on the coordinate mapping and gesture map logic.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AccessibilityActionDispatchTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var settingsRepository: SettingsRepository

    private lateinit var actionDispatcher: ActionDispatcher

    @Before
    fun setup() {
        hiltRule.inject()
        actionDispatcher = ActionDispatcher(settingsRepository)
    }

    // ========== Coordinate Mapping on Real Device ==========

    @Test
    fun normalizeToScreenX_centerOn1080Screen() {
        val result = actionDispatcher.normalizeToScreenX(0.5f, 1080)
        assertEquals(540f, result, 1f)
    }

    @Test
    fun normalizeToScreenY_centerOn2400Screen() {
        val result = actionDispatcher.normalizeToScreenY(0.5f, 2400)
        assertEquals(1200f, result, 1f)
    }

    @Test
    fun normalizeToScreenX_mirrorsFrontCamera() {
        // Front camera mirror: 1 - x
        val left = actionDispatcher.normalizeToScreenX(0.0f, 1080)
        val right = actionDispatcher.normalizeToScreenX(1.0f, 1080)
        // normX=0 should map to the right side of screen (mirrored)
        // normX=1 should map to the left side of screen (mirrored)
        assert(left > right)
    }

    @Test
    fun normalizeToScreenY_doesNotMirror() {
        val top = actionDispatcher.normalizeToScreenY(0.0f, 2400)
        val bottom = actionDispatcher.normalizeToScreenY(1.0f, 2400)
        // Y increases downward, no mirror
        assert(top < bottom)
    }

    @Test
    fun normalizeToScreenX_edgeValuesWithinScreenBounds() {
        val result0 = actionDispatcher.normalizeToScreenX(0.0f, 1080)
        val result1 = actionDispatcher.normalizeToScreenX(1.0f, 1080)
        assert(result0 >= 0f && result0 <= 1080f)
        assert(result1 >= 0f && result1 <= 1080f)
    }

    @Test
    fun normalizeToScreenY_edgeValuesWithinScreenBounds() {
        val result0 = actionDispatcher.normalizeToScreenY(0.0f, 2400)
        val result1 = actionDispatcher.normalizeToScreenY(1.0f, 2400)
        assert(result0 >= 0f && result1 <= 2400f)
    }

    // ========== Gesture Map ==========

    @Test
    fun defaultGestureMapHasExpectedActions() {
        val map = actionDispatcher.getGestureMap()
        assertEquals(GestureAction.SCROLL_RIGHT, map[ActionDispatcher.KEY_SWIPE_LEFT])
        assertEquals(GestureAction.SCROLL_LEFT, map[ActionDispatcher.KEY_SWIPE_RIGHT])
        assertEquals(GestureAction.SCROLL_DOWN, map[ActionDispatcher.KEY_SWIPE_UP])
        assertEquals(GestureAction.SCROLL_UP, map[ActionDispatcher.KEY_SWIPE_DOWN])
        assertEquals(GestureAction.TAP, map[ActionDispatcher.KEY_POSE_PINCH])
        assertEquals(GestureAction.NONE, map[ActionDispatcher.KEY_POSE_POINTING])
        assertEquals(GestureAction.MEDIA_PLAY_PAUSE, map[ActionDispatcher.KEY_POSE_VICTORY])
        assertEquals(GestureAction.VOLUME_UP, map[ActionDispatcher.KEY_POSE_THUMB_UP])
        assertEquals(GestureAction.VOLUME_DOWN, map[ActionDispatcher.KEY_POSE_THUMB_DOWN])
    }

    @Test
    fun updateGestureActionChangesMap() {
        actionDispatcher.updateGestureAction(ActionDispatcher.KEY_SWIPE_LEFT, GestureAction.HOME)
        val map = actionDispatcher.getGestureMap()
        assertEquals(GestureAction.HOME, map[ActionDispatcher.KEY_SWIPE_LEFT])
    }

    // ========== Dispatch without service ==========

    @Test
    fun dispatchReturnsFalseWithoutService() {
        val event = GestureEvent.Swipe(SwipeDirection.LEFT, System.currentTimeMillis())
        val result = actionDispatcher.dispatch(
            event = event,
            engineState = GestureEngineState.ARMED,
            cursorX = 0.5f,
            cursorY = 0.5f,
            screenWidth = 1080,
            screenHeight = 2400,
        )
        assertFalse(result)
    }

    @Test
    fun dispatchReturnsFalseForDisarmedState() {
        val event = GestureEvent.Swipe(SwipeDirection.LEFT, System.currentTimeMillis())
        val result = actionDispatcher.dispatch(
            event = event,
            engineState = GestureEngineState.DISARMED,
            cursorX = 0.5f,
            cursorY = 0.5f,
            screenWidth = 1080,
            screenHeight = 2400,
        )
        assertFalse(result)
    }

    @Test
    fun dispatchReturnsFalseForInternalEvents() {
        val armedEvent = GestureEvent.Armed(System.currentTimeMillis())
        val disarmedEvent = GestureEvent.Disarmed(System.currentTimeMillis())
        val cursorEvent = GestureEvent.CursorMoved(0.5f, 0.5f, System.currentTimeMillis())

        assertFalse(
            actionDispatcher.dispatch(
                armedEvent, GestureEngineState.ARMED,
                0.5f, 0.5f, 1080, 2400,
            ),
        )
        assertFalse(
            actionDispatcher.dispatch(
                disarmedEvent, GestureEngineState.DISARMED,
                0.5f, 0.5f, 1080, 2400,
            ),
        )
        assertFalse(
            actionDispatcher.dispatch(
                cursorEvent, GestureEngineState.ARMED,
                0.5f, 0.5f, 1080, 2400,
            ),
        )
    }
}
