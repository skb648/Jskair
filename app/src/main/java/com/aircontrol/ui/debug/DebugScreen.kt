package com.aircontrol.ui.debug

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import androidx.hilt.navigation.compose.hiltViewModel
import com.aircontrol.R
import com.aircontrol.gesture.model.GestureEngineState
import com.aircontrol.gesture.model.Pose
import com.aircontrol.tracking.HandConnections
import com.aircontrol.tracking.HandFrame
import com.aircontrol.tracking.Handedness
import com.aircontrol.ui.Dimens
import com.aircontrol.ui.theme.ElectricBlue
import com.aircontrol.ui.theme.SuccessGreen
import com.aircontrol.ui.theme.WarningOrange

/**
 * Debug screen for development: live camera preview with landmark skeleton overlay,
 * FPS counter, hand detection status, confidence readout, gesture label, and
 * state machine visualization.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    onNavigateBack: () -> Unit,
    viewModel: DebugViewModel = hiltViewModel(),
) {
    val handFrame by viewModel.handFrame.collectAsState()
    val isServiceRunning by viewModel.isServiceRunning.collectAsState()
    val currentFps by viewModel.currentFps.collectAsState()
    val isHandDetected by viewModel.isHandDetected.collectAsState()
    val measuredFps by viewModel.measuredFps.collectAsState()
    val gestureLabel by viewModel.gestureLabel.collectAsState()
    val engineState by viewModel.engineState.collectAsState()
    val currentPose by viewModel.currentPose.collectAsState()
    val armingProgress by viewModel.armingProgress.collectAsState()

    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(Unit) {
        viewModel.startTracking(context)
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopTracking(context)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.debug_title)) },
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
                .padding(innerPadding),
        ) {
            // Stats bar
            StatsBar(
                measuredFps = measuredFps,
                targetFps = currentFps,
                isHandDetected = isHandDetected,
                confidence = handFrame.confidence,
                handedness = handFrame.handedness,
                engineState = engineState,
                currentPose = currentPose,
            )

            // Camera preview with skeleton overlay
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                // Live camera preview
                if (isServiceRunning) {
                    AndroidView(
                        factory = { ctx ->
                            PreviewView(ctx).apply {
                                implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                                scaleType = PreviewView.ScaleType.FILL_CENTER
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .semantics {
                                contentDescription = context.getString(
                                    R.string.debug_camera_preview_desc,
                                )
                            },
                        update = { previewView ->
                            viewModel.bindPreview(previewView, lifecycleOwner)
                        },
                    )
                }

                // Landmark skeleton overlay
                HandSkeletonOverlay(
                    handFrame = handFrame,
                    modifier = Modifier.fillMaxSize(),
                )

                // FPS overlay in top-right corner
                FpsOverlay(
                    fps = measuredFps,
                    isHandDetected = isHandDetected,
                )

                // Gesture label overlay at bottom center
                GestureLabelOverlay(
                    gestureLabel = gestureLabel,
                    engineState = engineState,
                    armingProgress = armingProgress,
                )
            }
        }
    }
}

/**
 * Top stats bar showing FPS, detection status, confidence, handedness,
 * engine state, and current pose.
 */
