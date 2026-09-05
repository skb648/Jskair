package com.aircontrol.ui.gazecalibration

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aircontrol.camera.CameraService
import com.aircontrol.ui.Suppression
import com.aircontrol.data.repository.SettingsRepository
import com.aircontrol.tracking.CalibrationSample
import com.aircontrol.tracking.CalibrationTargets
import com.aircontrol.tracking.FaceTracker
import com.aircontrol.tracking.GazeCalibration
import com.aircontrol.tracking.GazeCalibrationFeatureSchema
import com.aircontrol.tracking.PersonalizedGazeCalibrationFitter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject

/** Why calibration is currently blocked / failed (Fix C4: specific, actionable reasons). */
enum class GazeCalibrationError {
    EYE_TRACKING_DISABLED,
    MASTER_SWITCH_OFF,
    CAMERA_NOT_RUNNING,
    FACE_NOT_VISIBLE,
    NOT_ENOUGH_SAMPLES,
    FIT_FAILED,
}

/**
 * UI state for the 9-point personalized gaze calibration flow.
 */
data class GazeCalibrationState(
    val currentPointIndex: Int = 0,
    val totalPoints: Int = CalibrationTargets.ALL.size,
    val isCollecting: Boolean = false,
    val isComplete: Boolean = false,
    val error: GazeCalibrationError? = null,
    val prerequisitesChecked: Boolean = false,
)

/**
 * Drives the personalized (Fix A5) 9-point gaze calibration.
 *
 * For each of the 9 targets (the deterministic [CalibrationTarget] grid) the
 * user fixates while head-pose-normalized feature vectors are sampled, then a
 * quadratic ridge regression is fitted and persisted. The legacy 5-point-style
 * affine fit on averaged iris ratios is still computed from the *same* session
 * and stored as an automatic fallback, so a model that later fails its
 * transform-signature check degrades to a working calibration instead of none.
 */
