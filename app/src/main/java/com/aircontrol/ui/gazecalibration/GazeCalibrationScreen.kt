package com.aircontrol.ui.gazecalibration

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
    LaunchedEffect(state.currentPointIndex) {
        if (!state.isComplete && !state.eyeTrackingDisabled) {
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
                state.eyeTrackingDisabled -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.gaze_calibration_need_eye_tracking),
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Button(
                            onClick = onNavigateBack,
                            modifier = Modifier.padding(top = 24.dp),
                        ) {
                            Text(stringResource(R.string.gaze_calibration_go_back))
                        }
                    }
                }
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
                state.error -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.calibration_error_hint),
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            color = ErrorRed,
                        )
                        Button(
                            onClick = { viewModel.retryCurrentPoint() },
                            modifier = Modifier.padding(top = 24.dp),
                        ) {
                            Text(stringResource(R.string.gaze_calibration_retry))
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
                        targets = GazeCalibrationViewModel.CALIBRATION_POINTS,
                        pointIndex = state.currentPointIndex,
                        isCollecting = state.isCollecting,
                    )
                }
            }
        }
    }
}

@Composable
private fun CalibrationCanvas(
    targets: List<Pair<Float, Float>>,
    pointIndex: Int,
    isCollecting: Boolean,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val offsets = targets.map { (nx, ny) ->
                Offset(size.width * nx, size.height * ny)
            }
            offsets.forEachIndexed { i, offset ->
                val active = i == pointIndex
                val color = if (active) SuccessGreen else ElectricBlue.copy(alpha = 0.3f)
                drawCircle(color = color, radius = 24.dp.toPx(), center = offset)
                if (active) {
                    drawCircle(color = Color.White, radius = 8.dp.toPx(), center = offset)
                }
            }
        }

        Text(
            text = stringResource(
                R.string.gaze_calibration_look_at,
                pointIndex + 1,
                targets.size,
            ),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 32.dp),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )

        if (isCollecting) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp),
                color = SuccessGreen,
            )
        }
    }
}
