package com.aircontrol.data.repository

import com.aircontrol.data.model.CustomGesture
import com.aircontrol.data.model.GestureMapConfig
import com.aircontrol.data.model.HandPreference
import com.aircontrol.data.model.UserPreferences
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {

    val userPreferences: Flow<UserPreferences>

    suspend fun updateGesturesEnabled(enabled: Boolean)

    suspend fun updateSensitivity(sensitivity: Int)

    suspend fun updateHandPreference(preference: HandPreference)

    suspend fun updateAnalysisFps(fps: Int)

    suspend fun updateCursorEnabled(enabled: Boolean)

    suspend fun updateHapticFeedback(enabled: Boolean)

    suspend fun updateOnboardingCompleted(completed: Boolean)

    suspend fun updateCursorSpeed(speed: Int)

    suspend fun updateHoldDuration(durationMs: Int)

    suspend fun updateBatterySaver(enabled: Boolean)

    suspend fun updateStartOnBoot(enabled: Boolean)

    suspend fun updateStatusPillEnabled(enabled: Boolean)

    suspend fun updateCalibrationData(handSizeMm: Float, pinchDistanceMm: Float)

    suspend fun updateDwellEnabled(enabled: Boolean)

    suspend fun updateDwellDuration(durationMs: Int)

    suspend fun updateStationaryClickEnabled(enabled: Boolean)

    suspend fun updatePalmHomeEnabled(enabled: Boolean)

    /** Fix A-11: swipes only register while an open palm is held. */
    suspend fun updateSwipeRequiresOpenHand(enabled: Boolean)

    suspend fun updateSitBackMode(enabled: Boolean)

    suspend fun updateReducedMotion(enabled: Boolean)

    suspend fun updateCursorGain(gain: Int)

    suspend fun updateEyeTrackingEnabled(enabled: Boolean)

    suspend fun updateGazeSensitivity(sensitivity: Int)

    suspend fun updateGazeInvertX(invert: Boolean)

    suspend fun updateBlinkClickEnabled(enabled: Boolean)

    /** Fix A10: user-tunable minimum blink duration (150–500ms). */
    suspend fun updateBlinkWindowMs(durationMs: Int)

    suspend fun updateGazeCalibration(coeffs: String)

    /** Fix A5: persists/clears the serialized personalized gaze calibration model. */
    suspend fun updatePersonalizedGazeCalibration(json: String)

    // Gesture map
    val gestureMapConfig: Flow<GestureMapConfig>

    suspend fun updateGestureAction(key: String, action: String)

    suspend fun resetGestureMapToDefaults()

    // Custom gestures
    val customGestures: Flow<List<CustomGesture>>

    suspend fun addCustomGesture(gesture: CustomGesture)

    suspend fun updateCustomGesture(gesture: CustomGesture)

    suspend fun deleteCustomGesture(gestureId: String)

    suspend fun enableCustomGesture(gestureId: String, enabled: Boolean)
}
