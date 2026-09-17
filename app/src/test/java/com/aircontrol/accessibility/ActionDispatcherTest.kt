package com.aircontrol.accessibility

import com.aircontrol.data.model.CustomGesture
import com.aircontrol.data.model.GestureMapConfig
import com.aircontrol.data.model.HandPreference
import com.aircontrol.data.model.UserPreferences
import com.aircontrol.data.repository.SettingsRepository
import com.aircontrol.gesture.model.GestureEngineState
import com.aircontrol.gesture.model.GestureEvent
import com.aircontrol.gesture.model.PinchPhase
import com.aircontrol.gesture.model.Pose
import com.aircontrol.gesture.model.SwipeDirection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ActionDispatcherTest {

    private lateinit var actionDispatcher: ActionDispatcher
    private lateinit var mockSettingsRepository: SettingsRepository
    private lateinit var userPreferencesFlow: MutableStateFlow<UserPreferences>

    private class FakeSettingsRepository(
        override val userPreferences: Flow<UserPreferences>,
        override val gestureMapConfig: Flow<GestureMapConfig> = MutableStateFlow(GestureMapConfig()),
        override val customGestures: Flow<List<CustomGesture>> = MutableStateFlow(emptyList()),
    ) : SettingsRepository {
        override suspend fun updateGesturesEnabled(enabled: Boolean) {}
        override suspend fun updateSensitivity(sensitivity: Int) {}
        override suspend fun updateHandPreference(preference: HandPreference) {}
        override suspend fun updateAnalysisFps(fps: Int) {}
        override suspend fun updateCursorEnabled(enabled: Boolean) {}
        override suspend fun updateHapticFeedback(enabled: Boolean) {}
        override suspend fun updateOnboardingCompleted(completed: Boolean) {}
        override suspend fun updateCursorSpeed(speed: Int) {}
        override suspend fun updateHoldDuration(durationMs: Int) {}
        override suspend fun updateBatterySaver(enabled: Boolean) {}
        override suspend fun updateStartOnBoot(enabled: Boolean) {}
        override suspend fun updateStatusPillEnabled(enabled: Boolean) {}
        override suspend fun updateCalibrationData(handSizeMm: Float, pinchDistanceMm: Float) {}
        override suspend fun updateDwellEnabled(enabled: Boolean) {}
        override suspend fun updateDwellDuration(durationMs: Int) {}
        override suspend fun updateStationaryClickEnabled(enabled: Boolean) {}
        override suspend fun updatePalmHomeEnabled(enabled: Boolean) {}
        override suspend fun updateSwipeRequiresOpenHand(enabled: Boolean) {}
        override suspend fun updateInvertScrollDirection(invert: Boolean) {}
        override suspend fun updateSitBackMode(enabled: Boolean) {}
        override suspend fun updateReducedMotion(enabled: Boolean) {}
        override suspend fun updateCursorGain(gain: Int) {}
        override suspend fun updateEyeTrackingEnabled(enabled: Boolean) {}
        override suspend fun updateGazeSensitivity(sensitivity: Int) {}
        override suspend fun updateGazeInvertX(invert: Boolean) {}
        override suspend fun updateBlinkClickEnabled(enabled: Boolean) {}
        override suspend fun updateBlinkWindowMs(durationMs: Int) {}
        override suspend fun updateNativeHidMouseEnabled(enabled: Boolean) {}
        override suspend fun updateGazeCalibration(coeffs: String) {}
        override suspend fun updatePersonalizedGazeCalibration(json: String) {}
        override suspend fun updateGestureAction(key: String, action: String) {}
        override suspend fun resetGestureMapToDefaults() {}
        override suspend fun addCustomGesture(gesture: CustomGesture) {}
        override suspend fun updateCustomGesture(gesture: CustomGesture) {}
        override suspend fun deleteCustomGesture(gestureId: String) {}
        override suspend fun enableCustomGesture(gestureId: String, enabled: Boolean) {}
    }

    @Before
    fun setup() {
        userPreferencesFlow = MutableStateFlow(UserPreferences())
        mockSettingsRepository = FakeSettingsRepository(userPreferencesFlow)
        actionDispatcher = ActionDispatcher(mockSettingsRepository)
        com.aircontrol.ui.Suppression.resetForTest()
    }

    @Test
    fun `normalizeToScreenX at center maps to center of screen`() {
        assertEquals(540f, ActionDispatcher.normalizeToScreenX(0.5f, 1080), 1f)
    }

    @Test
    fun `normalizeToScreenX clamps dead zones to edges`() {
        assertEquals(0f, ActionDispatcher.normalizeToScreenX(0.0f, 1080), 0.01f)
        assertEquals(1080f, ActionDispatcher.normalizeToScreenX(1.0f, 1080), 0.01f)
    }

    @Test
    fun `normalizeToScreenX edge values are within screen bounds`() {
        for (v in listOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f, -0.5f, 1.5f)) {
            val result = ActionDispatcher.normalizeToScreenX(v, 1080)
            assertTrue("X for $v should be in bounds", result >= 0f && result <= 1080f)
        }
    }

    @Test
    fun `normalizeToScreenX rejects nonfinite coordinates`() {
        assertEquals(0f, ActionDispatcher.normalizeToScreenX(Float.NaN, 1080), 0f)
        assertEquals(0f, ActionDispatcher.normalizeToScreenX(Float.POSITIVE_INFINITY, 1080), 0f)
    }

    @Test
    fun `normalizeToScreenX is monotonically increasing`() {
        val values = listOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f).map { ActionDispatcher.normalizeToScreenX(it, 1080) }
        for (i in 0 until values.lastIndex) assertTrue(values[i] <= values[i + 1])
    }

    @Test
    fun `normalizeToScreenY maps active zone center to screen center`() {
        val activeCenter = ActionDispatcher.TOP_DEAD_ZONE + (1f - ActionDispatcher.TOP_DEAD_ZONE) / 2f
        assertEquals(1200f, ActionDispatcher.normalizeToScreenY(activeCenter, 2400), 1f)
    }

    @Test
    fun `normalizeToScreenY clamps top dead zone to top of screen`() {
        assertEquals(0f, ActionDispatcher.normalizeToScreenY(0.0f, 2400), 0.01f)
        assertEquals(0f, ActionDispatcher.normalizeToScreenY(ActionDispatcher.TOP_DEAD_ZONE, 2400), 0.01f)
    }

    @Test
    fun `top dead zone leaves the notification shade reachable`() {
        val y = ActionDispatcher.normalizeToScreenY(0.12f, 2400)
        assertTrue("Y=$y should be inside the top 10% of the screen", y <= 240f)
    }

    @Test
    fun `normalizeToScreenY maps bottom to bottom of screen`() {
        assertEquals(2400f, ActionDispatcher.normalizeToScreenY(1.0f, 2400), 0.01f)
    }

    @Test
    fun `normalizeToScreenY edge values are within screen bounds`() {
        for (v in listOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f, -0.5f, 1.5f)) {
            val result = ActionDispatcher.normalizeToScreenY(v, 2400)
            assertTrue("Y for $v should be in bounds", result >= 0f && result <= 2400f)
        }
    }

    @Test
    fun `normalizeToScreenY rejects nonfinite coordinates`() {
        assertEquals(0f, ActionDispatcher.normalizeToScreenY(Float.NaN, 2400), 0f)
        assertEquals(0f, ActionDispatcher.normalizeToScreenY(Float.NEGATIVE_INFINITY, 2400), 0f)
    }

    @Test
    fun `normalizeToScreenY is monotonically increasing`() {
        val values = listOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f).map { ActionDispatcher.normalizeToScreenY(it, 2400) }
        for (i in 0 until values.lastIndex) assertTrue(values[i] <= values[i + 1])
    }

    @Test
    fun `every action is allowed outside a setup flow`() {
        for (action in GestureAction.values()) {
            assertTrue("$action must be allowed when no setup flow is open", actionDispatcher.actionAllowed(action))
        }
    }

    @Test
    fun `all synthetic actions are suppressed during setup flow`() {
        com.aircontrol.ui.Suppression.acquire()
        try {
            assertTrue("NONE is not a synthetic action", actionDispatcher.actionAllowed(GestureAction.NONE))
            for (action in GestureAction.values().filter { it != GestureAction.NONE }) {
                assertFalse("$action must be suppressed while setup owns the UI", actionDispatcher.actionAllowed(action))
            }
        } finally {
            com.aircontrol.ui.Suppression.release()
        }
        assertTrue("actions resume when the setup flow closes", actionDispatcher.actionAllowed(GestureAction.HOME))
    }

    @Test
    fun `dispatch returns false when service is not attached`() {
        val result = actionDispatcher.dispatch(
            GestureEvent.Swipe(SwipeDirection.LEFT, System.currentTimeMillis()),
            GestureEngineState.ARMED, 0.5f, 0.5f, 1080, 2400,
        )
        assertFalse(result)
    }

    @Test
    fun `dispatch returns false when engine is DISARMED`() {
        val result = actionDispatcher.dispatch(
            GestureEvent.Swipe(SwipeDirection.LEFT, System.currentTimeMillis()),
            GestureEngineState.DISARMED, 0.5f, 0.5f, 1080, 2400,
        )
        assertFalse(result)
    }

    @Test
    fun `dispatch returns false for ARMING state`() {
        val result = actionDispatcher.dispatch(
            GestureEvent.Swipe(SwipeDirection.LEFT, System.currentTimeMillis()),
            GestureEngineState.ARMING, 0.5f, 0.5f, 1080, 2400,
        )
        assertFalse(result)
    }

    @Test
    fun `dispatch returns false for COOLDOWN state`() {
        val result = actionDispatcher.dispatch(
            GestureEvent.Swipe(SwipeDirection.LEFT, System.currentTimeMillis()),
            GestureEngineState.COOLDOWN, 0.5f, 0.5f, 1080, 2400,
        )
        assertFalse(result)
    }

    @Test
    fun `dispatch returns false for Armed event`() {
        val result = actionDispatcher.dispatch(
            GestureEvent.Armed(System.currentTimeMillis()),
            GestureEngineState.ARMED, 0.5f, 0.5f, 1080, 2400,
        )
        assertFalse(result)
    }

    @Test
    fun `dispatch returns false for Disarmed event`() {
        val result = actionDispatcher.dispatch(
            GestureEvent.Disarmed(System.currentTimeMillis()),
            GestureEngineState.DISARMED, 0.5f, 0.5f, 1080, 2400,
        )
        assertFalse(result)
    }

    @Test
    fun `dispatch returns false for CursorMoved event`() {
        val result = actionDispatcher.dispatch(
            GestureEvent.CursorMoved(0.5f, 0.5f, System.currentTimeMillis()),
            GestureEngineState.ARMED, 0.5f, 0.5f, 1080, 2400,
        )
        assertFalse(result)
    }

    @Test
    fun `getGestureMap returns default map`() {
        val map = actionDispatcher.getGestureMap()
        assertEquals(GestureMapConfig.defaultEntries().size, map.size)
        assertEquals(GestureAction.SCROLL_RIGHT, map[ActionDispatcher.KEY_SWIPE_RIGHT])
        assertEquals(GestureAction.TAP, map[ActionDispatcher.KEY_POSE_PINCH])
        assertEquals(GestureAction.NONE, map[ActionDispatcher.KEY_POSE_THREE_FINGERS])
    }

    @Test
    fun `updateGestureAction updates the gesture map`() {
        actionDispatcher.updateGestureAction(ActionDispatcher.KEY_SWIPE_LEFT, GestureAction.HOME)
        assertEquals(GestureAction.HOME, actionDispatcher.getGestureMap()[ActionDispatcher.KEY_SWIPE_LEFT])
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
        assertEquals(GestureAction.NONE, actionDispatcher.getGestureMap()[ActionDispatcher.KEY_POSE_PINCH])
    }

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

    @Test
    fun `dispatch pinch START is accepted without service`() {
        val result = actionDispatcher.dispatch(
            GestureEvent.Pinch(PinchPhase.START, 0.5f, 0.5f, System.currentTimeMillis()),
            GestureEngineState.ARMED, 0.5f, 0.5f, 1080, 2400,
        )
        assertTrue(result)
    }

    @Test
    fun `dispatch pose event returns false without service for NONE action`() {
        val result = actionDispatcher.dispatch(
            GestureEvent.PoseTriggered(Pose.POINTING, System.currentTimeMillis()),
            GestureEngineState.ARMED, 0.5f, 0.5f, 1080, 2400,
        )
        assertFalse(result)
    }

    @Test
    fun `dispatch pose event for OPEN_PALM returns false`() {
        val result = actionDispatcher.dispatch(
            GestureEvent.PoseTriggered(Pose.OPEN_PALM, System.currentTimeMillis()),
            GestureEngineState.ARMED, 0.5f, 0.5f, 1080, 2400,
        )
        assertFalse(result)
    }

    @Test
    fun `dispatch pose event for FIST returns false`() {
        val result = actionDispatcher.dispatch(
            GestureEvent.PoseTriggered(Pose.FIST, System.currentTimeMillis()),
            GestureEngineState.ARMED, 0.5f, 0.5f, 1080, 2400,
        )
        assertFalse(result)
    }

    @Test
    fun `dispatch pose event for NONE pose returns false`() {
        val result = actionDispatcher.dispatch(
            GestureEvent.PoseTriggered(Pose.NONE, System.currentTimeMillis()),
            GestureEngineState.ARMED, 0.5f, 0.5f, 1080, 2400,
        )
        assertFalse(result)
    }

    @Test
    fun `detachService clears service reference`() {
        actionDispatcher.detachService()
    }

    @Test
    fun `normalizeToScreenX with values outside 0-1 range is coerced to screen`() {
        assertTrue(ActionDispatcher.normalizeToScreenX(-0.5f, 1080) >= 0f)
        assertTrue(ActionDispatcher.normalizeToScreenX(1.5f, 1080) <= 1080f)
    }

    @Test
    fun `normalizeToScreenY with values outside 0-1 range is coerced to screen`() {
        assertTrue(ActionDispatcher.normalizeToScreenY(-0.5f, 2400) >= 0f)
        assertTrue(ActionDispatcher.normalizeToScreenY(1.5f, 2400) <= 2400f)
    }
}
