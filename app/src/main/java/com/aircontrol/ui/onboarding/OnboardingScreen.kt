package com.aircontrol.ui.onboarding

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aircontrol.R
import com.aircontrol.ui.Dimens
import com.aircontrol.ui.theme.ElectricBlue
import com.aircontrol.ui.theme.SuccessGreen
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(
    onGetStarted: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val permissionStates by viewModel.permissionStates.collectAsState()
    val currentStep by viewModel.currentStep.collectAsState()
    val pagerState = rememberPagerState(initialPage = currentStep, pageCount = { 4 })
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.updateCameraGranted(granted)
    }

    LaunchedEffect(pagerState.currentPage) {
        viewModel.setCurrentStep(pagerState.currentPage)
    }

    LaunchedEffect(Unit) {
        viewModel.refreshPermissions()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Dimens.paddingXLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                when (page) {
                    0 -> WelcomeStep()
                    1 -> CameraPermissionStep(
                        isGranted = permissionStates.cameraGranted,
                        onRequestPermission = {
                            cameraLauncher.launch(android.Manifest.permission.CAMERA)
                        },
                        onOpenSettings = {
                            context.startActivity(
                                viewModel.permissionsManager.openAppSettings().apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                },
                            )
                        },
                    )
                    2 -> AccessibilityPermissionStep(
                        isGranted = permissionStates.accessibilityGranted,
                        onOpenSettings = {
                            context.startActivity(
                                viewModel.permissionsManager.requestAccessibilityPermission(),
                            )
                        },
                    )
                    3 -> OverlayPermissionStep(
                        isGranted = permissionStates.overlayGranted,
                        onOpenSettings = {
                            context.startActivity(
                                viewModel.permissionsManager.requestOverlayPermission(),
                            )
                        },
                        onGetStarted = {
                            viewModel.completeOnboarding()
                            onGetStarted()
                        },
                        allPreviousGranted = permissionStates.cameraGranted &&
                            permissionStates.accessibilityGranted,
                    )
                }
            }
        }

        PageIndicator(
            pageCount = 4,
            currentPage = pagerState.currentPage,
            modifier = Modifier.padding(vertical = Dimens.paddingMedium),
        )

        NavigationControls(
            currentPage = pagerState.currentPage,
            pageCount = 4,
            canProceed = when (pagerState.currentPage) {
                1 -> permissionStates.cameraGranted
                2 -> permissionStates.accessibilityGranted
                3 -> permissionStates.overlayGranted
                else -> true
            },
            onPrevious = {
                if (pagerState.currentPage > 0) {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                }
            },
            onNext = {
                if (pagerState.currentPage < 3) {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                }
            },
            onGetStarted = {
                viewModel.completeOnboarding()
                onGetStarted()
            },
        )
    }
}

