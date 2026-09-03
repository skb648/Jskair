package com.aircontrol.accessibility

import com.aircontrol.data.model.UserPreferences
import com.aircontrol.data.model.GestureMapConfig
import com.aircontrol.data.repository.SettingsRepository
import com.aircontrol.gesture.model.GestureEngineState
import com.aircontrol.gesture.model.GestureEvent
import com.aircontrol.gesture.model.PinchPhase
import com.aircontrol.gesture.model.Pose
import com.aircontrol.gesture.model.SwipeDirection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.mockito.kotlin.mock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ActionDispatcherTest {

    private lateinit var actionDispatcher: ActionDispatcher
    private lateinit var mockSettingsRepository: SettingsRepository
    private lateinit var userPreferencesFlow: MutableStateFlow<UserPreferences>

    @Before
    fun setup() {
        mockSettingsRepository = mock()
        userPreferencesFlow = MutableStateFlow(UserPreferences())

        // We'll use the real ActionDispatcher but with a mock repository
        actionDispatcher = ActionDispatcher(mockSettingsRepository)
    }

    // ========== normalizeToScreenX ==========
    // Dead-zone mapping: activePos = (normX - 0.10) / 0.80, clamped to [0,1].
    // No mirror here — front-camera mirroring is applied upstream in CameraService.

    @Test
    fun `normalizeToScreenX at center maps to center of screen`() {
        val screenWidth = 1080
        val result = ActionDispatcher.normalizeToScreenX(0.5f, screenWidth)
        // (0.5 - 0.10) / 0.80 = 0.5 -> 540
        assertEquals(540f, result, 1f)
    }

    @Test
    fun `normalizeToScreenX clamps dead zones to edges`() {
        val screenWidth = 1080
        val leftEdge = ActionDispatcher.normalizeToScreenX(0.0f, screenWidth)
        val rightEdge = ActionDispatcher.normalizeToScreenX(1.0f, screenWidth)
        // 0.0 is inside the left 10% dead zone -> clamps to left edge (0)
        assertEquals(0f, leftEdge, 0.01f)
        // 1.0 is inside the right 10% dead zone -> clamps to right edge (1080)
        assertEquals(1080f, rightEdge, 0.01f)
    }

    @Test
    fun `normalizeToScreenX edge values are within screen bounds`() {
        val screenWidth = 1080
        for (v in listOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f, -0.5f, 1.5f)) {
            val result = ActionDispatcher.normalizeToScreenX(v, screenWidth)
            assertTrue("X for $v should be in bounds", result >= 0f && result <= screenWidth.toFloat())
        }
    }

    @Test
    fun `normalizeToScreenX is monotonically increasing`() {
        val screenWidth = 1080
        val r0 = ActionDispatcher.normalizeToScreenX(0.0f, screenWidth)
        val r25 = ActionDispatcher.normalizeToScreenX(0.25f, screenWidth)
        val r50 = ActionDispatcher.normalizeToScreenX(0.5f, screenWidth)
        val r75 = ActionDispatcher.normalizeToScreenX(0.75f, screenWidth)
        val r100 = ActionDispatcher.normalizeToScreenX(1.0f, screenWidth)
        assertTrue(r0 <= r25)
        assertTrue(r25 <= r50)
        assertTrue(r50 <= r75)
        assertTrue(r75 <= r100)
    }

    // ========== normalizeToScreenY ==========
    // Dead-zone mapping: activePos = (normY - 0.40) / 0.60, clamped to [0,1].
    // MediaPipe Y is 0 at the top of the image. The top 40% is a dead zone
    // clamped to the screen top so the user does not have to raise their hand to
    // the camera's top edge to reach the top of the screen.

    @Test
    fun `normalizeToScreenY maps active zone center to screen center`() {
        val screenHeight = 2400
        val result = ActionDispatcher.normalizeToScreenY(0.65f, screenHeight)
        // (0.65 - 0.30) / 0.70 = 0.5 -> 1200
        assertEquals(1200f, result, 1f)
    }

    @Test
    fun `normalizeToScreenY clamps top dead zone to top of screen`() {
        val screenHeight = 2400
        val top = ActionDispatcher.normalizeToScreenY(0.0f, screenHeight)
        val deadZoneEdge = ActionDispatcher.normalizeToScreenY(0.3f, screenHeight)
        assertEquals(0f, top, 0.01f)
        assertEquals(0f, deadZoneEdge, 0.01f)
    }

    @Test
    fun `normalizeToScreenY maps bottom to bottom of screen`() {
        val screenHeight = 2400
        val bottom = ActionDispatcher.normalizeToScreenY(1.0f, screenHeight)
        assertEquals(2400f, bottom, 0.01f)
    }

    @Test
    fun `normalizeToScreenY edge values are within screen bounds`() {
        val screenHeight = 2400
        for (v in listOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f, -0.5f, 1.5f)) {
            val result = ActionDispatcher.normalizeToScreenY(v, screenHeight)
            assertTrue("Y for $v should be in bounds", result >= 0f && result <= screenHeight.toFloat())
        }
    }

    @Test
    fun `normalizeToScreenY is monotonically increasing`() {
        val screenHeight = 2400
        val r0 = ActionDispatcher.normalizeToScreenY(0.0f, screenHeight)
        val r25 = ActionDispatcher.normalizeToScreenY(0.25f, screenHeight)
        val r50 = ActionDispatcher.normalizeToScreenY(0.5f, screenHeight)
        val r75 = ActionDispatcher.normalizeToScreenY(0.75f, screenHeight)
        val r100 = ActionDispatcher.normalizeToScreenY(1.0f, screenHeight)
        assertTrue(r0 <= r25)
        assertTrue(r25 <= r50)
        assertTrue(r50 <= r75)
        assertTrue(r75 <= r100)
    }

    // ========== Coordinate mapping specific values ==========

    @Test
    fun `normalizeToScreenX 0,5 on 1080 screen gives 540`() {
        val result = ActionDispatcher.normalizeToScreenX(0.5f, 1080)
        assertEquals(540f, result, 0.01f)
    }

    @Test
    fun `normalizeToScreenY 0,65 on 2400 screen gives 1200`() {
        // (0.65 - 0.3) / 0.7 = 0.5 -> 1200
        val result = ActionDispatcher.normalizeToScreenY(0.65f, 2400)
        assertEquals(1200f, result, 0.01f)
    }

    @Test
    fun `normalizeToScreenX at 0 maps to 0 left dead zone`() {
        val result = ActionDispatcher.normalizeToScreenX(0.0f, 1080)
        assertEquals(0f, result, 0.01f)
    }

    @Test
    fun `normalizeToScreenY at 0 maps to 0 top dead zone`() {
        val result = ActionDispatcher.normalizeToScreenY(0.0f, 2400)
        assertEquals(0f, result, 0.01f)
    }

    // ========== setup-flow suppression policy (Fix B-3) ==========

    @Test
    fun `every action is allowed outside a setup flow`() {
        com.aircontrol.ui.Suppression.resetForTest()
        for (action in GestureAction.values()) {
            assertTrue(
                "$action must be allowed when no calibration screen is open",
                actionDispatcher.actionAllowed(action),
            )
        }
    }

    @Test
    fun `during calibration only pointer-local actions survive`() {
        com.aircontrol.ui.Suppression.resetForTest()
        com.aircontrol.ui.Suppression.acquire()
        try {
            // The user has to be able to press the calibration screen's own buttons.
            for (action in listOf(
                GestureAction.TAP,
                GestureAction.DOUBLE_TAP,
                GestureAction.LONG_PRESS,
                GestureAction.DRAG,
                GestureAction.NONE,
            )) {
                assertTrue("$action must still work", actionDispatcher.actionAllowed(action))
            }
            // Everything that leaves the screen would yank the flow away.
            for (action in listOf(
                GestureAction.HOME,
                GestureAction.BACK,
                GestureAction.RECENTS,
                GestureAction.NOTIFICATIONS,
                GestureAction.QUICK_SETTINGS,
                GestureAction.VOLUME_UP,
                GestureAction.VOLUME_DOWN,
                GestureAction.MEDIA_PLAY_PAUSE,
                GestureAction.SCREENSHOT,
                GestureAction.LOCK_SCREEN,
                GestureAction.SCROLL_UP,
                GestureAction.SCROLL_DOWN,
                GestureAction.SCROLL_LEFT,
                GestureAction.SCROLL_RIGHT,
            )) {
                assertFalse("$action must be suppressed", actionDispatcher.actionAllowed(action))
            }
        } finally {
            com.aircontrol.ui.Suppression.release()
        }
        assertTrue("actions resume when the flow closes", actionDispatcher.actionAllowed(GestureAction.HOME))
    }

    // ========== dispatch() behavior without service attached ==========

    @Test
    fun `dispatch returns false when service is not attached`() {
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
    fun `dispatch returns false when engine is DISARMED`() {
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
    fun `dispatch returns false for ARMING state`() {
        val event = GestureEvent.Swipe(SwipeDirection.LEFT, System.currentTimeMillis())
        val result = actionDispatcher.dispatch(
            event = event,
            engineState = GestureEngineState.ARMING,
            cursorX = 0.5f,
            cursorY = 0.5f,
            screenWidth = 1080,
            screenHeight = 2400,
        )
        assertFalse(result)
    }

    @Test
    fun `dispatch returns false for COOLDOWN state`() {
        val event = GestureEvent.Swipe(SwipeDirection.LEFT, System.currentTimeMillis())
        val result = actionDispatcher.dispatch(
            event = event,
            engineState = GestureEngineState.COOLDOWN,
            cursorX = 0.5f,
            cursorY = 0.5f,
            screenWidth = 1080,
            screenHeight = 2400,
        )
        assertFalse(result)
    }

    @Test
    fun `dispatch returns false for Armed event`() {
        val event = GestureEvent.Armed(System.currentTimeMillis())
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
    fun `dispatch returns false for Disarmed event`() {
        val event = GestureEvent.Disarmed(System.currentTimeMillis())
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
    fun `dispatch returns false for CursorMoved event`() {
        val event = GestureEvent.CursorMoved(0.5f, 0.5f, System.currentTimeMillis())
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

    // ========== Gesture map operations ==========

    @Test
    fun `getGestureMap returns default map`() {
        val map = actionDispatcher.getGestureMap()
        assertEquals(GestureMapConfig.defaultEntries().size, map.size)
        assertEquals(GestureAction.SCROLL_RIGHT, map[ActionDispatcher.KEY_SWIPE_RIGHT])
        assertEquals(GestureAction.TAP, map[ActionDispatcher.KEY_POSE_PINCH])
    }

    @Test
    fun `updateGestureAction updates the gesture map`() {
        actionDispatcher.updateGestureAction(ActionDispatcher.KEY_SWIPE_LEFT, GestureAction.HOME)
        val map = actionDispatcher.getGestureMap()
        assertEquals(GestureAction.HOME, map[ActionDispatcher.KEY_SWIPE_LEFT])
    }

    @Test
    fun `updateGestureAction for one key does not affect others`() {
        actionDispatcher.updateGestureAction(ActionDispatcher.KEY_SWIPE_LEFT, GestureAction.HOME)
        val map = actionDispatcher.getGestureMap()
        assertEquals(GestureAction.HOME, map[ActionDispatcher.KEY_SWIPE_LEFT])
        assertEquals(GestureAction.SCROLL_RIGHT, map[ActionDispatcher.KEY_SWIPE_RIGHT])
        assertEquals(GestureAction.TAP, map[ActionDispatcher.KEY_POSE_PINCH])
    }

    @Test
    fun `updateGestureAction can set action to NONE`() {
        actionDispatcher.updateGestureAction(ActionDispatcher.KEY_POSE_PINCH, GestureAction.NONE)
        val map = actionDispatcher.getGestureMap()
        assertEquals(GestureAction.NONE, map[ActionDispatcher.KEY_POSE_PINCH])
    }

    // ========== GestureAction enum constants ==========

    @Test
    fun `GestureAction constant keys are correct`() {
        assertEquals("swipe_left", ActionDispatcher.KEY_SWIPE_LEFT)
        assertEquals("swipe_right", ActionDispatcher.KEY_SWIPE_RIGHT)
        assertEquals("swipe_up", ActionDispatcher.KEY_SWIPE_UP)
        assertEquals("swipe_down", ActionDispatcher.KEY_SWIPE_DOWN)
        assertEquals("pose_pinch", ActionDispatcher.KEY_POSE_PINCH)
        assertEquals("pose_pointing", ActionDispatcher.KEY_POSE_POINTING)
        assertEquals("pose_victory", ActionDispatcher.KEY_POSE_VICTORY)
        assertEquals("pose_thumb_up", ActionDispatcher.KEY_POSE_THUMB_UP)
        assertEquals("pose_thumb_down", ActionDispatcher.KEY_POSE_THUMB_DOWN)
    }

    // ========== Pinch event dispatch (no service) ==========

    @Test
    fun `dispatch pinch START is accepted without service`() {
        val event = GestureEvent.Pinch(PinchPhase.START, 0.5f, 0.5f, System.currentTimeMillis())
        val result = actionDispatcher.dispatch(
            event = event,
            engineState = GestureEngineState.ARMED,
            cursorX = 0.5f,
            cursorY = 0.5f,
            screenWidth = 1080,
            screenHeight = 2400,
        )
        assertTrue(result)
    }

    // ========== Pose event dispatch (no service) ==========

    @Test
    fun `dispatch pose event returns false without service for NONE action`() {
        // Pointing is mapped to NONE by default
        val event = GestureEvent.PoseTriggered(Pose.POINTING, System.currentTimeMillis())
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
    fun `dispatch pose event for OPEN_PALM returns false`() {
        val event = GestureEvent.PoseTriggered(Pose.OPEN_PALM, System.currentTimeMillis())
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
    fun `dispatch pose event for FIST returns false`() {
        val event = GestureEvent.PoseTriggered(Pose.FIST, System.currentTimeMillis())
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
    fun `dispatch pose event for NONE pose returns false`() {
        val event = GestureEvent.PoseTriggered(Pose.NONE, System.currentTimeMillis())
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

    // ========== attachService / detachService ==========

    @Test
    fun `detachService clears service reference`() {
        // Detach without attach should not crash
        actionDispatcher.detachService()
    }

    // ========== Boundary coordinate values ==========

    @Test
    fun `normalizeToScreenX with values outside 0-1 range is coerced to screen`() {
        val screenWidth = 1080
        // These should be coerced to screen bounds
        val resultNeg = ActionDispatcher.normalizeToScreenX(-0.5f, screenWidth)
        val resultOver = ActionDispatcher.normalizeToScreenX(1.5f, screenWidth)

        assertTrue("Negative X coerced to >= 0", resultNeg >= 0f)
        assertTrue("Over X coerced to <= width", resultOver <= screenWidth.toFloat())
    }

    @Test
    fun `normalizeToScreenY with values outside 0-1 range is coerced to screen`() {
        val screenHeight = 2400
        val resultNeg = ActionDispatcher.normalizeToScreenY(-0.5f, screenHeight)
        val resultOver = ActionDispatcher.normalizeToScreenY(1.5f, screenHeight)

        assertTrue("Negative Y coerced to >= 0", resultNeg >= 0f)
        assertTrue("Over Y coerced to <= height", resultOver <= screenHeight.toFloat())
    }
}
