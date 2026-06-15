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
            // Refresh permissions and wait for the combine flow to propagate
            permissionsManager.refreshAllPermissions()
            // Give the combine flow a chance to propagate the updated values
            kotlinx.coroutines.delay(100)
            val perms = permissionStates.value

            if (enabled && !perms.allGranted) {
                Timber.w(
                    "Cannot start tracking: missing permissions camera=%s accessibility=%s overlay=%s",
                    perms.cameraGranted,
                    perms.accessibilityGranted,
                    perms.overlayGranted,
                )
                settingsRepository.updateGesturesEnabled(false)
                stopTrackingService()
                return@launch
            }

            settingsRepository.updateGesturesEnabled(enabled)
            if (enabled) {
                startTrackingService()
            } else {
                stopTrackingService()
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
                prefs.gesturesEnabled && perms.allGranted && !CameraService.isRunning.value -> {
                    startTrackingService()
                }
                (!prefs.gesturesEnabled || !perms.allGranted) && CameraService.isRunning.value -> {
                    stopTrackingService()
                }
            }
        }
    }

    // TODO: Extract duplicated service start/stop logic into a shared ServiceManager
    private fun startTrackingService() {
        runCatching {
            val intent = Intent(appContext, CameraService::class.java).apply {
                action = CameraService.ACTION_START
            }
            ContextCompat.startForegroundService(appContext, intent)
            Timber.i("Camera tracking foreground service start requested")
        }.onFailure { error ->
            Timber.e(error, "Failed to start CameraService")
        }
    }

    // TODO: Extract duplicated service start/stop logic into a shared ServiceManager
    private fun stopTrackingService() {
        runCatching {
            val intent = Intent(appContext, CameraService::class.java).apply {
                action = CameraService.ACTION_STOP
            }
            appContext.startService(intent)
            Timber.i("Camera tracking foreground service stop requested")
        }.onFailure { error ->
            Timber.e(error, "Failed to stop CameraService")
        }
    }
}