@Composable
private fun WelcomeStep() {
    val scale = remember { Animatable(0f) }
    val alpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        scale.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow,
            ),
        )
    }

    LaunchedEffect(Unit) {
        alpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 600),
        )
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(Dimens.onboardingIllustrationSize)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    this.alpha = alpha.value
                },
            contentAlignment = Alignment.Center,
        ) {
            HandWaveIllustration()
        }

        Spacer(modifier = Modifier.height(Dimens.spacing32))

        Text(
            text = stringResource(R.string.onboarding_welcome_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(modifier = Modifier.height(Dimens.spacing12))

        Text(
            text = stringResource(R.string.onboarding_welcome_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CameraPermissionStep(
    isGranted: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CameraIllustration(isGranted = isGranted)

        Spacer(modifier = Modifier.height(Dimens.spacing32))

        Text(
            text = stringResource(R.string.onboarding_camera_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(modifier = Modifier.height(Dimens.spacing12))

        Text(
            text = stringResource(R.string.onboarding_camera_rationale),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(Dimens.spacing32))

        if (isGranted) {
            PermissionGrantedBadge()
        } else {
            Button(
                onClick = onRequestPermission,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = context.getString(
                            R.string.onboarding_camera_button_desc,
                        )
                    },
                shape = RoundedCornerShape(Dimens.buttonCornerRadius),
            ) {
                Text(text = stringResource(R.string.onboarding_camera_button))
            }

            Spacer(modifier = Modifier.height(Dimens.spacing8))

            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Dimens.buttonCornerRadius),
            ) {
                Text(text = stringResource(R.string.onboarding_open_settings))
            }
        }
    }
}

@Composable
private fun AccessibilityPermissionStep(
    isGranted: Boolean,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AccessibilityIllustration(isGranted = isGranted)

        Spacer(modifier = Modifier.height(Dimens.spacing32))

        Text(
            text = stringResource(R.string.onboarding_accessibility_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(modifier = Modifier.height(Dimens.spacing12))

        Text(
            text = stringResource(R.string.onboarding_accessibility_rationale),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(Dimens.spacing12))

        Text(
            text = stringResource(R.string.onboarding_accessibility_guide),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(Dimens.spacing32))

        if (isGranted) {
            PermissionGrantedBadge()
        } else {
            Button(
                onClick = onOpenSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = context.getString(
                            R.string.onboarding_accessibility_button_desc,
                        )
                    },
                shape = RoundedCornerShape(Dimens.buttonCornerRadius),
            ) {
                Text(text = stringResource(R.string.onboarding_accessibility_button))
            }
        }
    }
}

@Composable
private fun OverlayPermissionStep(
    isGranted: Boolean,
    onOpenSettings: () -> Unit,
    onGetStarted: () -> Unit,
    allPreviousGranted: Boolean,
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        OverlayIllustration(isGranted = isGranted)

        Spacer(modifier = Modifier.height(Dimens.spacing32))

        Text(
            text = stringResource(R.string.onboarding_overlay_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(modifier = Modifier.height(Dimens.spacing12))

        Text(
            text = stringResource(R.string.onboarding_overlay_rationale),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(Dimens.spacing32))

        if (isGranted) {
            PermissionGrantedBadge()
        } else {
            Button(
                onClick = onOpenSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = context.getString(
                            R.string.onboarding_overlay_button_desc,
                        )
                    },
                shape = RoundedCornerShape(Dimens.buttonCornerRadius),
            ) {
                Text(text = stringResource(R.string.onboarding_overlay_button))
            }
        }
    }
}

@Composable
private fun PermissionGrantedBadge() {
    val context = LocalContext.current
    val scale = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        scale.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        )
    }

    Row(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .semantics {
                contentDescription = context.getString(R.string.onboarding_permission_granted)
            },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            tint = SuccessGreen,
            modifier = Modifier.size(Dimens.iconLarge),
        )
        Spacer(modifier = Modifier.width(Dimens.spacing8))
        Text(
            text = stringResource(R.string.onboarding_permission_granted),
            style = MaterialTheme.typography.titleMedium,
            color = SuccessGreen,
        )
    }
}

@Composable
private fun PageIndicator(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    Row(
        modifier = modifier.semantics {
            contentDescription = context.getString(
                R.string.onboarding_page_indicator_desc,
                currentPage + 1,
                pageCount,
            )
        },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { index ->
            val isSelected = index == currentPage
            val color = if (isSelected) {
                ElectricBlue
            } else {
                MaterialTheme.colorScheme.outline
            }
            val width = if (isSelected) Dimens.pageIndicatorActiveWidth else Dimens.pageIndicatorInactiveWidth

            Box(
                modifier = Modifier
                    .padding(horizontal = Dimens.spacing4)
                    .height(Dimens.pageIndicatorHeight)
                    .width(width)
                    .background(color = color, shape = RoundedCornerShape(Dimens.spacing4)),
            )
        }
    }
}

