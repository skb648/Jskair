package com.aircontrol.ui.settings

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aircontrol.camera.CameraService
import com.aircontrol.data.model.HandPreference
import com.aircontrol.data.model.UserPreferences
import com.aircontrol.data.repository.SettingsRepository
import com.aircontrol.permissions.PermissionsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val permissionsManager: PermissionsManager,
    // M-01 Fix: Use centralized CameraServiceManager instead of duplicated start/stop logic
    private val serviceManager: com.aircontrol.service.CameraServiceManager,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    val userPreferences: StateFlow<UserPreferences> = settingsRepository.userPreferences
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = UserPreferences(),
        )

    fun updateGesturesEnabled(enabled: Boolean) {
        viewModelScope.launch {
            permissionsManager.refreshAllPermissions()
            val perms = permissionsManager.permissionStates.value
            if (enabled && !perms.allGranted) {
                Timber.w("Cannot enable gestures from settings: required permissions missing")
                settingsRepository.updateGesturesEnabled(false)
                serviceManager.stopTracking()
                return@launch
            }

            settingsRepository.updateGesturesEnabled(enabled)
            if (enabled) serviceManager.startTracking() else serviceManager.stopTracking()
        }
    }

    fun updateSensitivity(sensitivity: Int) {
        viewModelScope.launch {
            settingsRepository.updateSensitivity(sensitivity)
        }
    }

    fun updateHandPreference(preference: HandPreference) {
        viewModelScope.launch {
            settingsRepository.updateHandPreference(preference)
        }
    }

    fun updateAnalysisFps(fps: Int) {
        viewModelScope.launch {
            settingsRepository.updateAnalysisFps(fps)
        }
    }

    fun updateCursorEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateCursorEnabled(enabled)
        }
    }

    fun updateHapticFeedback(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateHapticFeedback(enabled)
        }
    }

    fun updateCursorSpeed(speed: Int) {
        viewModelScope.launch {
            settingsRepository.updateCursorSpeed(speed)
        }
    }

    fun updateHoldDuration(durationMs: Int) {
        viewModelScope.launch {
            settingsRepository.updateHoldDuration(durationMs)
        }
    }

    fun updateBatterySaver(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateBatterySaver(enabled)
        }
    }

    fun updateStartOnBoot(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateStartOnBoot(enabled)
        }
    }

    fun updateStatusPillEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateStatusPillEnabled(enabled)
        }
    }

    fun updateDwellEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateDwellEnabled(enabled)
        }
    }

    fun updateDwellDuration(durationMs: Int) {
        viewModelScope.launch {
            settingsRepository.updateDwellDuration(durationMs)
        }
    }

    fun updateStationaryClickEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateStationaryClickEnabled(enabled)
        }
    }

    fun updatePalmHomeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updatePalmHomeEnabled(enabled)
        }
    }

    fun updateSitBackMode(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateSitBackMode(enabled)
        }
    }

    fun updateReducedMotion(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateReducedMotion(enabled)
        }
    }

    fun updateCursorGain(gain: Int) {
        viewModelScope.launch {
            settingsRepository.updateCursorGain(gain)
        }
    }

    fun updateEyeTrackingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateEyeTrackingEnabled(enabled)
        }
    }

    fun updateGazeSensitivity(sensitivity: Int) {
        viewModelScope.launch {
            settingsRepository.updateGazeSensitivity(sensitivity)
        }
    }

    fun updateGazeInvertX(invert: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateGazeInvertX(invert)
        }
    }

    fun updateBlinkClickEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateBlinkClickEnabled(enabled)
        }
    }

    fun updateGazeCalibration(coeffs: String) {
        viewModelScope.launch {
            settingsRepository.updateGazeCalibration(coeffs)
        }
    }
}