@Composable
private fun StatsBar(
    measuredFps: Int,
    targetFps: Int,
    isHandDetected: Boolean,
    confidence: Float,
    handedness: Handedness,
    engineState: GestureEngineState,
    currentPose: Pose,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Dimens.paddingSmall),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(Dimens.cardCornerRadius),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dimens.paddingMedium),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Measured FPS
                val fpsColor by animateColorAsState(
                    targetValue = when {
                        measuredFps >= targetFps * 0.9 -> SuccessGreen
                        measuredFps >= targetFps * 0.5 -> WarningOrange
                        else -> Color.Red
                    },
                    animationSpec = tween(durationMillis = 300),
                    label = "fps_color",
                )
                Text(
                    text = stringResource(R.string.debug_fps_label, measuredFps),
                    style = MaterialTheme.typography.labelMedium,
                    color = fpsColor,
                )

                // Target FPS
                Text(
                    text = stringResource(R.string.debug_target_fps_label, targetFps),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Hand detection status
                val detectedColor by animateColorAsState(
                    targetValue = if (isHandDetected) SuccessGreen
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = tween(durationMillis = 300),
                    label = "detected_color",
                )
                Text(
                    text = if (isHandDetected) {
                        stringResource(R.string.debug_hand_detected)
                    } else {
                        stringResource(R.string.debug_no_hand)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = detectedColor,
                )

                // Confidence
                Text(
                    text = stringResource(
                        R.string.debug_confidence_label,
                        (confidence * 100).toInt(),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (confidence > 0.6f) SuccessGreen else WarningOrange,
                )

                // Handedness
                val handednessLabel = when (handedness) {
                    Handedness.LEFT -> "L"
                    Handedness.RIGHT -> "R"
                    Handedness.UNKNOWN -> "-"
                }
                Text(
                    text = handednessLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(Dimens.spacing4))

            // Second row: Engine state and current pose
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Engine state
                val stateColor by animateColorAsState(
                    targetValue = when (engineState) {
                        GestureEngineState.ARMED -> SuccessGreen
                        GestureEngineState.ARMING -> WarningOrange
                        GestureEngineState.EXECUTING -> ElectricBlue
                        GestureEngineState.COOLDOWN -> Color.Yellow
                        GestureEngineState.DISARMED -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    animationSpec = tween(200),
                    label = "state_color",
                )
                Text(
                    text = stringResource(R.string.debug_engine_state_label, engineState.name),
                    style = MaterialTheme.typography.labelMedium,
                    color = stateColor,
                )

                // Current pose
                val poseColor by animateColorAsState(
                    targetValue = if (currentPose != Pose.NONE) ElectricBlue
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = tween(200),
                    label = "pose_color",
                )
                Text(
                    text = stringResource(R.string.debug_pose_label, currentPose.name),
                    style = MaterialTheme.typography.labelMedium,
                    color = poseColor,
                )
            }
        }
    }
}

/**
 * Semi-transparent FPS overlay in the top-right corner of the camera preview.
 */
@Composable
private fun FpsOverlay(
    fps: Int,
    isHandDetected: Boolean,
) {
    val bgColor by animateColorAsState(
        targetValue = if (isHandDetected) {
            Color.Black.copy(alpha = 0.6f)
        } else {
            Color.Black.copy(alpha = 0.4f)
        },
        animationSpec = tween(300),
        label = "fps_bg",
    )
    val indicatorColor by animateColorAsState(
        targetValue = if (isHandDetected) SuccessGreen else WarningOrange,
        animationSpec = tween(300),
        label = "fps_indicator",
    )

    Box(
        modifier = Modifier
            .padding(Dimens.paddingSmall)
            .background(bgColor, RoundedCornerShape(Dimens.spacing8)),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Dimens.spacing12, vertical = Dimens.spacing4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Detection indicator dot
            Canvas(modifier = Modifier.size(8.dp)) {
                drawCircle(color = indicatorColor)
            }
            Spacer(modifier = Modifier.width(Dimens.spacing4))
            Text(
                text = "$fps fps",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
        }
    }
}

/**
 * Gesture label overlay at the bottom center of the camera preview.
 * Shows the currently recognized gesture name and arming progress.
 */
@Composable
private fun GestureLabelOverlay(
    gestureLabel: String,
    engineState: GestureEngineState,
    armingProgress: Float,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Dimens.paddingMedium),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Arming progress bar (visible during ARMING state)
        if (engineState == GestureEngineState.ARMING) {
            LinearProgressIndicator(
                progress = { armingProgress },
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .padding(bottom = Dimens.paddingSmall),
                color = WarningOrange,
                trackColor = Color.Gray.copy(alpha = 0.3f),
            )
        }

        // Gesture label
        if (gestureLabel.isNotEmpty()) {
            val labelColor = when (engineState) {
                GestureEngineState.ARMED -> SuccessGreen
                GestureEngineState.EXECUTING -> ElectricBlue
                GestureEngineState.ARMING -> WarningOrange
                else -> Color.White
            }
            Box(
                modifier = Modifier
                    .background(
                        Color.Black.copy(alpha = 0.6f),
                        RoundedCornerShape(Dimens.spacing8),
                    )
                    .padding(horizontal = Dimens.paddingMedium, vertical = Dimens.paddingSmall),
            ) {
                Text(
                    text = gestureLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = labelColor,
                )
            }
        }
    }
}