@Composable
private fun NavigationControls(
    currentPage: Int,
    pageCount: Int,
    canProceed: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onGetStarted: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Dimens.paddingMedium),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        if (currentPage > 0) {
            OutlinedButton(
                onClick = onPrevious,
                shape = RoundedCornerShape(Dimens.buttonCornerRadius),
            ) {
                Text(text = stringResource(R.string.onboarding_back))
            }
        } else {
            Spacer(modifier = Modifier.width(1.dp))
        }

        if (currentPage < pageCount - 1) {
            FilledTonalButton(
                onClick = onNext,
                enabled = canProceed,
                shape = RoundedCornerShape(Dimens.buttonCornerRadius),
            ) {
                Text(text = stringResource(R.string.onboarding_next))
            }
        } else {
            Button(
                onClick = onGetStarted,
                enabled = canProceed,
                shape = RoundedCornerShape(Dimens.buttonCornerRadius),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ElectricBlue,
                ),
            ) {
                Text(text = stringResource(R.string.onboarding_get_started))
            }
        }
    }
}

@Composable
private fun HandWaveIllustration() {
    val infiniteTransition = rememberInfiniteTransition(label = "hand_wave")
    val waveAngle by infiniteTransition.animateFloat(
        initialValue = -15f,
        targetValue = 15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "wave_angle",
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val handScale = size.minDimension / 280f

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    ElectricBlue.copy(alpha = 0.15f),
                    Color.Transparent,
                ),
                center = Offset(centerX, centerY),
                radius = size.minDimension / 2f,
            ),
        )

        rotate(waveAngle, pivot = Offset(centerX, centerY + 60f * handScale)) {
            val palmPath = Path().apply {
                moveTo(centerX, centerY - 40f * handScale)
                lineTo(centerX - 30f * handScale, centerY - 40f * handScale)
                lineTo(centerX - 35f * handScale, centerY + 10f * handScale)
                lineTo(centerX + 35f * handScale, centerY + 10f * handScale)
                lineTo(centerX + 30f * handScale, centerY - 40f * handScale)
                close()
            }

            drawPath(
                path = palmPath,
                color = ElectricBlue,
                style = Stroke(width = 3f * handScale, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )

            val fingerOffsets = listOf(-20f, -8f, 4f, 16f)
            fingerOffsets.forEach { xOffset ->
                drawLine(
                    color = ElectricBlue,
                    start = Offset(centerX + xOffset * handScale, centerY - 40f * handScale),
                    end = Offset(centerX + (xOffset - 2f) * handScale, centerY - 70f * handScale),
                    strokeWidth = 3f * handScale,
                    cap = StrokeCap.Round,
                )
            }

            drawLine(
                color = ElectricBlue,
                start = Offset(centerX - 30f * handScale, centerY - 25f * handScale),
                end = Offset(centerX - 48f * handScale, centerY - 50f * handScale),
                strokeWidth = 3f * handScale,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun CameraIllustration(isGranted: Boolean) {
    val tint = if (isGranted) SuccessGreen else ElectricBlue

    Canvas(modifier = Modifier.size(Dimens.onboardingIllustrationSize)) {
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val camScale = size.minDimension / 240f

        drawRoundRect(
            color = tint,
            topLeft = Offset(centerX - 50f * camScale, centerY - 35f * camScale),
            size = Size(100f * camScale, 70f * camScale),
            cornerRadius = CornerRadius(12f * camScale),
            style = Stroke(width = 3f * camScale),
        )

        drawCircle(
            color = tint,
            center = Offset(centerX, centerY),
            radius = 22f * camScale,
            style = Stroke(width = 3f * camScale),
        )

        drawCircle(
            color = tint,
            center = Offset(centerX, centerY),
            radius = 8f * camScale,
        )

        drawRoundRect(
            color = tint,
            topLeft = Offset(centerX - 15f * camScale, centerY - 45f * camScale),
            size = Size(30f * camScale, 12f * camScale),
            cornerRadius = CornerRadius(4f * camScale),
            style = Stroke(width = 2f * camScale),
        )

        if (isGranted) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(SuccessGreen.copy(alpha = 0.2f), Color.Transparent),
                    center = Offset(centerX, centerY),
                    radius = 60f * camScale,
                ),
            )
        }
    }
}

