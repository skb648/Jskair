package com.aircontrol.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aircontrol.accessibility.ActionDispatcher
import com.aircontrol.camera.CameraService
import com.aircontrol.data.model.UserPreferences
import com.aircontrol.data.repository.SettingsRepository
import com.aircontrol.permissions.PermissionsManager
import com.aircontrol.permissions.PermissionStates
import com.aircontrol.tracking.HandTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** Service state for the home screen hero card. */
enum class ServiceState {
    ACTIVE,
    PAUSED,
    OFF,
}

/** Session statistics tracked during active gesture control. */
data class SessionStats(
    val gesturesExecuted: Int = 0,
    val uptimeSeconds: Long = 0L,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val permissionsManager: PermissionsManager,
    private val serviceManager: com.aircontrol.service.CameraServiceManager,
    private val handTracker: HandTracker,
    @ApplicationContext private val appContext: Context,
    private val actionDispatcher: ActionDispatcher,
) : ViewModel() {

    val userPreferences: StateFlow<UserPreferences> = settingsRepository.userPreferences
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = UserPreferences(),
        )

    val permissionStates: StateFlow<PermissionStates> = permissionsManager.permissionStates

    val serviceState: StateFlow<ServiceState> = combine(
        settingsRepository.userPreferences,
        permissionsManager.permissionStates,
        CameraService.isRunning,
        CameraService.isPaused,
    ) { prefs, perms, isRunning, isPaused ->
        when {
            !prefs.gesturesEnabled -> ServiceState.OFF
            !perms.allGranted -> ServiceState.OFF
            !isRunning -> ServiceState.OFF
            isPaused -> ServiceState.PAUSED
            else -> ServiceState.ACTIVE
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ServiceState.OFF,
    )

    // FIXED: Real hand detection instead of fake serviceState == ACTIVE
    private val _handDetected = MutableStateFlow(false)
    val handDetected: StateFlow<Boolean> = _handDetected

    private var _sessionStats = MutableStateFlow(SessionStats())
    val sessionStats: StateFlow<SessionStats> = _sessionStats

    private var uptimeJob: Job? = null
    private var handCollectJob: Job? = null

    init {
        viewModelScope.launch {
            CameraService.isRunning.collect { running ->
                if (running) {
                    startUptimeTimer()
                    startHandDetectionCollection()
                } else {
                    stopUptimeTimer()
                    stopHandDetectionCollection()
                    _sessionStats.value = SessionStats()
                    _handDetected.value = false
                }
            }
        }

        viewModelScope.launch {
            actionDispatcher.dispatchedEvents.collect {
                _sessionStats.value = _sessionStats.value.copy(
                    gesturesExecuted = _sessionStats.value.gesturesExecuted + 1,
                )
            }
        }
    }

    private fun startHandDetectionCollection() {
        handCollectJob?.cancel()
        handCollectJob = viewModelScope.launch {
            handTracker.handFrames.collect { frame ->
                _handDetected.value = frame.isDetected
            }
        }
    }

    private fun stopHandDetectionCollection() {
        handCollectJob?.cancel()
        handCollectJob = null
        _handDetected.value = false
    }

    private fun startUptimeTimer() {
        stopUptimeTimer()
        uptimeJob = viewModelScope.launch {
            while (true) {
                delay(1000L)
                _sessionStats.value = _sessionStats.value.copy(
                    uptimeSeconds = _sessionStats.value.uptimeSeconds + 1,
                )
            }
        }
    }

    private fun stopUptimeTimer() {
        uptimeJob?.cancel()
        uptimeJob = null
    }

    fun toggleGestures(enabled: Boolean) {
        Timber.d("Toggling gestures: %s", enabled)
        viewModelScope.launch {
            permissionsManager.refreshAllPermissions()
            kotlinx.coroutines.yield()
            val perms = permissionStates.value

            if (enabled && !perms.allGranted) {
                Timber.w(
                    "Cannot start tracking: missing permissions camera=%s accessibility=%s",
                    perms.cameraGranted,
                    perms.accessibilityGranted,
                )
                settingsRepository.updateGesturesEnabled(false)
                serviceManager.stopTracking()
                return@launch
            }

            settingsRepository.updateGesturesEnabled(enabled)
            if (enabled) {
                serviceManager.startTracking()
            } else {
                serviceManager.stopTracking()
            }
        }
    }

    fun toggleCursorMode(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateCursorEnabled(enabled)
        }
    }

    fun toggleHapticFeedback(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateHapticFeedback(enabled)
        }
    }

    fun toggleBatterySaver(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateBatterySaver(enabled)
        }
    }

    fun refreshPermissions() {
        permissionsManager.refreshAllPermissions()
        syncTrackingServiceWithSettings()
    }

    override fun onCleared() {
        super.onCleared()
        stopUptimeTimer()
        stopHandDetectionCollection()
    }

    private fun syncTrackingServiceWithSettings() {
        viewModelScope.launch {
            val prefs = userPreferences.value
            val perms = permissionStates.value
            when {
                prefs.gesturesEnabled && perms.allGranted && !serviceManager.isTracking() -> {
                    serviceManager.startTracking()
                }
                (!prefs.gesturesEnabled || !perms.allGranted) && serviceManager.isTracking() -> {
                    serviceManager.stopTracking()
                }
            }
        }
    }
}
