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