@HiltViewModel
class GazeCalibrationViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val faceTracker: FaceTracker,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    override fun onCleared() {
        Suppression.release()
        super.onCleared()
    }

    private val _uiState = MutableStateFlow(GazeCalibrationState())
    val uiState: StateFlow<GazeCalibrationState> = _uiState.asStateFlow()

    // Accumulated calibration data.
    private val featureSamples = ArrayList<CalibrationSample>(CalibrationTargets.ALL.size * 40)
    private val rawGazeAverages = ArrayList<Pair<Float, Float>>(CalibrationTargets.ALL.size)
    private val screenPoints = ArrayList<Pair<Float, Float>>(CalibrationTargets.ALL.size)

    init {
        // Fix B-3: while a setup flow is on screen, the accessibility service
        // must not act on the gestures the user is making *for* that flow.
        Suppression.acquire()
        _uiState.value = GazeCalibrationState(totalPoints = CalibrationTargets.ALL.size)
        checkPrerequisites()
    }

    /** Fix C4: checks every real precondition, each with its own actionable error. */
    private fun checkPrerequisites() {
        viewModelScope.launch {
            val prefs = settingsRepository.userPreferences.first()
            val blockedBy = when {
                !prefs.eyeTrackingEnabled -> GazeCalibrationError.EYE_TRACKING_DISABLED
                !prefs.gesturesEnabled -> GazeCalibrationError.MASTER_SWITCH_OFF
                !CameraService.isRunning.value -> GazeCalibrationError.CAMERA_NOT_RUNNING
                else -> null
            }
            if (blockedBy != null) {
                _uiState.value = _uiState.value.copy(
                    error = blockedBy,
                    prerequisitesChecked = true,
                    isCollecting = false,
                )
                return@launch
            }
            // The camera service may still be initializing the face landmarker
            // right after the toggle; wait briefly for the first face frame.
            val firstFace = withTimeoutOrNull(FACE_WARMUP_TIMEOUT_MS) {
                faceTracker.gazeObservations.first { it.faceDetected }
            }
            _uiState.value = _uiState.value.copy(
                error = if (firstFace == null) GazeCalibrationError.FACE_NOT_VISIBLE else null,
                prerequisitesChecked = true,
            )
        }
    }

    /** Re-checks prerequisites (called when the user returns to the screen). */
    fun refreshEyeTracking() {
        _uiState.value = _uiState.value.copy(prerequisitesChecked = false, error = null)
        checkPrerequisites()
    }

    /**
     * Collects calibration data for the currently active target.
     *
     * Applies a fixate delay first so the user has time to look at the dot
     * before sampling begins (Fix C5: 1.2s → 1.5s, sampling 0.9s → 2.5s; the
     * old timing captured mid-saccade looks and fed the fitter garbage).
     */
    fun collectCurrentPoint() {
        val state = _uiState.value
        if (state.isCollecting || state.isComplete) return
        if (!state.prerequisitesChecked || state.error != null) return
        val index = state.currentPointIndex
        val target = CalibrationTargets.ALL[index]
        _uiState.value = state.copy(isCollecting = true, error = null)

        viewModelScope.launch {
            // Fixate delay: let the user settle their gaze (and head) on the target.
            delay(FIXATE_MS)

            val samples = ArrayList<CalibrationSample>()
            var rawSumX = 0f
            var rawSumY = 0f
            var faceFrames = 0

            val collectJob = launch {
                faceTracker.gazeObservations.collect { obs ->
                    if (!obs.faceDetected) return@collect
                    faceFrames++
                    rawSumX += obs.rawX
                    rawSumY += obs.rawY
                    val vector = obs.featureVector ?: return@collect
                    when (val result = CalibrationSample.create(
                        target = target,
                        featureVector = vector,
                        timestampMs = obs.timestampMs,
                        quality = obs.quality,
                        poseValid = obs.poseValid,
                    )) {
                        is CalibrationSample.Result.Accepted -> samples.add(result.sample)
                        is CalibrationSample.Result.Rejected -> Unit // quality gate inside
                    }
                }
            }

            delay(COLLECT_WINDOW_MS)
            collectJob.cancel()

            if (faceFrames < MIN_FACE_FRAMES_PER_TARGET) {
                _uiState.value = _uiState.value.copy(isCollecting = false, error = GazeCalibrationError.FACE_NOT_VISIBLE)
                return@launch
            }
            if (samples.size < MIN_SAMPLES_PER_TARGET) {
                _uiState.value = _uiState.value.copy(isCollecting = false, error = GazeCalibrationError.NOT_ENOUGH_SAMPLES)
                return@launch
            }

            featureSamples.addAll(samples)
            rawGazeAverages.add((rawSumX / faceFrames) to (rawSumY / faceFrames))
            screenPoints.add(target.x to target.y)

            val next = index + 1
            if (next >= CalibrationTargets.ALL.size) {
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
        val metrics = appContext.resources.displayMetrics
        var anySaved = false

        // ---- 1) Personalized (head-pose aware) model ----
        val fit = PersonalizedGazeCalibrationFitter.fit(
            rawSamples = featureSamples.toList(),
            regularization = 1e-2,
            transformSignature = GazeCalibrationFeatureSchema.TRANSFORM_SIGNATURE,
            screenWidthPx = metrics.widthPixels,
            screenHeightPx = metrics.heightPixels,
            createdAtMs = System.currentTimeMillis(),
        )
        if (fit.isSuccess && fit.model != null) {
            settingsRepository.updatePersonalizedGazeCalibration(fit.model.toSerialized())
            anySaved = true
            Timber.i(
                "Personalized gaze calibration saved (%d samples, val p95=%.4f)",
                fit.model.validationMetrics.sampleCount,
                fit.model.validationMetrics.p95NormalizedError,
            )
        } else {
            Timber.w("Personalized gaze calibration failed: %s — falling back to affine", fit.failure)
        }

        // ---- 2) Affine fallback on the same session's averaged raw ratios ----
        if (fitAffineFallback()) anySaved = true

        if (anySaved) {
            _uiState.value = _uiState.value.copy(isCollecting = false, isComplete = true)
        } else {
            _uiState.value = _uiState.value.copy(isCollecting = false, error = GazeCalibrationError.FIT_FAILED)
        }
    }

    private suspend fun fitAffineFallback(): Boolean = try {
        var gaze = rawGazeAverages.toList()
        var screen = screenPoints.toList()
        if (gaze.size < 3) return false

        var calibration = GazeCalibration.fit(gaze, screen)
        var residuals = calibration.residuals(gaze, screen)

        // One clearly-off target is dropped and the fit recomputed (kept from
        // the old 5-point flow: an affine fit through enough points always
        // "succeeds", even when a sample was a miss).
        if (residuals.isNotEmpty()) {
            val worst = residuals.indices.maxByOrNull { residuals[it] } ?: -1
            val median = residuals.sorted().getOrNull(residuals.size / 2) ?: 0f
            if (worst >= 0 && residuals[worst] > OUTLIER_ABSOLUTE &&
                residuals[worst] > median * OUTLIER_RELATIVE && gaze.size > MIN_FIT_POINTS
            ) {
                gaze = gaze.filterIndexed { i, _ -> i != worst }
                screen = screen.filterIndexed { i, _ -> i != worst }
                calibration = GazeCalibration.fit(gaze, screen)
                residuals = calibration.residuals(gaze, screen)
            }
        }

        val meanResidual = if (residuals.isEmpty()) 1f else residuals.average().toFloat()
        if (meanResidual > MAX_MEAN_RESIDUAL) {
            Timber.w("Affine gaze fallback rejected: mean residual %.3f", meanResidual)
            return false
        }
        settingsRepository.updateGazeCalibration(calibration.toFloatArray().joinToString(","))
        Timber.i("Affine gaze fallback saved (mean residual %.3f)", meanResidual)
        true
    } catch (e: Exception) {
        Timber.e(e, "Affine gaze fallback fit failed")
        false
    }

    /** Clears the error and retries the current point (explicit user action). */
    fun retryCurrentPoint() {
        // A failure mid-flow often means a hard precondition broke (camera
        // stopped, master switch flipped). Re-verify everything — it is cheap —
        // instead of blindly sampling into the void.
        refreshEyeTracking()
    }

    /** Restarts calibration from scratch. */
    fun restartCalibration() {
        featureSamples.clear()
        rawGazeAverages.clear()
        screenPoints.clear()
        _uiState.value = GazeCalibrationState(totalPoints = CalibrationTargets.ALL.size)
        checkPrerequisites()
    }

    companion object {
        /**
         * The 9 calibration targets as (x, y) screen-normalized pairs — the UI
         * renders exactly these (single source of truth with the fitter).
         */
        val CALIBRATION_TARGET_POINTS: List<Pair<Float, Float>> =
            CalibrationTargets.ALL.map { it.x to it.y }

        /** A point this far off (normalized screen units) is a miss, not noise. */
        private const val OUTLIER_ABSOLUTE = 0.16f

        /** ...and only dropped when it is this many times worse than the median. */
        private const val OUTLIER_RELATIVE = 3f

        /** Refuse to save an affine mapping that is on average this far off. */
        private const val MAX_MEAN_RESIDUAL = 0.09f

        /** An affine fit needs three points; below that a dropped outlier is fatal. */
        private const val MIN_FIT_POINTS = 3

        /** How long to wait for the first face frame before declaring failure. */
        private const val FACE_WARMUP_TIMEOUT_MS = 4_000L

        // Fix C5: generous, human-paced timing.
        private const val FIXATE_MS = 1_500L
        private const val COLLECT_WINDOW_MS = 2_500L
        private const val MIN_FACE_FRAMES_PER_TARGET = 15

        /**
         * 12 per target × 9 targets = 108 raw samples, comfortably above the
         * fitter's MIN_TOTAL_SAMPLES (90) even after its robust outlier filter
         * drops a few — otherwise the personalized fit would silently fail and
         * every session would degrade to the affine fallback.
         */
        private const val MIN_SAMPLES_PER_TARGET = 12
    }
}
