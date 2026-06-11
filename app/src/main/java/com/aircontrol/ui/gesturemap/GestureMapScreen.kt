package com.aircontrol.ui.gesturemap

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aircontrol.R
import com.aircontrol.accessibility.GestureAction
import com.aircontrol.data.model.GestureMapEntry
import com.aircontrol.ui.Dimens
import com.aircontrol.ui.components.HandPoseIcon
import com.aircontrol.ui.theme.ElectricBlue
import com.aircontrol.ui.theme.SuccessGreen
import com.aircontrol.ui.theme.TextSecondary
import com.aircontrol.ui.theme.WarningOrange
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GestureMapScreen(
    onNavigateBack: () -> Unit,
    viewModel: GestureMapViewModel = hiltViewModel(),
) {
    val config by viewModel.gestureMapConfig.collectAsState()
    val showActionSheet by viewModel.showActionSheet.collectAsState()
    val selectedEntry by viewModel.selectedEntry.collectAsState()
    val conflictInfo by viewModel.conflictInfo.collectAsState()
    val showResetSnackbar by viewModel.showResetSnackbar.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Show undo snackbar after reset
    LaunchedEffect(showResetSnackbar) {
        if (showResetSnackbar) {
            val result = snackbarHostState.showSnackbar(
                message = "Gesture map reset to defaults",
                actionLabel = "Undo",
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.undoReset()
            } else {
                viewModel.dismissSnackbar()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.gesture_map_title),
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
                    OutlinedButton(
                        onClick = { viewModel.resetToDefaults() },
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            text = "Reset",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = Dimens.paddingLarge),
                verticalArrangement = Arrangement.spacedBy(Dimens.spacing8),
            ) {
                item {
                    Text(
                        text = "Tap any gesture to remap its action",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        modifier = Modifier.padding(vertical = Dimens.spacing8),
                    )
                }

                items(
                    items = config.entries,
                    key = { it.key },
                ) { entry ->
                    GestureMapItem(
                        entry = entry,
                        onClick = { viewModel.onEntryClicked(entry) },
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(Dimens.spacing64))
                }
            }

            // Bottom Sheet for Action Selection
            AnimatedVisibility(
                visible = showActionSheet,
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                ActionSelectionSheet(
                    selectedEntry = selectedEntry,
                    conflictInfo = conflictInfo,
                    onActionSelected = { viewModel.onActionSelected(it) },
                    onConflictSwap = { viewModel.onConflictResolved(swap = true) },
                    onConflictCancel = { viewModel.onConflictResolved(swap = false) },
                    onDismiss = { viewModel.dismissActionSheet() },
                )
            }
        }
    }
}

@Composable
private fun GestureMapItem(
    entry: GestureMapEntry,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable(
                role = Role.Button,
                onClick = onClick,
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(Dimens.cardCornerRadius),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dimens.paddingMedium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Hand pose icon
            HandPoseIcon(
                poseKey = entry.key,
                size = 44.dp,
                color = if (entry.action != GestureAction.NONE) ElectricBlue else TextSecondary,
            )

            Spacer(modifier = Modifier.width(Dimens.spacing16))

            // Gesture name and action
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = formatActionName(entry.action),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (entry.action != GestureAction.NONE) {
                        ElectricBlue
                    } else {
                        TextSecondary
                    },
                )
            }

            // Active indicator
            if (entry.action != GestureAction.NONE) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(SuccessGreen),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
    }
}

