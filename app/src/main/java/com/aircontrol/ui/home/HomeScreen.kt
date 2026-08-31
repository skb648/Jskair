package com.aircontrol.ui.home

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aircontrol.BuildConfig
import com.aircontrol.R
import com.aircontrol.permissions.MissingPermission
import com.aircontrol.ui.Dimens
import com.aircontrol.ui.components.AnimatedPowerButton
import com.aircontrol.ui.components.HandPresenceIndicator
import com.aircontrol.ui.theme.ElectricBlue
import com.aircontrol.ui.theme.ErrorRed
import com.aircontrol.ui.theme.SuccessGreen
import com.aircontrol.ui.theme.TextSecondary
import com.aircontrol.ui.theme.WarningOrange

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToGestureMap: () -> Unit,
    onNavigateToCalibration: () -> Unit,
    onNavigateToOnboarding: () -> Unit,
    onNavigateToDebug: () -> Unit,
    onNavigateToCustomGesture: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val preferences by viewModel.userPreferences.collectAsStateWithLifecycle()
    val permissionStates by viewModel.permissionStates.collectAsStateWithLifecycle()
    val serviceState by viewModel.serviceState.collectAsStateWithLifecycle()
    val sessionStats by viewModel.sessionStats.collectAsStateWithLifecycle()
    val handDetected by viewModel.handDetected.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    LaunchedEffect(Unit) {
        viewModel.refreshPermissions()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.home_title),
                        fontWeight = FontWeight.Bold,
                    )
                },
                actions = {
                    IconButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onNavigateToSettings()
                        },
                        modifier = Modifier.semantics {
                            contentDescription = "Settings"
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
                .padding(innerPadding)
                .padding(horizontal = Dimens.paddingLarge)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // === Permission Warning Cards ===
            AnimatedVisibility(
                visible = permissionStates.missingPermissions.isNotEmpty(),
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically(),
            ) {
                Column {
                    permissionStates.missingPermissions.forEach { missing ->
                        PermissionWarningCard(
                            missingPermission = missing,
                            onFixClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                when (missing) {
                                    MissingPermission.CAMERA -> {
                                        context.startActivity(
                                            Intent(
                                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                Uri.parse("package:${context.packageName}"),
                                            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
                                        )
                                    }
                                    MissingPermission.ACCESSIBILITY -> {
                                        context.startActivity(
                                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            },
                                        )
                                    }
                                    MissingPermission.NOTIFICATIONS -> {
                                        context.startActivity(
                                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            },
                                        )
                                    }
                                }
                            },
                            onReRunOnboarding = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onNavigateToOnboarding()
                            },
                        )
                        Spacer(modifier = Modifier.height(Dimens.spacing12))
                    }
                }
            }

            // === Hero Status Card ===
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                shape = RoundedCornerShape(Dimens.cardCornerRadius),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Dimens.spacing32),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Animated Power Button - FIXED logic: OFF -> ON, ON/PAUSED -> OFF with haptic
                    AnimatedPowerButton(
                        isActive = serviceState == ServiceState.ACTIVE,
                        isPaused = serviceState == ServiceState.PAUSED,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            val shouldEnable = serviceState == ServiceState.OFF
                            viewModel.toggleGestures(shouldEnable)
                        },
                        size = 120.dp,
                        contentDescription = when (serviceState) {
                            ServiceState.ACTIVE -> stringResource(R.string.home_power_active_cd)
                            ServiceState.PAUSED -> stringResource(R.string.home_power_paused_cd)
                            ServiceState.OFF -> stringResource(R.string.home_power_off_cd)
                        },
                    )

                    Spacer(modifier = Modifier.height(Dimens.spacing16))

                    // Service state label
                    Text(
                        text = when (serviceState) {
                            ServiceState.ACTIVE -> stringResource(R.string.home_status_active)
                            ServiceState.PAUSED -> stringResource(R.string.home_status_paused)
                            ServiceState.OFF -> stringResource(R.string.home_status_inactive)
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = when (serviceState) {
                            ServiceState.ACTIVE -> SuccessGreen
                            ServiceState.PAUSED -> WarningOrange
                            ServiceState.OFF -> ErrorRed
                        },
                    )

                    Spacer(modifier = Modifier.height(Dimens.spacing4))

                    // Hand presence indicator - FIXED: real detection vs fake service state
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.spacing8),
                    ) {
                        HandPresenceIndicator(
                            handDetected = handDetected,
                        )
                        Text(
                            text = when {
                                serviceState == ServiceState.OFF -> stringResource(R.string.home_service_inactive)
                                handDetected -> stringResource(R.string.home_hand_detected)
                                serviceState == ServiceState.PAUSED -> stringResource(R.string.home_status_paused)
                                else -> stringResource(R.string.home_no_hand)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                    }

                    Spacer(modifier = Modifier.height(Dimens.spacing16))

                    // Session stats
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Dimens.spacing32),
                    ) {
                        StatChip(
                            label = stringResource(R.string.home_gestures_executed),
                            value = sessionStats.gesturesExecuted.toString(),
                        )
                        StatChip(
                            label = stringResource(R.string.home_uptime),
                            value = formatUptime(sessionStats.uptimeSeconds),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(Dimens.spacing24))

            // === Quick Toggles Row ===
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimens.spacing8),
            ) {
                QuickToggleCard(
                    icon = Icons.Default.Mouse,
                    label = stringResource(R.string.home_cursor_mode),
                    enabled = preferences.cursorEnabled,
                    onToggle = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.toggleCursorMode(it)
                    },
                    modifier = Modifier.weight(1f),
                )
                QuickToggleCard(
                    icon = Icons.Outlined.Vibration,
                    label = stringResource(R.string.home_haptics),
                    enabled = preferences.hapticFeedback,
                    onToggle = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.toggleHapticFeedback(it)
                    },
                    modifier = Modifier.weight(1f),
                )
                QuickToggleCard(
                    icon = Icons.Default.Bolt,
                    label = stringResource(R.string.home_battery_saver),
                    enabled = preferences.batterySaver,
                    onToggle = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.toggleBatterySaver(it)
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(Dimens.spacing24))

            // === Action Buttons ===
            FilledTonalButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onNavigateToGestureMap()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Dimens.buttonHeight),
                shape = RoundedCornerShape(Dimens.buttonCornerRadius),
            ) {
                Icon(
                    imageVector = Icons.Default.TouchApp,
                    contentDescription = null,
                    modifier = Modifier.size(Dimens.iconMedium),
                )
                Spacer(modifier = Modifier.width(Dimens.spacing8))
                Text(
                    text = stringResource(R.string.home_view_gesture_map),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(Dimens.spacing12))

            FilledTonalButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onNavigateToCalibration()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Dimens.buttonHeight),
                shape = RoundedCornerShape(Dimens.buttonCornerRadius),
            ) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = null,
                    modifier = Modifier.size(Dimens.iconMedium),
                )
                Spacer(modifier = Modifier.width(Dimens.spacing8))
                Text(
                    text = stringResource(R.string.home_open_calibration),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (BuildConfig.DEBUG) {
                Spacer(modifier = Modifier.height(Dimens.spacing12))
                FilledTonalButton(
                    onClick = onNavigateToDebug,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(Dimens.buttonHeight),
                    shape = RoundedCornerShape(Dimens.buttonCornerRadius),
                ) {
                    Icon(
                        imageVector = Icons.Default.BugReport,
                        contentDescription = null,
                        modifier = Modifier.size(Dimens.iconMedium),
                    )
                    Spacer(modifier = Modifier.width(Dimens.spacing8))
                    Text(text = stringResource(R.string.home_debug), style = MaterialTheme.typography.titleSmall)
                }
            }

            Spacer(modifier = Modifier.height(Dimens.spacing12))

            FilledTonalButton(
                onClick = onNavigateToCustomGesture,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Dimens.buttonHeight),
                shape = RoundedCornerShape(Dimens.buttonCornerRadius),
            ) {
                Icon(
                    imageVector = Icons.Default.TouchApp,
                    contentDescription = null,
                    modifier = Modifier.size(Dimens.iconMedium),
                )
                Spacer(modifier = Modifier.width(Dimens.spacing8))
                Text(
                    text = stringResource(R.string.home_custom_gestures),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(Dimens.spacing32))
        }
    }
}

