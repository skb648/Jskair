package com.aircontrol.accessibility

import com.aircontrol.data.model.UserPreferences
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
import org.mockito.kotlin.mock

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

    @Test
    fun `normalizeToScreenX at center maps to roughly center of screen`() {
        val screenWidth = 1080
        val result = actionDispatcher.normalizeToScreenX(0.5f, screenWidth)
        // Mirrored: 1 - 0.5 = 0.5, then with margin: 0.1 + 0.5 * 0.8 = 0.5
        // 0.5 * 1080 = 540
        assertEquals(540f, result, 1f)
    }

    @Test
    fun `normalizeToScreenX applies mirroring`() {
        val screenWidth = 1080
        // normX = 0.0 -> mirrored = 1.0 -> expanded = 0.1 + 1.0 * 0.8 = 0.9 -> 972
        val result0 = actionDispatcher.normalizeToScreenX(0.0f, screenWidth)
        // normX = 1.0 -> mirrored = 0.0 -> expanded = 0.1 + 0.0 * 0.8 = 0.1 -> 108
        val result1 = actionDispatcher.normalizeToScreenX(1.0f, screenWidth)

        // Mirroring means 0 maps to the right side and 1 maps to the left side
        assertTrue("normX=0 should map to right side", result0 > screenWidth / 2f)
        assertTrue("normX=1 should map to left side", result1 < screenWidth / 2f)
    }

    @Test
    fun `normalizeToScreenX edge values are within screen bounds`() {
        val screenWidth = 1080
        val result0 = actionDispatcher.normalizeToScreenX(0.0f, screenWidth)
        val result1 = actionDispatcher.normalizeToScreenX(1.0f, screenWidth)

        assertTrue("X result should be >= 0 for normX=0", result0 >= 0f)
        assertTrue("X result should be <= screenWidth for normX=0", result0 <= screenWidth.toFloat())
        assertTrue("X result should be >= 0 for normX=1", result1 >= 0f)
        assertTrue("X result should be <= screenWidth for normX=1", result1 <= screenWidth.toFloat())
    }

    @Test
    fun `normalizeToScreenX with margin expansion makes corners reachable`() {
        val screenWidth = 1080
        // With margin fraction 0.1:
        // normX = 0 -> mirrored = 1.0 -> expanded = 0.1 + 1.0 * 0.8 = 0.9 -> 972
        val result0 = actionDispatcher.normalizeToScreenX(0.0f, screenWidth)
        assertTrue("Edge should be within 10% margin", result0 > screenWidth * 0.8f)

        // normX = 1 -> mirrored = 0.0 -> expanded = 0.1 + 0.0 * 0.8 = 0.1 -> 108
        val result1 = actionDispatcher.normalizeToScreenX(1.0f, screenWidth)
        assertTrue("Edge should be within 10% margin", result1 < screenWidth * 0.2f)
    }

    @Test
    fun `normalizeToScreenX is monotonic when accounting for mirror`() {
        val screenWidth = 1080
        // Due to mirroring, increasing normX decreases the screen position
        val result0 = actionDispatcher.normalizeToScreenX(0.0f, screenWidth)
        val result25 = actionDispatcher.normalizeToScreenX(0.25f, screenWidth)
        val result50 = actionDispatcher.normalizeToScreenX(0.5f, screenWidth)
        val result75 = actionDispatcher.normalizeToScreenX(0.75f, screenWidth)
        val result100 = actionDispatcher.normalizeToScreenX(1.0f, screenWidth)

        assertTrue("Should be decreasing due to mirror", result0 > result25)
        assertTrue("Should be decreasing due to mirror", result25 > result50)
        assertTrue("Should be decreasing due to mirror", result50 > result75)
        assertTrue("Should be decreasing due to mirror", result75 > result100)
    }

    // ========== normalizeToScreenY ==========

    @Test
    fun `normalizeToScreenY at center maps to roughly center of screen`() {
        val screenHeight = 2400
        val result = actionDispatcher.normalizeToScreenY(0.5f, screenHeight)
        // No mirror for Y: 0.1 + 0.5 * 0.8 = 0.5 -> 1200
        assertEquals(1200f, result, 1f)
    }

    @Test
    fun `normalizeToScreenY does NOT apply mirroring`() {
        val screenHeight = 2400
        // normY = 0.0 -> expanded = 0.1 + 0.0 * 0.8 = 0.1 -> 240
        val result0 = actionDispatcher.normalizeToScreenY(0.0f, screenHeight)
        // normY = 1.0 -> expanded = 0.1 + 1.0 * 0.8 = 0.9 -> 2160
        val result1 = actionDispatcher.normalizeToScreenY(1.0f, screenHeight)

        // No mirroring: 0 maps to top, 1 maps to bottom
        assertTrue("normY=0 should map to top", result0 < screenHeight / 2f)
        assertTrue("normY=1 should map to bottom", result1 > screenHeight / 2f)
    }

    @Test
    fun `normalizeToScreenY edge values are within screen bounds`() {
        val screenHeight = 2400
        val result0 = actionDispatcher.normalizeToScreenY(0.0f, screenHeight)
        val result1 = actionDispatcher.normalizeToScreenY(1.0f, screenHeight)

        assertTrue("Y result should be >= 0 for normY=0", result0 >= 0f)
        assertTrue("Y result should be <= screenHeight for normY=0", result0 <= screenHeight.toFloat())
        assertTrue("Y result should be >= 0 for normY=1", result1 >= 0f)
        assertTrue("Y result should be <= screenHeight for normY=1", result1 <= screenHeight.toFloat())
    }

    @Test
    fun `normalizeToScreenY is monotonically increasing`() {
        val screenHeight = 2400
        val result0 = actionDispatcher.normalizeToScreenY(0.0f, screenHeight)
        val result25 = actionDispatcher.normalizeToScreenY(0.25f, screenHeight)
        val result50 = actionDispatcher.normalizeToScreenY(0.5f, screenHeight)
        val result75 = actionDispatcher.normalizeToScreenY(0.75f, screenHeight)
        val result100 = actionDispatcher.normalizeToScreenY(1.0f, screenHeight)

        assertTrue("Y should be monotonically increasing", result0 < result25)
        assertTrue("Y should be monotonically increasing", result25 < result50)
        assertTrue("Y should be monotonically increasing", result50 < result75)
        assertTrue("Y should be monotonically increasing", result75 < result100)
    }

    // ========== Coordinate mapping specific values ==========

    @Test
    fun `normalizeToScreenX 0,5 on 1080 screen gives 540`() {
        // 1 - 0.5 = 0.5, 0.1 + 0.5 * 0.8 = 0.5, 0.5 * 1080 = 540
        val result = actionDispatcher.normalizeToScreenX(0.5f, 1080)
        assertEquals(540f, result, 0.01f)
    }

    @Test
    fun `normalizeToScreenY 0,5 on 2400 screen gives 1200`() {
        // 0.1 + 0.5 * 0.8 = 0.5, 0.5 * 2400 = 1200
        val result = actionDispatcher.normalizeToScreenY(0.5f, 2400)
        assertEquals(1200f, result, 0.01f)
    }

    @Test
    fun `normalizeToScreenX at 0 maps to 0_9 of screen width`() {
        val screenWidth = 1080
        // 1 - 0 = 1.0, 0.1 + 1.0 * 0.8 = 0.9, 0.9 * 1080 = 972
        val result = actionDispatcher.normalizeToScreenX(0.0f, screenWidth)
        assertEquals(972f, result, 0.01f)
    }

    @Test
    fun `normalizeToScreenY at 0 maps to 0_1 of screen height`() {
        val screenHeight = 2400
        // 0.1 + 0.0 * 0.8 = 0.1, 0.1 * 2400 = 240
        val result = actionDispatcher.normalizeToScreenY(0.0f, screenHeight)
        assertEquals(240f, result, 0.01f)
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
        assertEquals(9, map.size)
        assertEquals(GestureAction.SCROLL_LEFT, map[ActionDispatcher.KEY_SWIPE_RIGHT])
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
    fun `dispatch pinch START returns false without service`() {
        val event = GestureEvent.Pinch(PinchPhase.START, 0.5f, 0.5f, System.currentTimeMillis())
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
        val resultNeg = actionDispatcher.normalizeToScreenX(-0.5f, screenWidth)
        val resultOver = actionDispatcher.normalizeToScreenX(1.5f, screenWidth)

        assertTrue("Negative X coerced to >= 0", resultNeg >= 0f)
        assertTrue("Over X coerced to <= width", resultOver <= screenWidth.toFloat())
    }

    @Test
    fun `normalizeToScreenY with values outside 0-1 range is coerced to screen`() {
        val screenHeight = 2400
        val resultNeg = actionDispatcher.normalizeToScreenY(-0.5f, screenHeight)
        val resultOver = actionDispatcher.normalizeToScreenY(1.5f, screenHeight)

        assertTrue("Negative Y coerced to >= 0", resultNeg >= 0f)
        assertTrue("Over Y coerced to <= height", resultOver <= screenHeight.toFloat())
    }
}