@Composable
private fun ActionSelectionSheet(
    selectedEntry: GestureMapEntry?,
    conflictInfo: ConflictInfo?,
    onActionSelected: (GestureAction) -> Unit,
    onConflictSwap: () -> Unit,
    onConflictCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = Dimens.cardCornerRadius, topEnd = Dimens.cardCornerRadius),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dimens.paddingLarge),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = selectedEntry?.label ?: "Select Action",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Choose what this gesture does",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            Spacer(modifier = Modifier.height(Dimens.spacing16))

            // Conflict warning
            AnimatedVisibility(
                visible = conflictInfo != null,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically(),
            ) {
                if (conflictInfo != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = WarningOrange.copy(alpha = 0.12f),
                        ),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(Dimens.paddingMedium),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = WarningOrange,
                                    modifier = Modifier.size(Dimens.iconMedium),
                                )
                                Spacer(modifier = Modifier.width(Dimens.spacing8))
                                Text(
                                    text = "Conflict detected",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = WarningOrange,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Spacer(modifier = Modifier.height(Dimens.spacing4))
                            Text(
                                text = "\"${conflictInfo.existingLabel}\" is already mapped to ${formatActionName(conflictInfo.existingAction)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(Dimens.spacing8))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(Dimens.spacing8),
                            ) {
                                OutlinedButton(
                                    onClick = onConflictSwap,
                                    shape = RoundedCornerShape(8.dp),
                                ) {
                                    Icon(
                                        Icons.Default.SwapHoriz,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Swap")
                                }
                                OutlinedButton(
                                    onClick = onConflictCancel,
                                    shape = RoundedCornerShape(8.dp),
                                ) {
                                    Text("Cancel")
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(Dimens.spacing12))
                }
            }

            // Action list
            val actions = GestureAction.entries.filter { it != GestureAction.DRAG }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp),
                verticalArrangement = Arrangement.spacedBy(Dimens.spacing4),
            ) {
                items(
                    items = actions,
                    key = { it.name },
                ) { action ->
                    ActionOption(
                        action = action,
                        isSelected = action == selectedEntry?.action,
                        onClick = { onActionSelected(action) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionOption(
    action: GestureAction,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isSelected) ElectricBlue.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.surface
            )
            .clickable(
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = Dimens.paddingMedium, vertical = Dimens.spacing12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = actionIcon(action),
            contentDescription = null,
            tint = if (isSelected) ElectricBlue else TextSecondary,
            modifier = Modifier.size(Dimens.iconMedium),
        )
        Spacer(modifier = Modifier.width(Dimens.spacing12))
        Text(
            text = formatActionName(action),
            style = MaterialTheme.typography.bodyLarge,
            color = if (isSelected) ElectricBlue else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Selected",
                tint = ElectricBlue,
                modifier = Modifier.size(Dimens.iconMedium),
            )
        }
    }
}

private fun formatActionName(action: GestureAction): String = when (action) {
    GestureAction.NONE -> "None"
    GestureAction.SCROLL_UP -> "Scroll Up"
    GestureAction.SCROLL_DOWN -> "Scroll Down"
    GestureAction.SCROLL_LEFT -> "Scroll Left"
    GestureAction.SCROLL_RIGHT -> "Scroll Right"
    GestureAction.BACK -> "Back"
    GestureAction.HOME -> "Home"
    GestureAction.RECENTS -> "Recents"
    GestureAction.NOTIFICATIONS -> "Notifications"
    GestureAction.QUICK_SETTINGS -> "Quick Settings"
    GestureAction.VOLUME_UP -> "Volume Up"
    GestureAction.VOLUME_DOWN -> "Volume Down"
    GestureAction.MEDIA_PLAY_PAUSE -> "Media Play/Pause"
    GestureAction.SCREENSHOT -> "Screenshot"
    GestureAction.LOCK_SCREEN -> "Lock Screen"
    GestureAction.TAP -> "Tap"
    GestureAction.LONG_PRESS -> "Long Press"
    GestureAction.DRAG -> "Drag"
}

private fun actionIcon(action: GestureAction): ImageVector = when (action) {
    GestureAction.NONE -> Icons.Default.Close
    GestureAction.SCROLL_UP -> Icons.Default.SwapHoriz // rotated conceptually
    GestureAction.SCROLL_DOWN -> Icons.Default.SwapHoriz
    GestureAction.SCROLL_LEFT -> Icons.Default.SwapHoriz
    GestureAction.SCROLL_RIGHT -> Icons.Default.SwapHoriz
    GestureAction.BACK -> Icons.AutoMirrored.Filled.ArrowBack
    GestureAction.HOME -> Icons.Default.Check
    GestureAction.RECENTS -> Icons.Default.SwapHoriz
    GestureAction.NOTIFICATIONS -> Icons.Default.Warning
    GestureAction.QUICK_SETTINGS -> Icons.Default.Settings
    GestureAction.VOLUME_UP -> Icons.Default.Check
    GestureAction.VOLUME_DOWN -> Icons.Default.Close
    GestureAction.MEDIA_PLAY_PAUSE -> Icons.Default.Check
    GestureAction.SCREENSHOT -> Icons.Default.Check
    GestureAction.LOCK_SCREEN -> Icons.Default.Close
    GestureAction.TAP -> Icons.Default.Check
    GestureAction.LONG_PRESS -> Icons.Default.Check
    GestureAction.DRAG -> Icons.Default.SwapHoriz
}
