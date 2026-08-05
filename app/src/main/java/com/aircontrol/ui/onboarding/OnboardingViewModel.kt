package com.aircontrol.ui.onboarding

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aircontrol.data.repository.SettingsRepository
import com.aircontrol.permissions.PermissionsManager
import com.aircontrol.permissions.PermissionStates
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    val permissionsManager: PermissionsManager,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    companion object {
        private const val KEY_CURRENT_STEP = "current_step"
    }

    val permissionStates: StateFlow<PermissionStates> = permissionsManager.permissionStates

    private val _currentStep = savedStateHandle.getStateFlow(KEY_CURRENT_STEP, 0)
    val currentStep: StateFlow<Int> = _currentStep

    fun setCurrentStep(step: Int) {
        Timber.d("Onboarding step changed to: %d", step)
        savedStateHandle[KEY_CURRENT_STEP] = step
    }

    fun requestCameraPermission() {
        permissionsManager.requestCameraPermission()
    }

    fun updateCameraGranted(granted: Boolean) {
        permissionsManager.updateCameraGranted(granted)
    }

    fun refreshPermissions() {
        permissionsManager.refreshAllPermissions()
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            settingsRepository.updateOnboardingCompleted(true)
            Timber.i("Onboarding completed")
        }
    }
}