/**
 * Draws the hand skeleton overlay on a transparent Canvas.
 */
@Composable
private fun HandSkeletonOverlay(
    handFrame: HandFrame,
    modifier: Modifier = Modifier,
) {
    val landmarkColor = ElectricBlue
    val connectionColor = ElectricBlue.copy(alpha = 0.5f)
    val tipColor = SuccessGreen
    val wristColor = WarningOrange

    val contentDescriptionText = if (handFrame.isDetected) {
        "Hand skeleton with ${handFrame.landmarks.size} landmarks, " +
            "confidence ${(handFrame.confidence * 100).toInt()} percent"
    } else {
        "No hand detected"
    }

    Canvas(
        modifier = modifier.semantics {
            contentDescription = contentDescriptionText
        },
    ) {
        if (!handFrame.isDetected || handFrame.landmarks.isEmpty()) {
            return@Canvas
        }

        val canvasWidth = size.width
        val canvasHeight = size.height

        // Draw bone connections
        HandConnections.CONNECTIONS.forEach { (startIdx, endIdx) ->
            val start = handFrame.landmarks.getOrNull(startIdx)
            val end = handFrame.landmarks.getOrNull(endIdx)
            if (start != null && end != null) {
                val path = Path().apply {
                    moveTo(start.x * canvasWidth, start.y * canvasHeight)
                    lineTo(end.x * canvasWidth, end.y * canvasHeight)
                }
                drawPath(
                    path = path,
                    color = connectionColor,
                    style = Stroke(
                        width = 4f,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
            }
        }

        // Draw distal bone connections (fingertip segments) with brighter color
        val tipConnectionColor = ElectricBlue.copy(alpha = 0.7f)
        val tipConnections = listOf(
            3 to 4, 7 to 8, 11 to 12, 15 to 16, 19 to 20,
        )
        tipConnections.forEach { (startIdx, endIdx) ->
            val start = handFrame.landmarks.getOrNull(startIdx)
            val end = handFrame.landmarks.getOrNull(endIdx)
            if (start != null && end != null) {
                drawLine(
                    color = tipConnectionColor,
                    start = Offset(start.x * canvasWidth, start.y * canvasHeight),
                    end = Offset(end.x * canvasWidth, end.y * canvasHeight),
                    strokeWidth = 5f,
                    cap = StrokeCap.Round,
                )
            }
        }

        // Draw all landmark points
        val fingerTips = setOf(4, 8, 12, 16, 20)
        handFrame.landmarks.forEachIndexed { index, landmark ->
            val isTip = index in fingerTips
            val radius = if (isTip) 10f else 6f
            val color = when {
                index == 0 -> wristColor
                isTip -> tipColor
                else -> landmarkColor
            }

            if (isTip) {
                drawCircle(
                    color = color.copy(alpha = 0.3f),
                    center = Offset(landmark.x * canvasWidth, landmark.y * canvasHeight),
                    radius = radius + 4f,
                )
            }

            drawCircle(
                color = color,
                center = Offset(landmark.x * canvasWidth, landmark.y * canvasHeight),
                radius = radius,
            )
        }

        // Draw wrist with special marker
        handFrame.landmarks.getOrNull(0)?.let { wrist ->
            drawCircle(
                color = wristColor.copy(alpha = 0.3f),
                center = Offset(wrist.x * canvasWidth, wrist.y * canvasHeight),
                radius = 16f,
            )
            drawCircle(
                color = wristColor,
                center = Offset(wrist.x * canvasWidth, wrist.y * canvasHeight),
                radius = 10f,
            )
        }
    }
}
