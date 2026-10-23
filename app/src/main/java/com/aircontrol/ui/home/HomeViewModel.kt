package com.aircontrol.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aircontrol.accessibility.ActionDispatcher
import com.aircontrol.accessibility.GestureControlAccessibilityService
import com.aircontrol.camera.CameraService
import com.aircontrol.control.CursorController
import com.aircontrol.control.CursorState
import com.aircontrol.data.model.CursorResponseProfile
import com.aircontrol.data.model.UserPreferences
import com.aircontrol.data.repository.SettingsRepository
import com.aircontrol.gesture.model.GestureEngineState
import com.aircontrol.gesture.model.Pose
import com.aircontrol.gestures.GestureDetector
import com.aircontrol.permissions.PermissionsManager
import com.aircontrol.permissions.PermissionStates
import com.aircontrol.runtime.PerfTelemetry
import com.aircontrol.tracking.FaceTracker
import com.aircontrol.tracking.HandTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

/** Real-time pipeline telemetry exposed to the Home HUD. */
data class LivePipelineTelemetry(
    val activeDelegate: String = "UNINITIALIZED",
    val actualFps: Int = 0,
    val configuredFps: Int = 24,
    val handResultFps: Int = 0,
    val faceResultFps: Int = 0,
    val inferenceMs: Long = 0L,
    val photonToCursorMs: Long = 0L,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val permissionsManager: PermissionsManager,
    private val serviceManager: com.aircontrol.service.CameraServiceManager,
    private val handTracker: HandTracker,
    private val faceTracker: FaceTracker,
    private val gestureDetector: GestureDetector,
    private val cursorController: CursorController,
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

    /**
     * Fix (audit #23): "Enabled" vs "Ready". True only when eye tracking is
     * enabled AND the gaze pipeline is actually detecting a face right now —
     * the Home screen shows this so a still cursor is diagnosable at a glance.
     */
    val gazeTracking: StateFlow<Boolean> = combine(
        settingsRepository.userPreferences,
        GestureControlAccessibilityService.gazeTracking,
    ) { prefs, tracking ->
        prefs.gesturesEnabled && prefs.eyeTrackingEnabled && tracking
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

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

    private val _liveLandmarks = MutableStateFlow<List<Pair<Float, Float>>>(emptyList())
    val liveLandmarks: StateFlow<List<Pair<Float, Float>>> = _liveLandmarks.asStateFlow()

    private val _trackedHands = MutableStateFlow<List<com.aircontrol.ui.components.HudHandTrack>>(emptyList())
    val trackedHands: StateFlow<List<com.aircontrol.ui.components.HudHandTrack>> = _trackedHands.asStateFlow()

    val engineState: StateFlow<GestureEngineState> = gestureDetector.engineState
    val currentPose: StateFlow<Pose> = gestureDetector.currentPose
    val pinchProximity: StateFlow<Float> = gestureDetector.pinchProximity
    val cursorState: StateFlow<CursorState> = cursorController.cursorState

    private val _liveTelemetry = MutableStateFlow(LivePipelineTelemetry())
    val liveTelemetry: StateFlow<LivePipelineTelemetry> = _liveTelemetry.asStateFlow()

    private val _lastActionLabel = MutableStateFlow("")
    val lastActionLabel: StateFlow<String> = _lastActionLabel.asStateFlow()

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
                    _liveLandmarks.value = emptyList()
                    _trackedHands.value = emptyList()
                    _lastActionLabel.value = ""
                }
            }
        }

        viewModelScope.launch {
            actionDispatcher.dispatchedEvents.collect { actionName ->
                _sessionStats.value = _sessionStats.value.copy(
                    gesturesExecuted = _sessionStats.value.gesturesExecuted + 1,
                )
                _lastActionLabel.value = actionName.replace('_', ' ')
            }
        }
    }

    private fun startHandDetectionCollection() {
        handCollectJob?.cancel()
        handCollectJob = viewModelScope.launch {
            combine(
                handTracker.handFrames,
                gestureDetector.perHandStates,
            ) { frame, perHandMap ->
                frame to perHandMap
            }.collect { (frame, perHandMap) ->
                _handDetected.value = frame.isDetected
                _liveLandmarks.value = if (frame.isDetected && frame.landmarks.size >= 21) {
                    frame.landmarks.map { (1f - it.x).coerceIn(0f, 1f) to it.y.coerceIn(0f, 1f) }
                } else {
                    emptyList()
                }
                _trackedHands.value = frame.validHands.map { hand ->
                    val snapshot = perHandMap[hand.trackId]
                    val label = when (hand.handedness) {
                        com.aircontrol.tracking.Handedness.LEFT -> "LEFT"
                        com.aircontrol.tracking.Handedness.RIGHT -> "RIGHT"
                        com.aircontrol.tracking.Handedness.UNKNOWN -> "HAND"
                    }
                    com.aircontrol.ui.components.HudHandTrack(
                        trackId = hand.trackId,
                        handedness = label,
                        confidence = hand.confidence,
                        isValid = hand.isValid,
                        isPrimaryPointer = snapshot?.isPrimaryPointer ?: (hand.trackId == frame.primaryTrackId),
                        landmarks = hand.landmarks.map { (1f - it.x).coerceIn(0f, 1f) to it.y.coerceIn(0f, 1f) },
                        pinchProximity = snapshot?.pinchProximity ?: 0f,
                    )
                }
            }
        }
    }

    private fun stopHandDetectionCollection() {
        handCollectJob?.cancel()
        handCollectJob = null
        _handDetected.value = false
        _liveLandmarks.value = emptyList()
        _trackedHands.value = emptyList()
    }

    private fun startUptimeTimer() {
        stopUptimeTimer()
        uptimeJob = viewModelScope.launch {
            while (true) {
                delay(500L)
                val snap = PerfTelemetry.snapshot()
                val prefs = userPreferences.value
                val eyeMode = prefs.eyeTrackingEnabled
                // Truthful delegate reporting: report actual initialized tracker delegate or UNINITIALIZED (never guess "GPU").
                val delegate = when {
                    eyeMode && faceTracker.isInitialized() -> faceTracker.activeDelegate
                    handTracker.isInitialized() -> handTracker.activeDelegate
                    eyeMode -> snap.faceDelegate
                    else -> snap.handDelegate
                }
                val inferMs = if (eyeMode && snap.faceInferAvgMs > 0L) {
                    snap.faceInferAvgMs
                } else {
                    snap.handInferAvgMs
                }
                val stageAgeMs = when {
                    snap.cursorDispatchAgeP50Ms > 0L -> snap.cursorDispatchAgeP50Ms
                    eyeMode && snap.faceResultAgeP50Ms > 0L -> snap.faceResultAgeP50Ms
                    snap.handResultAgeP50Ms > 0L -> snap.handResultAgeP50Ms
                    else -> (snap.conversionAvgMs + inferMs).coerceAtLeast(0L)
                }
                _liveTelemetry.value = LivePipelineTelemetry(
                    activeDelegate = delegate,
                    actualFps = snap.actualFps.toInt(),
                    configuredFps = if (snap.configuredFps > 0) snap.configuredFps else prefs.analysisFps,
                    handResultFps = snap.handResultFps.toInt(),
                    faceResultFps = snap.faceResultFps.toInt(),
                    inferenceMs = inferMs,
                    photonToCursorMs = stageAgeMs,
                )
                delay(500L)
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

    fun updateCursorResponseProfile(profile: CursorResponseProfile) {
        viewModelScope.launch {
            settingsRepository.updateCursorResponseProfile(profile)
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
