package com.aircontrol.ui.gazecalibration

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aircontrol.R
import com.aircontrol.ui.theme.ElectricBlue
import com.aircontrol.ui.theme.ErrorRed
import com.aircontrol.ui.theme.SuccessGreen

/**
 * Full-screen 5-point gaze calibration.
 *
 * Shows one target dot at a time. The user fixates on it (a fixate delay gives
 * time to settle), then raw gaze is averaged per point. When all 5 are done an
 * affine transform is fitted and saved.
 *
 * Errors are surfaced with an explicit "Retry" button (no auto-retry loop), and
 * eye tracking being disabled shows clear guidance instead of a confusing error.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GazeCalibrationScreen(
    onNavigateBack: () -> Unit,
    viewModel: GazeCalibrationViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Auto-collect only when a NEW point becomes active (not on error changes).
    LaunchedEffect(state.currentPointIndex, state.prerequisitesChecked) {
        if (state.prerequisitesChecked && !state.isComplete && state.error == null) {
            viewModel.collectCurrentPoint()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.gaze_calibration_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.content_description_navigate_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            when {
                !state.prerequisitesChecked -> CircularProgressIndicator(color = ElectricBlue)
                state.isComplete -> {
                    Text(
                        text = stringResource(R.string.gaze_calibration_complete),
                        style = MaterialTheme.typography.headlineSmall,
                        color = SuccessGreen,
                    )
                    LaunchedEffect(Unit) {
                        kotlinx.coroutines.delay(1200)
                        onNavigateBack()
                    }
                }
                // Fix C4: every failure now says exactly what is wrong and what
                // to do about it, instead of a generic "error, retry?" loop.
                state.error != null -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp),
                    ) {
                        Text(
                            text = gazeErrorText(state.error),
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            color = ErrorRed,
                        )
                        val needsActionOutsideScreen = state.error in listOf(
                            GazeCalibrationError.EYE_TRACKING_DISABLED,
                            GazeCalibrationError.MASTER_SWITCH_OFF,
                            GazeCalibrationError.CAMERA_NOT_RUNNING,
                        )
                        if (needsActionOutsideScreen) {
                            Button(
                                onClick = onNavigateBack,
                                modifier = Modifier.padding(top = 24.dp),
                            ) {
                                Text(stringResource(R.string.gaze_calibration_go_back))
                            }
                        } else {
                            Button(
                                onClick = { viewModel.retryCurrentPoint() },
                                modifier = Modifier.padding(top = 24.dp),
                            ) {
                                Text(stringResource(R.string.gaze_calibration_retry))
                            }
                        }
                        Button(
                            onClick = { viewModel.restartCalibration() },
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Text(stringResource(R.string.gaze_calibration_restart))
                        }
                    }
                }
                else -> {
                    CalibrationCanvas(
                        targets = GazeCalibrationViewModel.CALIBRATION_TARGET_POINTS,
                        pointIndex = state.currentPointIndex,
                        isCollecting = state.isCollecting,
                    )
                }
            }
        }
    }
}

@Composable
private fun gazeErrorText(error: GazeCalibrationError?): String = when (error) {
    GazeCalibrationError.EYE_TRACKING_DISABLED ->
        stringResource(R.string.gaze_calibration_need_eye_tracking)
    GazeCalibrationError.MASTER_SWITCH_OFF ->
        stringResource(R.string.gaze_calibration_error_master_off)
    GazeCalibrationError.CAMERA_NOT_RUNNING ->
        stringResource(R.string.gaze_calibration_error_camera)
    GazeCalibrationError.FACE_NOT_VISIBLE ->
        stringResource(R.string.gaze_calibration_error_face)
    GazeCalibrationError.NOT_ENOUGH_SAMPLES ->
        stringResource(R.string.gaze_calibration_error_samples)
    GazeCalibrationError.FIT_FAILED, null ->
        stringResource(R.string.gaze_calibration_error_fit)
}

@Composable
private fun CalibrationCanvas(
    targets: List<Pair<Float, Float>>,
    pointIndex: Int,
    isCollecting: Boolean,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "calibrationPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseRing",
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val offsets = targets.map { (nx, ny) ->
                Offset(size.width * nx, size.height * ny)
            }
            // Draw subtle inactive markers so user knows calibration grid without distracting peripheral vision
            offsets.forEachIndexed { i, offset ->
                if (i != pointIndex) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.10f),
                        radius = 4.dp.toPx(),
                        center = offset,
                    )
                }
            }

            // Prominently draw the single active fixation target
            if (pointIndex in offsets.indices) {
                val activeOffset = offsets[pointIndex]
                val baseRadius = 20.dp.toPx()

                // Pulsing lock ring around active target
                drawCircle(
                    color = if (isCollecting) SuccessGreen.copy(alpha = 0.4f) else ElectricBlue.copy(alpha = 0.35f),
                    radius = baseRadius * (if (isCollecting) pulseScale else 1.15f),
                    center = activeOffset,
                    style = Stroke(width = 3.dp.toPx()),
                )

                // Outer bullseye circle
                drawCircle(
                    color = if (isCollecting) SuccessGreen else ElectricBlue,
                    radius = baseRadius,
                    center = activeOffset,
                )

                // High contrast white center pupil
                drawCircle(
                    color = Color.White,
                    radius = 6.dp.toPx(),
                    center = activeOffset,
                )
            }
        }

        Text(
            text = if (isCollecting) {
                stringResource(R.string.gaze_calibration_hold_still)
            } else {
                stringResource(
                    R.string.gaze_calibration_look_at,
                    pointIndex + 1,
                    targets.size,
                )
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 32.dp),
            style = MaterialTheme.typography.titleMedium,
            color = if (isCollecting) SuccessGreen else MaterialTheme.colorScheme.onBackground,
        )
    }
}
