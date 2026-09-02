package com.aircontrol.ui.calibration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aircontrol.data.model.UserPreferences
import com.aircontrol.data.repository.SettingsRepository
import com.aircontrol.tracking.HandFrame
import com.aircontrol.tracking.HandTracker
import com.aircontrol.gestures.GestureDetector
import com.aircontrol.gesture.model.Pose
import com.aircontrol.service.CameraServiceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Collections
import javax.inject.Inject

/** Steps in the calibration flow. */
enum class CalibrationStep {
    INTRO,
    PALM_DETECT,
    MEASURING,
    TEST_GESTURES,
    COMPLETE,
}

/** State for the calibration flow. */
data class CalibrationUiState(
    val step: CalibrationStep = CalibrationStep.INTRO,
    val handDetected: Boolean = false,
    val measuringProgress: Float = 0f,
    val handSizeMm: Float = 0f,
    val pinchDistanceMm: Float = 0f,
    val testGesturesCompleted: Int = 0,
    val testGesturesTotal: Int = 3,
    val lastTestGestureName: String = "",
    val canProceed: Boolean = false,
)

@HiltViewModel
class CalibrationViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val handTracker: HandTracker,
    private val gestureDetector: GestureDetector,
    private val cameraServiceManager: CameraServiceManager,
) : ViewModel() {

    val userPreferences: StateFlow<UserPreferences> = settingsRepository.userPreferences
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = UserPreferences(),
        )

    private val _uiState = MutableStateFlow(CalibrationUiState())
    val uiState: StateFlow<CalibrationUiState> = _uiState.asStateFlow()

    // Collected hand size samples for averaging
    private val handSizeSamples = Collections.synchronizedList(mutableListOf<Float>())
    private var measurementCount = 0
    private val REQUIRED_MEASUREMENTS = 20

    // Tracking job for hand frames
    private var handFrameJob: kotlinx.coroutines.Job? = null
    private var measuringJob: kotlinx.coroutines.Job? = null
    private var gestureTestJob: kotlinx.coroutines.Job? = null
    @Volatile private var latestFrame: HandFrame = HandFrame.EMPTY
    private var startedCameraForCalibration = false

    fun startCalibration() {
        if (!cameraServiceManager.isTracking()) {
            cameraServiceManager.startTracking()
            startedCameraForCalibration = true
        }
        _uiState.value = CalibrationUiState(step = CalibrationStep.PALM_DETECT)
        startMonitoringHandFrames()
    }

    fun onHandDetected(detected: Boolean) {
        _uiState.value = _uiState.value.copy(
            handDetected = detected,
            canProceed = detected,
        )
    }

    fun proceedFromPalmDetect() {
        _uiState.value = _uiState.value.copy(step = CalibrationStep.MEASURING, measuringProgress = 0f)
        startMeasuring()
    }

    private fun startMonitoringHandFrames() {
        handFrameJob?.cancel()
        handFrameJob = viewModelScope.launch {
            handTracker.handFrames.collect { frame ->
                latestFrame = frame
                _uiState.value = _uiState.value.copy(
                    handDetected = frame.isDetected,
                    canProceed = frame.isDetected && _uiState.value.step == CalibrationStep.PALM_DETECT,
                )
            }
        }
    }

    private fun startMeasuring() {
        // Real measurement from HandTracker — collect hand frame samples
        // and compute hand size (wrist to middle MCP) and pinch distance (thumb tip to index tip)
        handSizeSamples.clear()
        measurementCount = 0

        measuringJob?.cancel()
        measuringJob = viewModelScope.launch {
            handTracker.handFrames.collect { frame ->
                if (!frame.isDetected || frame.landmarks.size < 21) return@collect
                if (_uiState.value.step != CalibrationStep.MEASURING) return@collect

                // Calculate hand size: wrist (0) to middle MCP (9) in normalized units
                val wrist = frame.landmarks[0]
                val middleMcp = frame.landmarks[9]
                val handSizeNorm = kotlin.math.sqrt(
                    (middleMcp.x - wrist.x) * (middleMcp.x - wrist.x) +
                    (middleMcp.y - wrist.y) * (middleMcp.y - wrist.y) +
                    (middleMcp.z - wrist.z) * (middleMcp.z - wrist.z),
                )

                // This phase measures only hand scale while the palm is open. Pinch
                // distance is captured later, while the user performs a real pinch.
                if (handSizeNorm > 0.05f && handSizeNorm < 0.8f && frame.confidence >= 0.5f) {
                    handSizeSamples.add(handSizeNorm)
                    measurementCount++

                    val progress = (measurementCount.toFloat() / REQUIRED_MEASUREMENTS).coerceAtMost(1f)
                    _uiState.value = _uiState.value.copy(measuringProgress = progress)

                    if (measurementCount >= REQUIRED_MEASUREMENTS) {
                        // Compute averages and convert normalized to approximate mm
                        // Average adult hand size (wrist to middle MCP) is approximately 90-100mm
                        // We use a standard reference: if handSizeNorm ≈ 0.20, that's about 95mm
                        val avgHandSizeNorm = handSizeSamples.sorted().let { sorted ->
                            // Trim outliers: remove top and bottom 20%
                            val trim = (sorted.size * 0.2).toInt().coerceAtLeast(0)
                            sorted.drop(trim).dropLast(trim).average().toFloat()
                        }
                        // The absolute millimetre value is an estimate; recognition
                        // uses the dimensionless pinch/hand ratio, captured below.
                        val handSizeMm = (avgHandSizeNorm / 0.20f) * 95f

                        _uiState.value = _uiState.value.copy(
                            handSizeMm = handSizeMm,
                            pinchDistanceMm = 0f,
                            canProceed = false,
                            step = CalibrationStep.TEST_GESTURES,
                        )
                        startGestureVerification()
                        Timber.i("Hand scale measured: estimatedHandSize=%.1fmm", handSizeMm)
                        measuringJob?.cancel() // Cancel self when done
                        return@collect // Done measuring
                    }
                }
            }
        }
    }

    private fun startGestureVerification() {
        gestureTestJob?.cancel()
        gestureTestJob = viewModelScope.launch {
            gestureDetector.currentPose.collect { pose ->
                val expected = when (_uiState.value.testGesturesCompleted) {
                    0 -> Pose.OPEN_PALM
                    1 -> Pose.FIST
                    2 -> Pose.PINCH
                    else -> null
                }
                if (pose == expected) {
                    if (pose == Pose.PINCH) captureRealPinchDistance()
                    onTestGestureRecognized(pose.name)
                }
            }
        }
    }

    private fun captureRealPinchDistance() {
        val frame = latestFrame
        if (frame.landmarks.size < 21 || _uiState.value.handSizeMm <= 0f) return
        val wrist = frame.landmarks[0]
        val middleMcp = frame.landmarks[9]
        val thumbTip = frame.landmarks[4]
        val indexTip = frame.landmarks[8]
        val handNorm = kotlin.math.sqrt(
            (middleMcp.x - wrist.x) * (middleMcp.x - wrist.x) +
                (middleMcp.y - wrist.y) * (middleMcp.y - wrist.y),
        )
        val pinchNorm = kotlin.math.sqrt(
            (thumbTip.x - indexTip.x) * (thumbTip.x - indexTip.x) +
                (thumbTip.y - indexTip.y) * (thumbTip.y - indexTip.y),
        )
        if (handNorm > 0.05f && pinchNorm > 0f) {
            val safeRatio = (pinchNorm / handNorm).coerceIn(0.12f, 0.45f)
            _uiState.value = _uiState.value.copy(
                pinchDistanceMm = safeRatio * _uiState.value.handSizeMm,
            )
        }
    }

    fun onTestGestureRecognized(gestureName: String) {
        if (_uiState.value.testGesturesCompleted == 2 && _uiState.value.pinchDistanceMm <= 0f) {
            captureRealPinchDistance()
        }
        val current = _uiState.value
        if (current.testGesturesCompleted < current.testGesturesTotal) {
            val newCount = current.testGesturesCompleted + 1
            _uiState.value = current.copy(
                testGesturesCompleted = newCount,
                lastTestGestureName = gestureName,
                canProceed = newCount >= current.testGesturesTotal &&
                    _uiState.value.pinchDistanceMm > 0f,
            )
        }
    }

    fun skipCalibration() {
        stopCalibrationJobs()
        _uiState.value = _uiState.value.copy(step = CalibrationStep.COMPLETE)
        releaseCalibrationCameraIfNeeded()
    }

    fun completeCalibration() {
        stopCalibrationJobs()
        if (_uiState.value.handSizeMm <= 0f || _uiState.value.pinchDistanceMm <= 0f) return
        _uiState.value = _uiState.value.copy(step = CalibrationStep.COMPLETE)
        // Persist calibration data to DataStore
        viewModelScope.launch {
            settingsRepository.updateCalibrationData(
                handSizeMm = _uiState.value.handSizeMm,
                pinchDistanceMm = _uiState.value.pinchDistanceMm,
            )
        }
        releaseCalibrationCameraIfNeeded()
        Timber.i("Calibration complete and persisted: handSize=%.1fmm, pinchDist=%.1fmm",
            _uiState.value.handSizeMm, _uiState.value.pinchDistanceMm)
    }

    private fun stopCalibrationJobs() {
        handFrameJob?.cancel()
        measuringJob?.cancel()
        gestureTestJob?.cancel()
    }

    private fun releaseCalibrationCameraIfNeeded() {
        if (startedCameraForCalibration && !userPreferences.value.gesturesEnabled) {
            cameraServiceManager.stopTracking()
        }
        startedCameraForCalibration = false
    }

    override fun onCleared() {
        super.onCleared()
        stopCalibrationJobs()
        releaseCalibrationCameraIfNeeded()
    }
}
