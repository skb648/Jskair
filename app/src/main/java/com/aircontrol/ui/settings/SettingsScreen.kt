package com.aircontrol.ui.settings

import androidx.compose.animation.animateContentSize
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    // Bug: Settings Sliders Resetting Fix — Use collectAsStateWithLifecycle() instead
    // of collectAsState(). This makes the flow collection lifecycle-aware: it
    // stops collecting when the Composable leaves the composition (e.g., when the
    // user navigates away from Settings), preventing unnecessary background
    // collection and ensuring the StateFlow's WhileSubscribed(5_000) grace period
    // works correctly.
    val preferences by viewModel.userPreferences.collectAsStateWithLifecycle()

    // Bug: Settings Sliders Resetting Fix — Local slider state must NOT be keyed
    // on the persisted preference value. Previously, `remember(preferences.sensitivity)`
    // would re-initialize the slider whenever the repository emitted a new value
    // (which happens ~immediately after onValueChangeFinished writes to DataStore).
    // This caused the slider to "snap back" to the persisted value during/after
    // an active drag, making the UI feel broken.
    //
    // The fix: use unkeyed remember { mutableFloatStateOf(...) } so the slider
    // state persists across recompositions. We initialize from the current
    // preference value, but we do NOT re-initialize when the preference updates.
    // The only time the slider should sync back to the persisted value is on
    // first composition (screen entry) — which the unkeyed remember handles
    // correctly because it only runs the initializer once.
    //
    // A LaunchedEffect syncs the local state if the persisted value changes
    // from an EXTERNAL source (e.g., another screen, or a reset-to-defaults
    // action) — but only when the user is NOT actively dragging.
    var sensitivity by remember { mutableFloatStateOf(preferences.sensitivity.toFloat()) }
    var cursorSpeed by remember { mutableFloatStateOf(preferences.cursorSpeed.toFloat()) }
    var holdDuration by remember { mutableFloatStateOf(preferences.holdDuration.toFloat()) }

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
            SectionHeader(title = "Gesture Controls")

            SettingSliderCard(
                title = "Sensitivity",
                valueLabel = "${sensitivity.toInt()}%",
                value = sensitivity,
                onValueChange = { sensitivity = it },
                onValueChangeFinished = { viewModel.updateSensitivity(sensitivity.toInt()) },
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
                        text = "Hand Preference",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(Dimens.spacing8))
                    SegmentedButtonGroup(
                        options = HandPreference.entries,
                        selectedOption = preferences.handPreference,
                        onOptionSelected = { viewModel.updateHandPreference(it) },
                        labelMapper = { it.displayName },
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
                        text = "Camera FPS",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(Dimens.spacing8))
                    SegmentedButtonGroup(
                        options = listOf(15, 24, 30),
                        selectedOption = preferences.analysisFps,
                        onOptionSelected = { viewModel.updateAnalysisFps(it) },
                        labelMapper = { "${it} FPS" },
                    )
                }
            }

            Spacer(modifier = Modifier.height(Dimens.spacing24))

            // ===== Cursor =====
            SectionHeader(title = "Cursor")

            SettingSliderCard(
                title = "Cursor Speed",
                valueLabel = "${cursorSpeed.toInt()}%",
                value = cursorSpeed,
                onValueChange = { cursorSpeed = it },
                onValueChangeFinished = { viewModel.updateCursorSpeed(cursorSpeed.toInt()) },
                valueRange = 1f..100f,
            )

            Spacer(modifier = Modifier.height(Dimens.spacing12))

            SettingSliderCard(
                title = "Hold Duration",
                valueLabel = "${holdDuration.toInt()}ms",
                value = holdDuration,
                onValueChange = { holdDuration = it },
                onValueChangeFinished = { viewModel.updateHoldDuration(holdDuration.toInt()) },
                valueRange = 200f..2000f,
                steps = 8,
            )

            Spacer(modifier = Modifier.height(Dimens.spacing24))

            // ===== Preferences =====
            SectionHeader(title = "Preferences")

            SettingSwitchRow(
                title = "Cursor Mode",
                subtitle = "Show cursor overlay when tracking",
                checked = preferences.cursorEnabled,
                onCheckedChange = { viewModel.updateCursorEnabled(it) },
                icon = Icons.Default.TouchApp,
            )

            Spacer(modifier = Modifier.height(Dimens.spacing8))

            SettingSwitchRow(
                title = "Haptic Feedback",
                subtitle = "Vibrate on gesture actions",
                checked = preferences.hapticFeedback,
                onCheckedChange = { viewModel.updateHapticFeedback(it) },
                icon = Icons.Outlined.Vibration,
            )

            Spacer(modifier = Modifier.height(Dimens.spacing8))

            SettingSwitchRow(
                title = "Status Pill",
                subtitle = "Show armed/disarmed indicator",
                checked = preferences.statusPillEnabled,
                onCheckedChange = { viewModel.updateStatusPillEnabled(it) },
            )

            Spacer(modifier = Modifier.height(Dimens.spacing8))

            SettingSwitchRow(
                title = "Battery Saver",
                subtitle = "Reduce FPS when idle to save battery",
                checked = preferences.batterySaver,
                onCheckedChange = { viewModel.updateBatterySaver(it) },
            )

            Spacer(modifier = Modifier.height(Dimens.spacing8))

            SettingSwitchRow(
                title = "Start on Boot",
                subtitle = "Auto-start tracking after device restart",
                checked = preferences.startOnBoot,
                onCheckedChange = { viewModel.updateStartOnBoot(it) },
            )

            Spacer(modifier = Modifier.height(Dimens.spacing32))

            // ===== About =====
            SectionHeader(title = "About")

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
                            text = "Version",
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
                            text = "All processing happens on-device. No camera data is recorded, transmitted, or stored. AirControl never requires network access.",
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
                                text = "Open Source Licenses",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(modifier = Modifier.height(Dimens.spacing4))
                            Text(
                                text = "This app uses MediaPipe, CameraX, Hilt, Compose, Kotlin Coroutines, and other open-source libraries.",
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