@Composable
private fun AccessibilityIllustration(isGranted: Boolean) {
    val tint = if (isGranted) SuccessGreen else ElectricBlue
    val outlineColor = MaterialTheme.colorScheme.outline

    Canvas(modifier = Modifier.size(Dimens.onboardingIllustrationSize)) {
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val accScale = size.minDimension / 240f

        drawCircle(
            color = tint,
            center = Offset(centerX, centerY),
            radius = 50f * accScale,
            style = Stroke(width = 3f * accScale),
        )

        drawCircle(
            color = tint,
            center = Offset(centerX, centerY - 28f * accScale),
            radius = 8f * accScale,
        )

        drawLine(
            color = tint,
            start = Offset(centerX, centerY - 20f * accScale),
            end = Offset(centerX, centerY + 15f * accScale),
            strokeWidth = 3f * accScale,
            cap = StrokeCap.Round,
        )

        drawLine(
            color = tint,
            start = Offset(centerX - 18f * accScale, centerY - 5f * accScale),
            end = Offset(centerX + 18f * accScale, centerY - 5f * accScale),
            strokeWidth = 3f * accScale,
            cap = StrokeCap.Round,
        )

        drawLine(
            color = tint,
            start = Offset(centerX, centerY + 15f * accScale),
            end = Offset(centerX - 15f * accScale, centerY + 35f * accScale),
            strokeWidth = 3f * accScale,
            cap = StrokeCap.Round,
        )

        drawLine(
            color = tint,
            start = Offset(centerX, centerY + 15f * accScale),
            end = Offset(centerX + 15f * accScale, centerY + 35f * accScale),
            strokeWidth = 3f * accScale,
            cap = StrokeCap.Round,
        )

        drawRoundRect(
            color = tint,
            topLeft = Offset(centerX + 55f * accScale, centerY - 30f * accScale),
            size = Size(50f * accScale, 25f * accScale),
            cornerRadius = CornerRadius(6f * accScale),
            style = Stroke(width = 2f * accScale),
        )

        val toggleX = if (isGranted) centerX + 83f * accScale else centerX + 65f * accScale
        drawCircle(
            color = if (isGranted) SuccessGreen else outlineColor,
            center = Offset(toggleX, centerY - 17.5f * accScale),
            radius = 8f * accScale,
        )
    }
}

@Composable
private fun OverlayIllustration(isGranted: Boolean) {
    val tint = if (isGranted) SuccessGreen else ElectricBlue

    Canvas(modifier = Modifier.size(Dimens.onboardingIllustrationSize)) {
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val olScale = size.minDimension / 240f

        drawRoundRect(
            color = tint.copy(alpha = 0.3f),
            topLeft = Offset(centerX - 55f * olScale, centerY - 40f * olScale),
            size = Size(110f * olScale, 80f * olScale),
            cornerRadius = CornerRadius(8f * olScale),
        )

        drawRoundRect(
            color = tint,
            topLeft = Offset(centerX - 55f * olScale, centerY - 40f * olScale),
            size = Size(110f * olScale, 80f * olScale),
            cornerRadius = CornerRadius(8f * olScale),
            style = Stroke(width = 3f * olScale),
        )

        drawRoundRect(
            color = tint,
            topLeft = Offset(centerX - 35f * olScale, centerY - 25f * olScale),
            size = Size(70f * olScale, 35f * olScale),
            cornerRadius = CornerRadius(4f * olScale),
        )

        drawCircle(
            color = Color.White,
            center = Offset(centerX, centerY - 7.5f * olScale),
            radius = 10f * olScale,
        )

        drawLine(
            color = Color.White,
            start = Offset(centerX - 5f * olScale, centerY - 7.5f * olScale),
            end = Offset(centerX + 5f * olScale, centerY - 7.5f * olScale),
            strokeWidth = 2f * olScale,
            cap = StrokeCap.Round,
        )

        drawLine(
            color = Color.White,
            start = Offset(centerX, centerY - 12.5f * olScale),
            end = Offset(centerX, centerY - 2.5f * olScale),
            strokeWidth = 2f * olScale,
            cap = StrokeCap.Round,
        )

        if (isGranted) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(SuccessGreen.copy(alpha = 0.15f), Color.Transparent),
                    center = Offset(centerX, centerY),
                    radius = 70f * olScale,
                ),
            )
        }
    }
}
