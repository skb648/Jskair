package com.aircontrol.ui.calibration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aircontrol.data.model.UserPreferences
import com.aircontrol.data.repository.SettingsRepository
import com.aircontrol.tracking.HandFrame
import com.aircontrol.tracking.HandTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
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
    private val handSizeSamples = mutableListOf<Float>()
    private val pinchDistanceSamples = mutableListOf<Float>()
    private var measurementCount = 0
    private val REQUIRED_MEASUREMENTS = 20

    // Tracking job for hand frames
    private var handFrameJob: kotlinx.coroutines.Job? = null

    fun startCalibration() {
        _uiState.value = _uiState.value.copy(step = CalibrationStep.PALM_DETECT)
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
        pinchDistanceSamples.clear()
        measurementCount = 0

        viewModelScope.launch {
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

                // Calculate pinch distance: thumb tip (4) to index tip (8) in normalized units
                val thumbTip = frame.landmarks[4]
                val indexTip = frame.landmarks[8]
                val pinchDistNorm = kotlin.math.sqrt(
                    (thumbTip.x - indexTip.x) * (thumbTip.x - indexTip.x) +
                    (thumbTip.y - indexTip.y) * (thumbTip.y - indexTip.y) +
                    (thumbTip.z - indexTip.z) * (thumbTip.z - indexTip.z),
                )

                // Only accept reasonable values (filter outliers)
                if (handSizeNorm > 0.05f && handSizeNorm < 0.8f && pinchDistNorm > 0.001f && pinchDistNorm < 0.5f) {
                    handSizeSamples.add(handSizeNorm)
                    pinchDistanceSamples.add(pinchDistNorm)
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
                        val avgPinchDistNorm = pinchDistanceSamples.sorted().let { sorted ->
                            val trim = (sorted.size * 0.2).toInt().coerceAtLeast(0)
                            sorted.drop(trim).dropLast(trim).average().toFloat()
                        }

                        // Convert normalized to mm using standard proportion
                        // Average hand size wrist-to-middle-MCP ≈ 95mm
                        val handSizeMm = (avgHandSizeNorm / 0.20f) * 95f
                        val pinchDistanceMm = (avgPinchDistNorm / avgHandSizeNorm) * handSizeMm

                        _uiState.value = _uiState.value.copy(
                            handSizeMm = handSizeMm,
                            pinchDistanceMm = pinchDistanceMm,
                            canProceed = true,
                            step = CalibrationStep.TEST_GESTURES,
                        )
                        Timber.i("Calibration measured: handSize=%.1fmm, pinchDist=%.1fmm", handSizeMm, pinchDistanceMm)
                        return@collect // Done measuring
                    }
                }
            }
        }
    }

    fun onTestGestureRecognized(gestureName: String) {
        val current = _uiState.value
        if (current.testGesturesCompleted < current.testGesturesTotal) {
            val newCount = current.testGesturesCompleted + 1
            _uiState.value = current.copy(
                testGesturesCompleted = newCount,
                lastTestGestureName = gestureName,
                canProceed = newCount >= current.testGesturesTotal,
            )
        }
    }

    fun skipCalibration() {
        handFrameJob?.cancel()
        _uiState.value = _uiState.value.copy(step = CalibrationStep.COMPLETE)
    }

    fun completeCalibration() {
        handFrameJob?.cancel()
        _uiState.value = _uiState.value.copy(step = CalibrationStep.COMPLETE)
        // Persist calibration data to DataStore
        viewModelScope.launch {
            settingsRepository.updateCalibrationData(
                handSizeMm = _uiState.value.handSizeMm,
                pinchDistanceMm = _uiState.value.pinchDistanceMm,
            )
        }
        Timber.i("Calibration complete and persisted: handSize=%.1fmm, pinchDist=%.1fmm",
            _uiState.value.handSizeMm, _uiState.value.pinchDistanceMm)
    }

    override fun onCleared() {
        super.onCleared()
        handFrameJob?.cancel()
    }
}
