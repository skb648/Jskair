package com.aircontrol.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aircontrol.data.model.UserPreferences
import com.aircontrol.data.repository.SettingsRepository
import com.aircontrol.permissions.PermissionsManager
import com.aircontrol.permissions.PermissionStates
import dagger.hilt.android.lifecycle.HiltViewModel
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
) : ViewModel() {

    val userPreferences: StateFlow<UserPreferences> = settingsRepository.userPreferences
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = UserPreferences(),
        )

    val permissionStates: StateFlow<PermissionStates> = permissionsManager.permissionStates

    /** Derived service state based on preferences + permissions. */
    val serviceState: StateFlow<ServiceState> = combine(
        settingsRepository.userPreferences,
        permissionsManager.permissionStates,
    ) { prefs, perms ->
        when {
            !prefs.gesturesEnabled -> ServiceState.OFF
            !perms.allGranted -> ServiceState.OFF
            else -> ServiceState.ACTIVE
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ServiceState.OFF,
    )

    private var _sessionStats = kotlinx.coroutines.flow.MutableStateFlow(SessionStats())
    val sessionStats: StateFlow<SessionStats> = _sessionStats

    fun toggleGestures(enabled: Boolean) {
        Timber.d("Toggling gestures: %s", enabled)
        viewModelScope.launch {
            settingsRepository.updateGesturesEnabled(enabled)
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
    }

    fun incrementGestureCount() {
        _sessionStats.value = _sessionStats.value.copy(
            gesturesExecuted = _sessionStats.value.gesturesExecuted + 1,
        )
    }
}