@Composable
private fun StatChip(
    label: String,
    value: String,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
        )
    }
}

@Composable
private fun QuickToggleCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) {
                ElectricBlue.copy(alpha = 0.12f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        shape = RoundedCornerShape(Dimens.cardCornerRadius),
        onClick = { onToggle(!enabled) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dimens.spacing12),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null, // decorative: label below provides TalkBack
                tint = if (enabled) ElectricBlue else TextSecondary,
                modifier = Modifier.size(Dimens.iconMedium),
            )
            Spacer(modifier = Modifier.height(Dimens.spacing4))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (enabled) ElectricBlue else TextSecondary,
            )
            Spacer(modifier = Modifier.height(Dimens.spacing4))
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (enabled) ElectricBlue else MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}

@Composable
private fun PermissionWarningCard(
    missingPermission: MissingPermission,
    onFixClick: () -> Unit,
    onReRunOnboarding: () -> Unit,
) {
    val title = when (missingPermission) {
        MissingPermission.CAMERA -> stringResource(R.string.home_warning_camera_title)
        MissingPermission.ACCESSIBILITY -> stringResource(R.string.home_warning_accessibility_title)
        MissingPermission.NOTIFICATIONS -> stringResource(R.string.home_warning_notifications_title)
    }
    val message = when (missingPermission) {
        MissingPermission.CAMERA -> stringResource(R.string.home_warning_camera_message)
        MissingPermission.ACCESSIBILITY -> stringResource(R.string.home_warning_accessibility_message)
        MissingPermission.NOTIFICATIONS -> stringResource(R.string.home_warning_notifications_message)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = ErrorRed.copy(alpha = 0.12f),
        ),
        shape = RoundedCornerShape(Dimens.cardCornerRadius),
    ) {
        Column(
            modifier = Modifier.padding(Dimens.paddingMedium),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(ErrorRed),
                )
                Spacer(modifier = Modifier.width(Dimens.spacing8))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = ErrorRed,
                )
            }
            Spacer(modifier = Modifier.height(Dimens.spacing4))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(Dimens.spacing12))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimens.spacing8),
            ) {
                OutlinedButton(
                    onClick = onFixClick,
                    shape = RoundedCornerShape(Dimens.buttonCornerRadius),
                ) {
                    Text(text = stringResource(R.string.home_warning_fix_button))
                }
                OutlinedButton(
                    onClick = onReRunOnboarding,
                    shape = RoundedCornerShape(Dimens.buttonCornerRadius),
                ) {
                    Text(text = stringResource(R.string.home_warning_rerun_button))
                }
            }
        }
    }
}

private fun formatUptime(seconds: Long): String {
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${seconds % 3600 / 60}m"
    }
}
