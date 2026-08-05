package com.aircontrol.ui.calibration

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aircontrol.BuildConfig
import com.aircontrol.R
import com.aircontrol.ui.Dimens
import com.aircontrol.ui.theme.ElectricBlue
import com.aircontrol.ui.theme.SuccessGreen
import com.aircontrol.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationScreen(
    onNavigateBack: () -> Unit,
    viewModel: CalibrationViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.calibration_title),
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(
                                R.string.content_description_navigate_back,
                            ),
                        )
                    }
                },
                actions = {
                    if (uiState.step != CalibrationStep.COMPLETE) {
                        TextButton(onClick = { viewModel.skipCalibration() }) {
                            Text("Skip", color = TextSecondary)
                        }
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = Dimens.paddingLarge)
                .animateContentSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Step indicator
            CalibrationStepIndicator(
                currentStep = uiState.step,
                modifier = Modifier.padding(vertical = Dimens.spacing16),
            )

            AnimatedContent(
                targetState = uiState.step,
                transitionSpec = {
                    fadeIn() + slideInVertically() togetherWith fadeOut() + slideOutVertically()
                },
                label = "calibration_step",
            ) { step ->
                when (step) {
                    CalibrationStep.INTRO -> IntroStep(
                        onStart = { viewModel.startCalibration() },
                        onSkip = { viewModel.skipCalibration() },
                    )
                    CalibrationStep.PALM_DETECT -> PalmDetectStep(
                        handDetected = uiState.handDetected,
                        onProceed = { viewModel.proceedFromPalmDetect() },
                    )
                    CalibrationStep.MEASURING -> MeasuringStep(
                        progress = uiState.measuringProgress,
                    )
                    CalibrationStep.TEST_GESTURES -> TestGesturesStep(
                        completed = uiState.testGesturesCompleted,
                        total = uiState.testGesturesTotal,
                        lastGesture = uiState.lastTestGestureName,
                        canProceed = uiState.canProceed,
                        onSimulateGesture = { viewModel.onTestGestureRecognized(it) },
                        onComplete = { viewModel.completeCalibration() },
                    )
                    CalibrationStep.COMPLETE -> CompleteStep(
                        handSizeMm = uiState.handSizeMm,
                        pinchDistanceMm = uiState.pinchDistanceMm,
                    )
                }
            }
        }
    }
}

@Composable
private fun TextButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    OutlinedButton(onClick = onClick) { content() }
}

@Composable
private fun CalibrationStepIndicator(
    currentStep: CalibrationStep,
    modifier: Modifier = Modifier,
) {
    val steps = CalibrationStep.entries
    val currentIndex = steps.indexOf(currentStep)

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        steps.forEachIndexed { index, step ->
            val isActive = index == currentIndex
            val isCompleted = index < currentIndex

            Box(
                modifier = Modifier
                    .size(if (isActive) 10.dp else 6.dp)
                    .animateContentSize(),
            ) {
                Canvas(modifier = Modifier.matchParentSize()) {
                    drawCircle(
                        color = when {
                            isCompleted -> SuccessGreen
                            isActive -> ElectricBlue
                            else -> Color.Gray.copy(alpha = 0.3f)
                        },
                        radius = if (isActive) size.minDimension * 0.5f else size.minDimension * 0.35f,
                    )
                }
            }
            if (index < steps.lastIndex) {
                Spacer(modifier = Modifier.width(Dimens.spacing8))
            }
        }
    }
}

@Composable
private fun IntroStep(
    onStart: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(vertical = Dimens.spacing32),
    ) {
        // Hand wave illustration
        CalibrationHandIllustration()

        Spacer(modifier = Modifier.height(Dimens.spacing32))

        Text(
            text = "Calibrate Your Hand",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(Dimens.spacing12))

        Text(
            text = "We'll measure your hand size and pinch distance to personalize gesture recognition. This takes about 30 seconds.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(Dimens.spacing32))

        Button(
            onClick = onStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(Dimens.buttonHeight),
            shape = RoundedCornerShape(Dimens.buttonCornerRadius),
        ) {
            Text("Start Calibration", style = MaterialTheme.typography.titleSmall)
        }

        Spacer(modifier = Modifier.height(Dimens.spacing8))

        OutlinedButton(
            onClick = onSkip,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Dimens.buttonCornerRadius),
        ) {
            Text("Skip for now", color = TextSecondary)
        }
    }
}

