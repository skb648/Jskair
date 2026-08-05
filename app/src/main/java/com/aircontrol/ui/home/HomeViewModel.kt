package com.aircontrol.ui.home

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aircontrol.camera.CameraService
import com.aircontrol.data.model.UserPreferences
import com.aircontrol.data.repository.SettingsRepository
import com.aircontrol.permissions.PermissionsManager
import com.aircontrol.permissions.PermissionStates
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

    val permissionStates: StateFlow<PermissionStates> = permissionsManager.permissionStates

    /**
     * Real service state. The old implementation treated "preference enabled +
     * permissions granted" as ACTIVE even if CameraService was never started —
     * that is why UI could say active while Android's camera green dot never
     * appeared. This now reflects the actual foreground service state.
     */
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

    private var _sessionStats = MutableStateFlow(SessionStats())
    val sessionStats: StateFlow<SessionStats> = _sessionStats

    private var uptimeJob: Job? = null

    init {
        // Start uptime timer when service is running
        viewModelScope.launch {
            CameraService.isRunning.collect { running ->
                if (running) {
                    startUptimeTimer()
                } else {
                    stopUptimeTimer()
                    _sessionStats.value = SessionStats()
                }
            }
        }
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
            // Refresh permissions and read the updated state
            permissionsManager.refreshAllPermissions()
            // L-05 Fix: Replace fragile delay(100) with yield() to allow flow
            // propagation. The old code used delay(100) which was unreliable.
            // yield() ensures the coroutine dispatcher processes pending work
            // (including StateFlow updates from refreshAllPermissions) before
            // we read the value. This is deterministic, not time-based.
            kotlinx.coroutines.yield()
            val perms = permissionStates.value

            if (enabled && !perms.allGranted) {
                Timber.w(
                    "Cannot start tracking: missing permissions camera=%s accessibility=%s overlay=%s",
                    perms.cameraGranted,
                    perms.accessibilityGranted,
                    perms.overlayGranted,
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

    fun incrementGestureCount() {
        _sessionStats.value = _sessionStats.value.copy(
            gesturesExecuted = _sessionStats.value.gesturesExecuted + 1,
        )
    }

    override fun onCleared() {
        super.onCleared()
        stopUptimeTimer()
    }

    /**
     * If the user had gestures enabled but Android killed the service, restart
     * it when Home opens. If permissions were revoked, stop it and mark disabled.
     */
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
