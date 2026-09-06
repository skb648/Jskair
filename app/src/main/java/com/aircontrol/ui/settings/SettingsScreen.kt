package com.aircontrol.ui.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aircontrol.BuildConfig
import com.aircontrol.R
import com.aircontrol.data.model.HandPreference
import com.aircontrol.ui.Dimens
import com.aircontrol.ui.components.SegmentedButtonGroup
import com.aircontrol.ui.components.SettingSliderCard
import com.aircontrol.ui.components.SettingSwitchRow
import com.aircontrol.ui.theme.ElectricBlue
import com.aircontrol.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToGazeCalibration: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val preferences by viewModel.userPreferences.collectAsStateWithLifecycle()

    var sensitivity by remember { mutableFloatStateOf(preferences.sensitivity.toFloat()) }
    var cursorSpeed by remember { mutableFloatStateOf(preferences.cursorSpeed.toFloat()) }
    var holdDuration by remember { mutableFloatStateOf(preferences.holdDuration.toFloat()) }
    var dwellDuration by remember { mutableFloatStateOf(preferences.dwellDurationMs.toFloat()) }
    var cursorGain by remember { mutableFloatStateOf(preferences.cursorGain.toFloat()) }
    var gazeSensitivity by remember { mutableFloatStateOf(preferences.gazeSensitivity.toFloat()) }
    var blinkWindow by remember { mutableFloatStateOf(preferences.blinkWindowMs.toFloat()) }
    var isDraggingSensitivity by remember { androidx.compose.runtime.mutableStateOf(false) }
    var isDraggingCursorSpeed by remember { androidx.compose.runtime.mutableStateOf(false) }
    var isDraggingHoldDuration by remember { androidx.compose.runtime.mutableStateOf(false) }
    var isDraggingDwellDuration by remember { androidx.compose.runtime.mutableStateOf(false) }
    var isDraggingCursorGain by remember { androidx.compose.runtime.mutableStateOf(false) }
    var isDraggingGazeSensitivity by remember { androidx.compose.runtime.mutableStateOf(false) }
    var isDraggingBlinkWindow by remember { androidx.compose.runtime.mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(preferences.sensitivity) { if (!isDraggingSensitivity) sensitivity = preferences.sensitivity.toFloat() }
    LaunchedEffect(preferences.cursorSpeed) { if (!isDraggingCursorSpeed) cursorSpeed = preferences.cursorSpeed.toFloat() }
    LaunchedEffect(preferences.holdDuration) { if (!isDraggingHoldDuration) holdDuration = preferences.holdDuration.toFloat() }
    LaunchedEffect(preferences.dwellDurationMs) { if (!isDraggingDwellDuration) dwellDuration = preferences.dwellDurationMs.toFloat() }
    LaunchedEffect(preferences.cursorGain) { if (!isDraggingCursorGain) cursorGain = preferences.cursorGain.toFloat() }
    LaunchedEffect(preferences.gazeSensitivity) { if (!isDraggingGazeSensitivity) gazeSensitivity = preferences.gazeSensitivity.toFloat() }
    LaunchedEffect(preferences.blinkWindowMs) { if (!isDraggingBlinkWindow) blinkWindow = preferences.blinkWindowMs.toFloat() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_title),
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
        ) {
            // ===== Gesture Controls =====
            SectionHeader(title = stringResource(R.string.settings_section_gesture_controls))

            SettingSliderCard(
                title = stringResource(R.string.settings_sensitivity),
                valueLabel = stringResource(R.string.settings_percent_value, sensitivity.toInt()),
                value = sensitivity,
                onValueChange = { isDraggingSensitivity = true; sensitivity = it },
                onValueChangeFinished = { isDraggingSensitivity = false; haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); viewModel.updateSensitivity(sensitivity.toInt()) },
                valueRange = 1f..100f,
            )

            Spacer(modifier = Modifier.height(Dimens.spacing12))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(Dimens.cardCornerRadius),
            ) {
                Column(modifier = Modifier.padding(Dimens.paddingMedium)) {
                    Text(
                        text = stringResource(R.string.settings_hand_preference),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(Dimens.spacing8))
                    SegmentedButtonGroup(
                        options = HandPreference.entries,
                        selectedOption = preferences.handPreference,
                        onOptionSelected = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); viewModel.updateHandPreference(it) },
                        labelMapper = { it.displayName() },
                    )
                }
            }

            Spacer(modifier = Modifier.height(Dimens.spacing12))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(Dimens.cardCornerRadius),
            ) {
                Column(modifier = Modifier.padding(Dimens.paddingMedium)) {
                    Text(
                        text = stringResource(R.string.settings_analysis_fps),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(Dimens.spacing8))
                    SegmentedButtonGroup(
                        options = listOf(15, 24, 30),
                        selectedOption = preferences.analysisFps,
                        onOptionSelected = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); viewModel.updateAnalysisFps(it) },
                        labelMapper = { stringResource(R.string.settings_analysis_fps_value, it) },
                    )
                    // Fix (audit #28): eye mode caps analysis at 20 FPS (battery +
                    // thermal). Silently capping a "30 FPS" selection looked like
                    // the setting not working — say so up front.
                    if (preferences.eyeTrackingEnabled && preferences.analysisFps > 20) {
                        Spacer(modifier = Modifier.height(Dimens.spacing8))
                        Text(
                            text = stringResource(R.string.settings_analysis_fps_eye_cap_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(Dimens.spacing24))

            // ===== Cursor =====
            SectionHeader(title = stringResource(R.string.settings_section_cursor))

            SettingSliderCard(
                title = stringResource(R.string.settings_cursor_speed),
                valueLabel = stringResource(R.string.settings_percent_value, cursorSpeed.toInt()),
                value = cursorSpeed,
                onValueChange = { isDraggingCursorSpeed = true; cursorSpeed = it },
                onValueChangeFinished = { isDraggingCursorSpeed = false; haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); viewModel.updateCursorSpeed(cursorSpeed.toInt()) },
                valueRange = 1f..100f,
            )

            Spacer(modifier = Modifier.height(Dimens.spacing12))

            SettingSliderCard(
                title = stringResource(R.string.settings_hold_duration),
                valueLabel = stringResource(R.string.settings_duration_ms_value, holdDuration.toInt()),
                value = holdDuration,
                onValueChange = { isDraggingHoldDuration = true; holdDuration = it },
                onValueChangeFinished = { isDraggingHoldDuration = false; haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); viewModel.updateHoldDuration(holdDuration.toInt()) },
                valueRange = 200f..2000f,
                steps = 8,
            )

            Spacer(modifier = Modifier.height(Dimens.spacing24))

            // ===== Preferences =====
            SectionHeader(title = stringResource(R.string.settings_section_preferences))

            SettingSwitchRow(
                title = stringResource(R.string.settings_cursor_mode),
                subtitle = stringResource(R.string.settings_cursor_mode_subtitle),
                checked = preferences.cursorEnabled,
                onCheckedChange = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); viewModel.updateCursorEnabled(it) },
                icon = Icons.Default.TouchApp,
            )

            Spacer(modifier = Modifier.height(Dimens.spacing8))

            SettingSwitchRow(
                title = stringResource(R.string.settings_haptic_feedback),
                subtitle = stringResource(R.string.settings_haptic_subtitle),
                checked = preferences.hapticFeedback,
                onCheckedChange = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); viewModel.updateHapticFeedback(it) },
                icon = Icons.Outlined.Vibration,
            )

            Spacer(modifier = Modifier.height(Dimens.spacing8))

            SettingSwitchRow(
                title = stringResource(R.string.settings_status_pill),
                subtitle = stringResource(R.string.settings_status_pill_subtitle),
                checked = preferences.statusPillEnabled,
                onCheckedChange = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); viewModel.updateStatusPillEnabled(it) },
            )

            Spacer(modifier = Modifier.height(Dimens.spacing8))

            SettingSwitchRow(
                title = stringResource(R.string.settings_battery_saver),
                subtitle = stringResource(R.string.settings_battery_saver_subtitle),
                checked = preferences.batterySaver,
                onCheckedChange = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); viewModel.updateBatterySaver(it) },
            )

            Spacer(modifier = Modifier.height(Dimens.spacing8))

            SettingSwitchRow(
                title = stringResource(R.string.settings_start_on_boot),
                subtitle = stringResource(R.string.settings_start_on_boot_subtitle),
                checked = preferences.startOnBoot,
                onCheckedChange = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); viewModel.updateStartOnBoot(it) },
            )

            Spacer(modifier = Modifier.height(Dimens.spacing24))

            // ===== Accessibility (Vision Pro features) =====
            SectionHeader(title = stringResource(R.string.settings_section_accessibility))

            SettingSwitchRow(
                title = stringResource(R.string.settings_dwell_to_click),
                subtitle = stringResource(R.string.settings_dwell_to_click_subtitle),
                checked = preferences.dwellEnabled,
                onCheckedChange = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); viewModel.updateDwellEnabled(it) },
            )

            Spacer(modifier = Modifier.height(Dimens.spacing12))

            SettingSliderCard(
                title = stringResource(R.string.settings_dwell_duration),
                valueLabel = stringResource(R.string.settings_duration_ms_value, dwellDuration.toInt()),
                value = dwellDuration,
                onValueChange = { isDraggingDwellDuration = true; dwellDuration = it },
                onValueChangeFinished = { isDraggingDwellDuration = false; haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); viewModel.updateDwellDuration(dwellDuration.toInt()) },
                valueRange = 400f..3000f,
                steps = 12,
            )

            Spacer(modifier = Modifier.height(Dimens.spacing8))

            SettingSwitchRow(
                title = stringResource(R.string.settings_stationary_click),
                subtitle = stringResource(R.string.settings_stationary_click_subtitle),
                checked = preferences.stationaryClickEnabled,
                onCheckedChange = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); viewModel.updateStationaryClickEnabled(it) },
            )

            Spacer(modifier = Modifier.height(Dimens.spacing8))

            SettingSwitchRow(
                title = stringResource(R.string.settings_palm_home),
                subtitle = stringResource(R.string.settings_palm_home_subtitle),
                checked = preferences.palmHomeEnabled,
                onCheckedChange = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); viewModel.updatePalmHomeEnabled(it) },
            )

            Spacer(modifier = Modifier.height(Dimens.spacing8))

            SettingSwitchRow(
                title = stringResource(R.string.settings_swipe_open_palm),
                subtitle = stringResource(R.string.settings_swipe_open_palm_subtitle),
                checked = preferences.swipeRequiresOpenHand,
                onCheckedChange = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); viewModel.updateSwipeRequiresOpenHand(it) },
            )

            Spacer(modifier = Modifier.height(Dimens.spacing8))

            SettingSwitchRow(
                title = stringResource(R.string.settings_sit_back_mode),
                subtitle = stringResource(R.string.settings_sit_back_mode_subtitle),
                checked = preferences.sitBackMode,
                onCheckedChange = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); viewModel.updateSitBackMode(it) },
            )

            Spacer(modifier = Modifier.height(Dimens.spacing8))

            SettingSwitchRow(
                title = stringResource(R.string.settings_reduced_motion),
                subtitle = stringResource(R.string.settings_reduced_motion_subtitle),
                checked = preferences.reducedMotion,
                onCheckedChange = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); viewModel.updateReducedMotion(it) },
            )

            Spacer(modifier = Modifier.height(Dimens.spacing12))

            SettingSliderCard(
                title = stringResource(R.string.settings_cursor_gain),
                valueLabel = stringResource(R.string.settings_percent_value, cursorGain.toInt()),
                value = cursorGain,
                onValueChange = { isDraggingCursorGain = true; cursorGain = it },
                onValueChangeFinished = { isDraggingCursorGain = false; haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); viewModel.updateCursorGain(cursorGain.toInt()) },
                valueRange = 0f..100f,
            )

            Spacer(modifier = Modifier.height(Dimens.spacing24))

            // ===== Eye Tracking (Eye is Mouse) =====
            SectionHeader(title = stringResource(R.string.settings_section_eye_tracking))

            SettingSwitchRow(
                title = stringResource(R.string.settings_eye_is_mouse),
                subtitle = stringResource(R.string.settings_eye_is_mouse_subtitle),
                checked = preferences.eyeTrackingEnabled,
                onCheckedChange = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); viewModel.updateEyeTrackingEnabled(it) },
            )

            Spacer(modifier = Modifier.height(Dimens.spacing12))

            // Fix C2/C3: "Gaze Sensitivity" and "Invert Horizontal Gaze" only
            // affect the *uncalibrated* gain/invert path. Once a calibration is
            // saved they are dead controls — silently showing sliders that do
            // nothing was pure confusion. Show an honest hint instead.
            if (preferences.gazeCalibration.isNotBlank() ||
                preferences.personalizedGazeCalibration.isNotBlank()
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(Dimens.cardCornerRadius),
                ) {
                    Column(modifier = Modifier.padding(Dimens.paddingMedium)) {
                        Text(
                            text = stringResource(R.string.settings_gaze_calibrated_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                SettingSliderCard(
                    title = stringResource(R.string.settings_gaze_sensitivity),
                    valueLabel = stringResource(R.string.settings_percent_value, gazeSensitivity.toInt()),
                    value = gazeSensitivity,
                    onValueChange = { isDraggingGazeSensitivity = true; gazeSensitivity = it },
                    onValueChangeFinished = { isDraggingGazeSensitivity = false; haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); viewModel.updateGazeSensitivity(gazeSensitivity.toInt()) },
                    valueRange = 0f..100f,
                )

                Spacer(modifier = Modifier.height(Dimens.spacing8))

                SettingSwitchRow(
                    title = stringResource(R.string.settings_invert_gaze),
                    subtitle = stringResource(R.string.settings_invert_gaze_subtitle),
                    checked = preferences.gazeInvertX,
                    onCheckedChange = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); viewModel.updateGazeInvertX(it) },
                )
            }

            Spacer(modifier = Modifier.height(Dimens.spacing8))

            SettingSwitchRow(
                title = stringResource(R.string.settings_blink_click),
                subtitle = stringResource(R.string.settings_blink_click_subtitle),
                checked = preferences.blinkClickEnabled,
                onCheckedChange = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); viewModel.updateBlinkClickEnabled(it) },
            )

            Spacer(modifier = Modifier.height(Dimens.spacing8))

            // Fix A10: tunable blink window — the fixed 300ms minimum forced an
            // unnaturally slow blink; now the user picks what feels natural.
            SettingSliderCard(
                title = stringResource(R.string.settings_blink_window),
                valueLabel = stringResource(R.string.settings_duration_ms_value, blinkWindow.toInt()),
                value = blinkWindow,
                onValueChange = { isDraggingBlinkWindow = true; blinkWindow = it },
                onValueChangeFinished = { isDraggingBlinkWindow = false; haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); viewModel.updateBlinkWindowMs(blinkWindow.toInt()) },
                valueRange = 150f..500f,
            )

            Spacer(modifier = Modifier.height(Dimens.spacing12))

            // 5-point gaze calibration entry.
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(Dimens.cardCornerRadius),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToGazeCalibration() }
                        .padding(horizontal = Dimens.paddingMedium, vertical = Dimens.paddingSmall),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.gaze_calibration_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            // Fix (audit #29): a saved PERSONALIZED model also means
                            // "calibrated" — the label used to read only the legacy
                            // affine string, so a completed 9-point personalized
                            // session still showed "run the calibration".
                            text = if (preferences.gazeCalibration.isNotBlank() ||
                                preferences.personalizedGazeCalibration.isNotBlank()
                            ) {
                                stringResource(R.string.gaze_calibration_calibrated)
                            } else {
                                stringResource(R.string.gaze_calibration_run)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = ElectricBlue,
                    )
                }
            }

            Spacer(modifier = Modifier.height(Dimens.spacing32))

            // ===== About =====
            SectionHeader(title = stringResource(R.string.settings_section_about))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(Dimens.cardCornerRadius),
            ) {
                Column(modifier = Modifier.padding(Dimens.paddingMedium)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(R.string.settings_version),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextSecondary,
                        )
                    }

                    Spacer(modifier = Modifier.height(Dimens.spacing16))

                    // Privacy note
                    Row(
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = ElectricBlue,
                            modifier = Modifier.size(Dimens.iconMedium),
                        )
                        Spacer(modifier = Modifier.padding(Dimens.spacing8))
                        Text(
                            text = stringResource(R.string.settings_privacy_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                    }

                    Spacer(modifier = Modifier.height(Dimens.spacing16))

                    // Open source licenses
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Column(modifier = Modifier.padding(Dimens.spacing12)) {
                            Text(
                                text = stringResource(R.string.settings_licenses),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(modifier = Modifier.height(Dimens.spacing4))
                            Text(
                                text = stringResource(R.string.settings_licenses_detail),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(Dimens.spacing48))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = ElectricBlue,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = Dimens.spacing8),
    )
}

@Composable
private fun HandPreference.displayName(): String = when (this) {
    HandPreference.LEFT -> stringResource(R.string.settings_hand_left)
    HandPreference.RIGHT -> stringResource(R.string.settings_hand_right)
    HandPreference.ANY -> stringResource(R.string.settings_hand_any)
}