@Composable
private fun PalmDetectStep(
    handDetected: Boolean,
    onProceed: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(vertical = Dimens.spacing32),
    ) {
        // Animated palm preview area
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            shape = RoundedCornerShape(Dimens.cardCornerRadius),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                if (handDetected) {
                    // Show skeleton preview placeholder
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        HandSkeletonPreview()
                        Spacer(modifier = Modifier.height(Dimens.spacing8))
                        Text(
                            text = "Hand detected!",
                            style = MaterialTheme.typography.titleSmall,
                            color = SuccessGreen,
                        )
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        PulsingPalmOutline()
                        Spacer(modifier = Modifier.height(Dimens.spacing12))
                        Text(
                            text = "Hold your open palm in front of the camera",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = Dimens.paddingLarge),
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(Dimens.spacing24))

        if (handDetected) {
            Button(
                onClick = onProceed,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Dimens.buttonHeight),
                shape = RoundedCornerShape(Dimens.buttonCornerRadius),
            ) {
                Text("Proceed", style = MaterialTheme.typography.titleSmall)
            }
        } else {
            Text(
                text = "Position your hand about 30cm from the camera",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun MeasuringStep(progress: Float) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(vertical = Dimens.spacing48),
    ) {
        Text(
            text = "Measuring your hand...",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(Dimens.spacing32))

        CircularProgressIndicator(
            modifier = Modifier.size(80.dp),
            color = ElectricBlue,
            strokeWidth = 4.dp,
        )

        Spacer(modifier = Modifier.height(Dimens.spacing24))

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .semantics { contentDescription = "Measurement progress ${(progress * 100).toInt()}%" },
            color = ElectricBlue,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )

        Spacer(modifier = Modifier.height(Dimens.spacing12))

        Text(
            text = "${(progress * 100).toInt()}% complete",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )

        Spacer(modifier = Modifier.height(Dimens.spacing24))

        Text(
            text = "Keep your hand steady with fingers spread",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
    }
}

