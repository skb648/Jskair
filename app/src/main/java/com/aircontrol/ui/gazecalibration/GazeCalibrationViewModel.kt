package com.aircontrol.ui.gazecalibration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aircontrol.ui.Suppression
import com.aircontrol.data.repository.SettingsRepository
import com.aircontrol.tracking.FaceTracker
import com.aircontrol.tracking.GazeCalibration
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * UI state for the 5-point gaze calibration flow.
 */
data class GazeCalibrationState(
    val currentPointIndex: Int = 0,
    val totalPoints: Int = 5,
    val isCollecting: Boolean = false,
    val isComplete: Boolean = false,
    val error: Boolean = false,
    val eyeTrackingDisabled: Boolean = false,
    val prerequisitesChecked: Boolean = false,
)

/**
 * Drives the 5-point gaze calibration.
 *
 * For each of the 5 targets (Top-Left, Top-Right, Center, Bottom-Left,
 * Bottom-Right) the user fixates while raw gaze is averaged, then a 2D affine
 * transform is fitted (least squares) and persisted via [SettingsRepository].
 */
@HiltViewModel
class GazeCalibrationViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val faceTracker: FaceTracker,
) : ViewModel() {

    override fun onCleared() {
        Suppression.release()
        super.onCleared()
    }

    private val _uiState = MutableStateFlow(GazeCalibrationState())
    val uiState: StateFlow<GazeCalibrationState> = _uiState.asStateFlow()

    // Accumulated calibration data.
    private val gazeSamples = ArrayList<Pair<Float, Float>>(CALIBRATION_POINTS.size)
    private val screenPoints = ArrayList<Pair<Float, Float>>(CALIBRATION_POINTS.size)

    init {
        // Fix B-3: while a setup flow is on screen, the accessibility service
        // must not act on the gestures the user is making *for* that flow. Without
        // this, pinching to press "Next" also sent a tap through to the
        // calibration screen, and swiping to test a pose scrolled the app out from
        // under the finger. The service reads [Suppression.isSuppressed].
        Suppression.acquire()

        // Single source of truth for the 5 targets (the screen reads this too,
        // so targets can't drift out of sync between the VM and the UI).
        _uiState.value = GazeCalibrationState(totalPoints = CALIBRATION_POINTS.size)
        checkEyeTracking()
    }

    /** Checks whether eye tracking is enabled before starting (clear guidance). */
    private fun checkEyeTracking() {
        viewModelScope.launch {
            val prefs = settingsRepository.userPreferences.first()
            _uiState.value = _uiState.value.copy(
                eyeTrackingDisabled = !prefs.eyeTrackingEnabled,
                prerequisitesChecked = true,
            )
        }
    }

    /** Re-checks eye tracking (called when the user returns to the screen). */
    fun refreshEyeTracking() {
        _uiState.value = _uiState.value.copy(prerequisitesChecked = false)
        checkEyeTracking()
    }

    /**
     * Collects raw gaze for the currently active target.
     *
     * Applies a fixate delay first so the user has time to look at the dot before
     * sampling begins (prevents the first sample being captured mid-saccade).
     */
    fun collectCurrentPoint() {
        if (_uiState.value.isCollecting || _uiState.value.isComplete) return
        if (!_uiState.value.prerequisitesChecked || _uiState.value.eyeTrackingDisabled) return
        val index = _uiState.value.currentPointIndex
        _uiState.value = _uiState.value.copy(isCollecting = true, error = false)

        viewModelScope.launch {
            // Fixate delay: let the user settle their gaze on the target.
            delay(FIXATE_MS)

            var sumX = 0f
            var sumY = 0f
            var count = 0

            val collectJob = launch {
                faceTracker.gazePoints.collect { gaze ->
                    if (gaze.isDetected) {
                        sumX += gaze.x
                        sumY += gaze.y
                        count++
                    }
                }
            }

            delay(SAMPLE_WINDOW_MS)
            collectJob.cancel()

            if (count < MIN_SAMPLES) {
                _uiState.value = _uiState.value.copy(isCollecting = false, error = true)
                return@launch
            }

            val avg = (sumX / count) to (sumY / count)
            gazeSamples.add(avg)
            screenPoints.add(CALIBRATION_POINTS[index])

            val next = index + 1
            if (next >= CALIBRATION_POINTS.size) {
                finishCalibration()
            } else {
                _uiState.value = _uiState.value.copy(
                    currentPointIndex = next,
                    isCollecting = false,
                )
            }
        }
    }

    private suspend fun finishCalibration() {
        try {
            var gaze = gazeSamples.toList()
            var screen = screenPoints.toList()
            var calibration = GazeCalibration.fit(gaze, screen)
            var residuals = calibration.residuals(gaze, screen)

            // Fix F-4: an affine fit through five points always "succeeds", even when
            // the user's head moved and one sample landed far from its target. That
            // produced a cursor that tracked the *opposite* side of the screen, with
            // no hint that calibration had gone wrong. One clearly-off point is
            // dropped and the fit recomputed (four points are still enough); a fit
            // that stays bad is refused instead of persisted.
            if (residuals.isNotEmpty()) {
                val worst = residuals.indices.maxByOrNull { residuals[it] } ?: -1
                val median = residuals.sorted().getOrNull(residuals.size / 2) ?: 0f
                if (worst >= 0 && residuals[worst] > OUTLIER_ABSOLUTE &&
                    residuals[worst] > median * OUTLIER_RELATIVE && gaze.size > MIN_FIT_POINTS
                ) {
                    Timber.w(
                        "Gaze calibration: dropping outlier point %d (residual %.3f, median %.3f)",
                        worst, residuals[worst], median,
                    )
                    gaze = gaze.filterIndexed { i, _ -> i != worst }
                    screen = screen.filterIndexed { i, _ -> i != worst }
                    calibration = GazeCalibration.fit(gaze, screen)
                    residuals = calibration.residuals(gaze, screen)
                }
            }

            val meanResidual = if (residuals.isEmpty()) 1f
            else residuals.average().toFloat()
            if (meanResidual > MAX_MEAN_RESIDUAL) {
                Timber.w("Gaze calibration rejected: mean residual %.3f", meanResidual)
                _uiState.value = _uiState.value.copy(isCollecting = false, error = true)
                return
            }

            settingsRepository.updateGazeCalibration(
                calibration.toFloatArray().joinToString(","),
            )
            _uiState.value = _uiState.value.copy(isCollecting = false, isComplete = true)
            Timber.i("Gaze calibration saved (mean residual %.3f)", meanResidual)
        } catch (e: Exception) {
            Timber.e(e, "Gaze calibration fit failed")
            _uiState.value = _uiState.value.copy(isCollecting = false, error = true)
        }
    }

    /** Clears the error and retries the current point (explicit user action). */
    fun retryCurrentPoint() {
        _uiState.value = _uiState.value.copy(error = false)
        collectCurrentPoint()
    }

    /** Restarts calibration from scratch. */
    fun restartCalibration() {
        gazeSamples.clear()
        screenPoints.clear()
        _uiState.value = GazeCalibrationState(totalPoints = CALIBRATION_POINTS.size)
        checkEyeTracking()
    }

    companion object {
        // Normalized calibration targets (Top-Left, Top-Right, Center, Bottom-Left, Bottom-Right).
        // Single source of truth — exposed so the UI renders these exact targets.
        val CALIBRATION_POINTS: List<Pair<Float, Float>> = listOf(
            0.1f to 0.1f,
            0.9f to 0.1f,
            0.5f to 0.5f,
            0.1f to 0.9f,
            0.9f to 0.9f,
        )
        /** A point this far off (normalized screen units) is a miss, not noise. */
        private const val OUTLIER_ABSOLUTE = 0.16f

        /** ...and only dropped when it is this many times worse than the median. */
        private const val OUTLIER_RELATIVE = 3f

        /** Refuse to save a mapping that is on average this far from the targets. */
        private const val MAX_MEAN_RESIDUAL = 0.09f

        /** An affine fit needs three points; below that a dropped outlier is fatal. */
        private const val MIN_FIT_POINTS = 3

        private const val FIXATE_MS = 1200L
        private const val SAMPLE_WINDOW_MS = 900L
        private const val MIN_SAMPLES = 5
    }
}
