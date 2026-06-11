package com.aircontrol.ui.calibration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aircontrol.data.model.UserPreferences
import com.aircontrol.data.repository.SettingsRepository
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
) : ViewModel() {

    val userPreferences: StateFlow<UserPreferences> = settingsRepository.userPreferences
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = UserPreferences(),
        )

    private val _uiState = MutableStateFlow(CalibrationUiState())
    val uiState: StateFlow<CalibrationUiState> = _uiState.asStateFlow()

    fun startCalibration() {
        _uiState.value = _uiState.value.copy(step = CalibrationStep.PALM_DETECT)
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

    private fun startMeasuring() {
        // Simulate measuring animation — in production this reads from HandTracker
        viewModelScope.launch {
            val steps = 20
            for (i in 1..steps) {
                kotlinx.coroutines.delay(100)
                _uiState.value = _uiState.value.copy(
                    measuringProgress = i.toFloat() / steps,
                )
            }
            // Simulate measured values
            _uiState.value = _uiState.value.copy(
                handSizeMm = 185f,
                pinchDistanceMm = 42f,
                canProceed = true,
                step = CalibrationStep.TEST_GESTURES,
            )
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
        _uiState.value = _uiState.value.copy(step = CalibrationStep.COMPLETE)
    }

    fun completeCalibration() {
        _uiState.value = _uiState.value.copy(step = CalibrationStep.COMPLETE)
        // In production: save handSizeMm and pinchDistanceMm to DataStore
        Timber.i("Calibration complete: handSize=%.1fmm, pinchDist=%.1fmm",
            _uiState.value.handSizeMm, _uiState.value.pinchDistanceMm)
    }
}