@Composable
private fun TestGesturesStep(
    completed: Int,
    total: Int,
    lastGesture: String,
    canProceed: Boolean,
    onSimulateGesture: (String) -> Unit,
    onComplete: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(vertical = Dimens.spacing32),
    ) {
        Text(
            text = "Test Your Gestures",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(Dimens.spacing8))

        Text(
            text = "Perform $total gestures to verify recognition",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )

        Spacer(modifier = Modifier.height(Dimens.spacing32))

        // Gesture test cards
        val gestures = listOf("Open Palm" to "Show an open palm", "Fist" to "Make a fist", "Pinch" to "Pinch thumb and index")
        gestures.forEachIndexed { index, (name, instruction) ->
            val isCompleted = index < completed
            val isCurrent = index == completed

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize(),
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        isCompleted -> SuccessGreen.copy(alpha = 0.08f)
                        isCurrent -> ElectricBlue.copy(alpha = 0.08f)
                        else -> MaterialTheme.colorScheme.surface
                    },
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Dimens.paddingMedium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isCompleted) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Completed",
                            tint = SuccessGreen,
                            modifier = Modifier.size(Dimens.iconMedium),
                        )
                    } else if (isCurrent) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(Dimens.iconMedium),
                            strokeWidth = 2.dp,
                            color = ElectricBlue,
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(Dimens.iconMedium)
                                .then(Modifier.animateContentSize()),
                        ) {
                            Canvas(modifier = Modifier.matchParentSize()) {
                                drawCircle(
                                    color = Color.Gray.copy(alpha = 0.3f),
                                    radius = size.minDimension * 0.3f,
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(Dimens.spacing12))

                    Column {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            color = if (isCompleted) SuccessGreen else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = instruction,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(Dimens.spacing8))
        }

        Spacer(modifier = Modifier.height(Dimens.spacing16))

        // Simulate gesture recognition (in production, this comes from HandTracker)
        if (BuildConfig.DEBUG && completed < total) {
            FilledTonalButton(
                onClick = {
                    val gestureNames = listOf("Open Palm", "Fist", "Pinch")
                    onSimulateGesture(gestureNames[completed])
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Dimens.buttonCornerRadius),
            ) {
                Text("Simulate: ${listOf("Open Palm", "Fist", "Pinch")[completed]}")
            }
        }

        if (canProceed) {
            Spacer(modifier = Modifier.height(Dimens.spacing12))
            Button(
                onClick = onComplete,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Dimens.buttonHeight),
                shape = RoundedCornerShape(Dimens.buttonCornerRadius),
            ) {
                Text("Complete Calibration", style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

@Composable
private fun CompleteStep(
    handSizeMm: Float,
    pinchDistanceMm: Float,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(vertical = Dimens.spacing48),
    ) {
        // Success checkmark
        Box(
            modifier = Modifier.size(80.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    color = SuccessGreen.copy(alpha = 0.15f),
                    radius = size.minDimension * 0.5f,
                )
                drawCircle(
                    color = SuccessGreen,
                    radius = size.minDimension * 0.35f,
                )
            }
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Calibration complete",
                tint = Color.White,
                modifier = Modifier.size(40.dp),
            )
        }

        Spacer(modifier = Modifier.height(Dimens.spacing24))

        Text(
            text = "Calibration Complete",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(Dimens.spacing12))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(Dimens.cardCornerRadius),
        ) {
            Column(
                modifier = Modifier.padding(Dimens.paddingMedium),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Hand Size", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                    Text(
                        "%.0f mm".format(handSizeMm),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(modifier = Modifier.height(Dimens.spacing8))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Pinch Distance", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                    Text(
                        "%.0f mm".format(pinchDistanceMm),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(Dimens.spacing24))

        Text(
            text = "Your personal thresholds have been saved. You can re-run calibration anytime from Settings.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CalibrationHandIllustration() {
    val infiniteTransition = rememberInfiniteTransition(label = "hand_wave")
    val waveOffset by infiniteTransition.animateFloat(
        initialValue = -5f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "wave_offset",
    )

    Canvas(modifier = Modifier.size(120.dp)) {
        val s = size.minDimension
        val cx = s / 2f
        val cy = s / 2f + waveOffset
        val strokeW = s * 0.04f
        val color = ElectricBlue

        // Simple palm
        val palmW = s * 0.3f
        val palmH = s * 0.25f
        drawRoundRect(
            color = color,
            topLeft = Offset(cx - palmW / 2, cy + s * 0.05f),
            size = androidx.compose.ui.geometry.Size(palmW, palmH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.03f),
            style = Stroke(width = strokeW),
        )

        // Fingers
        val fingerSpacing = palmW / 5f
        for (i in 0..4) {
            val fx = cx - palmW / 2 + fingerSpacing * (i + 0.5f)
            val fh = when (i) {
                0 -> s * 0.18f
                1 -> s * 0.3f
                2 -> s * 0.33f
                3 -> s * 0.27f
                else -> s * 0.2f
            }
            drawLine(
                color = color,
                start = Offset(fx, cy + s * 0.05f),
                end = Offset(fx, cy + s * 0.05f - fh),
                strokeWidth = strokeW * 1.2f,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun PulsingPalmOutline() {
    val infiniteTransition = rememberInfiniteTransition(label = "palm_pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "palm_scale",
    )

    Canvas(modifier = Modifier.size(100.dp)) {
        val s = size.minDimension * scale
        val cx = center.x
        val cy = center.y
        drawCircle(
            color = ElectricBlue.copy(alpha = 0.15f),
            radius = s * 0.45f,
            center = Offset(cx, cy),
        )
        drawCircle(
            color = ElectricBlue.copy(alpha = 0.4f),
            radius = s * 0.3f,
            center = Offset(cx, cy),
            style = Stroke(width = s * 0.03f),
        )
    }
}

@Composable
private fun HandSkeletonPreview() {
    Canvas(modifier = Modifier.size(160.dp)) {
        val s = size.minDimension
        val cx = s / 2f
        val cy = s / 2f
        val strokeW = s * 0.02f
        val landmarkRadius = s * 0.015f
        val color = ElectricBlue

        // Simplified skeleton: palm + 5 fingers with 3 joints each
        val palm = Offset(cx, cy + s * 0.1f)

        // Finger landmarks (tip, dip, pip)
        val fingerTips = listOf(
            Offset(cx - s * 0.2f, cy - s * 0.2f), // thumb
            Offset(cx - s * 0.12f, cy - s * 0.35f), // index
            Offset(cx, cy - s * 0.38f), // middle
            Offset(cx + s * 0.12f, cy - s * 0.35f), // ring
            Offset(cx + s * 0.2f, cy - s * 0.25f), // pinky
        )
        val fingerMids = fingerTips.map { Offset(it.x * 0.7f + palm.x * 0.3f, it.y * 0.6f + palm.y * 0.4f) }

        // Draw connections
        fingerMids.forEach { mid ->
            drawLine(color, palm, mid, strokeWidth = strokeW, cap = StrokeCap.Round)
        }
        fingerTips.forEachIndexed { i, tip ->
            drawLine(color, fingerMids[i], tip, strokeWidth = strokeW, cap = StrokeCap.Round)
        }

        // Draw landmarks
        drawCircle(color, landmarkRadius, palm)
        fingerMids.forEach { drawCircle(color, landmarkRadius, it) }
        fingerTips.forEach { drawCircle(SuccessGreen, landmarkRadius * 1.3f, it) }
    }
}
